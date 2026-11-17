"use strict";

const fs = (() => { try { return require("original-fs"); } catch { return require("node:fs"); } })();
const path = require("node:path");
const crypto = require("node:crypto");
const zlib = require("node:zlib");
const { spawn } = require("node:child_process");

const ROOT = "River-Client-Linux";
const REQUIRED = ["river-client", "river-client-bin", "resources/app.asar", "icudtl.dat"];
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function hashFile(file) {
  const hash = crypto.createHash("sha256");
  for await (const chunk of fs.createReadStream(file)) hash.update(chunk);
  return hash.digest("hex");
}

function linuxPackage(manifest, arch = process.arch) {
  if (arch !== "x64") throw new Error("This Linux update is for x64.");
  const file = manifest?.files?.linux;
  const url = new URL(manifest?.linuxUrl || "");
  if (url.protocol !== "https:" || !/^[a-f0-9]{64}$/i.test(file?.sha256 || "") ||
      !Number.isSafeInteger(file?.size) || file.size <= 0 || file.size > 2 * 1024 ** 3) {
    throw new Error("The signed release has no verified Linux package.");
  }
  return { url: url.href, sha256: file.sha256.toLowerCase(), size: file.size };
}

function archivePath(name) {
  const parts = name.replace(/\/$/, "").split("/");
  if (parts.shift() !== ROOT || parts.some((part) => !part || part === "." || part === ".." || /[\\\x00-\x1f\x7f]/.test(part))) {
    throw new Error("Unsafe Linux archive path.");
  }
  return parts.join("/");
}

function octal(block, start, length) {
  const text = block.subarray(start, start + length).toString("ascii").replace(/\0.*$/, "").trim();
  if (!/^[0-7]+$/.test(text)) throw new Error("Invalid tar number.");
  const value = parseInt(text, 8);
  if (!Number.isSafeInteger(value)) throw new Error("Oversized tar number.");
  return value;
}

/** Reads only the regular files, directories and GNU long names produced by package-linux.js. */
async function extractLinuxArchive(archive, destination) {
  fs.mkdirSync(destination, { recursive: true, mode: 0o700 });
  if (fs.readdirSync(destination).length) throw new Error("Linux extraction needs an empty directory.");
  let buffered = Buffer.alloc(0), entry = null, longName = "", zeroes = 0, total = 0;
  const names = new Set();
  const files = [];
  const stream = fs.createReadStream(archive).pipe(zlib.createGunzip());
  try {
    for await (const chunk of stream) {
      total += chunk.length;
      if (total > 5 * 1024 ** 3) throw new Error("Linux archive is too large.");
      buffered = buffered.length ? Buffer.concat([buffered, chunk]) : chunk;
      while (true) {
        if (!entry) {
          if (buffered.length < 512) break;
          const header = buffered.subarray(0, 512);
          buffered = buffered.subarray(512);
          if (header.every((byte) => byte === 0)) { zeroes++; continue; }
          if (zeroes) throw new Error("Data after the end of the tar archive.");
          let checksum = 0;
          for (let i = 0; i < 512; i++) checksum += i >= 148 && i < 156 ? 32 : header[i];
          if (checksum !== octal(header, 148, 8)) throw new Error("Invalid tar checksum.");
          const size = octal(header, 124, 12);
          if (size > 2 * 1024 ** 3) throw new Error("Oversized Linux file.");
          const type = String.fromCharCode(header[156]);
          if (!["0", "\0", "5", "L"].includes(type)) throw new Error("Linux archive contains a link or special file.");
          const field = (start, length) => header.subarray(start, start + length).toString("utf8").replace(/\0.*$/, "");
          const rawName = longName || [field(345, 155), field(0, 100)].filter(Boolean).join("/");
          if (type === "L") {
            if (longName || size > 4096) throw new Error("Invalid tar long name.");
            entry = { remaining: size, padding: (512 - size % 512) % 512, long: [] };
          } else {
            longName = "";
            const relative = archivePath(rawName);
            if (names.has(relative) || names.size > 50_000) throw new Error("Duplicate or excessive Linux archive entries.");
            names.add(relative);
            const target = path.join(destination, relative);
            if (type === "5") {
              if (size !== 0) throw new Error("Invalid tar directory.");
              fs.mkdirSync(target, { recursive: true, mode: 0o755 });
              entry = { remaining: 0, padding: 0 };
            } else {
              if (!relative) throw new Error("Invalid Linux root file.");
              fs.mkdirSync(path.dirname(target), { recursive: true, mode: 0o755 });
              const executable = (octal(header, 100, 8) & 0o111) !== 0;
              const mode = executable ? 0o755 : 0o644;
              const fd = fs.openSync(target, "wx", mode);
              entry = { remaining: size, padding: (512 - size % 512) % 512, fd, target, mode, relative, hash: crypto.createHash("sha256") };
            }
          }
        }
        if (entry.remaining) {
          if (!buffered.length) break;
          const count = Math.min(buffered.length, entry.remaining);
          const data = buffered.subarray(0, count);
          if (entry.long) entry.long.push(Buffer.from(data));
          else {
            let written = 0;
            while (written < data.length) written += fs.writeSync(entry.fd, data, written, data.length - written);
            entry.hash.update(data);
          }
          buffered = buffered.subarray(count);
          entry.remaining -= count;
          if (entry.remaining) break;
        }
        if (buffered.length < entry.padding) break;
        buffered = buffered.subarray(entry.padding);
        if (entry.fd !== undefined) {
          fs.fchmodSync(entry.fd, entry.mode);
          fs.closeSync(entry.fd);
          delete entry.fd;
          files.push({ path: entry.relative, sha256: entry.hash.digest("hex"), mode: entry.mode });
        }
        if (entry.long) longName = Buffer.concat(entry.long).toString("utf8").replace(/\0$/, "");
        entry = null;
      }
    }
    if (entry || longName || zeroes < 2 || buffered.some((byte) => byte !== 0)) throw new Error("Truncated Linux archive.");
    for (const name of REQUIRED) {
      if (!files.some((file) => file.path === name)) throw new Error(`Incomplete Linux package: ${name}`);
    }
    for (const name of ["river-client", "river-client-bin"]) {
      if (!files.some((file) => file.path === name && file.mode === 0o755)) throw new Error(`Linux executable mode missing: ${name}`);
    }
    return files;
  } finally {
    if (entry?.fd !== undefined) fs.closeSync(entry.fd);
    stream.destroy();
  }
}

async function prepareLinuxUpdate({ archive, installDir, expected }) {
  installDir = fs.realpathSync(installDir);
  if (installDir === path.parse(installDir).root || !fs.existsSync(path.join(installDir, "resources/app.asar"))) throw new Error("Invalid Linux installation.");
  if (fs.statSync(archive).size !== expected.size || await hashFile(archive) !== expected.sha256) throw new Error("Linux package checksum mismatch.");
  fs.accessSync(path.dirname(installDir), fs.constants.W_OK);
  const work = fs.mkdtempSync(path.join(path.dirname(installDir), ".river-update-"));
  fs.chmodSync(work, 0o700);
  const stagedDir = path.join(work, "next");
  const unpacked = path.join(work, "package");
  try {
    const files = await extractLinuxArchive(archive, unpacked);
    // Keep unrecognised folders (including custom game instances) and replace only runtime files.
    fs.cpSync(installDir, stagedDir, { recursive: true, dereference: false, verbatimSymlinks: true });
    for (const name of fs.readdirSync(unpacked)) {
      fs.rmSync(path.join(stagedDir, name), { force: true, recursive: true });
      fs.cpSync(path.join(unpacked, name), path.join(stagedDir, name), { recursive: true });
    }
    fs.rmSync(unpacked, { recursive: true });
    return { installDir, stagedDir, backupDir: path.join(work, "previous"), work, files, pid: process.pid };
  } catch (error) {
    fs.rmSync(work, { recursive: true, force: true });
    throw error;
  }
}

function alive(pid) {
  if (!Number.isInteger(pid) || pid <= 0) return false;
  try { process.kill(pid, 0); return true; } catch (error) { return error.code === "EPERM"; }
}

async function applyLinuxUpdate(job, { rename = fs.renameSync, restart = true, waitMs = 120_000 } = {}) {
  const { installDir, stagedDir, backupDir, work } = job;
  if (path.dirname(work) !== path.dirname(installDir) || path.dirname(stagedDir) !== work || path.dirname(backupDir) !== work || !path.basename(work).startsWith(".river-update-")) {
    throw new Error("Invalid Linux update transaction.");
  }
  const log = (state) => {
    fs.writeFileSync(path.join(work, "transaction.json"), JSON.stringify({ state, installDir, backupDir, stagedDir }) + "\n", { mode: 0o600 });
  };
  const lock = installDir + ".update-lock";
  fs.mkdirSync(lock, { mode: 0o700 });
  let moved = false, installed = false;
  try {
    const deadline = Date.now() + waitMs;
    while (alive(job.pid)) {
      if (Date.now() >= deadline) throw new Error("The previous launcher is still running.");
      await sleep(200);
    }
    for (const file of job.files) {
      const relative = archivePath(ROOT + "/" + file.path);
      const target = path.join(stagedDir, relative);
      if (!fs.lstatSync(target).isFile() || await hashFile(target) !== file.sha256) throw new Error(`The staged Linux update changed: ${file.path}`);
      fs.chmodSync(target, file.mode);
    }
    log("prepared");
    rename(installDir, backupDir);
    moved = true;
    rename(stagedDir, installDir);
    installed = true;
    if (restart) await relaunch(installDir);
    log("completed");
    return { ok: true, backupDir };
  } catch (error) {
    try {
      if (installed) fs.renameSync(installDir, stagedDir);
      if (moved) fs.renameSync(backupDir, installDir);
    } catch (rollbackError) {
      log("restore-required");
      throw new Error(`Linux update recovery needs the retained backup at ${backupDir}: ${rollbackError.message}`, { cause: error });
    }
    log("rolled-back");
    if (restart && !alive(job.pid) && fs.existsSync(path.join(installDir, "river-client"))) await relaunch(installDir).catch(() => {});
    throw error;
  } finally { fs.rmSync(lock, { recursive: true, force: true }); }
}

async function relaunch(directory) {
  const env = { ...process.env };
  delete env.ELECTRON_RUN_AS_NODE;
  const child = spawn(path.join(directory, "river-client"), [], { cwd: directory, detached: true, stdio: "ignore", env });
  await new Promise((resolve, reject) => {
    const timer = setTimeout(resolve, 1500);
    child.once("error", (error) => { clearTimeout(timer); reject(error); });
    child.once("exit", (code) => {
      clearTimeout(timer);
      if (code !== 0) reject(new Error(`The updated Linux launcher exited with code ${code}.`));
      else resolve();
    });
  });
  child.unref();
}

module.exports = { linuxPackage, archivePath, extractLinuxArchive, prepareLinuxUpdate, applyLinuxUpdate, hashFile };

if (require.main === module) {
  const file = process.argv[2];
  Promise.resolve().then(() => applyLinuxUpdate(JSON.parse(fs.readFileSync(file, "utf8"))))
    .catch((error) => { fs.writeFileSync(file + ".error", error.message + "\n", { mode: 0o600 }); process.exitCode = 1; });
}

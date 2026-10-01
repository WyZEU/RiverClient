"use strict";

/**
 * Packs dist/linux-unpacked into dist/River-Client-Linux.tar.gz.
 *
 * electron-builder's own tar.gz target, run on Windows, stores every file as 0644, so the
 * launcher binary comes out of the archive without its executable bit and "./river-client"
 * says "permission denied". Windows has no executable bit to read, and the tar library
 * takes each mode straight from the file system, so the archive is written here by hand:
 * the binaries, helpers and shared libraries 0755, everything else 0644, folders 0755.
 * It is plain ustar with GNU long names, which every tar on Linux reads.
 *
 *   npm run dist:linux     builds linux-unpacked, then runs this
 */

const fs = require("node:fs");
const path = require("node:path");
const zlib = require("node:zlib");

const distDir = path.resolve(__dirname, "..", "dist");
const unpacked = path.join(distDir, "linux-unpacked");
const output = path.join(distDir, "River-Client-Linux.tar.gz");
const folderName = "River-Client-Linux";

const EXECUTABLES = new Set(["river-client", "chrome-sandbox", "chrome_crashpad_handler"]);

const README = `River Client for Linux (experimental)
=====================================

Run it:

    tar -xzf River-Client-Linux.tar.gz
    cd River-Client-Linux
    ./river-client

If it will not start, it is almost always a missing system library. On Debian and
Ubuntu:

    sudo apt install libgtk-3-0 libnss3 libasound2

River downloads Java 21 for you if it does not find one. Updates are manual for now:
River tells you when a new version is out and opens the download page.

Problems: https://discord.riverclient.xyz
`;

function modeFor(name, isDirectory) {
  if (isDirectory) return 0o755;
  if (EXECUTABLES.has(name) || /\.so(\.\d+)*$/.test(name)) return 0o755;
  return 0o644;
}

function octal(value, width) {
  return value.toString(8).padStart(width - 1, "0") + "\0";
}

function header({ name, size, mode, type, mtime }) {
  const buf = Buffer.alloc(512);
  buf.write(name, 0, 100, "utf8");
  buf.write(octal(mode, 8), 100, "latin1");
  buf.write(octal(0, 8), 108, "latin1");
  buf.write(octal(0, 8), 116, "latin1");
  buf.write(octal(size, 12), 124, "latin1");
  buf.write(octal(mtime, 12), 136, "latin1");
  buf.fill(0x20, 148, 156);
  buf.write(type, 156, "latin1");
  buf.write("ustar\0", 257, "latin1");
  buf.write("00", 263, "latin1");
  buf.write("root", 265, "latin1");
  buf.write("root", 297, "latin1");
  let sum = 0;
  for (const byte of buf) sum += byte;
  buf.write(sum.toString(8).padStart(6, "0") + "\0 ", 148, "latin1");
  return buf;
}

function listAll(dir, rel = "") {
  const out = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name))) {
    const relPath = rel ? `${rel}/${entry.name}` : entry.name;
    if (entry.isDirectory()) {
      out.push({ rel: relPath, dir: true }, ...listAll(path.join(dir, entry.name), relPath));
    } else if (entry.isFile()) {
      out.push({ rel: relPath, dir: false });
    }
  }
  return out;
}

async function main() {
  if (!fs.existsSync(path.join(unpacked, "river-client"))) {
    throw new Error(`Nothing to pack: ${unpacked} has no river-client. Run electron-builder --linux dir first.`);
  }
  fs.writeFileSync(path.join(unpacked, "README.txt"), README);
  fs.rmSync(output, { force: true });

  const gzip = zlib.createGzip({ level: 9 });
  const sink = fs.createWriteStream(output);
  gzip.pipe(sink);
  const write = (chunk) => new Promise((resolve, reject) => {
    gzip.write(chunk, (error) => (error ? reject(error) : resolve()));
  });
  const pad = (size) => (size % 512 ? Buffer.alloc(512 - (size % 512)) : Buffer.alloc(0));
  const mtime = Math.floor(Date.now() / 1000);

  const put = async (relName, { dir, filePath }) => {
    const name = `${folderName}/${relName}${dir ? "/" : ""}`.replace(/\/\/$/, "/");
    const base = relName.split("/").pop();
    const size = dir ? 0 : fs.statSync(filePath).size;
    const nameBytes = Buffer.byteLength(name, "utf8");
    if (nameBytes > 100) {
      const long = Buffer.from(name + "\0", "utf8");
      await write(header({ name: "././@LongLink", size: long.length, mode: 0o644, type: "L", mtime }));
      await write(long);
      await write(pad(long.length));
    }
    await write(header({ name: name.slice(0, 100), size, mode: modeFor(base, dir), type: dir ? "5" : "0", mtime }));
    if (!dir) {
      await new Promise((resolve, reject) => {
        const input = fs.createReadStream(filePath);
        input.on("error", reject);
        input.on("end", resolve);
        input.on("data", (chunk) => {
          if (!gzip.write(chunk)) {
            input.pause();
            gzip.once("drain", () => input.resume());
          }
        });
      });
      await write(pad(size));
    }
  };

  await put("", { dir: true });
  for (const entry of listAll(unpacked)) {
    await put(entry.rel, { dir: entry.dir, filePath: path.join(unpacked, ...entry.rel.split("/")) });
  }
  await write(Buffer.alloc(1024));
  await new Promise((resolve, reject) => {
    sink.on("finish", resolve);
    sink.on("error", reject);
    gzip.end();
  });

  const mb = (fs.statSync(output).size / (1024 * 1024)).toFixed(0);
  console.log(`Packed ${output} (${mb} MB)`);
}

main().catch((error) => {
  console.error(error.message);
  process.exit(1);
});

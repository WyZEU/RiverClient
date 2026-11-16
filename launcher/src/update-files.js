const crypto = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");
// Use the publisher's chunking algorithm so existing chunks can be reused.
const { chunkBuffer } = require("../scripts/blockmap.js");
// Electron's fs treats .asar files as directories. Updates must read the archive bytes.
const ofs = (() => { try { return require("original-fs"); } catch { return fs; } })();

function normalizeUpdateFilePath(value) {
  return String(value || "").replace(/\\/g, "/").replace(/^\/+/, "");
}

function listRelativeFiles(root) {
  const files = [];
  if (!root || !ofs.existsSync(root)) return files;
  const stack = [root];
  while (stack.length) {
    const current = stack.pop();
    for (const entry of ofs.readdirSync(current, { withFileTypes: true })) {
      const full = path.join(current, entry.name);
      if (entry.isDirectory()) {
        stack.push(full);
        continue;
      }
      files.push(normalizeUpdateFilePath(path.relative(root, full)));
    }
  }
  return files.sort((left, right) => left.localeCompare(right));
}

/**
 * SHA-256 of a file, read in chunks, without blocking.
 *
 * The alternative is `createHash("sha256").update(ofs.readFileSync(p))`, which loads the
 * whole file into memory before hashing any of it. That is a 213 MB allocation for
 * RiverClient.exe alone, and being synchronous it happens on the main process, where
 * nothing else - no window, no input, no IPC - runs until it finishes.
 */
function hashFileStreaming(filePath) {
  return new Promise((resolve, reject) => {
    const hash = crypto.createHash("sha256");
    const stream = ofs.createReadStream(filePath, { highWaterMark: 1024 * 1024 });
    stream.on("data", (chunk) => hash.update(chunk));
    stream.on("error", reject);
    stream.on("end", () => resolve(hash.digest("hex").toLowerCase()));
  });
}

/*
  Works out which files an update actually has to fetch.

  This checks every file the install is made of, and on a normal update almost all of them
  are unchanged, so almost all of them get hashed: about half a gigabyte across ninety odd
  files. It used to do that synchronously, so the launcher was frozen solid for the whole
  sweep - which is what "a bit laggy when you update" was. It now streams each file and
  yields between them, so the window keeps painting while it works.
*/
async function buildDifferentialUpdatePlan(fileManifest, installDir) {
  const files = Array.isArray(fileManifest?.files)
    ? fileManifest.files
      .map((entry) => ({
        path: normalizeUpdateFilePath(entry.path),
        size: Number(entry.size || 0),
        sha256: String(entry.sha256 || "").trim().toLowerCase(),
        url: String(entry.url || "").trim(),
        blockmapUrl: String(entry.blockmapUrl || "").trim()
      }))
      .filter((entry) => entry.path && entry.url)
    : [];

  const downloads = [];
  for (const entry of files) {
    const localPath = path.join(installDir, ...entry.path.split("/"));
    if (!ofs.existsSync(localPath)) {
      downloads.push(entry);
      continue;
    }
    // Size is checked first because it is a cheap stat and settles most changed files
    // without reading a byte.
    const localSize = Number(ofs.statSync(localPath).size || 0);
    if (entry.size > 0 && localSize !== entry.size) {
      downloads.push(entry);
      continue;
    }
    if (entry.sha256) {
      let localSha = "";
      try {
        localSha = await hashFileStreaming(localPath);
      } catch {
        // Unreadable means it cannot be verified, so fetch it rather than trust it.
        downloads.push(entry);
        continue;
      }
      if (localSha !== entry.sha256) downloads.push(entry);
    }
  }

  return {
    version: String(fileManifest?.version || ""),
    installDir,
    files,
    downloads,
    totalBytes: downloads.reduce((sum, entry) => sum + Number(entry.size || 0), 0),
    existingFiles: listRelativeFiles(installDir)
  };
}

/*
  Rebuilds a changed file from the copy already on disk plus the few parts of it that
  actually differ.

  File level diffing already skips anything unchanged, which covers the Electron DLLs.
  It does nothing for the two files that change on every single build - RiverClient.exe,
  because the ASAR integrity hash and the signature are written into it, and app.asar,
  because it holds our own JavaScript. Those two are 321MB together and almost none of
  either is different, so every update re-downloaded a third of a gigabyte to change a
  couple of megabytes.

  The server publishes a blockmap beside each big file: the content-defined chunks it is
  made of, and a hash for each. Chunk the local copy the same way and most of those
  hashes are already on disk, so only the rest has to come over the network, as a handful
  of range requests.

  Everything here is best effort. The rebuilt file is checked against the whole-file hash
  from the release manifest, and anything at all that does not line up - no blockmap, an
  algorithm this build does not know, a bad range response, a hash that does not match -
  returns false and lets the caller download the file the ordinary way. It can make an
  update smaller; it must never make one wrong.
*/
async function downloadFileViaBlockmap(file, localPath, target, onProgress, {
  fetch: fetchRemote = globalThis.fetch,
  userAgent = "",
  log = () => {},
  formatBytes = (value) => `${value} bytes`
} = {}) {
  if (!file.blockmapUrl || !file.sha256 || !localPath || !ofs.existsSync(localPath)) return false;

  let map;
  try {
    const response = await fetchRemote(file.blockmapUrl, {
      headers: { "User-Agent": userAgent },
      signal: AbortSignal.timeout(30000)
    });
    if (!response.ok) return false;
    map = await response.json();
  } catch {
    return false;
  }
  if (!map || map.algorithm !== "gear-cdc-1" || !Array.isArray(map.chunks) || !map.chunks.length) return false;
  if (Number(map.size) !== Number(file.size)) return false;

  // Index the local copy by chunk hash. The bytes are dropped straight after; only the
  // offsets are kept, and the ranges are read back off disk when they are needed.
  const local = new Map();
  try {
    const buffer = ofs.readFileSync(localPath);
    let offset = 0;
    for (const chunk of chunkBuffer(buffer)) {
      if (!local.has(chunk.hash)) local.set(chunk.hash, { offset, size: chunk.size });
      offset += chunk.size;
    }
  } catch {
    // Too big to hold, or unreadable. Either way this is not the path to take.
    return false;
  }

  // Work out what comes from disk and what has to be fetched, coalescing neighbouring
  // missing chunks so this is a few range requests rather than one per chunk.
  const steps = [];
  let position = 0;
  let missing = 0;
  for (const chunk of map.chunks) {
    const size = Number(chunk.size || 0);
    if (!size) return false;
    const hit = local.get(chunk.hash);
    if (hit && hit.size === size) {
      steps.push({ kind: "local", at: position, from: hit.offset, size });
    } else {
      const last = steps[steps.length - 1];
      if (last && last.kind === "remote" && last.at + last.size === position) last.size += size;
      else steps.push({ kind: "remote", at: position, size });
      missing += size;
    }
    position += size;
  }
  if (position !== Number(file.size)) return false;

  // Nothing to gain if most of it has to come anyway.
  if (missing > Number(file.size) * 0.75) return false;

  log(`[update] ${file.path}: ${formatBytes(missing)} of ${formatBytes(file.size)} changed`);

  let readFd = null;
  let writeFd = null;
  try {
    readFd = ofs.openSync(localPath, "r");
    writeFd = ofs.openSync(target, "w");
    let done = 0;
    for (const step of steps) {
      if (step.kind === "local") {
        const buffer = Buffer.allocUnsafe(step.size);
        ofs.readSync(readFd, buffer, 0, step.size, step.from);
        ofs.writeSync(writeFd, buffer, 0, step.size, step.at);
      } else {
        const response = await fetchRemote(file.url, {
          headers: {
            "User-Agent": userAgent,
            Range: `bytes=${step.at}-${step.at + step.size - 1}`
          },
          signal: AbortSignal.timeout(120000)
        });
        // A server that ignored the range would hand back the whole file, which would be
        // written to the wrong place. Only a 206 means the range was honoured.
        if (response.status !== 206) return false;
        const buffer = Buffer.from(await response.arrayBuffer());
        if (buffer.length !== step.size) return false;
        ofs.writeSync(writeFd, buffer, 0, buffer.length, step.at);
        done += buffer.length;
        if (onProgress) onProgress({ downloaded: done, total: missing });
      }
    }
  } catch {
    return false;
  } finally {
    if (readFd !== null) { try { ofs.closeSync(readFd); } catch {} }
    if (writeFd !== null) { try { ofs.closeSync(writeFd); } catch {} }
  }

  // The whole point of the exercise is that this cannot be taken on trust.
  let rebuilt = "";
  try {
    rebuilt = await hashFileStreaming(target);
  } catch {
    return false;
  }
  if (rebuilt !== file.sha256) {
    log(`[update] ${file.path}: rebuilt copy did not match, downloading it in full`);
    try { ofs.rmSync(target, { force: true }); } catch {}
    return false;
  }

  log(`[update] ${file.path}: rebuilt from disk, ${formatBytes(missing)} downloaded`);
  return true;
}

module.exports = { normalizeUpdateFilePath, buildDifferentialUpdatePlan, downloadFileViaBlockmap };

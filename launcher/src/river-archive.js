const fs = require("node:fs");
const fsp = require("node:fs/promises");
const path = require("node:path");
const { once } = require("node:events");
const { Transform } = require("node:stream");
const { pipeline } = require("node:stream/promises");
const { crc32 } = require("node:zlib");
const yauzl = require("yauzl");
const yazl = require("yazl");

// Use ZIP's slash-separated paths on every OS. Reject names that Windows would
// interpret as device paths, alternate data streams or aliases of another file.
function archivePathParts(name) {
  const parts = name.replace(/\\/g, "/").replace(/\/$/, "").split("/");
  if (parts.some((part) => !part || part === "." || part === ".." ||
    /[<>:"|?*\x00-\x1f]/.test(part) || /[. ]$/.test(part) ||
    /^(?:con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)/i.test(part))) {
    throw new Error(`Unsafe archive path: ${name}`);
  }
  return parts;
}

async function compressPathsToRiverArchive(sourcePaths, destinationRvrPath) {
  const entries = [];
  const names = new Set();
  async function collect(source, name) {
    const portableName = archivePathParts(name).join("/");
    const key = portableName.normalize("NFC").toLowerCase();
    if (names.has(key)) throw new Error(`Duplicate archive path: ${name}`);
    names.add(key);
    const stat = await fsp.lstat(source);
    if (!stat.isFile() && !stat.isDirectory()) throw new Error(`Cannot archive a link or special file: ${name}`);
    entries.push({ source, name: portableName, stat });
    if (stat.isDirectory()) {
      for (const child of await fsp.readdir(source)) await collect(path.join(source, child), `${portableName}/${child}`);
    }
  }
  for (const source of sourcePaths) await collect(source, path.basename(source));

  // Stage beside the destination so the final rename is on the same filesystem.
  // A failed export leaves any previously exported .rvr intact.
  const destination = path.resolve(destinationRvrPath);
  const staging = await fsp.mkdtemp(path.join(path.dirname(destination), ".river-export-"));
  const temporary = path.join(staging, "archive.zip");
  const zip = new yazl.ZipFile();
  zip.on("error", (error) => zip.outputStream.destroy(error));
  const writing = pipeline(zip.outputStream, fs.createWriteStream(temporary, { flags: "wx" }));
  try {
    for (const { source, name, stat } of entries) {
      if (stat.isDirectory()) zip.addEmptyDirectory(name, { mtime: stat.mtime });
      else zip.addFile(source, name, { compressionLevel: 1 });
    }
    zip.end();
    await writing;
    await fsp.rename(temporary, destination);
  } catch (error) {
    zip.outputStream.destroy(error);
    await writing.catch(() => {});
    throw error;
  } finally {
    await fsp.rm(staging, { recursive: true, force: true });
  }
}

async function extractRiverArchive(archivePath, destinationDir) {
  await fsp.mkdir(destinationDir, { recursive: true });
  if (!(await fsp.lstat(destinationDir)).isDirectory() || (await fsp.readdir(destinationDir)).length) {
    throw new Error("River archives must be extracted into an empty directory.");
  }
  const zip = await yauzl.openPromise(archivePath, { lazyEntries: true, autoClose: false });
  try {
    for await (const entry of zip.eachEntry()) {
      const target = path.join(destinationDir, ...archivePathParts(entry.fileName));
      const type = (entry.externalFileAttributes >>> 16) & 0xf000;
      if (type && type !== 0x8000 && type !== 0x4000) throw new Error(`Unsupported archive entry: ${entry.fileName}`);
      if (entry.fileName.endsWith("/")) {
        await fsp.mkdir(target, { recursive: true });
        continue;
      }
      if (type === 0x4000) throw new Error(`Invalid directory entry: ${entry.fileName}`);
      await fsp.mkdir(path.dirname(target), { recursive: true });
      let checksum = 0;
      const verify = new Transform({
        transform(chunk, _encoding, callback) {
          checksum = crc32(chunk, checksum);
          callback(null, chunk);
        }
      });
      await pipeline(await zip.openReadStreamPromise(entry), verify, fs.createWriteStream(target, { flags: "wx" }));
      if (checksum !== entry.crc32) throw new Error(`Archive checksum mismatch: ${entry.fileName}`);
    }
  } finally {
    // Wait for the OS handle to close before the caller can move/delete the archive,
    // which Windows does not allow while a reader still holds it open.
    const closed = once(zip, "close");
    zip.close();
    await closed;
  }
}

module.exports = { compressPathsToRiverArchive, extractRiverArchive, archivePathParts };

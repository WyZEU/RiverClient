"use strict";

const fs = require("node:fs");
const path = require("node:path");
const crypto = require("node:crypto");
const { Transform } = require("node:stream");
const { pipeline } = require("node:stream/promises");
const yauzl = require("yauzl");
const signing = require("../windows-signing.json");

const ARTIFACT_FILES = ["River-Client-Setup.exe", "River-Client-Portable.exe", "River-Client-App.zip",
  "WINDOWS-BUILD-INFO.json", "WINDOWS-FILES.json", "signatures.jsonl", "SHA256SUMS.txt"];
const MAX_BYTES = 8 * 1024 ** 3;
const isHash = (value) => typeof value === "string" && /^[a-f0-9]{64}$/.test(value);
function assert(condition, message) { if (!condition) throw new Error(message); }
function assertCommit(value) { assert(/^[a-f0-9]{40}$/.test(value || ""), "A full expected source commit is required."); }
function json(file) {
  assert(fs.statSync(file).size <= 16 * 1024 ** 2, `JSON metadata is too large: ${path.basename(file)}`);
  return JSON.parse(fs.readFileSync(file, "utf8").replace(/^\uFEFF/, ""));
}
function safePath(name) {
  assert(typeof name === "string" && name.length > 0 && name.length < 1024, "Invalid release path.");
  const parts = name.split("/");
  assert(parts.every((part) => part && part !== "." && part !== ".." &&
    !/[\\:*?"<>|\x00-\x1f\x7f]/.test(part) && !/[. ]$/.test(part) &&
    !/^(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)/i.test(part)), `Unsafe release path: ${name}`);
  return name;
}
function regularFile(root, name) {
  safePath(name);
  let current = path.resolve(root);
  assert(!fs.lstatSync(current).isSymbolicLink(), "Release root cannot be a symlink.");
  for (const part of name.split("/")) {
    current = path.join(current, part);
    assert(!fs.lstatSync(current).isSymbolicLink(), `Symlink in release path: ${name}`);
  }
  assert(fs.statSync(current).isFile(), `Expected a regular release file: ${name}`);
  return current;
}
async function digestStream(stream, maximum = MAX_BYTES) {
  const hash = crypto.createHash("sha256");
  let size = 0;
  for await (const chunk of stream) {
    size += chunk.length;
    assert(size <= maximum, "Release data exceeds its expected size.");
    hash.update(chunk);
  }
  return { size, sha256: hash.digest("hex") };
}
const digestFile = (file) => digestStream(fs.createReadStream(file));
function fileIndex(files) {
  assert(Array.isArray(files) && files.length > 0 && files.length <= 10000, "Invalid release file inventory.");
  const names = new Set();
  const result = new Map();
  let total = 0;
  for (const file of files) {
    safePath(file.path);
    assert(!names.has(file.path.toLowerCase()), `Duplicate release path: ${file.path}`);
    names.add(file.path.toLowerCase());
    assert(Number.isSafeInteger(file.size) && file.size >= 0 && isHash(file.sha256), "Invalid release size/hash.");
    total += file.size;
    assert(total <= MAX_BYTES, "Unpacked release is too large.");
    result.set(file.path, file);
  }
  return result;
}

// Never extract unlisted files, links, duplicate Windows paths or oversized ZIP entries.
// expected can be an inventory Map or a Set for the small, outer Actions archive.
async function extractZip(archive, destination, expected) {
  assert(!fs.existsSync(destination), "Extraction destination already exists.");
  fs.mkdirSync(destination, { recursive: true });
  const seen = new Set();
  let total = 0;
  let zip;
  try {
    zip = await yauzl.openPromise(archive, { lazyEntries: true, strictFileNames: true, validateEntrySizes: true });
    for await (const entry of zip.eachEntry()) {
      const directory = entry.fileName.endsWith("/");
      const name = safePath(directory ? entry.fileName.slice(0, -1) : entry.fileName);
      const type = (entry.externalFileAttributes >>> 16) & 0xf000;
      assert(type === 0 || type === (directory ? 0x4000 : 0x8000), `Unsupported ZIP entry: ${name}`);
      assert(!(entry.generalPurposeBitFlag & 1), "Encrypted release ZIP is not supported.");
      assert(!seen.has(name.toLowerCase()), `Duplicate ZIP entry: ${name}`);
      seen.add(name.toLowerCase());
      if (directory) {
        assert([...expected.keys()].some((file) => file.startsWith(`${name}/`)), `Unlisted ZIP directory: ${name}`);
        continue;
      }
      assert(expected.has(name), `Unlisted ZIP file: ${name}`);
      const metadata = expected instanceof Map ? expected.get(name) : null;
      assert(Number.isSafeInteger(entry.uncompressedSize) && entry.uncompressedSize >= 0, "Invalid ZIP size.");
      if (metadata) assert(entry.uncompressedSize === metadata.size, `ZIP size mismatch: ${name}`);
      total += entry.uncompressedSize;
      assert(total <= MAX_BYTES, "Release ZIP is too large.");
      const target = path.join(destination, ...name.split("/"));
      fs.mkdirSync(path.dirname(target), { recursive: true });
      const hash = crypto.createHash("sha256");
      let count = 0;
      const meter = new Transform({ transform(chunk, _encoding, callback) {
        count += chunk.length;
        if (count > entry.uncompressedSize) return callback(new Error(`Oversized ZIP entry: ${name}`));
        hash.update(chunk); callback(null, chunk);
      } });
      await pipeline(await zip.openReadStreamPromise(entry), meter, fs.createWriteStream(target, { flags: "wx" }));
      assert(count === entry.uncompressedSize, `Truncated ZIP entry: ${name}`);
      if (metadata) assert(hash.digest("hex") === metadata.sha256, `ZIP checksum mismatch: ${name}`);
    }
    for (const name of expected.keys()) assert(seen.has(name.toLowerCase()), `ZIP omitted ${name}`);
  } catch (error) {
    fs.rmSync(destination, { recursive: true, force: true });
    throw error;
  } finally { zip?.close(); }
}

async function verifyArtifact(directory, expectedCommit) {
  assertCommit(expectedCommit);
  assert(fs.readdirSync(directory).sort().join("\n") === [...ARTIFACT_FILES].sort().join("\n"), "Unexpected signed artifact contents.");
  for (const name of ARTIFACT_FILES) regularFile(directory, name);
  const lines = fs.readFileSync(path.join(directory, "SHA256SUMS.txt"), "utf8").replace(/^\uFEFF/, "").trim().split(/\r?\n/);
  const sums = new Map();
  for (const line of lines) {
    const match = /^([a-f0-9]{64}) {2}([^/\\]+)$/.exec(line);
    assert(match && ARTIFACT_FILES.includes(match[2]) && match[2] !== "SHA256SUMS.txt" && !sums.has(match[2]), "Invalid artifact checksum inventory.");
    sums.set(match[2], match[1]);
  }
  assert(sums.size === ARTIFACT_FILES.length - 1, "Incomplete artifact checksums.");
  const hashes = new Map();
  for (const [name, hash] of sums) {
    const actual = await digestFile(path.join(directory, name));
    assert(actual.sha256 === hash, `Artifact checksum mismatch: ${name}`);
    hashes.set(name, actual);
  }
  const info = json(path.join(directory, "WINDOWS-BUILD-INFO.json"));
  const inventory = json(path.join(directory, "WINDOWS-FILES.json"));
  assert(info.sourceCommit === expectedCommit && inventory.sourceCommit === expectedCommit, "Artifact source commit mismatch.");
  assert(/^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/.test(info.version) && inventory.version === info.version, "Artifact version mismatch.");
  const files = fileIndex(inventory.files);
  assert(info.signing?.subject === signing.subject, "Unexpected Windows publisher.");
  for (const name of ["install", "electron-runtime", "media-helper", "uninstall"]) {
    assert(info.checks?.some((check) => check.name === name && check.exitCode === 0), `Missing successful Windows check: ${name}`);
  }
  const logs = fs.readFileSync(path.join(directory, "signatures.jsonl"), "utf8").replace(/^\uFEFF/, "").trim().split(/\r?\n/).map(JSON.parse);
  const signed = [
    ["River-Client-Setup.exe", hashes.get("River-Client-Setup.exe")],
    ["River-Client-Portable.exe", hashes.get("River-Client-Portable.exe")],
    ["RiverClient.exe", files.get("RiverClient.exe")],
    ["np-helper.exe", files.get("resources/app.asar.unpacked/app/np-helper.exe")]
  ];
  for (const [name, file] of signed) {
    assert(file && info.signatures?.some((s) => s.file.replace(/\\/g, "/").split("/").pop() === name && s.sha256 === file.sha256 && s.subject === signing.subject), `Missing signature evidence: ${name}`);
    assert(logs.some((s) => s.sha256 === file.sha256 && s.subject === signing.subject && s.timestampSubject), `Missing timestamped signing log: ${name}`);
  }
  const runtime = info.runtime;
  assert(runtime?.sourceCommit === expectedCommit && runtime.version === info.version && Array.isArray(runtime.minecraftVersions) && runtime.minecraftVersions.length, "Missing runtime provenance.");
  assert(Array.isArray(runtime.files) && runtime.files.length === runtime.minecraftVersions.length + 1, "Incomplete runtime inventory.");
  assert(new Set(runtime.minecraftVersions).size === runtime.minecraftVersions.length, "Duplicate Minecraft target.");
  const jarNames = runtime.minecraftVersions.map((mc) => `clientcore-${info.version}+${mc}.jar`);
  assert(runtime.files.filter((f) => /^river-bootstrap-[0-9.]+\.jar$/.test(f.name)).length === 1, "Missing bootstrap JAR.");
  for (const name of jarNames) assert(runtime.files.some((f) => f.name === name), `Missing Minecraft target: ${name}`);
  for (const file of runtime.files) {
    assert(!file.name.includes("/"), "Invalid bundled JAR name.");
    const entry = files.get(`resources/bundled/${file.name}`);
    assert(entry && entry.sha256 === file.sha256 && entry.size === file.size, `Runtime JAR differs from staged build: ${file.name}`);
  }
  return { info, files, hashes };
}

module.exports = { ARTIFACT_FILES, MAX_BYTES, assert, assertCommit, isHash, safePath, regularFile, json,
  digestStream, digestFile, fileIndex, extractZip, verifyArtifact };

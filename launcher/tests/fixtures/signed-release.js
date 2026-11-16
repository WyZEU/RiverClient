const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const crypto = require("node:crypto");
const signing = require("../../windows-signing.json");
const { ARTIFACT_FILES } = require("../../scripts/release-artifact");
const hash = (bytes) => crypto.createHash("sha256").update(bytes).digest("hex");

// Tiny independent stored-ZIP writer for malformed-archive and extraction fixtures.
function zip(entries) {
  const local = [], central = [];
  let offset = 0;
  for (const { name, bytes, mode = 0x81a4 } of entries) {
    const filename = Buffer.from(name), data = Buffer.from(bytes);
    let crc = 0xffffffff;
    for (const byte of data) {
      crc ^= byte;
      for (let bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ ((crc & 1) ? 0xedb88320 : 0);
    }
    crc = (crc ^ 0xffffffff) >>> 0;
    const header = Buffer.alloc(30);
    header.writeUInt32LE(0x04034b50); header.writeUInt16LE(20, 4);
    header.writeUInt32LE(crc, 14); header.writeUInt32LE(data.length, 18); header.writeUInt32LE(data.length, 22);
    header.writeUInt16LE(filename.length, 26);
    local.push(header, filename, data);
    const directory = Buffer.alloc(46);
    directory.writeUInt32LE(0x02014b50); directory.writeUInt16LE(0x0314, 4); directory.writeUInt16LE(20, 6);
    directory.writeUInt32LE(crc, 16); directory.writeUInt32LE(data.length, 20); directory.writeUInt32LE(data.length, 24);
    directory.writeUInt16LE(filename.length, 28); directory.writeUInt32LE((mode * 65536) >>> 0, 38); directory.writeUInt32LE(offset, 42);
    central.push(directory, filename);
    offset += header.length + filename.length + data.length;
  }
  const directory = Buffer.concat(central), end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50); end.writeUInt16LE(entries.length, 8); end.writeUInt16LE(entries.length, 10);
  end.writeUInt32LE(directory.length, 12); end.writeUInt32LE(offset, 16);
  return Buffer.concat([...local, directory, end]);
}

function fixture(t, { large = false } = {}) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "river-publish-test-"));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const artifactDirectory = path.join(root, "artifact"), outputDirectory = path.join(root, "prepared");
  fs.mkdirSync(artifactDirectory);
  const version = "0.3.1", commit = "c".repeat(40);
  // 1.21.11 and the bootstrap ship in the app; 26.3 travels separately in River-Client-Mods.zip.
  const jars = [{ name: `clientcore-${version}+1.21.11.jar`, bytes: Buffer.from("PK synthetic default mod") },
    { name: "river-bootstrap-0.1.9.0.jar", bytes: Buffer.from("PK synthetic bootstrap") }];
  const separateJars = [{ name: `clientcore-${version}+26.3.jar`, bytes: Buffer.from("PK synthetic mod") }];
  const runtime = { schemaVersion: 1, version, sourceCommit: commit, minecraftVersions: ["1.21.11", "26.3"], bundledMinecraft: "1.21.11",
    files: [...jars, ...separateJars].map(({ name, bytes }) => ({ name, size: bytes.length, sha256: hash(bytes) })) };
  const app = [
    { name: "RiverClient.exe", bytes: Buffer.from("MZ synthetic signed app") },
    { name: "resources/app.asar.unpacked/app/np-helper.exe", bytes: Buffer.from("MZ synthetic signed helper") },
    { name: "resources/app.asar", bytes: Buffer.alloc(large ? 8 * 1024 ** 2 : 128, 0x41) },
    { name: "resources/bundled/river-build-info.json", bytes: Buffer.from(JSON.stringify(runtime)) },
    ...jars.map(({ name, bytes }) => ({ name: `resources/bundled/${name}`, bytes }))
  ];
  const write = (name, bytes) => fs.writeFileSync(path.join(artifactDirectory, name), bytes);
  write("River-Client-Setup.exe", "MZ synthetic signed installer");
  write("River-Client-Portable.exe", "MZ synthetic signed portable");
  write("River-Client-App.zip", zip(app));
  write("River-Client-Mods.zip", zip(separateJars));
  const inventory = { sourceCommit: commit, version, files: app.map(({ name, bytes }) => ({ path: name, size: bytes.length, sha256: hash(bytes) })) };
  write("WINDOWS-FILES.json", JSON.stringify(inventory));
  const signed = [
    ...["River-Client-Setup.exe", "River-Client-Portable.exe"].map((name) => ({ file: name, sha256: hash(fs.readFileSync(path.join(artifactDirectory, name))) })),
    ...app.filter((entry) => entry.name.endsWith(".exe")).map((entry) => ({ file: entry.name, sha256: hash(entry.bytes) }))
  ].map((entry) => ({ ...entry, subject: signing.subject, thumbprint: "synthetic-test-only" }));
  const info = { sourceCommit: commit, version, signing, runtime, signatures: signed,
    checks: ["install", "electron-runtime", "media-helper", "uninstall"].map((name) => ({ name, exitCode: 0 })) };
  write("WINDOWS-BUILD-INFO.json", JSON.stringify(info));
  write("signatures.jsonl", signed.map((entry) => JSON.stringify({ ...entry, path: entry.file, timestampSubject: "test timestamp" })).join("\n") + "\n");
  function checksums() {
    write("SHA256SUMS.txt", ARTIFACT_FILES.filter((name) => name !== "SHA256SUMS.txt")
      .map((name) => `${hash(fs.readFileSync(path.join(artifactDirectory, name)))}  ${name}`).join("\n") + "\n");
  }
  checksums();
  const linuxArtifactDirectory = path.join(root, "linux-artifact");
  fs.mkdirSync(linuxArtifactDirectory);
  const linuxBytes = require("node:zlib").gzipSync("synthetic Linux distribution");
  fs.writeFileSync(path.join(linuxArtifactDirectory, "River-Client-Linux.tar.gz"), linuxBytes);
  const linuxInfo = { schemaVersion: 1, platform: "linux", arch: "x64", version, sourceCommit: commit, runtime,
    archive: { name: "River-Client-Linux.tar.gz", size: linuxBytes.length, sha256: hash(linuxBytes) } };
  fs.writeFileSync(path.join(linuxArtifactDirectory, "LINUX-BUILD-INFO.json"), JSON.stringify(linuxInfo));
  const pair = crypto.generateKeyPairSync("ed25519");
  return { root, artifactDirectory, linuxArtifactDirectory, linuxInfo, outputDirectory, app, info, inventory, write, checksums,
    privateKey: pair.privateKey.export({ type: "pkcs8", format: "pem" }),
    publicKey: pair.publicKey.export({ type: "spki", format: "der" }).toString("base64"),
    options: { artifactDirectory, linuxArtifactDirectory, outputDirectory, expectedCommit: commit, channel: "stable", minimumVersion: "0.2.9",
      publishedAt: "2026-11-16T11:00:00.000Z", notes: { version, approved: true, title: "River Client 0.3.1", summary: "Test release", items: ["Signed Windows distribution"] } } };
}

module.exports = { fixture, zip, hash };

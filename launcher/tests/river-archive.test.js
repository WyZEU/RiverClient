const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { crc32 } = require("node:zlib");
const { pipeline } = require("node:stream/promises");
const yazl = require("yazl");
const { compressPathsToRiverArchive, extractRiverArchive, archivePathParts } = require("../src/river-archive.js");

function temporary(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "river-archive-test-"));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  return root;
}

// Independent stored-ZIP fixture: allows Windows backslashes and malformed names
// that yazl deliberately refuses to create. No OS archiver is needed to run tests.
function storedZip(entries) {
  const files = [], directory = [];
  let offset = 0;
  for (const { name, data = "example", mode = 0, checksum } of entries) {
    const nameBytes = Buffer.from(name), bytes = Buffer.from(data);
    const crc = checksum ?? crc32(bytes);
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50);
    local.writeUInt16LE(20, 4);
    local.writeUInt16LE(0x800, 6);
    local.writeUInt32LE(crc, 14);
    local.writeUInt32LE(bytes.length, 18);
    local.writeUInt32LE(bytes.length, 22);
    local.writeUInt16LE(nameBytes.length, 26);
    const central = Buffer.alloc(46);
    central.writeUInt32LE(0x02014b50);
    central.writeUInt16LE(0x0314, 4);
    central.writeUInt16LE(20, 6);
    central.writeUInt16LE(0x800, 8);
    central.writeUInt32LE(crc, 16);
    central.writeUInt32LE(bytes.length, 20);
    central.writeUInt32LE(bytes.length, 24);
    central.writeUInt16LE(nameBytes.length, 28);
    central.writeUInt32LE((mode << 16) >>> 0, 38);
    central.writeUInt32LE(offset, 42);
    files.push(local, nameBytes, bytes);
    directory.push(central, nameBytes);
    offset += local.length + nameBytes.length + bytes.length;
  }
  const central = Buffer.concat(directory), end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50);
  end.writeUInt16LE(entries.length, 8);
  end.writeUInt16LE(entries.length, 10);
  end.writeUInt32LE(central.length, 12);
  end.writeUInt32LE(offset, 16);
  return Buffer.concat([...files, central, end]);
}

test("RVR round trip preserves root layout, Unicode, hidden files and empty directories", async (t) => {
  const root = temporary(t), source = path.join(root, "source"), destination = path.join(root, "import");
  const contents = {
    "river-manifest.json": Buffer.from('{"format":"river.rvr","version":1}'),
    "mods/custom mod.jar": Buffer.alloc(2 * 1024 * 1024, 42),
    "config/žluťoučký kůň.json": Buffer.from('{"name":"Příliš žluťoučký"}'),
    ".hidden": Buffer.from("included"),
    "config/empty.json": Buffer.alloc(0)
  };
  for (const [name, bytes] of Object.entries(contents)) {
    const file = path.join(source, name);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, bytes);
  }
  fs.mkdirSync(path.join(source, "shaderpacks"));
  const archive = path.join(root, "instance with spaces.rvr");
  fs.writeFileSync(archive, "previous export");
  await compressPathsToRiverArchive(fs.readdirSync(source).map((name) => path.join(source, name)), archive);
  await extractRiverArchive(archive, destination);
  for (const [name, bytes] of Object.entries(contents)) assert.deepEqual(fs.readFileSync(path.join(destination, name)), bytes);
  assert.deepEqual(fs.readdirSync(path.join(destination, "shaderpacks")), []);
  assert.equal(fs.readdirSync(root).some((name) => name.startsWith(".river-export-")), false);
});

test("legacy Windows ZIP entries are restored as directories on every OS", async (t) => {
  const root = temporary(t), archive = path.join(root, "old.rvr");
  fs.writeFileSync(archive, storedZip([{ name: "mods\\můj mod.jar", data: "jar" }, { name: "resourcepacks\\" }]));
  await extractRiverArchive(archive, path.join(root, "out"));
  assert.equal(fs.readFileSync(path.join(root, "out", "mods", "můj mod.jar"), "utf8"), "jar");
  assert.ok(fs.statSync(path.join(root, "out", "resourcepacks")).isDirectory());
});

test("ZIP64 archives can be imported without renaming the .rvr file", async (t) => {
  const root = temporary(t), archive = path.join(root, "zip64.rvr"), zip = new yazl.ZipFile();
  const writing = pipeline(zip.outputStream, fs.createWriteStream(archive));
  zip.addBuffer(Buffer.from("ZIP64"), "mods/example.jar", { forceZip64Format: true });
  zip.end({ forceZip64Format: true });
  await writing;
  await extractRiverArchive(archive, path.join(root, "out"));
  assert.equal(fs.readFileSync(path.join(root, "out", "mods", "example.jar"), "utf8"), "ZIP64");
});

test("failed exports leave an existing archive intact", async (t) => {
  const root = temporary(t), archive = path.join(root, "existing.rvr");
  fs.writeFileSync(archive, "previous export");
  await assert.rejects(compressPathsToRiverArchive([path.join(root, "missing")], archive), /ENOENT/);
  assert.equal(fs.readFileSync(archive, "utf8"), "previous export");
});

test("concurrent exports use separate staging files", async (t) => {
  const root = temporary(t), source = path.join(root, "instance.json");
  fs.writeFileSync(source, "instance");
  await Promise.all(["one", "two"].map(async (name) => {
    const archive = path.join(root, `${name}.rvr`), out = path.join(root, name);
    await compressPathsToRiverArchive([source], archive);
    await extractRiverArchive(archive, out);
    assert.equal(fs.readFileSync(path.join(out, "instance.json"), "utf8"), "instance");
  }));
});

test("malformed and corrupt archives are rejected", async (t) => {
  const root = temporary(t);
  for (const [name, bytes] of [
    ["invalid", Buffer.from("not a ZIP")],
    ["truncated", storedZip([{ name: "mods/example.jar" }]).subarray(0, 40)],
    ["checksum", storedZip([{ name: "mods/example.jar", checksum: 1 }])]
  ]) {
    const archive = path.join(root, `${name}.rvr`);
    fs.writeFileSync(archive, bytes);
    await assert.rejects(extractRiverArchive(archive, path.join(root, name)));
  }
});

test("archive and settings paths cannot escape their destination or use Windows device paths", async (t) => {
  const root = temporary(t);
  for (const [index, name] of ["../escape", "..\\escape", "/escape", "C:\\escape", "file:stream", "NUL.txt", "mods/../escape", "mods /file"].entries()) {
    assert.throws(() => archivePathParts(name));
    const archive = path.join(root, `${index}.rvr`);
    fs.writeFileSync(archive, storedZip([{ name }]));
    await assert.rejects(extractRiverArchive(archive, path.join(root, `out-${index}`)));
  }
  assert.equal(fs.existsSync(path.join(root, "escape")), false);
});

test("links and duplicate file entries cannot overwrite other files", async (t) => {
  const root = temporary(t);
  for (const [name, entries] of [
    ["link", [{ name: "escape", data: "../outside", mode: 0o120777 }]],
    ["duplicate", [{ name: "file", data: "one" }, { name: "file", data: "two" }]]
  ]) {
    const archive = path.join(root, `${name}.rvr`);
    fs.writeFileSync(archive, storedZip(entries));
    await assert.rejects(extractRiverArchive(archive, path.join(root, name)));
  }
  const out = path.join(root, "nonempty");
  fs.mkdirSync(out);
  fs.writeFileSync(path.join(out, "precious"), "unchanged");
  await assert.rejects(extractRiverArchive(path.join(root, "duplicate.rvr"), out), /empty directory/);
  assert.equal(fs.readFileSync(path.join(out, "precious"), "utf8"), "unchanged");
});

const test = require("node:test");
const assert = require("node:assert/strict");
const crypto = require("node:crypto");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const vm = require("node:vm");
const { installContentFile } = require("../src/content-install.js");

const before = Buffer.from("previous working mod");
const after = Buffer.from("verified new mod");
const source = fs.readFileSync(path.join(__dirname, "../src/main.js"), "utf8");
const start = source.indexOf("async function installModrinthVersion(");
assert.notEqual(start, -1);
const installSource = source.slice(start, source.indexOf("\n}", start) + 2);

// Exercise the launcher's real orchestration and file transaction together. Only remote
// metadata/downloads are simulated; installed files and the manifest live on disk.
function fixture(t, { sameName = false, disabled = false, io = fs } = {}) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "river-content-test-"));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const mods = path.join(root, "mods");
  fs.mkdirSync(mods);
  const oldName = disabled ? "old.jar.disabled" : "old.jar";
  const newName = sameName ? oldName : "new.jar";
  const oldPath = path.join(mods, oldName);
  const newPath = path.join(mods, newName);
  const manifestPath = path.join(root, "manifest.json");
  fs.writeFileSync(oldPath, before);
  const initial = {
    mods: { [oldName]: { projectId: "example", versionId: "old-version", disabled } },
    resourcepacks: {}, shaders: {}, updates: { [`mod:${oldName}`]: { versionId: "new-version" } }, checkedAt: "before"
  };
  fs.writeFileSync(manifestPath, JSON.stringify(initial));
  const originalManifest = fs.readFileSync(manifestPath);
  const file = {
    filename: newName, url: "https://example.invalid/new.jar", size: after.length,
    hashes: { sha512: crypto.createHash("sha512").update(after).digest("hex") }
  };
  const context = vm.createContext({
    fs, path, installContentFile: (args) => installContentFile(args, io),
    contentTypeInfo: () => ({ key: "mods", folder: "mods", label: "mod", extensions: [".jar"] }),
    normalizeContentType: (type) => type,
    getModrinthProject: async () => ({ id: "example", title: "Example" }),
    readModManifest: () => JSON.parse(fs.readFileSync(manifestPath, "utf8")),
    modManifestPath: () => manifestPath,
    manifestSection: (manifest) => manifest.mods,
    findIncomingConflict: () => null,
    updateKey: (type, name) => `${type}:${name}`,
    sanitizeFilename: (name) => name,
    downloadFile: async (_url, destination) => {
      assert.deepEqual(fs.readFileSync(oldPath), before, "old mod is present throughout download");
      fs.writeFileSync(destination, after);
    },
    resolveIncompatibilities: async () => [],
    getModrinthProjectAuthor: async () => "Author",
    emit() {}, emitActivity() {}
  });
  vm.runInContext(installSource, context);
  return {
    root, mods, oldPath, newPath, manifestPath, originalManifest, file, context,
    readManifest: () => JSON.parse(fs.readFileSync(manifestPath, "utf8")),
    install: () => context.installModrinthVersion({
      projectIdOrSlug: "example", title: "Example", gameVersion: "1.21.11", loader: "fabric",
      instancePath: root, reason: disabled ? "dependency" : "update",
      version: { id: "new-version", project_id: "example", files: [file], dependencies: [] }
    })
  };
}

function assertUnchanged(f) {
  assert.deepEqual(fs.readFileSync(f.oldPath), before);
  assert.deepEqual(fs.readFileSync(f.manifestPath), f.originalManifest);
  assert.deepEqual(fs.readdirSync(f.mods), [path.basename(f.oldPath)]);
}

test("a failed content download preserves the working mod and original manifest", async (t) => {
  const f = fixture(t);
  f.context.downloadFile = async (_url, destination) => {
    fs.writeFileSync(destination, "partial download");
    throw new Error("network outage");
  };
  await assert.rejects(f.install(), /network outage/);
  assertUnchanged(f);
});

test("corrupt, truncated and unverifiable downloads cannot replace installed content", async (t) => {
  for (const kind of ["checksum", "size", "missing checksum"]) {
    await t.test(kind, async (t) => {
      const f = fixture(t, { sameName: true });
      if (kind === "checksum") f.file.hashes.sha512 = "0".repeat(128);
      if (kind === "size") f.file.size++;
      if (kind === "missing checksum") f.file.hashes = {};
      await assert.rejects(f.install(), /checksum|size/);
      assertUnchanged(f);
    });
  }
});

test("verified updates replace old and same-name files and retain other manifest changes", async (t) => {
  for (const sameName of [false, true]) {
    await t.test(sameName ? "same filename" : "new filename", async (t) => {
      const f = fixture(t, { sameName });
      f.context.downloadFile = async (_url, destination) => {
        assert.deepEqual(fs.readFileSync(f.oldPath), before);
        fs.writeFileSync(destination, after);
        const manifest = f.readManifest();
        manifest.mods["another.jar"] = { projectId: "another", versionId: "installed-while-downloading" };
        fs.writeFileSync(f.manifestPath, JSON.stringify(manifest));
      };
      assert.equal((await f.install()).ok, true);
      assert.deepEqual(fs.readFileSync(f.newPath), after);
      assert.deepEqual(fs.readdirSync(f.mods), [path.basename(f.newPath)]);
      const manifest = f.readManifest();
      assert.equal(manifest.mods[path.basename(f.newPath)].versionId, "new-version");
      assert.equal(manifest.mods["another.jar"].versionId, "installed-while-downloading");
      assert.deepEqual(manifest.updates, {});
    });
  }
});

test("metadata lookup failures and failed dependency updates leave disabled mods intact", async (t) => {
  const f = fixture(t, { disabled: true });
  f.context.getModrinthProjectAuthor = async () => { throw new Error("metadata unavailable"); };
  await assert.rejects(f.install(), /metadata unavailable/);
  assertUnchanged(f);
});

test("a required disabled dependency is enabled after its new bytes are verified", async (t) => {
  const f = fixture(t, { disabled: true });
  assert.equal((await f.install()).ok, true);
  assert.deepEqual(fs.readdirSync(f.mods), ["new.jar"]);
  assert.equal(f.readManifest().mods["new.jar"].versionId, "new-version");
  assert.equal(Boolean(f.readManifest().mods["new.jar"].disabled), false);
});

test("file or manifest replacement errors roll back the complete previous installation", async (t) => {
  for (const failedSource of ["content", "manifest.json"]) {
    await t.test(failedSource, async (t) => {
      const io = Object.create(fs);
      io.renameSync = (from, to) => {
        if (path.basename(from) === failedSource) throw Object.assign(new Error("file locked"), { code: "EPERM" });
        return fs.renameSync(from, to);
      };
      const f = fixture(t, { sameName: true, io });
      await assert.rejects(f.install(), /file locked/);
      assertUnchanged(f);
    });
  }
});

test("a failed rollback retains recoverable backups and reports their location", async (t) => {
  const io = Object.create(fs);
  io.renameSync = (from, to) => {
    if (path.basename(from) === "content" || path.basename(from).startsWith("backup-")) {
      throw Object.assign(new Error("disk unavailable"), { code: "EACCES" });
    }
    return fs.renameSync(from, to);
  };
  const f = fixture(t, { io });
  await assert.rejects(f.install(), /Backups are in/);
  const staging = path.join(f.mods, fs.readdirSync(f.mods).find((name) => name.startsWith(".river-install-")));
  assert.deepEqual(fs.readFileSync(path.join(staging, "backup-0")), before);
  assert.deepEqual(fs.readFileSync(path.join(staging, "backup-1")), f.originalManifest);
});

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const vm = require("node:vm");
const { planRvrRestore } = require("../src/rvr-restore.js");
const { compressPathsToRiverArchive, extractRiverArchive, archivePathParts } = require("../src/river-archive.js");

test("the launcher's export/import flow restores local files and plans exact remote versions", async (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "river-roundtrip-test-"));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const original = path.join(root, "original");
  const sourceFiles = {
    "mods/local.jar.disabled": "custom disabled jar",
    "mods/sodium.jar": "remote mod bytes",
    "resourcepacks/český balík.zip": "local resourcepack",
    "shaderpacks/shader.zip": "remote shader bytes",
    "options.txt": "lang:cs_cz",
    "config/yacl.json5": '{"example":true}',
    "config/noisium.json": '{"enabled":false}'
  };
  for (const [name, contents] of Object.entries(sourceFiles)) {
    const file = path.join(original, name);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, contents);
  }
  const manifest = {
    mods: {
      "local.jar.disabled": { source: "curseforge", projectId: "12345", title: "Local", disabled: true },
      "sodium.jar": { source: "modrinth", projectId: "AANobbMI", versionId: "exact-mod", title: "Sodium" }
    },
    resourcepacks: {},
    shaders: { "shader.zip": { source: "modrinth", projectId: "shaderAB", versionId: "exact-shader", title: "Shader" } },
    updates: {}, checkedAt: ""
  };
  fs.writeFileSync(path.join(original, "riv3r-mods.json"), JSON.stringify(manifest));
  const info = (type) => ({
    mod: { key: "mods", folder: "mods", extensions: [".jar", ".jar.disabled"] },
    resourcepack: { key: "resourcepacks", folder: "resourcepacks", extensions: [".zip"] },
    shader: { key: "shaders", folder: "shaderpacks", extensions: [".zip"] }
  }[type]);
  let restored, instances = [];
  const context = vm.createContext({
    fs, path, os: { tmpdir: () => root },
    planRvrRestore, compressPathsToRiverArchive, extractRiverArchive, archivePathParts,
    RVR_FORMAT_ID: "river.rvr", RVR_FORMAT_VERSION: 1,
    contentTypeInfo: info,
    manifestSection: (value, type) => value[info(type).key],
    readModManifest: (directory) => JSON.parse(fs.readFileSync(path.join(directory, "riv3r-mods.json"), "utf8")),
    writeModManifest: (directory, value) => fs.writeFileSync(path.join(directory, "riv3r-mods.json"), JSON.stringify(value)),
    identifyFilesByHash: async () => new Map(),
    modrinthProjectTitles: async () => new Map(),
    knownLoader: (loader) => ["fabric", "forge"].includes(loader) ? loader : "",
    isCoreClientMod: (file) => file.startsWith("clientcore-"),
    getJarIconDataUrl: () => "", getConflictsForInstalledFile: () => [],
    updateKey: (type, file) => `${type}:${file}`,
    sanitizeFilename: (name) => name.replace(/[\\/:*?"<>|]/g, "_"),
    instancesRootPath: () => path.join(root, "instances"),
    riverSupportsInstance: () => ({ supported: true }),
    isRvrPath: (name) => name.endsWith(".rvr"),
    offlineResult: async () => null,
    restoreRvrContent: async (_instance, items) => { restored = JSON.parse(JSON.stringify(items)); return []; },
    readInstances: () => instances,
    writeInstances: (value) => { instances = value; },
    prefetchRiverModJar: async () => {}, ensureBundledClientCoreMod() {},
    emitStatus() {}, emitActivity() {}, emit() {}
  });
  const source = fs.readFileSync(path.join(__dirname, "../src/main.js"), "utf8");
  for (const name of ["getInstalledMods", "getInstalledContent", "listRvrContentFiles", "buildCompactContentIndex",
    "buildRvrStagingDir", "readRvrManifest", "resolveRvrRootPath", "importRvrArchive", "importRvrArchiveFromFile"]) {
    const start = source.indexOf(`function ${name}(`);
    assert.notEqual(start, -1);
    const asyncStart = source.slice(start - 6, start) === "async " ? start - 6 : start;
    vm.runInContext(source.slice(asyncStart, source.indexOf("\n}", start) + 2), context);
  }
  const staged = await context.buildRvrStagingDir({ id: "source", name: "Česká instance", path: original, version: "1.21.4", loader: "fabric" });
  assert.equal(staged.riverManifest.bundled, 2);
  const archive = path.join(root, "instance.rvr");
  await compressPathsToRiverArchive(fs.readdirSync(staged.stagingDir).map((name) => path.join(staged.stagingDir, name)), archive);
  const result = await context.importRvrArchiveFromFile(archive);
  assert.equal(result.ok, true, result.message);
  assert.equal(instances.length, 1);
  const imported = instances[0];
  assert.equal(imported.name, "Česká instance");
  assert.equal(imported.version, "1.21.4");
  assert.equal(imported.loader, "fabric");
  for (const name of ["mods/local.jar.disabled", "resourcepacks/český balík.zip", "options.txt", "config/yacl.json5", "config/noisium.json"]) {
    assert.equal(fs.readFileSync(path.join(imported.path, name), "utf8"), sourceFiles[name]);
  }
  assert.deepEqual(restored.map((item) => [item.contentType, item.projectId, item.versionId]), [
    ["mod", "AANobbMI", "exact-mod"], ["shader", "shaderAB", "exact-shader"]
  ]);
  const installed = JSON.parse(fs.readFileSync(path.join(imported.path, "riv3r-mods.json"), "utf8"));
  assert.equal(installed.mods["local.jar.disabled"].disabled, true);
  assert.equal(installed.mods["sodium.jar"], undefined, "remote entries only become installed after a successful download");
  assert.equal(fs.readdirSync(root).some((name) => name.startsWith("river-import-")), false);
});

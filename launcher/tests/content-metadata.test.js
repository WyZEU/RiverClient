const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const vm = require("node:vm");
const { sameInstalledContent, mergeMissingMetadata } = require("../src/content-metadata.js");

const source = fs.readFileSync(path.join(__dirname, "../src/main.js"), "utf8");
function loadFunctions(context, names) {
  for (const name of names) {
    const start = source.indexOf(`async function ${name}(`);
    assert.notEqual(start, -1);
    vm.runInContext(source.slice(start, source.indexOf("\n}", start) + 2), context);
  }
}

function fixture(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "river-metadata-test-"));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  fs.mkdirSync(path.join(root, "mods"));
  fs.writeFileSync(path.join(root, "mods", "example.jar"), "original mod");
  const manifestPath = path.join(root, "riv3r-mods.json");
  const write = (manifest) => fs.writeFileSync(manifestPath, JSON.stringify(manifest));
  const read = () => JSON.parse(fs.readFileSync(manifestPath, "utf8"));
  write({
    mods: { "example.jar": { source: "modrinth", projectId: "example", versionId: "v1", installedAt: "before" } },
    resourcepacks: {}, shaders: {}, updates: {}, checkedAt: "before"
  });
  let release, started;
  const gate = new Promise((resolve) => { release = resolve; });
  const waiting = new Promise((resolve) => { started = resolve; });
  const context = vm.createContext({
    fs, path, sameInstalledContent, mergeMissingMetadata,
    readModManifest: read, writeModManifest: (_root, manifest) => write(manifest),
    contentTypeInfo: (type) => ({ key: { mod: "mods", resourcepack: "resourcepacks", shader: "shaders" }[type],
      folder: { mod: "mods", resourcepack: "resourcepacks", shader: "shaderpacks" }[type] }),
    manifestSection: (manifest, type) => manifest[{ mod: "mods", resourcepack: "resourcepacks", shader: "shaders" }[type]],
    updateKey: (type, file) => `${type}:${file}`,
    resolveInstalledProject: async () => { started(); await gate; return { id: "example", slug: "example", title: "Resolved", icon_url: "remote" }; },
    cacheProjectIcon: async () => "cached",
    getModrinthProjectAuthor: async () => "Resolved Author",
    getModrinthVersion: async () => { started(); await gate; return { id: "v2", version_number: "2" }; },
    emit() {}
  });
  loadFunctions(context, ["backfillInstalledIcons", "checkModUpdates"]);
  return { root, read, write, context, release, waiting };
}

test("icon lookup preserves installations, removals, updates and metadata edits made while waiting", async (t) => {
  const f = fixture(t);
  const pending = f.context.backfillInstalledIcons(f.root);
  await f.waiting;
  const current = f.read();
  current.mods["new.jar"] = { projectId: "new", versionId: "new-version" };
  Object.assign(current.mods["example.jar"], { title: "Edited", author: "User Author", iconUrl: "user-icon", pinned: true });
  current.updates["mod:new.jar"] = { available: true };
  current.checkedAt = "newer-scan";
  f.write(current);
  f.release();
  assert.equal(await pending, true);
  const actual = f.read();
  assert.deepEqual(actual.mods["new.jar"], current.mods["new.jar"]);
  assert.equal(actual.mods["example.jar"].title, "Edited");
  assert.equal(actual.mods["example.jar"].author, "User Author");
  assert.equal(actual.mods["example.jar"].iconUrl, "user-icon");
  assert.equal(actual.mods["example.jar"].pinned, true);
  assert.deepEqual(actual.updates, current.updates);
  assert.equal(actual.checkedAt, "newer-scan");
});

test("stale icon results cannot resurrect, re-enable or decorate a replaced mod", async (t) => {
  for (const change of ["remove", "disable", "replace", "replace-bytes", "newer-lookup"]) {
    await t.test(change, async (t) => {
      const f = fixture(t), pending = f.context.backfillInstalledIcons(f.root);
      await f.waiting;
      const current = f.read(), entry = current.mods["example.jar"];
      if (change === "remove") { delete current.mods["example.jar"]; fs.unlinkSync(path.join(f.root, "mods", "example.jar")); }
      if (change === "disable") {
        current.mods["example.jar.disabled"] = { ...entry, disabled: true };
        delete current.mods["example.jar"];
        fs.renameSync(path.join(f.root, "mods", "example.jar"), path.join(f.root, "mods", "example.jar.disabled"));
      }
      if (change === "replace") Object.assign(entry, { versionId: "v2", installedAt: "new-install" });
      if (change === "replace-bytes") fs.writeFileSync(path.join(f.root, "mods", "example.jar"), "different mod bytes");
      if (change === "newer-lookup") entry.iconCheckedAt = "newer";
      f.write(current);
      f.release();
      assert.equal(await pending, false);
      assert.deepEqual(f.read(), current);
    });
  }
});

test("backfill fills missing metadata and retains the negative-lookup cooldown", async (t) => {
  for (const found of [true, false]) {
    await t.test(found ? "resolved project" : "no project", async (t) => {
      const f = fixture(t);
      if (!found) f.context.resolveInstalledProject = async () => null;
      f.release();
      assert.equal(await f.context.backfillInstalledIcons(f.root), true);
      const entry = f.read().mods["example.jar"];
      assert.ok(Date.parse(entry.iconCheckedAt));
      if (found) {
        assert.equal(entry.iconUrl, "cached");
        assert.equal(entry.author, "Resolved Author");
        assert.equal(entry.versionId, "v1");
      }
      let lookedUp = false;
      f.context.resolveInstalledProject = async () => { lookedUp = true; return null; };
      assert.equal(await f.context.backfillInstalledIcons(f.root), false);
      assert.equal(lookedUp, false);
    });
  }
});

test("update scans merge into current content without overwriting concurrent changes", async (t) => {
  for (const change of ["metadata", "install", "remove", "replace", "pin", "newer-scan"]) {
    await t.test(change, async (t) => {
      const f = fixture(t), pending = f.context.checkModUpdates(f.root);
      await f.waiting;
      const current = f.read();
      if (change === "metadata") Object.assign(current.mods["example.jar"], { author: "Filled while scanning", slug: "resolved-slug" });
      if (change === "install") {
        current.mods["new.jar"] = { projectId: "new", versionId: "v1" };
        current.updates["mod:new.jar"] = { available: true, latestVersionId: "new-v2" };
      }
      if (change === "remove") delete current.mods["example.jar"];
      if (change === "replace") current.mods["example.jar"].versionId = "v3";
      if (change === "pin") current.mods["example.jar"].pinned = true;
      if (change === "newer-scan") current.updates["mod:example.jar"] = { available: true, latestVersionId: "v4" };
      f.write(current);
      f.release();
      assert.equal((await pending).ok, true);
      const actual = f.read();
      assert.deepEqual(actual.mods, current.mods);
      if (["metadata", "install"].includes(change)) assert.equal(actual.updates["mod:example.jar"].latestVersionId, "v2");
      else assert.deepEqual(actual.updates, current.updates);
      if (change === "install") assert.deepEqual(actual.updates["mod:new.jar"], current.updates["mod:new.jar"]);
    });
  }
});

const voiceChatRelease = { id: "DKSq5wO6", version_type: "release", date_published: "2026-08-08T00:00:00Z" };
const voiceChatBeta = { id: "ul3tRpKz", version_type: "beta", date_published: "2026-10-28T00:00:00Z" };

function voiceChatFixture(t, versions) {
  const f = fixture(t);
  const manifest = f.read();
  Object.assign(manifest.mods["example.jar"], {
    projectId: "simple-voice-chat", versionId: voiceChatBeta.id, gameVersion: "26.2", loader: "fabric"
  });
  f.write(manifest);
  const installed = [];
  Object.assign(f.context, {
    URL, AbortSignal, launcherUserAgent: "Test", normalizeContentType: (type) => type,
    fetch: async () => ({ ok: true, json: async () => versions }),
    emitActivity() {},
    installModrinthVersion: async ({ version }) => { installed.push(version.id); return { ok: true }; }
  });
  loadFunctions(f.context, ["getModrinthVersion", "applyAvailableModUpdates"]);
  return { ...f, installed };
}

test("content repair preserves a newer beta instead of downgrading to an older release", async (t) => {
  const f = voiceChatFixture(t, [voiceChatBeta, voiceChatRelease]);
  const result = await f.context.applyAvailableModUpdates(f.root);
  assert.equal(result.ok, true);
  assert.equal(f.read().updates["mod:example.jar"].available, false);
  assert.deepEqual(f.installed, []);
});

test("fresh installs still prefer releases and updates can select a newer release", async (t) => {
  const versions = [voiceChatBeta, voiceChatRelease];
  const f = voiceChatFixture(t, versions);
  const initial = await f.context.getModrinthVersion("simple-voice-chat", "26.2", "fabric");
  assert.equal(initial.id, voiceChatRelease.id);
  const newerRelease = { id: "new-release", version_type: "release", date_published: "2026-11-15T00:00:00Z" };
  versions.unshift(newerRelease);
  const result = await f.context.checkModUpdates(f.root);
  assert.equal(result.count, 1);
  assert.equal(f.read().updates["mod:example.jar"].latestVersionId, newerRelease.id);
});

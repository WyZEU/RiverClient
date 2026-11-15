const test = require("node:test");
const assert = require("node:assert/strict");
const { planRvrRestore, isModrinthProjectId } = require("../src/rvr-restore.js");

const none = () => false;

const manifest = {
  mods: {
    "sodium-0.6.jar": { source: "modrinth", title: "Sodium", projectId: "AANobbMI", versionId: "v1" },
    "lithium-0.14.jar.disabled": { source: "modrinth", title: "Lithium", projectId: "gvQqBUqZ", versionId: "v2", disabled: true }
  },
  resourcepacks: { "Faithful.zip": { source: "modrinth", title: "Faithful", projectId: "pack1abc", versionId: "v3" } },
  shaders: {}
};

test("every file the launcher installed is downloaded again at its exact version", () => {
  const plan = planRvrRestore(manifest, null, none);
  assert.deepEqual(plan.restore.map((r) => [r.contentType, r.projectId, r.versionId]), [
    ["mod", "AANobbMI", "v1"],
    ["mod", "gvQqBUqZ", "v2"],
    ["resourcepack", "pack1abc", "v3"]
  ]);
  assert.deepEqual(plan.keep, { mods: {}, resourcepacks: {}, shaders: {} });
  assert.deepEqual(plan.local, []);
});

test("a mod that was disabled comes back disabled", () => {
  const plan = planRvrRestore(manifest, null, none);
  assert.equal(plan.restore.find((r) => r.projectId === "gvQqBUqZ").disabled, true);
  assert.equal(plan.restore.find((r) => r.projectId === "AANobbMI").disabled, false);
});

test("files the archive already carries are kept, not downloaded", () => {
  const plan = planRvrRestore(manifest, null, (type, file) => file === "sodium-0.6.jar");
  assert.deepEqual(Object.keys(plan.keep.mods), ["sodium-0.6.jar"]);
  assert.equal(plan.restore.some((r) => r.projectId === "AANobbMI"), false);
  assert.equal(plan.restore.length, 2);
});

test("CurseForge files are never sent to Modrinth", () => {
  const cf = { mods: { "MouseTweaks.jar": { source: "curseforge", title: "Mouse Tweaks", projectId: "60089" } } };
  const index = { mods: [{ file: "Clumps.jar", title: "Clumps", projectId: "256717" }], resourcepacks: [], shaders: [] };
  const plan = planRvrRestore(cf, index, none);
  assert.deepEqual(plan.restore, []);
  assert.deepEqual(plan.local.map((l) => l.file), ["MouseTweaks.jar", "Clumps.jar"]);
});

test("Modrinth project ids are told from CurseForge ids", () => {
  assert.equal(isModrinthProjectId("AANobbMI"), true);
  assert.equal(isModrinthProjectId("60089"), false);
  assert.equal(isModrinthProjectId(""), false);
  assert.equal(isModrinthProjectId(undefined), false);
});

test("hand-added files are reported, and River's own jar is not", () => {
  const index = {
    mods: [
      { file: "sodium-0.6.jar", projectId: "AANobbMI" },
      { file: "my-custom-mod.jar", title: "Custom", projectId: "" },
      { file: "clientcore-0.2.6+1.21.11.jar", title: "River Client", projectId: "" }
    ],
    resourcepacks: [],
    shaders: []
  };
  const plan = planRvrRestore(manifest, index, none, (file) => file.startsWith("clientcore-"));
  assert.deepEqual(plan.local, [{ contentType: "mod", file: "my-custom-mod.jar", title: "Custom" }]);
});

test("an index entry with a Modrinth project but no manifest entry is still downloaded", () => {
  const index = { mods: [{ file: "iris.jar", title: "Iris", projectId: "YL57xq9U", versionId: "" }], resourcepacks: [], shaders: [] };
  const plan = planRvrRestore({ mods: {}, resourcepacks: {}, shaders: {} }, index, none);
  assert.deepEqual(plan.restore.map((r) => [r.projectId, r.versionId]), [["YL57xq9U", ""]]);
});

test("the same project is only downloaded once", () => {
  const twice = {
    mods: {
      "a.jar": { source: "modrinth", projectId: "AAAAAAAA" },
      "b.jar": { source: "modrinth", projectId: "AAAAAAAA" }
    }
  };
  assert.equal(planRvrRestore(twice, null, none).restore.length, 1);
});

test("a missing or damaged manifest gives an empty plan instead of throwing", () => {
  for (const bad of [null, undefined, [], "x", { mods: [] }, { mods: { "a.jar": null } }]) {
    const plan = planRvrRestore(bad, undefined, none);
    assert.deepEqual(plan.restore, []);
    assert.deepEqual(plan.local, []);
  }
});

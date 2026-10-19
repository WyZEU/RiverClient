const test = require("node:test");
const assert = require("node:assert/strict");
const { migrationPlan, migrationVersion } = require("../src/migration-plan.js");

test("matching Fabric profile carries its content", () => {
  const plan = migrationPlan("1.21.11", "fabric", "1.21.11");
  assert.equal(plan.compatible, true);
  assert.deepEqual(plan.folders, ["mods", "resourcepacks", "shaderpacks", "config", "saves", "screenshots"]);
  assert.deepEqual(plan.files, ["options.txt", "servers.dat", "hotbar.nbt"]);
});

test("different version or unknown loader keeps everything but the mods", () => {
  for (const [version, loader] of [["1.21.4", "fabric"], ["1.21.11", ""], ["1.21.11", "forge"]]) {
    const plan = migrationPlan(version, loader, "1.21.11");
    assert.equal(plan.compatible, false);
    assert.deepEqual(plan.folders, ["resourcepacks", "shaderpacks", "config", "saves", "screenshots"]);
    assert.deepEqual(plan.files, ["options.txt", "servers.dat", "hotbar.nbt"]);
  }
});

test("proprietary clients never bring their mods, but do bring settings and hotbars", () => {
  for (const launcher of ["Feather", "Lunar Client", "Badlion"]) {
    const plan = migrationPlan("1.21.11", "fabric", "1.21.11", launcher);
    assert.equal(plan.compatible, false);
    assert.equal(plan.folders.includes("mods"), false);
    assert.ok(plan.files.includes("options.txt") && plan.files.includes("hotbar.nbt"));
  }
});

test("keep an old supported Minecraft version to avoid upgrading copied worlds", () => {
  const supported = ["1.21.4", "1.21.11"];
  assert.equal(migrationVersion("1.21.4", "1.21.11", supported), "1.21.4");
  assert.equal(migrationVersion("1.20.1", "1.21.11", supported), "1.21.11");
});

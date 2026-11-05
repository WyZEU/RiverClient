const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const crypto = require("node:crypto");
const { pickRiverModEntry, sha256File, fileMatchesSha256 } = require("../src/river-mod-cache.js");

const manifest = {
  mods: [
    { minecraft: "1.21.11", name: "clientcore-0.2.7+1.21.11.jar", url: "https://x/a.jar", size: 1, sha256: "aa" },
    { minecraft: "26.2", name: "clientcore-0.2.7+26.2.jar", url: "https://x/b.jar", size: 1, sha256: "bb" }
  ]
};

test("picks the entry for the exact Minecraft version", () => {
  assert.equal(pickRiverModEntry(manifest, "26.2").name, "clientcore-0.2.7+26.2.jar");
  assert.equal(pickRiverModEntry(manifest, " 1.21.11 ").name, "clientcore-0.2.7+1.21.11.jar");
});

test("no entry for a version this release does not build for", () => {
  assert.equal(pickRiverModEntry(manifest, "1.21.9"), null);
  assert.equal(pickRiverModEntry(manifest, ""), null);
  assert.equal(pickRiverModEntry(undefined, "1.21.11"), null);
  assert.equal(pickRiverModEntry({ mods: "x" }, "1.21.11"), null);
});

test("an entry without a checksum or a usable name is ignored", () => {
  const bad = { mods: [
    { minecraft: "1.21.4", name: "clientcore-0.2.7+1.21.4.jar", url: "https://x/c.jar" },
    { minecraft: "1.21.5", name: "../../evil.jar", url: "https://x/d.jar", sha256: "cc" }
  ] };
  assert.equal(pickRiverModEntry(bad, "1.21.4"), null);
  assert.equal(pickRiverModEntry(bad, "1.21.5"), null);
});

test("sha256 of a file matches crypto's own answer, and a wrong hash is rejected", async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "river-cache-test-"));
  const file = path.join(dir, "x.jar");
  const data = crypto.randomBytes(3 * 1024 * 1024 + 17);
  fs.writeFileSync(file, data);
  const expected = crypto.createHash("sha256").update(data).digest("hex");
  assert.equal(await sha256File(file), expected);
  assert.equal(await fileMatchesSha256(file, expected), true);
  assert.equal(await fileMatchesSha256(file, expected.replace(/.$/, (c) => (c === "0" ? "1" : "0"))), false);
  assert.equal(await fileMatchesSha256(path.join(dir, "missing.jar"), expected), false);
  fs.rmSync(dir, { recursive: true, force: true });
});

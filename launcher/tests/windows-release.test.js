const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const crypto = require("node:crypto");
const { releasePlan, stageRelease } = require("../scripts/prepare-windows-release");

function fixture(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "river-build-"));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const write = (name, value) => {
    const target = path.join(root, name);
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.writeFileSync(target, value);
  };
  write("launcher/package.json", JSON.stringify({ riverVersion: "0.3.0" }));
  write("settings.gradle.kts", 'versions("1.21.11", "26.3")\n');
  write("river-bootstrap/build.gradle.kts", 'version = "0.1.9.0"\n');
  for (const file of releasePlan(root).files) {
    // A synthetic ZIP header is sufficient here: these tests exercise staging and
    // provenance, while the actual Gradle job produces/validates real Java archives.
    fs.mkdirSync(path.dirname(file.source), { recursive: true });
    fs.writeFileSync(file.source, Buffer.from([0x50, 0x4b, 0x03, 0x04, 1, 2, 3]));
  }
  return { root, write };
}

test("release staging includes every active target and records exact artifact hashes", (t) => {
  const { root, write } = fixture(t);
  write("launcher/src/bundled/clientcore-old.jar", "stale");
  write("launcher/src/bundled/README.md", "keep");
  const commit = "a".repeat(40);
  const result = stageRelease(root, commit);
  assert.deepEqual(result.minecraftVersions, ["1.21.11", "26.3"]);
  assert.equal(result.sourceCommit, commit);
  assert.equal(result.files.length, 3);
  assert.equal(fs.existsSync(path.join(root, "launcher/src/bundled/clientcore-old.jar")), false);
  assert.equal(fs.readFileSync(path.join(root, "launcher/src/bundled/README.md"), "utf8"), "keep");
  for (const file of result.files) {
    const bytes = fs.readFileSync(path.join(root, "launcher/src/bundled", file.name));
    assert.equal(file.sha256, crypto.createHash("sha256").update(bytes).digest("hex"));
    assert.equal(file.size, bytes.length);
  }
});

test("a missing target aborts before changing the previous staged bundle", (t) => {
  const { root, write } = fixture(t);
  write("launcher/src/bundled/clientcore-old.jar", "previous");
  fs.unlinkSync(releasePlan(root).files[1].source);
  assert.throws(() => stageRelease(root, "a".repeat(40)), /ENOENT/);
  assert.equal(fs.readFileSync(path.join(root, "launcher/src/bundled/clientcore-old.jar"), "utf8"), "previous");
});

test("release staging rejects invalid versions, corrupt artifacts and unreviewed jars", (t) => {
  const { root, write } = fixture(t);
  assert.throws(() => stageRelease(root, "short-sha"), /full source commit/);
  write("launcher/package.json", JSON.stringify({ riverVersion: "0.3.0.1" }));
  assert.throws(() => releasePlan(root), /MAJOR.MINOR.PATCH/);
  write("launcher/package.json", JSON.stringify({ riverVersion: "0.3.0" }));
  write("launcher/src/bundled/unreviewed.jar", "unknown");
  assert.throws(() => stageRelease(root, "a".repeat(40)), /third-party/);
  fs.unlinkSync(path.join(root, "launcher/src/bundled/unreviewed.jar"));
  fs.writeFileSync(releasePlan(root).files[0].source, "not a jar");
  assert.throws(() => stageRelease(root, "a".repeat(40)), /Not a JAR/);
});

test("changing only riverVersion selects the new mod artifacts and stamps release metadata", (t) => {
  const { root, write } = fixture(t);
  write("launcher/package.json", JSON.stringify({ riverVersion: "0.3.1" }));
  const plan = releasePlan(root);
  assert.equal(plan.version, "0.3.1");
  assert.deepEqual(plan.files.map((file) => file.name), [
    "clientcore-0.3.1+1.21.11.jar", "clientcore-0.3.1+26.3.jar", "river-bootstrap-0.1.9.0.jar"
  ]);
  // Old artifacts must not be relabelled as the new version after a bump.
  assert.throws(() => stageRelease(root, "a".repeat(40)), /ENOENT/);
  for (const file of plan.files.slice(0, -1)) {
    fs.writeFileSync(file.source, Buffer.from([0x50, 0x4b, 0x03, 0x04, 4, 5, 6]));
  }
  assert.equal(stageRelease(root, "a".repeat(40)).version, "0.3.1");
});

test("release config fails closed and invokes the signer inside electron-builder", () => {
  const config = require("../electron-builder.windows.cjs");
  assert.equal(config.forceCodeSigning, true);
  assert.equal(config.publish, null);
  assert.deepEqual(config.win.signtoolOptions.signingHashAlgorithms, ["sha256"]);
  assert.equal(config.win.signtoolOptions.publisherName, "Supro Nivelo s.r.o.");
  assert.equal(fs.existsSync(config.win.signtoolOptions.sign), true);
});

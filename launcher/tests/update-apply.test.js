const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { apply, write, snapshot, hasPowerShell } = require("./fixtures/update-apply.js");

function fixture(t, mode = "delta") {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "river-apply-"));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const installDir = path.join(root, "installation with space");
  const sourceDir = path.join(root, "staging");
  const old = { "RiverClient.exe": "old exe", "resources/app.asar": "old asar", "resources/late.txt": "old later file",
    "resources/obsolete.txt": "old removed resource", "locales/obsolete.pak": "old removed locale", "uninstall.exe": "preserved uninstaller" };
  const next = { "RiverClient.exe": "new exe", "resources/new.txt": "new file", "resources/app.asar": "new asar", "resources/late.txt": "new later file", "locales/en.pak": "new locale" };
  for (const [file, contents] of Object.entries(old)) write(installDir, file, contents);
  for (const [file, contents] of Object.entries(next)) write(sourceDir, file, contents);
  const manifestPath = write(root, "manifest.json", JSON.stringify({ files: Object.keys(next).map((path) => ({ path })), changedFiles: Object.keys(next) }));
  return { root, installDir, sourceDir, manifestPath, mode, old: snapshot(installDir), next };
}

for (const mode of ["delta", "package"]) {
  test(`${mode} update copies new files and removes obsolete resources, preserving the uninstaller`, { skip: !hasPowerShell }, (t) => {
    const f = fixture(t, mode);
    const result = apply(f.root, f);
    assert.equal(result.status, 0, result.stderr + result.log);
    for (const [file, contents] of Object.entries(f.next)) assert.equal(fs.readFileSync(path.join(f.installDir, file), "utf8"), contents);
    assert.equal(fs.existsSync(path.join(f.installDir, "resources/obsolete.txt")), false);
    assert.equal(fs.existsSync(path.join(f.installDir, "locales/obsolete.pak")), false);
    assert.equal(fs.readFileSync(path.join(f.installDir, "uninstall.exe"), "utf8"), "preserved uninstaller");
    assert.match(result.launches, /RiverClient.exe/);
    assert.doesNotMatch(result.launches, /river-update-failed/);
  });
  for (const faults of [{ backup: "resources/app.asar" }, { apply: "resources/new.txt" }, { apply: "resources/app.asar" }, { remove: "locales/obsolete.pak" }, { corrupt: "resources/app.asar" }]) {
    test(`${mode} restores every original byte after ${JSON.stringify(faults)}`, { skip: !hasPowerShell }, (t) => {
      const f = fixture(t, mode);
      const result = apply(f.root, { ...f, faults });
      assert.equal(result.status, 1, result.stderr + result.log);
      assert.deepEqual(snapshot(f.installDir), f.old, result.log);
      assert.match(result.log, /Previous launcher files preserved/);
      assert.match(result.launches, /--river-update-failed=/);
    });
  }
}

test("failed restoration retains backups and never restarts a mixed installation", { skip: !hasPowerShell }, (t) => {
  const f = fixture(t);
  const result = apply(f.root, { ...f, faults: { apply: "resources/app.asar", restore: "RiverClient.exe" } });
  assert.equal(result.status, 1, result.stderr + result.log);
  assert.equal(result.launches, "");
  const transactions = path.join(result.updateRoot, "transactions");
  const backup = path.join(transactions, fs.readdirSync(transactions)[0]);
  assert.equal(fs.readFileSync(path.join(backup, "files/RiverClient.exe"), "utf8"), "old exe");
  assert.equal(JSON.parse(fs.readFileSync(path.join(backup, "transaction.json"), "utf8").replace(/^\uFEFF/, "")).state, "restore-failed");
  assert.equal(fs.readFileSync(path.join(f.installDir, "resources/late.txt"), "utf8"), "old later file");
  // Neither starting another update nor its success cleanup can erase failed recovery.
  assert.equal(apply(f.root, f).status, 0);
  assert.equal(fs.existsSync(path.join(backup, "files/RiverClient.exe")), true);
});

test("a delta with only resources keeps its relative staging root", { skip: !hasPowerShell }, (t) => {
  const f = fixture(t);
  fs.rmSync(path.join(f.sourceDir, "RiverClient.exe"));
  fs.rmSync(path.join(f.sourceDir, "locales"), { recursive: true });
  const files = Object.keys(f.next).filter((file) => file !== "locales/en.pak");
  write(f.root, "manifest.json", JSON.stringify({ files: files.map((path) => ({ path })), changedFiles: files.filter((file) => file !== "RiverClient.exe") }));
  const result = apply(f.root, f);
  assert.equal(result.status, 0, result.stderr + result.log);
  assert.equal(fs.readFileSync(path.join(f.installDir, "RiverClient.exe"), "utf8"), "old exe");
  assert.equal(fs.readFileSync(path.join(f.installDir, "resources/app.asar"), "utf8"), "new asar");
});

test("missing staged files fail before touching the installation", { skip: !hasPowerShell }, (t) => {
  const f = fixture(t);
  fs.rmSync(path.join(f.sourceDir, "resources/late.txt"));
  const result = apply(f.root, f);
  assert.equal(result.status, 1);
  assert.deepEqual(snapshot(f.installDir), f.old);
  assert.match(result.log, /Missing staged file/);
});

test("paths escaping the installation are rejected before any copy", { skip: !hasPowerShell }, (t) => {
  const f = fixture(t);
  const manifest = JSON.parse(fs.readFileSync(f.manifestPath, "utf8"));
  manifest.files.push({ path: "../escape.txt" });
  fs.writeFileSync(f.manifestPath, JSON.stringify(manifest));
  const result = apply(f.root, f);
  assert.equal(result.status, 1);
  assert.deepEqual(snapshot(f.installDir), f.old);
  assert.equal(fs.existsSync(path.join(f.root, "escape.txt")), false);
});

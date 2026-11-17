const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const os = require("node:os");
const { execFileSync } = require("node:child_process");
const { linuxPackage, archivePath, hashFile, extractLinuxArchive, prepareLinuxUpdate, applyLinuxUpdate } = require("../src/linux-update");

const posix = { skip: process.platform === "win32" };
function write(root, relative, contents, mode = 0o644) {
  const file = path.join(root, relative);
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, contents, { mode });
}
function snapshot(root) {
  const files = {};
  for (const entry of fs.readdirSync(root, { withFileTypes: true })) {
    if (entry.isDirectory()) for (const [name, value] of Object.entries(snapshot(path.join(root, entry.name)))) files[`${entry.name}/${name}`] = value;
    else files[entry.name] = fs.readFileSync(path.join(root, entry.name), "hex");
  }
  return files;
}
async function fixture(t, extra = () => {}) {
  const root = fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), "river-linux-update-test-")));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const installDir = path.join(root, "River with spaces '$()`");
  const packageDir = path.join(root, "River-Client-Linux");
  const userData = path.join(root, "user-data");
  write(userData, "minecraft-auth.json", '{"testRefreshToken":"fixture-only"}');
  write(installDir, "resources/app.asar", "public launcher");
  write(installDir, "resources/removed.js", "obsolete");
  write(installDir, "river-client", "#!/bin/sh\nexit 0\n", 0o755);
  write(installDir, "instances/world/level.dat", "saved world");
  write(packageDir, "resources/app.asar", "candidate launcher");
  write(packageDir, "resources/new.js", "new resource");
  write(packageDir, "icudtl.dat", "runtime");
  write(packageDir, "river-client", "#!/bin/sh\nexit 0\n", 0o755);
  write(packageDir, "river-client-bin", "#!/bin/sh\nexit 0\n", 0o755);
  extra(packageDir);
  const archive = path.join(root, "package.tar.gz");
  execFileSync("tar", ["--format=ustar", "-czf", archive, "-C", root, "River-Client-Linux"]);
  const expected = { size: fs.statSync(archive).size, sha256: await hashFile(archive) };
  return { root, archive, expected, installDir, userData, before: snapshot(installDir) };
}

test("Linux package selection requires x64, HTTPS, size and a SHA-256 from the signed manifest", () => {
  const manifest = { linuxUrl: "https://updates.riverclient.xyz/releases/test/linux.tar.gz", files: { linux: { size: 100, sha256: "a".repeat(64) } } };
  assert.equal(linuxPackage(manifest, "x64").size, 100);
  assert.throws(() => linuxPackage(manifest, "arm64"));
  assert.throws(() => linuxPackage({ ...manifest, linuxUrl: "file:///tmp/evil" }, "x64"));
  assert.throws(() => linuxPackage({ ...manifest, files: {} }, "x64"));
});

test("Linux update preserves accounts and custom instances, replaces runtime and keeps executable modes", posix, async (t) => {
  const f = await fixture(t);
  const auth = snapshot(f.userData);
  const job = await prepareLinuxUpdate(f);
  assert.deepEqual(snapshot(f.installDir), f.before);
  const result = await applyLinuxUpdate({ ...job, pid: 0 });
  assert.equal(result.ok, true);
  assert.equal(fs.readFileSync(path.join(f.installDir, "resources/app.asar"), "utf8"), "candidate launcher");
  assert.equal(fs.existsSync(path.join(f.installDir, "resources/removed.js")), false);
  assert.equal(fs.readFileSync(path.join(f.installDir, "instances/world/level.dat"), "utf8"), "saved world");
  assert.deepEqual(snapshot(f.userData), auth);
  assert.deepEqual(snapshot(job.backupDir), f.before);
  assert.equal(fs.statSync(path.join(f.installDir, "river-client-bin")).mode & 0o777, 0o755);
});

test("a failed directory replacement rolls back every original byte", posix, async (t) => {
  const f = await fixture(t);
  const job = await prepareLinuxUpdate(f);
  let calls = 0;
  await assert.rejects(applyLinuxUpdate({ ...job, pid: 0 }, { restart: false, rename: (a, b) => {
    if (++calls === 2) throw new Error("Injected disk failure");
    fs.renameSync(a, b);
  } }), /Injected/);
  assert.deepEqual(snapshot(f.installDir), f.before);
  assert.equal(JSON.parse(fs.readFileSync(path.join(job.work, "transaction.json"))).state, "rolled-back");
});

test("an updated launcher that exits with a failure restores the previous installation", posix, async (t) => {
  const f = await fixture(t, (dir) => write(dir, "river-client", "#!/bin/sh\nexit 7\n", 0o755));
  const job = await prepareLinuxUpdate(f);
  await assert.rejects(applyLinuxUpdate({ ...job, pid: 0 }), /exited with code 7/);
  assert.deepEqual(snapshot(f.installDir), f.before);
  assert.equal(JSON.parse(fs.readFileSync(path.join(job.work, "transaction.json"))).state, "rolled-back");
});

test("staged corruption and a running launcher cannot change the installation", posix, async (t) => {
  const f = await fixture(t);
  const job = await prepareLinuxUpdate(f);
  await assert.rejects(applyLinuxUpdate(job, { restart: false, waitMs: 0 }), /still running/);
  write(job.stagedDir, "resources/app.asar", "corrupt");
  await assert.rejects(applyLinuxUpdate({ ...job, pid: 0 }, { restart: false }), /staged Linux update changed/);
  assert.deepEqual(snapshot(f.installDir), f.before);
});

test("a competing update lock is preserved", posix, async (t) => {
  const f = await fixture(t);
  const job = await prepareLinuxUpdate(f);
  fs.mkdirSync(f.installDir + ".update-lock");
  await assert.rejects(applyLinuxUpdate({ ...job, pid: 0 }, { restart: false }), { code: "EEXIST" });
  assert.ok(fs.existsSync(f.installDir + ".update-lock"));
  assert.deepEqual(snapshot(f.installDir), f.before);
});

test("bad downloads, missing executables and archive links fail before touching installed files", posix, async (t) => {
  for (const extra of [() => {}, (dir) => fs.rmSync(path.join(dir, "river-client-bin")), (dir) => fs.symlinkSync("/tmp", path.join(dir, "escape"))]) {
    const f = await fixture(t, extra);
    if (fs.existsSync(path.join(f.root, "River-Client-Linux/escape")) || !fs.existsSync(path.join(f.root, "River-Client-Linux/river-client-bin"))) {
      await assert.rejects(prepareLinuxUpdate(f));
    } else await assert.rejects(prepareLinuxUpdate({ ...f, expected: { ...f.expected, sha256: "0".repeat(64) } }), /checksum/);
    assert.deepEqual(snapshot(f.installDir), f.before);
  }
});

test("archive paths cannot escape or use Windows separators", () => {
  for (const name of ["/tmp/file", "River-Client-Linux/../file", "River-Client-Linux/a/../../file", "River-Client-Linux/a\\b", "Other/file", "River-Client-Linux/a\0b"]) {
    assert.throws(() => archivePath(name));
  }
  assert.equal(archivePath("River-Client-Linux/resources/app.asar"), "resources/app.asar");
});

test("a truncated tar stream cannot become an installation", posix, async (t) => {
  const f = await fixture(t);
  const bytes = fs.readFileSync(f.archive);
  fs.writeFileSync(f.archive, bytes.subarray(0, bytes.length - 20));
  await assert.rejects(extractLinuxArchive(f.archive, path.join(f.root, "truncated")));
  assert.deepEqual(snapshot(f.installDir), f.before);
});

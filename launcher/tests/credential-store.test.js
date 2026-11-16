const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { createCredentialStore, publicAuth, publicSavedProfile } = require("../src/credential-store.js");
const { encryptionProvider, account } = require("./fixtures/auth-storage.js");

function fixture(t, options = {}) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "river-credentials-"));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const safeStorage = encryptionProvider();
  const store = createCredentialStore({ safeStorage, ...options });
  const file = path.join(root, "minecraft-auth.json");
  const auth = account();
  const raw = JSON.stringify(auth, null, 2);
  fs.writeFileSync(file, raw);
  return { root, file, auth, raw, store, safeStorage };
}

test("migrates active, multiple saved accounts and River sessions without changing their data", (t) => {
  const f = fixture(t);
  const saved = [f.auth, account("bob")].map((auth) => ({ id: auth.profile.id, name: auth.profile.name, savedAt: "2026-11-16", auth }));
  const river = { uuid: "alice", token: "test-river-session", expiresAt: f.auth.expiresAt };
  for (const [name, data] of [["minecraft-auth.json", f.auth], ["profiles.json", saved], ["river-session.json", river]]) {
    const file = path.join(f.root, name);
    fs.writeFileSync(file, JSON.stringify(data));
    assert.deepEqual(f.store.read(file, null), data);
    const encrypted = fs.readFileSync(file, "utf8");
    assert.equal(JSON.parse(encrypted).format, "river-safe-storage");
    assert.doesNotMatch(encrypted, /test-ms-refresh|test-minecraft-access|test-river-session/);
    if (process.platform !== "win32") assert.equal(fs.statSync(file).mode & 0o777, 0o600);
    const restarted = createCredentialStore({ safeStorage: f.safeStorage });
    assert.deepEqual(restarted.read(file, null), data);
    assert.equal(fs.readFileSync(file, "utf8"), encrypted, "restart must not keep rewriting the encrypted file");
  }
  assert.equal(f.store.status().state, "encrypted");
  assert.deepEqual(fs.readdirSync(f.root).sort(), ["minecraft-auth.json", "profiles.json", "river-session.json"]);
});

test("legacy accounts keep working when the keychain is locked, then migrate after it becomes available", (t) => {
  let clock = 0;
  const f = fixture(t, { now: () => clock });
  f.safeStorage.available = false;
  assert.deepEqual(f.store.read(f.file, {}), f.auth);
  assert.equal(fs.readFileSync(f.file, "utf8"), f.raw);
  assert.equal(f.store.status().state, "legacy");
  const refreshed = { ...f.auth, microsoftRefreshToken: "test-rotated-token" };
  f.store.write(f.file, refreshed, {});
  assert.deepEqual(JSON.parse(fs.readFileSync(f.file, "utf8")), refreshed);
  f.safeStorage.available = true;
  clock += 30_001;
  assert.deepEqual(f.store.read(f.file, {}), refreshed);
  assert.equal(f.store.status().state, "encrypted");
  assert.doesNotMatch(fs.readFileSync(f.file, "utf8"), /test-rotated-token/);
});

test("Linux basic_text and unknown backends never count as secure storage", (t) => {
  for (const backend of ["basic_text", "unknown"]) {
    const f = fixture(t, { platform: "linux" });
    f.safeStorage.backend = backend;
    assert.deepEqual(f.store.read(f.file, {}), f.auth);
    assert.equal(f.store.status().state, "legacy");
    assert.equal(fs.readFileSync(f.file, "utf8"), f.raw);
    assert.deepEqual(f.store.write(path.join(f.root, "new.json"), f.auth, {}), f.auth);
    assert.equal(f.store.status().state, "temporary");
    assert.equal(fs.existsSync(path.join(f.root, "new.json")), false);
  }
});

test("temporary sessions work without new plaintext files and persist securely when storage returns", (t) => {
  let clock = 0;
  const f = fixture(t, { now: () => clock });
  f.store.remove(f.file);
  f.safeStorage.available = false;
  f.store.assertWritable(f.file, {});
  f.store.write(f.file, f.auth, {});
  const rotated = { ...f.auth, microsoftRefreshToken: "test-rotated-in-memory" };
  f.store.write(f.file, rotated, {});
  assert.deepEqual(f.store.read(f.file, {}), rotated);
  assert.equal(f.store.status().state, "temporary");
  assert.match(f.store.status().message, /ends when the launcher closes/);
  assert.deepEqual(fs.readdirSync(f.root), []);
  assert.deepEqual(createCredentialStore({ safeStorage: f.safeStorage }).read(f.file, {}), {});
  f.safeStorage.available = true;
  clock += 30_001;
  assert.deepEqual(f.store.read(f.file, {}), rotated);
  assert.equal(f.store.status().state, "encrypted");
  assert.doesNotMatch(fs.readFileSync(f.file, "utf8"), /test-/);
  assert.deepEqual(createCredentialStore({ safeStorage: f.safeStorage }).read(f.file, {}), rotated);
});

test("logout removes temporary credentials and a late disk file is not overwritten by them", (t) => {
  const f = fixture(t);
  f.store.remove(f.file);
  f.safeStorage.available = false;
  f.store.write(f.file, f.auth, {});
  f.store.remove(f.file);
  assert.deepEqual(f.store.read(f.file, {}), {});
  f.store.write(f.file, f.auth, {});
  fs.writeFileSync(f.file, "unreadable-existing-credentials");
  assert.throws(() => f.store.read(f.file, {}), /preserved/);
  assert.equal(fs.readFileSync(f.file, "utf8"), "unreadable-existing-credentials");
});

test("supported Linux and Windows provider paths encrypt normally", (t) => {
  for (const platform of ["linux", "win32"]) {
    const f = fixture(t, { platform });
    assert.deepEqual(f.store.read(f.file, {}), f.auth);
    assert.equal(f.store.status().state, "encrypted");
  }
});

test("an encrypted account never downgrades or gets overwritten when OS storage becomes unavailable", (t) => {
  const f = fixture(t);
  f.store.read(f.file, {});
  const encrypted = fs.readFileSync(f.file, "utf8");
  f.safeStorage.available = false;
  assert.deepEqual(f.store.read(f.file, {}), f.auth, "already unlocked credentials remain usable in memory");
  assert.throws(() => f.store.write(f.file, account("bob"), {}), /Secure system storage/);
  const restarted = createCredentialStore({ safeStorage: f.safeStorage });
  assert.throws(() => restarted.read(f.file, {}), /saved accounts have not been deleted/);
  assert.throws(() => restarted.write(f.file, {}, {}), /saved accounts have not been deleted/);
  assert.equal(restarted.status().state, "unavailable");
  assert.equal(fs.readFileSync(f.file, "utf8"), encrypted);
  f.safeStorage.available = true;
  assert.deepEqual(restarted.read(f.file, {}), f.auth);
});

test("a different OS key preserves the original ciphertext until the correct key is restored", (t) => {
  const f = fixture(t);
  f.store.read(f.file, {});
  const encrypted = fs.readFileSync(f.file, "utf8");
  const wrongKey = createCredentialStore({ safeStorage: encryptionProvider() });
  assert.throws(() => wrongKey.read(f.file, {}), /Secure system storage/);
  assert.throws(() => wrongKey.write(f.file, account("bob"), {}), /Secure system storage/);
  assert.equal(fs.readFileSync(f.file, "utf8"), encrypted);
  assert.deepEqual(createCredentialStore({ safeStorage: f.safeStorage }).read(f.file, {}), f.auth);
});

test("migration failures leave the original byte-for-byte intact and clean temporary files", async (t) => {
  for (const failure of ["encrypt", "verify", "write", "fsync", "readback", "rename"]) {
    await t.test(failure, (t) => {
      const fileSystem = { ...fs };
      const f = fixture(t, { fileSystem });
      const fail = () => { throw new Error("Injected failure"); };
      if (failure === "encrypt") f.safeStorage.encryptString = fail;
      if (failure === "verify") f.safeStorage.decryptString = () => "incorrect round-trip";
      if (failure === "write") fileSystem.writeFileSync = fail;
      if (failure === "fsync") fileSystem.fsyncSync = fail;
      if (failure === "readback") fileSystem.readFileSync = (file, ...args) => String(file).endsWith(".tmp") ? "corrupt temporary bytes" : fs.readFileSync(file, ...args);
      if (failure === "rename") fileSystem.renameSync = fail;
      assert.deepEqual(f.store.read(f.file, {}), f.auth);
      assert.equal(fs.readFileSync(f.file, "utf8"), f.raw);
      assert.equal(f.store.status().state, "legacy");
      assert.deepEqual(fs.readdirSync(f.root), ["minecraft-auth.json"]);
    });
  }
});

test("a failed update leaves the previous encrypted account intact", (t) => {
  const fileSystem = { ...fs };
  const f = fixture(t, { fileSystem });
  f.store.read(f.file, {});
  const encrypted = fs.readFileSync(f.file, "utf8");
  fileSystem.renameSync = () => { throw new Error("Disk full"); };
  assert.throws(() => f.store.write(f.file, account("bob"), {}), /previous sign-in file has been preserved/);
  assert.equal(fs.readFileSync(f.file, "utf8"), encrypted);
  assert.deepEqual(f.store.read(f.file, {}), f.auth);
  assert.deepEqual(fs.readdirSync(f.root), ["minecraft-auth.json"]);
});

test("corrupt and unsupported formats cannot be silently replaced by an empty session", (t) => {
  const f = fixture(t);
  for (const raw of ["invalid test-secret-json", '{"format":"river-safe-storage","version":9,"ciphertext":"abc"}', "null"]) {
    fs.writeFileSync(f.file, raw);
    const validate = (value) => value && typeof value === "object";
    assert.throws(() => f.store.read(f.file, {}, validate), /files have been preserved/);
    assert.throws(() => f.store.write(f.file, {}, {}, validate), /files have been preserved/);
    assert.equal(fs.readFileSync(f.file, "utf8"), raw);
    assert.doesNotMatch(f.store.status().message, /test-secret/);
  }
});

test("public projections allow only UI fields, including nested saved-account profiles", () => {
  const auth = account();
  auth.secret = "test-hidden-extra";
  auth.profile.secret = "test-hidden-nested";
  auth.profile.capes = [{ id: "cape", alias: "Cape", url: "https://example.invalid/cape", active: true, secret: "test-cape-secret" }];
  const result = publicSavedProfile({ id: "alice", name: "alice", savedAt: "today", secret: "test-profile-secret", auth });
  assert.doesNotMatch(JSON.stringify(result), /test-|Token|secret/);
  assert.equal(result.auth.profile.skinUrl, auth.profile.skinUrl);
  assert.equal(result.auth.profile.capes[0].id, "cape");
  assert.equal(publicAuth({ ...auth, refreshError: "Network failed" }).refreshError, "Network failed");
  assert.equal(publicAuth({ ...auth, storageUnavailable: true }).storageUnavailable, true);
  assert.equal(auth.microsoftRefreshToken, "test-ms-refresh-alice", "projection must not mutate the main-process session");
});

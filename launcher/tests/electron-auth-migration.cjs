// Run twice with a disposable directory to exercise a real Electron/OS-keychain restart:
// electron launcher/tests/electron-auth-migration.cjs <temp-dir>/river-auth-smoke-* seed
// electron launcher/tests/electron-auth-migration.cjs <same-dir> restart
const { app, safeStorage } = require("electron");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { createCredentialStore } = require("../src/credential-store.js");
const { account } = require("./fixtures/auth-storage.js");

const directory = path.resolve(process.argv[2] || "");
const phase = process.argv[3];
const tempRoots = process.platform === "win32" ? [os.tmpdir()] : [os.tmpdir(), "/tmp"];
if (!fs.existsSync(directory) || !tempRoots.some((root) => fs.realpathSync(directory).startsWith(fs.realpathSync(root) + path.sep))
    || !path.basename(directory).startsWith("river-auth-smoke-") || !["seed", "restart"].includes(phase)) {
  console.error("Pass an existing river-auth-smoke-* temporary directory and seed/restart phase.");
  app.exit(1);
}
app.setName("River Auth Migration Test");
app.setPath("userData", directory);
app.setPath("sessionData", directory);
const timeout = setTimeout(() => { console.error("OS-storage migration test timed out"); app.exit(1); }, 25_000);

app.whenReady().then(() => {
  const store = createCredentialStore({ safeStorage });
  const fixedAccount = (id) => ({ ...account(id), expiresAt: 4102444800000, sessionExpiresAt: 4102444800000 });
  const alice = fixedAccount("alice");
  const saved = [alice, fixedAccount("bob")].map((auth) => ({ id: auth.profile.id, name: auth.profile.name, savedAt: "2026-11-16", auth }));
  for (const [name, value] of [
    ["minecraft-auth.json", alice],
    ["profiles.json", saved],
    ["river-session.json", { uuid: "alice", token: "test-river-session", expiresAt: alice.expiresAt }]
  ]) {
    const file = path.join(directory, name);
    if (phase === "seed") {
      assert.equal(fs.existsSync(file), false);
      fs.writeFileSync(file, JSON.stringify(value));
    }
    assert.deepEqual(store.read(file, null), value);
    assert.equal(store.status().state, "encrypted", "a real OS keychain is required for this smoke test");
    assert.equal(JSON.parse(fs.readFileSync(file, "utf8")).format, "river-safe-storage");
    assert.doesNotMatch(fs.readFileSync(file, "utf8"), /test-ms-|test-minecraft-|test-river-/);
  }
  assert.equal(fs.readdirSync(directory).some((name) => name.endsWith(".tmp")), false);
  clearTimeout(timeout);
  console.log(JSON.stringify({ electron: process.versions.electron, platform: process.platform, phase, encryptedFiles: 3, savedAccounts: 2, credentialsPreserved: true }));
  app.exit(0);
}).catch((error) => { console.error(error); app.exit(1); });

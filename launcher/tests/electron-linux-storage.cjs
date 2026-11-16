const { app, safeStorage } = require("electron");
const fs = require("node:fs");
const path = require("node:path");
const os = require("node:os");
const assert = require("node:assert/strict");
const { createCredentialStore } = require("../src/credential-store");

const directory = path.resolve(process.argv[2] || "");
const phase = process.argv[3];
assert.equal(process.platform, "linux");
assert.ok(fs.realpathSync(directory).startsWith(fs.realpathSync(os.tmpdir()) + path.sep));
assert.ok(path.basename(directory).startsWith("river-linux-storage-"));
assert.ok(["write", "restart"].includes(phase));
app.setName("River Linux Storage Test");
app.setPath("userData", directory);
app.setPath("sessionData", directory);
app.commandLine.appendSwitch("password-store", "basic");
app.disableHardwareAcceleration();
const timeout = setTimeout(() => app.exit(1), 30_000);
app.whenReady().then(() => {
  assert.equal(safeStorage.getSelectedStorageBackend(), "basic_text");
  const store = createCredentialStore({ safeStorage });
  const auth = { signedIn: true, microsoftRefreshToken: "test-linux-refresh", profile: { id: "test", name: "Test" } };
  for (const [name, value] of [["minecraft-auth.json", auth], ["profiles.json", [{ id: "test", auth }]], ["river-session.json", { token: "test-river" }]]) {
    const file = path.join(directory, name);
    if (phase === "write") { store.assertWritable(file, null); store.write(file, value, null); }
    assert.deepEqual(store.read(file, null), value);
    assert.equal(fs.statSync(file).mode & 0o777, 0o600);
    assert.equal(store.status().state, "legacy");
    assert.match(store.status().message, /encryption is pending/);
  }
  clearTimeout(timeout);
  console.log(`Linux ${phase}: active account, saved account and River session persist with mode 0600; encryption pending.`);
  app.exit(0);
}).catch((error) => { console.error(error); app.exit(1); });

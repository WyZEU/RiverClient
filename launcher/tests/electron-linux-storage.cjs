const { app, safeStorage } = require("electron");
const fs = require("node:fs");
const path = require("node:path");
const os = require("node:os");
const assert = require("node:assert/strict");
const { createCredentialStore } = require("../src/credential-store");

// Electron keeps switches before the script in argv. Never let an early assertion
// open an invisible error dialog and leave the headless runner waiting forever.
process.on("uncaughtException", (error) => { console.error(error); app.exit(1); });
const timeout = setTimeout(() => { console.error("Linux storage smoke timed out"); app.exit(1); }, 30_000);
const scriptIndex = process.argv.findIndex((argument) => path.resolve(argument) === __filename);
assert.ok(scriptIndex > 0, "Missing storage test script argument");
const directory = path.resolve(process.argv[scriptIndex + 1] || "");
const phase = process.argv[scriptIndex + 2];
assert.equal(process.platform, "linux");
assert.ok(fs.realpathSync(directory).startsWith(fs.realpathSync(os.tmpdir()) + path.sep));
assert.ok(path.basename(directory).startsWith("river-linux-storage-"));
assert.ok(["write", "restart"].includes(phase));
app.setName("River Linux Storage Test");
app.setPath("userData", directory);
app.setPath("sessionData", directory);
app.commandLine.appendSwitch("password-store", "basic");
app.disableHardwareAcceleration();
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

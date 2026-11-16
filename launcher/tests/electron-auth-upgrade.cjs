// Real main process, renderer IPC and OS credential provider; Microsoft endpoints are
// simulated. Only a disposable userData directory is accepted. No game is started.
const { app, session, safeStorage, shell } = require("electron");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const assert = require("node:assert/strict");
const directory = path.resolve(process.argv[2] || "");
const main = path.resolve(process.argv[3] || "");
const phase = process.argv[4];
assert.ok(fs.realpathSync(directory).startsWith(fs.realpathSync(os.tmpdir()) + path.sep));
assert.ok(path.basename(directory).startsWith("river-auth-smoke-"));
assert.ok(["upgrade", "restart", "temporary"].includes(phase));
app.setName("River Auth Migration Test");
app.setPath("userData", directory);
app.setPath("sessionData", directory);
global.WebSocket = undefined;
shell.openExternal = async () => {};
if (phase === "temporary") safeStorage.isEncryptionAvailable = () => false;
const json = (value) => new Response(JSON.stringify(value), { status: 200, headers: { "Content-Type": "application/json" } });
const calls = [];
global.fetch = async (input, options = {}) => {
  const url = String(input);
  if (url.includes("version_manifest_v2.json")) return json({ latest: {}, versions: [] });
  if (url.endsWith("/devicecode")) return json({ user_code: "TEST", device_code: "test-device", expires_in: 60, interval: 5 });
  if (url.endsWith("/oauth2/v2.0/token")) {
    calls.push("microsoft-token");
    return json({ access_token: "test-ms-ui-access", refresh_token: "test-ms-ui-rotated" });
  }
  if (url.includes("auth.xboxlive.com")) return json({ Token: "test-xbox", DisplayClaims: { xui: [{ uhs: "test-uhs" }] } });
  if (url.endsWith("/authentication/login_with_xbox")) return json({ access_token: "test-minecraft-ui", expires_in: 86400 });
  if (url.endsWith("/entitlements/mcstore")) return json({ items: [{ name: "game_minecraft" }] });
  if (url.endsWith("/minecraft/profile")) return json({ id: "alice", name: "alice", skins: [], capes: [] });
  throw new Error("Network disabled by auth upgrade test");
};
const timeout = setTimeout(() => { console.error("Auth upgrade smoke timed out"); app.exit(1); }, 60_000);
app.whenReady().then(() => session.defaultSession.webRequest.onBeforeRequest({ urls: ["https://*/*", "http://*/*"] }, (_details, callback) => callback({ cancel: true })));
let tested = false;
app.on("browser-window-created", (_event, window) => {
  window.show = () => {};
  window.showInactive = () => {};
  window.hide();
  window.webContents.setAudioMuted(true);
  window.webContents.on("preload-error", (_event, _path, error) => { console.error(error); app.exit(1); });
  window.webContents.once("did-finish-load", async () => {
    if (tested) return;
    tested = true;
    try {
      const invoke = (method, ...args) => window.webContents.executeJavaScript(`window.clientcore[${JSON.stringify(method)}](...${JSON.stringify(args)})`);
      let status = await invoke("getStatus");
      assert.equal(status.auth.signedIn, true);
      assert.equal(status.auth.profile.id, "alice");
      assert.equal((await invoke("getProfiles")).profiles.length, 2);
      assert.equal(status.credentialStorage.state, phase === "temporary" ? "legacy" : "encrypted");
      assert.equal(await window.webContents.executeJavaScript("typeof window.require"), "undefined");
      if (phase !== "restart") {
        assert.equal((await invoke("switchProfile", "bob")).ok, true);
        assert.equal((await invoke("getStatus")).auth.profile.id, "bob");
        assert.equal((await invoke("switchProfile", "alice")).ok, true);
        const refreshed = await invoke("refreshAuth");
        assert.equal(refreshed.ok, true, refreshed.message);
        assert.ok(calls.includes("microsoft-token"), "the expired 0.3.0 token must refresh");
        assert.equal((await invoke("microsoftLogout")).ok, true);
        assert.equal((await invoke("getStatus")).auth.signedIn, false);
        const login = await invoke("microsoftLogin");
        assert.equal(login.ok, true, login.message);
        assert.equal(login.auth.profile.id, "alice");
        assert.equal((await invoke("saveProfile")).ok, true);
        status = await invoke("getStatus");
      }
      assert.doesNotMatch(JSON.stringify([status, await invoke("getProfiles")]), /test-ms-|test-minecraft-|microsoftRefreshToken|minecraftAccessToken/);
      assert.equal(status.credentialStorage.state, phase === "temporary" ? "temporary" : "encrypted");
      if (phase === "temporary") {
        assert.equal(fs.existsSync(path.join(directory, "minecraft-auth.json")), false);
        assert.match(status.credentialStorage.message, /ends when the launcher closes/);
      } else {
        for (const name of ["minecraft-auth.json", "profiles.json"]) {
          const envelope = JSON.parse(fs.readFileSync(path.join(directory, name), "utf8"));
          assert.equal(envelope.format, "river-safe-storage");
          const value = JSON.parse(safeStorage.decryptString(Buffer.from(envelope.ciphertext, "base64")));
          const active = Array.isArray(value) ? value.find((entry) => entry.id === "alice").auth : value;
          assert.equal(active.microsoftRefreshToken, "test-ms-ui-rotated");
        }
      }
      clearTimeout(timeout);
      console.log(JSON.stringify({ platform: process.platform, electron: process.versions.electron, phase, signedIn: true, savedAccounts: 2, storage: status.credentialStorage.state, ipcSecrets: false }));
      app.exit(0);
    } catch (error) { console.error(error); app.exit(1); }
  });
});
require(main);

// Runs the real packaged launcher with disposable accounts and simulated Microsoft endpoints.
const { app, session, shell } = require("electron");
const fs = require("node:fs"), path = require("node:path"), os = require("node:os");
const assert = require("node:assert/strict");
process.on("uncaughtException", (error) => { console.error(error); app.exit(1); });
const index = process.argv.findIndex((argument) => path.resolve(argument) === __filename);
const directory = path.resolve(process.argv[index + 1]);
const main = path.resolve(process.argv[index + 2]);
const phase = process.argv[index + 3];
assert.equal(process.platform, "linux");
assert.ok(directory.startsWith(fs.realpathSync(os.tmpdir()) + "/river-linux-upgrade-"));
app.setName("River Linux Upgrade Test");
app.setPath("userData", directory);
app.setPath("sessionData", directory);
app.commandLine.appendSwitch("password-store", "basic");
app.disableHardwareAcceleration();
global.WebSocket = undefined;
shell.openExternal = async () => {};
const json = (value) => new Response(JSON.stringify(value), { status: 200, headers: { "Content-Type": "application/json" } });
global.fetch = async (input) => {
  const url = String(input);
  if (url.includes("version_manifest_v2.json")) return json({ latest: {}, versions: [] });
  if (url.endsWith("/devicecode")) return json({ user_code: "TEST", device_code: "test-device", expires_in: 60, interval: 1 });
  if (url.endsWith("/oauth2/v2.0/token")) return json({ access_token: "test-ms-access", refresh_token: "test-ms-refresh" });
  if (url.includes("auth.xboxlive.com")) return json({ Token: "test-xbox", DisplayClaims: { xui: [{ uhs: "test-uhs" }] } });
  if (url.endsWith("/authentication/login_with_xbox")) return json({ access_token: "test-minecraft", expires_in: 86400 });
  if (url.endsWith("/entitlements/mcstore")) return json({ items: [{ name: "game_minecraft" }] });
  if (url.endsWith("/minecraft/profile")) return json({ id: "alice", name: "alice", skins: [], capes: [] });
  throw new Error("Network disabled by the Linux upgrade test");
};
const timer = setTimeout(() => { console.error("Linux upgrade UI timed out"); app.exit(1); }, 60_000);
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
      if (phase === "seed") {
        const login = await invoke("microsoftLogin");
        assert.equal(login.ok, true, login.message);
        assert.equal((await invoke("saveProfile")).ok, true);
      }
      const status = await invoke("getStatus");
      assert.equal(status.auth.signedIn, true);
      assert.equal(status.auth.profile.id, "alice");
      assert.equal((await invoke("getProfiles")).profiles.length, 1);
      assert.ok(!JSON.stringify(status.auth).includes("test-ms-refresh"));
      for (const name of ["minecraft-auth.json", "profiles.json"]) {
        assert.equal(fs.statSync(path.join(directory, name)).mode & 0o777, 0o600);
      }
      if (phase === "upgraded") assert.equal((await invoke("refreshAuth")).ok, true);
      console.log(JSON.stringify({ phase, version: app.getVersion(), signedIn: true, savedAccounts: 1, storage: status.credentialStorage?.state }));
      clearTimeout(timer);
      app.exit(0);
    } catch (error) { console.error(error); app.exit(1); }
  });
});
require(main);

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const vm = require("node:vm");
const { createCredentialStore, publicAuth, publicSavedProfile } = require("../src/credential-store.js");
const { encryptionProvider, account } = require("./fixtures/auth-storage.js");

const source = fs.readFileSync(path.join(__dirname, "../src/main.js"), "utf8");
const plain = (value) => JSON.parse(JSON.stringify(value));

function fixture(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "river-auth-session-"));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const safeStorage = encryptionProvider();
  const auth = account();
  const saved = [auth, account("bob")].map((auth) => ({ id: auth.profile.id, name: auth.profile.name, savedAt: "2026-11-16", auth }));
  fs.writeFileSync(path.join(root, "minecraft-auth.json"), JSON.stringify(auth));
  fs.writeFileSync(path.join(root, "profiles.json"), JSON.stringify(saved));
  const f = { root, auth, saved, safeStorage, calls: [], events: [], handlers: new Map() };
  f.restart = () => {
    f.context = vm.createContext({
      fs, path, Date, publicAuth, publicSavedProfile,
      credentialStore: createCredentialStore({ safeStorage }),
      app: { getPath: () => root },
      AUTH_SESSION_MS: 30 * 24 * 60 * 60 * 1000, AUTH_REFRESH_SKEW_MS: 300_000,
      riverSessionCache: { token: "test-river-old", expiresAt: auth.expiresAt },
      getMicrosoftClientId: () => "test-client-id",
      offlineResult: async () => null,
      requestMicrosoftDeviceCode: async () => ({ user_code: "ABCD", verification_uri: "https://example.invalid" }),
      shell: { openExternal: async () => {} },
      pollMicrosoftToken: async () => ({ access_token: "test-ms-new", refresh_token: "test-ms-rotated" }),
      refreshMicrosoftToken: async (_client, token) => { f.calls.push(token); return { access_token: "test-ms-new", refresh_token: "test-ms-rotated" }; },
      authenticateXboxLive: async () => ({ Token: "test-xbl" }),
      authorizeXsts: async () => ({}),
      authenticateMinecraft: async () => ({ access_token: "test-minecraft-new", expires_in: 86400 }),
      assertMinecraftOwnership: async () => {},
      fetchMinecraftProfile: async () => auth.profile,
      ensureAccountSkinSeeded: async () => {}, refreshKeptSeasons: () => {}, scheduleDiscordPresenceRefresh: () => {},
      emit: (channel, payload) => f.events.push({ channel, payload: plain(payload) }),
      ipcMain: { handle: (channel, handler) => f.handlers.set(channel, handler) },
      readSettings: () => ({ instancePath: path.join(root, "instance"), selectedVersion: "1.21" }),
      lastPlayedByInstance: () => ({}), lastSessionStartByInstance: () => ({}), readInstances: () => [],
      ensureBundledClientCoreMod: () => {}, stageRiverRuntimeForInstance: () => {}, scheduleInstalledIconBackfill: () => {},
      getSystemMemoryMb: () => 8192, getMemoryLimitMb: () => 8192, getRecommendedMemoryMb: () => 4096,
      launchProcess: null, launchState: "idle", clientModVersion: "0.3.1", lastLaunchFailure: null, setupRunning: false,
      readBuildVersion: () => "0.3.1", networkState: {}, getVersions: () => [], getInstalledMods: () => [],
      getInstalledContent: () => [], getLocalFiles: () => [], getCrashInfo: () => null, getLauncherUpdateState: () => ({}),
      getChangelog: () => ({}), getSkinHistory: () => [], readTotalPlaytimeMs: () => 0, discordRpcState: {}
    });
    // Execute the production functions and IPC registrations, not copies of their logic.
    for (const name of ["authPath", "readAuth", "isStoredAuth", "normalizeAuth", "normalizeCapeEntry", "writeAuth", "clearAuth",
      "hasUsableMinecraftToken", "withAuthRefreshError", "profilesPath", "readProfiles", "isStoredProfiles", "writeProfiles", "updateSavedAuth",
      "riverSessionFile", "readStoredRiverSession", "isStoredRiverSession", "storeRiverSession", "forgetRiverSession",
      "getStatus", "emitStatus", "signInWithMicrosoft", "refreshMicrosoftSession", "ensureFreshAuth"]) {
      const match = new RegExp(`(?:async )?function ${name}\\(`).exec(source);
      assert.ok(match, name);
      const firstLineEnd = source.indexOf("\n", match.index);
      const end = source.slice(match.index, firstLineEnd).endsWith("}") ? firstLineEnd : source.indexOf("\n}", match.index) + 2;
      vm.runInContext(source.slice(match.index, end), f.context);
    }
    for (const name of ["get-status", "refresh-auth", "microsoft-login", "microsoft-logout", "microsoft-status", "get-profiles", "save-profile", "switch-profile", "remove-profile"]) {
      const start = source.indexOf(`ipcMain.handle("launcher:${name}",`);
      assert.notEqual(start, -1, name);
      const firstLineEnd = source.indexOf("\n", start);
      const firstLine = source.slice(start, firstLineEnd);
      const terminator = firstLine.includes("=> ({") ? "\n}));" : "\n});";
      const end = firstLine.endsWith(");") ? firstLineEnd : source.indexOf(terminator, start) + terminator.length;
      vm.runInContext(source.slice(start, end), f.context);
    }
  };
  f.invoke = async (name, ...args) => plain(await f.handlers.get(`launcher:${name}`)(null, ...args));
  f.restart();
  return f;
}

test("legacy active and saved accounts survive migration, restart, refresh, switching and logout", async (t) => {
  const f = fixture(t);
  assert.deepEqual(plain(f.context.readAuth()), f.auth);
  assert.deepEqual(plain(f.context.readProfiles()), f.saved);
  f.restart();
  assert.equal(f.context.readAuth().microsoftRefreshToken, f.auth.microsoftRefreshToken);
  const refreshed = await f.context.refreshMicrosoftSession();
  assert.deepEqual(f.calls, [f.auth.microsoftRefreshToken]);
  assert.equal(refreshed.minecraftAccessToken, "test-minecraft-new");
  assert.equal(f.context.readProfiles()[0].auth.microsoftRefreshToken, "test-ms-rotated");
  f.restart();
  assert.equal((await f.invoke("switch-profile", "bob")).ok, true);
  assert.equal(f.context.readAuth().minecraftAccessToken, "test-minecraft-access-bob");
  assert.equal(f.context.riverSessionCache.token, "", "switching must drop the previous account's River session");
  assert.equal((await f.invoke("switch-profile", "alice")).ok, true);
  assert.equal(f.context.readAuth().microsoftRefreshToken, "test-ms-rotated");
  assert.equal((await f.invoke("save-profile")).ok, true);
  assert.equal((await f.invoke("remove-profile", "bob")).ok, true);
  assert.equal(f.context.readProfiles().length, 1);
  assert.equal((await f.invoke("microsoft-logout")).ok, true);
  f.restart();
  assert.equal(f.context.readAuth().signedIn, false);
  assert.equal(f.context.readProfiles().length, 1, "logout preserves explicitly saved accounts");
  assert.equal((await f.invoke("switch-profile", "alice")).ok, true);
  assert.equal(f.context.readAuth().microsoftRefreshToken, "test-ms-rotated");
});

test("old sessions without sessionExpiresAt keep the existing 30-day migration policy", (t) => {
  const f = fixture(t);
  const old = { ...f.auth };
  delete old.sessionExpiresAt;
  fs.writeFileSync(path.join(f.root, "minecraft-auth.json"), JSON.stringify(old));
  const before = Date.now();
  const migrated = f.context.readAuth();
  assert.equal(migrated.signedIn, true);
  assert.ok(migrated.sessionExpiresAt >= before + 30 * 86400_000);
  assert.equal(migrated.microsoftRefreshToken, old.microsoftRefreshToken);
  f.restart();
  assert.equal(f.context.readAuth().sessionExpiresAt, migrated.sessionExpiresAt);
});

test("every auth IPC response and status event omits credentials, including refresh failures", async (t) => {
  const f = fixture(t);
  for (const name of ["get-status", "microsoft-status", "get-profiles", "refresh-auth", "microsoft-login", "microsoft-logout"]) {
    const response = await f.invoke(name);
    assert.doesNotMatch(JSON.stringify(response), /test-ms-|test-minecraft-|test-river-|microsoftRefreshToken|minecraftAccessToken/, name);
  }
  f.context.writeAuth({ ...f.auth, expiresAt: Date.now() - 1 });
  f.context.refreshMicrosoftToken = async () => { throw new Error("Network unavailable"); };
  const failed = await f.invoke("refresh-auth");
  assert.equal(failed.ok, false);
  assert.equal(failed.auth.refreshError, "Network unavailable");
  assert.doesNotMatch(JSON.stringify([failed, f.events]), /test-ms-|test-minecraft-|test-river-|microsoftRefreshToken|minecraftAccessToken/);
  assert.equal(f.context.readAuth().microsoftRefreshToken, f.auth.microsoftRefreshToken);
});

test("locked encrypted storage blocks refresh, login and account mutations without deleting sessions", async (t) => {
  const f = fixture(t);
  f.context.readAuth();
  f.context.readProfiles();
  const before = fs.readdirSync(f.root).map((file) => [file, fs.readFileSync(path.join(f.root, file), "utf8")]);
  f.safeStorage.available = false;
  f.restart();
  const unavailable = f.context.readAuth();
  assert.equal(unavailable.storageUnavailable, true);
  assert.equal((await f.invoke("refresh-auth")).ok, false);
  for (const name of ["microsoft-login", "save-profile", "switch-profile", "remove-profile", "get-profiles"]) {
    assert.equal((await f.invoke(name, "bob")).ok, false, name);
  }
  assert.deepEqual(f.calls, []);
  for (const [file, raw] of before) assert.equal(fs.readFileSync(path.join(f.root, file), "utf8"), raw);
  f.safeStorage.available = true;
  assert.equal(f.context.readAuth().signedIn, true);
  assert.equal(f.context.readProfiles().length, 2);
  assert.equal((await f.invoke("switch-profile", "bob")).ok, true);
});

test("refresh is not started when an already-unlocked encrypted account cannot be saved", async (t) => {
  const f = fixture(t);
  f.context.readAuth();
  f.safeStorage.available = false;
  await assert.rejects(f.context.refreshMicrosoftSession(), /Secure system storage/);
  assert.deepEqual(f.calls, []);
  assert.equal(f.context.readAuth().minecraftAccessToken, f.auth.minecraftAccessToken);
});

test("legacy refresh still persists a rotated token while encryption is temporarily unavailable", async (t) => {
  const f = fixture(t);
  f.safeStorage.available = false;
  await f.context.refreshMicrosoftSession();
  f.restart();
  assert.equal(f.context.readAuth().microsoftRefreshToken, "test-ms-rotated");
  assert.equal(f.context.readProfiles()[0].auth.microsoftRefreshToken, "test-ms-rotated");
  assert.equal(f.context.getStatus().credentialStorage.state, "legacy");
});

test("fresh Microsoft device-code sign-in stores tokens privately and returns the same public profile", async (t) => {
  const f = fixture(t);
  f.context.clearAuth();
  const response = await f.invoke("microsoft-login");
  assert.equal(response.ok, true);
  assert.equal(response.auth.signedIn, true);
  assert.equal(response.auth.profile.name, "alice");
  assert.doesNotMatch(JSON.stringify(response), /test-ms-|test-minecraft-|Token/);
  f.restart();
  assert.equal(f.context.readAuth().microsoftRefreshToken, "test-ms-rotated");
  assert.equal(f.context.readAuth().minecraftAccessToken, "test-minecraft-new");
  assert.doesNotMatch(fs.readFileSync(path.join(f.root, "minecraft-auth.json"), "utf8"), /test-ms-|test-minecraft-/);
});

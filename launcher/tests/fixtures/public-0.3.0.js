const fs = require("node:fs");
const crypto = require("node:crypto");
const vm = require("node:vm");
const assert = require("node:assert/strict");
const asar = require("@electron/asar");
const path = require("node:path");
const { account } = require("./auth-storage.js");

const release = {
  version: "0.3.0",
  url: "https://updates.riverclient.xyz/downloads/releases/0.3.0/River-Client-App-0.3.0.zip",
  size: 308463114,
  sha256: "8218736a3f5d7516ff20730dfcaed6cb0a2b4c016f57a4d6a69698fc0ca02ab4",
  asarSha256: "4ee93aabc6deb8249cc0a84f2e9d3de588bb32f0a3071994f1f26336e332bd36"
};
const hash = (bytes) => crypto.createHash("sha256").update(bytes).digest("hex");
function publicSource(file) {
  assert.equal(hash(fs.readFileSync(file)), release.asarSha256, "Use the exact public 0.3.0 ASAR");
  return asar.extractFile(file, "app/main.js").toString("utf8");
}
function loadFunctions(source, names, bindings) {
  const context = vm.createContext(bindings);
  for (const name of names) {
    const match = new RegExp(`(?:async )?function ${name}\\(`).exec(source);
    assert.ok(match, name);
    const first = source.indexOf("\n", match.index);
    const end = source.slice(match.index, first).endsWith("}") ? first : source.indexOf("\n}", match.index) + 2;
    vm.runInContext(source.slice(match.index, end), context);
  }
  return context;
}

function seedLegacy(source, directory) {
  fs.mkdirSync(directory, { recursive: true });
  const old = loadFunctions(source, ["authPath", "normalizeAuth", "normalizeCapeEntry", "writeAuth", "profilesPath", "writeProfiles"], {
    fs, path, Date, AUTH_SESSION_MS: 30 * 86400_000, app: { getPath: () => directory }
  });
  const alice = { ...account("alice"), expiresAt: Date.now() - 60_000 };
  old.writeAuth(alice);
  old.writeProfiles([alice, account("bob")].map((auth) => ({ id: auth.profile.id, name: auth.profile.name, savedAt: "2026-11-16", auth })));
  fs.writeFileSync(path.join(directory, "settings.json"), JSON.stringify({ instancePath: path.join(directory, "instance"),
    tutorialCompleted: true, lastSeenChangelogVersion: "0.3.1", discordRichPresence: false, autoCheckUpdates: false, launcherLanguage: "en", uiScale: "100" }));
  fs.writeFileSync(path.join(directory, "upgrade-canary.txt"), "existing user data");
}

module.exports = { release, publicSource, loadFunctions, seedLegacy, hash };

// Windows integration: the pinned public client applies this checkout's candidate
// using its own updater. This never signs, uploads or publishes a release.
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const crypto = require("node:crypto");
const assert = require("node:assert/strict");
const { Readable } = require("node:stream");
const { pipeline } = require("node:stream/promises");
const { spawnSync } = require("node:child_process");
const asar = require("@electron/asar");
const { release, publicSource, loadFunctions, seedLegacy, hash } = require("./fixtures/public-0.3.0.js");
const { apply, powershell, shellEnv } = require("./fixtures/update-apply.js");
const { digestFile } = require("../scripts/release-artifact.js");

function run(executable, args, options = {}) {
  const result = spawnSync(executable, args, { encoding: "utf8", windowsHide: true, timeout: 180_000, ...options });
  if (result.error) throw result.error;
  assert.equal(result.status, 0, result.stderr + result.stdout);
  return result.stdout.trim();
}
const quote = (value) => String(value).replace(/'/g, "''");
async function main() {
  assert.equal(process.platform, "win32", "Run this integration on Windows");
  const candidate = path.resolve(process.argv[2] || "");
  assert.ok(fs.existsSync(path.join(candidate, "RiverClient.exe")), "Pass a built win-unpacked candidate directory");
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "river-upgrade-030-"));
  try {
    const archive = process.env.RIVER_PUBLIC_030_ZIP || path.join(root, "public-0.3.0.zip");
    if (!fs.existsSync(archive)) {
      const response = await fetch(release.url, { signal: AbortSignal.timeout(180_000) });
      assert.ok(response.ok, "Public 0.3.0 download failed");
      await pipeline(Readable.fromWeb(response.body), fs.createWriteStream(archive, { flags: "wx" }));
    }
    assert.deepEqual(await digestFile(archive), { size: release.size, sha256: release.sha256 });
    const original = path.join(root, "public-installation");
    run(powershell, ["-NoProfile", "-NonInteractive", "-Command", `Expand-Archive -LiteralPath '${quote(archive)}' -DestinationPath '${quote(original)}'`], { env: shellEnv });
    const oldAsar = path.join(original, "resources/app.asar");
    const source = publicSource(oldAsar);
    const old = loadFunctions(source, ["verifyJsonSha256", "normalizeUpdateFilePath", "hashFileStreaming", "listRelativeFiles", "buildDifferentialUpdatePlan", "buildApplyUpdateScript"], {
      fs, ofs: fs, path, crypto, Buffer, escapePowerShell: quote
    });
    assert.equal(asar.extractFile(oldAsar, "app/config/update-public-key.txt").toString().trim(),
      asar.extractFile(path.join(candidate, "resources/app.asar"), "app/config/update-public-key.txt").toString().trim());
    const nativeVersion = (dir) => JSON.parse(run(path.join(dir, "RiverClient.exe"), ["-p", "JSON.stringify({electron:process.versions.electron,node:process.versions.node})"], {
      env: { ...process.env, ELECTRON_RUN_AS_NODE: "1" }
    }));
    const beforeVersion = nativeVersion(original);
    const expectedVersion = nativeVersion(candidate);
    const files = [];
    for (const entry of fs.readdirSync(candidate, { recursive: true, withFileTypes: true }).filter((entry) => entry.isFile())) {
      const file = path.join(entry.parentPath || entry.path, entry.name);
      const relative = path.relative(candidate, file).split(path.sep).join("/");
      files.push({ path: relative, ...await digestFile(file), url: `https://example.invalid/${relative}` });
    }
    const manifest = { version: "0.3.1", generatedAt: new Date().toISOString(), files };
    old.verifyJsonSha256(manifest, hash(JSON.stringify(manifest)));
    const { ELECTRON_RUN_AS_NODE, ...electronEnv } = process.env;
    for (const mode of ["delta", "package"]) {
      const testRoot = path.join(root, mode);
      const installDir = path.join(testRoot, "River Client");
      fs.cpSync(original, installDir, { recursive: true });
      fs.writeFileSync(path.join(installDir, "Uninstall River Client.exe"), "existing uninstaller fixture");
      const userData = path.join(testRoot, "river-auth-smoke-existing");
      seedLegacy(source, userData);
      const beforeAuth = fs.readFileSync(path.join(userData, "minecraft-auth.json"));
      const plan = await old.buildDifferentialUpdatePlan(manifest, installDir);
      assert.ok(plan.downloads.length > 0);
      const sourceDir = path.join(testRoot, "staging");
      for (const entry of mode === "delta" ? plan.downloads : files) {
        const staged = path.join(sourceDir, entry.path);
        fs.mkdirSync(path.dirname(staged), { recursive: true });
        fs.copyFileSync(path.join(candidate, entry.path), staged);
      }
      const manifestPath = path.join(testRoot, "release-files.json");
      fs.writeFileSync(manifestPath, JSON.stringify({ version: "0.3.1", files: plan.files.map(({ path }) => ({ path })), changedFiles: plan.downloads.map(({ path }) => path) }));
      const result = apply(testRoot, { sourceDir, installDir, mode, manifestPath, buildScript: old.buildApplyUpdateScript });
      assert.equal(result.status, 0, result.stderr + result.log);
      assert.match(result.launches, /RiverClient.exe/);
      assert.doesNotMatch(result.launches, /river-update-failed/);
      for (const entry of files) assert.deepEqual(await digestFile(path.join(installDir, entry.path)), { size: entry.size, sha256: entry.sha256 }, entry.path);
      assert.equal(fs.readFileSync(path.join(installDir, "Uninstall River Client.exe"), "utf8"), "existing uninstaller fixture");
      assert.deepEqual(fs.readFileSync(path.join(userData, "minecraft-auth.json")), beforeAuth, "app replacement must not touch credentials");
      assert.deepEqual(nativeVersion(installDir), expectedVersion);
      const bundledMain = path.join(installDir, "resources/app.asar/app/main.js");
      for (const phase of ["upgrade", "restart"]) {
        console.log(run(require("electron"), [path.join(__dirname, "electron-auth-upgrade.cjs"), userData, bundledMain, phase], { env: electronEnv }));
      }
      assert.equal(fs.readFileSync(path.join(userData, "upgrade-canary.txt"), "utf8"), "existing user data");
      const temporary = path.join(testRoot, "river-auth-smoke-temporary");
      seedLegacy(source, temporary);
      console.log(run(require("electron"), [path.join(__dirname, "electron-auth-upgrade.cjs"), temporary, bundledMain, "temporary"], { env: electronEnv }));
      console.log(JSON.stringify({ publicVersion: release.version, mode, changedFiles: plan.downloads.length, verifiedCandidateFiles: files.length, beforeVersion, afterVersion: expectedVersion, userDataPreserved: true }));
    }
  } finally { fs.rmSync(root, { recursive: true, force: true, maxRetries: 5, retryDelay: 300 }); }
}
main().catch((error) => { console.error(error); process.exitCode = 1; });

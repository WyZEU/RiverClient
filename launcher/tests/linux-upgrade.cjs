// Upgrade the current signed public Linux installation, then exercise its real UI and account store.
const fs = require("node:fs"), os = require("node:os"), path = require("node:path");
const assert = require("node:assert/strict");
const { Readable } = require("node:stream");
const { pipeline } = require("node:stream/promises");
const { spawnSync } = require("node:child_process");
const { verifyManifest } = require("../src/update-signature");
const { linuxPackage, extractLinuxArchive, prepareLinuxUpdate, hashFile } = require("../src/linux-update");

function run(file, args, env = {}) {
  const result = spawnSync(file, args, { encoding: "utf8", timeout: 180_000, env: { ...process.env, ...env } });
  if (result.error) throw result.error;
  assert.equal(result.status, 0, result.stderr + result.stdout);
  return result.stdout.trim();
}
async function main() {
  assert.equal(process.platform, "linux", "Run the native upgrade check on Linux");
  const candidate = path.resolve(process.argv[2] || "");
  assert.ok(fs.statSync(candidate).isFile(), "Pass the candidate Linux tar.gz");
  const root = fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), "river-linux-upgrade-")));
  try {
    const manifest = process.env.RIVER_PUBLIC_LINUX_MANIFEST
      ? JSON.parse(fs.readFileSync(process.env.RIVER_PUBLIC_LINUX_MANIFEST, "utf8"))
      : await (await fetch("https://updates.riverclient.xyz/latest.json", { signal: AbortSignal.timeout(30_000) })).json();
    assert.ok(verifyManifest(manifest), "Verify the current public manifest with the installed trust key");
    const expected = linuxPackage(manifest);
    const archive = process.env.RIVER_PUBLIC_LINUX_ARCHIVE || path.join(root, "public.tar.gz");
    if (!fs.existsSync(archive)) {
      const response = await fetch(expected.url, { signal: AbortSignal.timeout(600_000) });
      assert.ok(response.ok, "Public Linux download failed");
      await pipeline(Readable.fromWeb(response.body), fs.createWriteStream(archive, { flags: "wx" }));
    }
    assert.equal(fs.statSync(archive).size, expected.size);
    assert.equal(await hashFile(archive), expected.sha256);
    const installDir = path.join(root, "River Client");
    await extractLinuxArchive(archive, installDir);
    const binary = path.join(installDir, "river-client-bin");
    const userData = path.join(root, "account");
    fs.mkdirSync(userData);
    fs.writeFileSync(path.join(userData, "settings.json"), JSON.stringify({ tutorialCompleted: true, discordRichPresence: false,
      autoCheckUpdates: false, instancePath: path.join(userData, "instance"), launcherLanguage: "en", lastSeenChangelogVersion: "999.0.0" }));
    fs.mkdirSync(path.join(installDir, "custom-instance"));
    fs.writeFileSync(path.join(installDir, "custom-instance", "world.txt"), "preserved world");
    const probe = (phase) => {
      // Packaged Electron ignores a script argument. Load the probe as its entry point,
      // requiring the untouched, verified public/candidate ASAR as the real application.
      const asar = path.join(installDir, "resources/app.asar");
      const tested = path.join(installDir, "resources/app-tested.asar");
      const entry = path.join(installDir, "resources/app");
      assert.ok(!fs.existsSync(entry));
      fs.renameSync(asar, tested);
      fs.mkdirSync(entry);
      const script = path.join(__dirname, "electron-linux-upgrade.cjs");
      fs.writeFileSync(path.join(entry, "package.json"), JSON.stringify({ name: "river-linux-upgrade-probe", version: "0.0.0", main: "main.cjs" }));
      fs.writeFileSync(path.join(entry, "main.cjs"), `require(${JSON.stringify(script)});\n`);
      try {
        console.log(run("xvfb-run", ["-a", binary, "--no-sandbox", script, userData, path.join(tested, "app/main.js"), phase]));
      } finally {
        fs.rmSync(entry, { recursive: true, force: true });
        fs.renameSync(tested, asar);
      }
    };
    probe("seed");
    probe("public-restart");
    const authBefore = fs.readFileSync(path.join(userData, "minecraft-auth.json"));
    const job = await prepareLinuxUpdate({ archive: candidate, installDir, expected: { size: fs.statSync(candidate).size, sha256: await hashFile(candidate) } });
    const helper = path.join(job.work, "apply.js");
    const jobFile = path.join(job.work, "job.json");
    fs.copyFileSync(path.join(__dirname, "../src/linux-update.js"), helper);
    fs.writeFileSync(jobFile, JSON.stringify({ ...job, pid: 0 }));
    console.log(run(binary, ["-e", "require(process.argv[1]).applyLinuxUpdate(JSON.parse(require('fs').readFileSync(process.argv[2])),{restart:false}).then(()=>console.log('applied')).catch(e=>{console.error(e);process.exitCode=1})", helper, jobFile], { ELECTRON_RUN_AS_NODE: "1" }));
    assert.deepEqual(fs.readFileSync(path.join(userData, "minecraft-auth.json")), authBefore);
    assert.equal(fs.readFileSync(path.join(installDir, "custom-instance/world.txt"), "utf8"), "preserved world");
    for (const file of job.files) assert.equal(await hashFile(path.join(installDir, file.path)), file.sha256, file.path);
    probe("upgraded");
    probe("restart");
    console.log(JSON.stringify({ publicVersion: manifest.version, verifiedFiles: job.files.length, accountsPreserved: true, nativeLinux: true }));
  } finally { fs.rmSync(root, { recursive: true, force: true, maxRetries: 3 }); }
}
main().catch((error) => { console.error(error); process.exitCode = 1; });

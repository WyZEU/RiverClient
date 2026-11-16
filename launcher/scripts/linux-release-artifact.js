"use strict";

const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { execFileSync } = require("node:child_process");
const { assert, assertCommit, regularFile, json, digestFile, isHash } = require("./release-artifact");
const { releasePlan } = require("./prepare-windows-release");

const LINUX_ARTIFACT_FILES = ["River-Client-Linux.tar.gz", "LINUX-BUILD-INFO.json"];

async function verifyLinuxArtifact(directory, expectedCommit, expectedVersion, minecraftVersions) {
  assertCommit(expectedCommit);
  assert(directory && fs.existsSync(directory), "A Linux build from the same release run is required.");
  assert(JSON.stringify(fs.readdirSync(directory).sort()) === JSON.stringify([...LINUX_ARTIFACT_FILES].sort()), "Unexpected Linux artifact inventory.");
  const info = json(regularFile(directory, "LINUX-BUILD-INFO.json"));
  assert(info.schemaVersion === 1 && info.platform === "linux" && info.arch === "x64", "Invalid Linux build provenance.");
  assert(info.sourceCommit === expectedCommit && info.version === expectedVersion, "Linux build source/version mismatch.");
  const runtime = info.runtime;
  assert(runtime?.sourceCommit === expectedCommit && runtime.version === expectedVersion &&
    JSON.stringify(runtime.minecraftVersions) === JSON.stringify(minecraftVersions), "Linux Minecraft targets differ from the Windows build.");
  assert(Array.isArray(runtime.files) && runtime.files.length === minecraftVersions.length + 1, "Incomplete Linux runtime inventory.");
  const names = new Set(runtime.files.map((file) => file.name));
  assert(names.size === runtime.files.length && runtime.files.filter((file) => /^river-bootstrap-[0-9.]+\.jar$/.test(file.name)).length === 1,
    "Invalid Linux bootstrap inventory.");
  for (const mc of minecraftVersions) assert(names.has(`clientcore-${expectedVersion}+${mc}.jar`), `Missing Linux Minecraft target: ${mc}`);
  for (const file of runtime.files) assert(!file.name.includes("/") && !file.name.includes("\\") && isHash(file.sha256) && Number.isSafeInteger(file.size) && file.size > 0, "Invalid Linux runtime file.");
  const archive = await digestFile(regularFile(directory, LINUX_ARTIFACT_FILES[0]));
  assert(info.archive?.name === LINUX_ARTIFACT_FILES[0] && archive.size === info.archive.size && archive.sha256 === info.archive.sha256,
    "Linux archive checksum mismatch.");
  return { info, archive };
}

async function stageLinuxRelease(root, expectedCommit) {
  assertCommit(expectedCommit);
  const plan = releasePlan(root);
  const dist = path.join(root, "launcher/dist");
  const archive = regularFile(dist, LINUX_ARTIFACT_FILES[0]);
  const output = path.join(dist, "linux-release");
  assert(!fs.existsSync(output), "Linux release staging already exists.");
  const scratch = fs.mkdtempSync(path.join(os.tmpdir(), "river-linux-check-"));
  try {
    // Inspect the archive produced by package-linux.js in this trusted build checkout.
    execFileSync("tar", ["-xzf", archive, "-C", scratch], { stdio: "pipe" });
    const app = path.join(scratch, "River-Client-Linux");
    const binary = regularFile(app, "river-client-bin");
    const header = Buffer.alloc(20);
    const descriptor = fs.openSync(binary, "r");
    try { fs.readSync(descriptor, header, 0, header.length, 0); } finally { fs.closeSync(descriptor); }
    assert(header.subarray(0, 4).equals(Buffer.from([0x7f, 0x45, 0x4c, 0x46])) && header[4] === 2 && header[5] === 1 && header.readUInt16LE(18) === 62,
      "Linux release must contain an x64 ELF executable.");
    for (const name of ["river-client", "river-client-bin", "chrome_crashpad_handler"]) {
      assert((fs.statSync(regularFile(app, name)).mode & 0o111) !== 0, `Linux executable mode missing: ${name}`);
    }
    const runtime = json(regularFile(app, "resources/bundled/river-build-info.json"));
    assert(runtime.sourceCommit === expectedCommit && runtime.version === plan.version &&
      JSON.stringify(runtime.minecraftVersions) === JSON.stringify(plan.targets), "Linux packaged runtime provenance mismatch.");
    for (const file of runtime.files) {
      const actual = await digestFile(regularFile(app, `resources/bundled/${file.name}`));
      assert(actual.size === file.size && actual.sha256 === file.sha256, `Linux packaged JAR changed: ${file.name}`);
    }
    const asar = await import("@electron/asar");
    const packed = regularFile(app, "resources/app.asar");
    const stamp = JSON.parse(asar.extractFile(packed, "app/config/build-version.json"));
    const pkg = JSON.parse(asar.extractFile(packed, "package.json"));
    assert(stamp.version === plan.version && stamp.sourceCommit === expectedCommit && pkg.version === plan.version,
      "Linux packaged launcher version/source mismatch.");
    const info = { schemaVersion: 1, platform: "linux", arch: "x64", version: plan.version,
      sourceCommit: expectedCommit, runtime, archive: { name: LINUX_ARTIFACT_FILES[0], ...await digestFile(archive) } };
    fs.mkdirSync(output);
    fs.copyFileSync(archive, path.join(output, LINUX_ARTIFACT_FILES[0]));
    fs.writeFileSync(path.join(output, "LINUX-BUILD-INFO.json"), JSON.stringify(info, null, 2) + "\n");
    await verifyLinuxArtifact(output, expectedCommit, plan.version, plan.targets);
    return info;
  } catch (error) {
    fs.rmSync(output, { recursive: true, force: true });
    throw error;
  } finally { fs.rmSync(scratch, { recursive: true, force: true }); }
}

if (require.main === module) {
  const root = path.resolve(__dirname, "../..");
  const commit = execFileSync("git", ["rev-parse", "HEAD"], { cwd: root, encoding: "utf8" }).trim();
  assert(!process.env.GITHUB_SHA || process.env.GITHUB_SHA === commit, "Linux release source commit mismatch.");
  stageLinuxRelease(root, commit).then((info) => console.log(`Verified Linux ${info.version}: ${info.runtime.files.length} runtime JARs, executable modes and packaged source ${commit}.`))
    .catch((error) => { console.error(error.message); process.exitCode = 1; });
}

module.exports = { LINUX_ARTIFACT_FILES, verifyLinuxArtifact, stageLinuxRelease };

const fs = require("node:fs");
const path = require("node:path");
const crypto = require("node:crypto");
const { execFileSync } = require("node:child_process");

function releasePlan(root) {
  const pkg = JSON.parse(fs.readFileSync(path.join(root, "launcher/package.json"), "utf8"));
  const version = pkg.riverVersion;
  if (!/^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/.test(version)) {
    throw new Error("riverVersion must be canonical MAJOR.MINOR.PATCH.");
  }
  const settings = fs.readFileSync(path.join(root, "settings.gradle.kts"), "utf8");
  const declaration = settings.match(/^\s*versions\(([^)]+)\)/m);
  if (!declaration) throw new Error("No active Stonecutter versions declaration found.");
  const targets = [...declaration[1].matchAll(/"([0-9]+(?:\.[0-9]+)+)"/g)].map((m) => m[1]);
  if (!targets.length || new Set(targets).size !== targets.length) {
    throw new Error("Empty or duplicate Minecraft targets.");
  }
  const bootstrap = fs.readFileSync(path.join(root, "river-bootstrap/build.gradle.kts"), "utf8")
    .match(/^version\s*=\s*"([0-9.]+)"/m)?.[1];
  if (!bootstrap) throw new Error("Missing explicit bootstrap version.");
  const files = targets.map((target) => ({
    name: `clientcore-${version}+${target}.jar`,
    source: path.join(root, "versions", target, "build/libs", `clientcore-${version}+${target}.jar`)
  }));
  files.push({ name: `river-bootstrap-${bootstrap}.jar`,
    source: path.join(root, "river-bootstrap/build/libs", `river-bootstrap-${bootstrap}.jar`) });
  return { version, targets, files };
}

function stageRelease(root, sourceCommit) {
  if (!/^[a-f0-9]{40}$/.test(sourceCommit)) throw new Error("A full source commit is required.");
  const plan = releasePlan(root);
  // Validate every expected output before replacing any previous staged runtime.
  const files = plan.files.map((file) => {
    const data = fs.readFileSync(file.source);
    if (data.length < 4 || data.readUInt32LE(0) !== 0x04034b50) {
      throw new Error(`Not a JAR/ZIP archive: ${file.source}`);
    }
    return { name: file.name, size: data.length,
      sha256: crypto.createHash("sha256").update(data).digest("hex") };
  });
  const destination = path.join(root, "launcher/src/bundled");
  fs.mkdirSync(destination, { recursive: true });
  const existing = fs.readdirSync(destination).filter((name) => name.endsWith(".jar"));
  if (existing.some((name) => !/^(clientcore-|river-bootstrap-)/.test(name))) {
    throw new Error("Unexpected third-party JAR in the release bundle.");
  }
  for (const name of existing) fs.unlinkSync(path.join(destination, name));
  for (const file of plan.files) fs.copyFileSync(file.source, path.join(destination, file.name));
  const manifest = { schemaVersion: 1, version: plan.version, sourceCommit,
    minecraftVersions: plan.targets, files };
  fs.writeFileSync(path.join(destination, "river-build-info.json"), JSON.stringify(manifest, null, 2) + "\n");
  return manifest;
}

if (require.main === module) {
  const root = path.resolve(__dirname, "../..");
  if (process.argv.includes("--check")) {
    const plan = releasePlan(root);
    console.log(`Release ${plan.version}: ${plan.targets.length} Minecraft targets plus bootstrap.`);
  } else {
    const head = execFileSync("git", ["rev-parse", "HEAD"], { cwd: root, encoding: "utf8" }).trim();
    if (process.env.GITHUB_SHA && head !== process.env.GITHUB_SHA) throw new Error("Source commit mismatch.");
    const manifest = stageRelease(root, head);
    console.log(`Staged ${manifest.files.length} runtime JARs from ${head}.`);
  }
}

module.exports = { releasePlan, stageRelease };

"use strict";

const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { execFileSync } = require("node:child_process");
const { parseArgs } = require("node:util");
const { assert, regularFile, json, digestFile, runtimeFileBundled } = require("./release-artifact");
const { architectures, verifyMacArtifact } = require("./prepare-macos-publication");

async function verifyMacSignatures(directory, expectedCommit, requestedArchitectures = architectures) {
  assert(process.platform === "darwin", "Verify Apple signatures on macOS.");
  const run = (command, args) => execFileSync(command, args, { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] }).trim();
  const requirement = '=anchor apple generic and certificate leaf[subject.OU] = "A2A543D72R"';
  const results = [];
  for (const arch of requestedArchitectures) {
    const location = path.join(directory, arch);
    const artifact = await verifyMacArtifact(location, expectedCommit, arch);
    const dmg = regularFile(location, artifact.file);
    run("codesign", ["--verify", "--strict", "-R", requirement, dmg]);
    run("hdiutil", ["verify", dmg]);
    run("xcrun", ["stapler", "validate", dmg]);
    run("spctl", ["--assess", "--type", "open", "--context", "context:primary-signature", dmg]);
    const mount = fs.mkdtempSync(path.join(os.tmpdir(), "river-mac-verify-"));
    let attached = false;
    try {
      run("hdiutil", ["attach", dmg, "-readonly", "-nobrowse", "-mountpoint", mount]);
      attached = true;
      const app = path.join(mount, "River Client.app");
      assert(fs.lstatSync(app).isDirectory(), "Missing River application in DMG.");
      run("codesign", ["--verify", "--deep", "--strict", "-R", `${requirement} and identifier "dev.wyz.river.launcher"`, app]);
      run("xcrun", ["stapler", "validate", app]);
      run("spctl", ["--assess", "--type", "execute", app]);
      const plist = (key) => run("/usr/libexec/PlistBuddy", ["-c", `Print :${key}`, path.join(app, "Contents/Info.plist")]);
      assert(plist("CFBundleIdentifier") === "dev.wyz.river.launcher" && plist("CFBundleShortVersionString") === artifact.version, "Mac application identity/version mismatch.");
      const executable = plist("CFBundleExecutable");
      assert(executable && path.basename(executable) === executable, "Invalid Mac executable name.");
      assert(run("lipo", ["-archs", regularFile(app, `Contents/MacOS/${executable}`)]) === (arch === "x64" ? "x86_64" : "arm64"), "Mac binary architecture mismatch.");
      const bundle = path.join(app, "Contents/Resources/bundled");
      const runtime = json(regularFile(bundle, "river-build-info.json"));
      assert(runtime.version === artifact.version && runtime.sourceCommit === expectedCommit && runtime.sourceDirty === false, "Packaged Mac runtime provenance mismatch.");
      assert(Array.isArray(runtime.minecraftVersions) && runtime.minecraftVersions.length > 0
        && Array.isArray(runtime.files) && runtime.files.length === runtime.minecraftVersions.length + 1, "Incomplete packaged Mac runtime inventory.");
      for (const file of runtime.files) {
        // Only the default Minecraft version ships in the app; the rest download on demand.
        if (!runtimeFileBundled(runtime, file.name)) continue;
        const digest = await digestFile(regularFile(bundle, file.name));
        assert(digest.sha256 === file.sha256 && digest.size === file.size, "Packaged Mac JAR checksum mismatch.");
      }
      results.push({ arch, file: artifact.file, sha256: artifact.sha256, teamId: "A2A543D72R", verified: true });
    } finally {
      // Never recursively remove a mount point if detach fails.
      if (attached) run("hdiutil", ["detach", mount]);
      fs.rmdirSync(mount);
    }
  }
  return results;
}

if (require.main === module) {
  const { values } = parseArgs({ options: { artifact: { type: "string" }, commit: { type: "string" }, output: { type: "string" } } });
  (async () => {
    assert(values.artifact && values.output, "Required: --artifact DIR --commit SHA --output JSON");
    const checks = await verifyMacSignatures(path.resolve(values.artifact), values.commit);
    fs.writeFileSync(values.output, JSON.stringify({ sourceCommit: values.commit, checks }, null, 2) + "\n", { flag: "wx" });
    console.log("Both DMGs passed Apple signature, stapling, Gatekeeper and packaged-source checks.");
  })().catch((error) => { console.error(error.message); process.exitCode = 1; });
}

module.exports = { verifyMacSignatures };

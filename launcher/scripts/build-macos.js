const fs = require("node:fs");
const path = require("node:path");
const { execFileSync } = require("node:child_process");
const crypto = require("node:crypto");
const { stageRelease } = require("./prepare-windows-release.js");

const root = path.resolve(__dirname, "..");
const repo = path.dirname(root);
const args = process.argv.slice(2);
if (process.platform !== "darwin") throw new Error("Build the Mac application on macOS.");
if (args.some((arg) => !["--signed", "--notarize", "--arm64", "--x64"].includes(arg))) {
  throw new Error("Usage: build-macos.js [--signed] [--notarize] [--arm64 | --x64]");
}
const notarize = args.includes("--notarize");
const signed = notarize || args.includes("--signed");
if (signed && !process.env.CSC_NAME) throw new Error("Set CSC_NAME to your Developer ID Application identity.");
if (notarize && !process.env.APPLE_KEYCHAIN_PROFILE) throw new Error("Set APPLE_KEYCHAIN_PROFILE to a notarytool Keychain profile.");
const env = { ...process.env, RIVER_RELEASE_BUILD: "0", RIVER_MAC_SIGNED: signed ? "1" : "0", RIVER_MAC_NOTARIZE: notarize ? "1" : "0" };
const run = (command, argv, options = {}) => execFileSync(command, argv, { cwd: root, env, stdio: "inherit", ...options });

// Fail before spending time packaging if Apple credentials/agreements are unavailable.
if (notarize) {
  const keychainArgs = process.env.APPLE_KEYCHAIN ? ["--keychain", process.env.APPLE_KEYCHAIN] : [];
  run("xcrun", ["notarytool", "history", "--keychain-profile", process.env.APPLE_KEYCHAIN_PROFILE, ...keychainArgs, "--output-format", "json"], { stdio: "pipe" });
}

// Build the mod with Gradle first. This shared, platform-independent staging code
// refuses missing targets or release-version mismatches.
const sourceCommit = execFileSync("git", ["rev-parse", "HEAD"], { cwd: repo, encoding: "utf8" }).trim();
const sourceDirty = Boolean(execFileSync("git", ["status", "--porcelain"], { cwd: repo, encoding: "utf8" }).trim());
if (notarize && sourceDirty) throw new Error("Commit the source changes before building a notarized release; use a preview build for a dirty working tree.");
const runtime = { ...stageRelease(repo, sourceCommit), sourceDirty };
fs.writeFileSync(path.join(root, "src/bundled/river-build-info.json"), JSON.stringify(runtime, null, 2) + "\n");
run(process.execPath, [path.join(__dirname, "build-app.js")]);
fs.writeFileSync(path.join(root, "app/config/build-version.json"), JSON.stringify({
  version: runtime.version, channel: "stable", sourceCommit, sourceDirty, builtAt: new Date().toISOString()
}, null, 2) + "\n");

// Convert the existing River artwork into Apple's icon format; no replacement logo.
const iconset = path.join(root, "build/River.iconset");
fs.mkdirSync(iconset, { recursive: true });
for (const size of [16, 32, 128, 256, 512]) {
  for (const scale of [1, 2]) {
    const pixels = String(size * scale);
    run("sips", ["-z", pixels, pixels, path.join(root, "src/assets/riv3r-client.png"), "--out",
      path.join(iconset, `icon_${size}x${size}${scale === 2 ? "@2x" : ""}.png`)], { stdio: "pipe" });
  }
}
run("iconutil", ["--convert", "icns", "--output", path.join(root, "build/icon.icns"), iconset]);
const architectures = args.filter((arg) => ["--arm64", "--x64"].includes(arg));
// An explicit --mac dmg target makes electron-builder default to the host arch,
// even when mac.target lists both. Always pass the requested architectures.
if (!architectures.length) architectures.push("--arm64", "--x64");
run(process.execPath, [require.resolve("electron-builder/cli.js"), "--config", "electron-builder.macos.cjs", "--mac", "dmg", ...architectures, "--publish", "never"]);
const output = path.join(root, "dist/macos");
const builtArchitectures = architectures.length ? architectures.map((arg) => arg.slice(2)) : ["arm64", "x64"];
const artifacts = [];
for (const arch of builtArchitectures) {
  const appPath = path.join(output, arch === "arm64" ? "mac-arm64" : "mac", "River Client.app");
  const dmg = path.join(output, `River-Client-${runtime.version}-macOS-${arch}.dmg`);
  run("codesign", ["--verify", "--deep", "--strict", "--verbose=2", appPath]);
  run("hdiutil", ["verify", dmg], { stdio: "pipe" });
  let submission = null;
  if (notarize) {
    run("xcrun", ["stapler", "validate", appPath]);
    run("spctl", ["--assess", "--type", "execute", "--verbose=2", appPath]);
    // The builder notarizes/staples the application. Notarize the signed disk
    // image as well so Gatekeeper can verify the downloaded container itself.
    console.log(`Notarizing ${path.basename(dmg)}...`);
    const keychainArgs = process.env.APPLE_KEYCHAIN ? ["--keychain", process.env.APPLE_KEYCHAIN] : [];
    const result = run("xcrun", ["notarytool", "submit", dmg, "--keychain-profile", process.env.APPLE_KEYCHAIN_PROFILE,
      ...keychainArgs, "--wait", "--output-format", "json"], { stdio: "pipe", encoding: "utf8" });
    submission = JSON.parse(result);
    fs.writeFileSync(path.join(output, `notarization-${arch}.json`), JSON.stringify(submission, null, 2) + "\n");
    if (submission.status !== "Accepted") throw new Error(`Apple rejected the ${arch} disk image: ${submission.id}`);
    run("xcrun", ["stapler", "staple", dmg]);
    run("xcrun", ["stapler", "validate", dmg]);
    run("spctl", ["--assess", "--type", "open", "--context", "context:primary-signature", "--verbose=2", dmg]);
  }
  if (signed) run("codesign", ["--verify", "--strict", "--verbose=2", dmg]);
  const bytes = fs.readFileSync(dmg);
  artifacts.push({ file: path.basename(dmg), arch, size: bytes.length,
    sha256: crypto.createHash("sha256").update(bytes).digest("hex"), signed, notarized: notarize,
    notarizationId: submission?.id || null });
  fs.writeFileSync(path.join(output, `build-result-${arch}.json`), JSON.stringify({
    version: runtime.version, sourceCommit, sourceDirty, builtAt: new Date().toISOString(),
    artifacts: [artifacts[artifacts.length - 1]]
  }, null, 2) + "\n");
}
fs.writeFileSync(path.join(output, "build-result.json"), JSON.stringify({
  version: runtime.version, sourceCommit, sourceDirty, builtAt: new Date().toISOString(), artifacts
}, null, 2) + "\n");
fs.writeFileSync(path.join(output, "SHA256SUMS"), artifacts.map((a) => `${a.sha256}  ${a.file}`).join("\n") + "\n");
console.log(notarize ? "Mac applications signed, notarized and packaged." : signed
  ? "Signed Mac preview built. It is not notarized; use --notarize for distribution."
  : "Local Mac preview built with an ad-hoc signature. Use --notarize for distribution.");

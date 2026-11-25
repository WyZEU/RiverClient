const path = require("node:path");
const os = require("node:os");

function normalizedArch(arch) {
  if (["aarch64", "arm64"].includes(arch)) return "arm64";
  if (["amd64", "x86_64", "x64"].includes(arch)) return "x64";
  if (["x86", "i386", "i686", "ia32"].includes(arch)) return "ia32";
  return arch;
}

function nativeClassifierKey(platform = process.platform, arch = process.arch) {
  const suffix = normalizedArch(arch) === "arm64" ? "-arm64"
    : normalizedArch(arch) === "ia32" && platform === "win32" ? "-x86" : "";
  return `natives-${platform === "win32" ? "windows" : platform === "darwin" ? "macos" : "linux"}${suffix}`;
}

function matchesOsRule(rule, platform = process.platform, arch = process.arch, version = os.release()) {
  const current = platform === "win32" ? "windows" : platform === "darwin" ? "osx" : "linux";
  if (rule?.name && rule.name !== current) return false;
  if (rule?.arch && normalizedArch(rule.arch) !== normalizedArch(arch)) return false;
  if (rule?.version) {
    try { if (!new RegExp(rule.version).test(version)) return false; }
    catch { return false; }
  }
  return true;
}

// Mojang lists both Mac architectures with the same osx rule. Only the native
// JAR matching the JVM belongs on the classpath; ordinary Java JARs stay shared.
function isMacNativeLibraryAllowed(library, platform = process.platform, arch = process.arch) {
  if (platform !== "darwin") return true;
  const classifier = String(library?.name || "").split(":")[3] || "";
  if (!/^natives-(macos|osx)(?:-|$)/.test(classifier)) return true;
  const nativeArch = /-(arm64|aarch64)$/.test(classifier) ? "arm64" : "x64";
  return nativeArch === normalizedArch(arch);
}

function macJavaCandidates(home) {
  return [
    "/Library/Java/JavaVirtualMachines",
    path.join(home, "Library/Java/JavaVirtualMachines")
  ];
}

function javaArchitecture(output) {
  const value = String(output).match(/^\s*os\.arch\s*=\s*(\S+)\s*$/m)?.[1];
  return value ? normalizedArch(value) : "";
}

function javaArchitectureMatches(output, arch = process.arch) {
  const detected = javaArchitecture(output);
  return Boolean(detected && detected === normalizedArch(arch));
}

function manualUpdateTarget(update, fallback, platform = process.platform) {
  if (platform === "win32") return update?.portableUrl || update?.installerUrl || fallback;
  if (platform === "darwin") return update?.macUrl || fallback;
  return update?.linuxUrl || fallback;
}

module.exports = {
  nativeClassifierKey, matchesOsRule, isMacNativeLibraryAllowed,
  macJavaCandidates, javaArchitecture, javaArchitectureMatches, manualUpdateTarget
};

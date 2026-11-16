const path = require("node:path");
const { execFileSync } = require("node:child_process");

exports.default = async function signWindows(configuration) {
  if (process.platform !== "win32" || process.env.RIVER_RELEASE_BUILD !== "1") {
    throw new Error("Release signing requires Windows and RIVER_RELEASE_BUILD=1.");
  }
  if (configuration.hash !== "sha256") throw new Error("Only SHA-256 signing is supported.");
  execFileSync("pwsh.exe", [
    "-NoLogo", "-NoProfile", "-NonInteractive", "-File",
    path.join(__dirname, "sign-windows.ps1"), "-FilePath", configuration.path
  ], { stdio: "inherit", windowsHide: true });
};

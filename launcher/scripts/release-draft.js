const fs = require("node:fs");
const path = require("node:path");

const launcherRoot = path.resolve(__dirname, "..");
const packageFile = path.join(launcherRoot, "package.json");
const draftFile = path.join(launcherRoot, "release-draft.json");
const packageJson = JSON.parse(fs.readFileSync(packageFile, "utf8"));
const requestedVersion = readArgument("--version") || packageJson.riverVersion || packageJson.version;
const existing = readJson(draftFile);

if (existing && existing.version === requestedVersion && !process.argv.includes("--force")) {
  console.log(`Release draft already exists: ${draftFile}`);
  console.log("Edit it, set approved to true, then run npm run publish:update.");
  process.exit(0);
}

const draft = {
  version: requestedVersion,
  approved: false,
  title: `River Client ${requestedVersion}`,
  summary: "Replace this with the short public release summary.",
  items: [
    "Replace this with the first finished change.",
    "Replace this with the second finished change."
  ],
  discord: {
    announcement: {
      title: `River Client ${requestedVersion} is available`,
      message: "Replace this with the announcement message."
    },
    release: {
      title: `River Client ${requestedVersion}`,
      message: "Replace this with the release notes."
    },
    devLog: {
      title: `Development update ${requestedVersion}`,
      message: "Replace this with the developer log message."
    }
  }
};

fs.writeFileSync(draftFile, `${JSON.stringify(draft, null, 2)}\n`, "utf8");
console.log(`Created editable release draft: ${draftFile}`);
console.log("Edit every placeholder, set approved to true, then run npm run publish:update.");

function readArgument(name) {
  const index = process.argv.indexOf(name);
  return index >= 0 ? String(process.argv[index + 1] || "").trim() : "";
}

function readJson(file) {
  try {
    return JSON.parse(fs.readFileSync(file, "utf8"));
  } catch {
    return null;
  }
}

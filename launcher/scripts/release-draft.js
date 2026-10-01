"use strict";

/**
 * Creates the editable release draft for the next version.
 *
 * This used to write a wall of "Replace this with..." placeholders, which meant
 * every release started by typing the same list out three times. Now it reads
 * the commits since the last release tag and fills the draft in, so the job is
 * editing lines rather than writing them.
 *
 * It is still a draft on purpose. Nothing is posted, `approved` stays false, and
 * you are expected to read it - generated notes are only ever as good as the
 * commit subjects they came from.
 *
 *   npm run release:draft            use the version in package.json
 *   npm run release:draft 0.1.8      or name one
 */

const fs = require("node:fs");
const path = require("node:path");
const { collectNotes, changelogItems, discordMessage } = require("./release-notes.js");

const launcherRoot = path.resolve(__dirname, "..");
const draftFile = path.join(launcherRoot, "release-draft.json");
const packageJson = JSON.parse(fs.readFileSync(path.join(launcherRoot, "package.json"), "utf8"));

const requestedVersion = process.argv[2] || packageJson.riverVersion || packageJson.version;
const force = process.argv.includes("--force");

if (fs.existsSync(draftFile) && !force) {
  const existing = JSON.parse(fs.readFileSync(draftFile, "utf8"));
  console.log(`Release draft already exists for ${existing.version}: ${draftFile}`);
  console.log("Edit it, set approved to true, then run npm run publish:update.");
  console.log("Pass --force to regenerate it from git and lose your edits.");
  process.exit(0);
}

// The previous release is whatever changelog.json says shipped last, which is
// what the version tags are named after.
let previousVersion = "";
try {
  const changelog = JSON.parse(fs.readFileSync(path.join(launcherRoot, "src", "config", "changelog.json"), "utf8"));
  previousVersion = String(changelog?.releases?.[0]?.version || "");
} catch {}

const notes = collectNotes({ previousVersion });
const items = changelogItems(notes);

if (!notes.exactAnchor) {
  console.log(`! No v${previousVersion} tag found, so this covers everything since ${notes.since}.`);
  console.log("! Check the items before publishing, and tag releases from now on.");
}
if (!items.length) {
  console.log("! No user-facing commits found since the last release.");
  console.log("! Either nothing shipped, or the commits only touched things outside launcher/src and src/.");
}

const draft = {
  version: requestedVersion,
  approved: false,
  title: `River Client ${requestedVersion}`,
  summary: items[0] || "Write the one-line summary here.",
  items: items.length ? items : ["Write the first finished change here."],
  discord: {
    announcement: {
      title: `River Client ${requestedVersion} is available`,
      message: discordMessage(requestedVersion, notes)
    },
    release: {
      title: `River Client ${requestedVersion}`,
      message: discordMessage(requestedVersion, notes)
    }
  }
};

fs.writeFileSync(draftFile, `${JSON.stringify(draft, null, 2)}\n`, "utf8");

console.log(`Release draft for ${requestedVersion}: ${draftFile}`);
console.log(`  from ${notes.since}: ${notes.total} commits -> ${notes.added.length} new, ${notes.fixed.length} fixed, ${notes.changed.length} other`);
console.log("Read it, fix the wording, set approved to true, then npm run publish:update.");

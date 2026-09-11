"use strict";

/**
 * Builds release notes from git history so a release does not mean writing the
 * same list out three times by hand.
 *
 * The rules are deliberately dumb, because anything clever here gets wrong in a
 * way you only notice after it is posted:
 *
 *   - Only commits that touch code people actually run count. A README pass or a
 *     repo cleanup is not a release note.
 *   - The commit subject is the note. Write subjects like a person reading the
 *     changelog, not like a diff summary, and there is nothing else to do.
 *   - `Release: <text>` in the commit body overrides the subject, for when the
 *     real subject is too technical to show anyone.
 *   - `Release: skip` drops the commit entirely.
 *
 * Nothing here posts anything. It produces text, you look at it, you publish.
 */

const { spawnSync } = require("node:child_process");
const path = require("node:path");

const repoRoot = path.resolve(__dirname, "..", "..");

/** Code a user actually runs. Everything else is housekeeping and stays out. */
const USER_FACING_PATHS = [
  "launcher/src",
  "src",
  "clientcore-1.21.4/src",
  "river-bootstrap/src",
  "cloudflare-updates/src"
];

/**
 * Subjects that are real work but say nothing to a player. Version bumps are the
 * obvious one - every release has one and nobody wants to read it.
 */
const NOISE = [
  /^\s*(bump|bumped|release|releasing|publish|published)\b/i,
  /^\s*v?\d+\.\d+(\.\d+)*\s*$/,
  /^\s*(wip|tmp|temp|test|typo|oops|fixup|amend)\b/i,
  /^\s*merge\b/i
];

function git(args, { cwd = repoRoot } = {}) {
  const result = spawnSync("git", args, { cwd, encoding: "utf8", maxBuffer: 32 * 1024 * 1024 });
  if (result.status !== 0) {
    throw new Error(`git ${args.join(" ")} failed: ${String(result.stderr || "").trim()}`);
  }
  return String(result.stdout || "");
}

/**
 * Where the previous release ended. Prefers a `v<version>` tag, because that is
 * exact. Falls back to the newest tag, and then to "everything", which is only
 * right the very first time and is worth saying out loud when it happens.
 */
function previousReleasePoint(previousVersion = "") {
  const tags = git(["tag", "--list", "v*", "--sort=-v:refname"]).split("\n").map((t) => t.trim()).filter(Boolean);
  if (previousVersion) {
    const exact = tags.find((t) => t === `v${previousVersion}`);
    if (exact) return { ref: exact, exact: true };
  }
  if (tags.length) return { ref: tags[0], exact: false };
  return { ref: "", exact: false };
}

/** One commit, already reduced to the line that would be shown. */
function parseCommit(raw) {
  const [hash, subject, body] = raw.split("\u0000");
  if (!hash) return null;

  const trailer = /^Release:[ \t]*(.+)$/im.exec(body || "");
  const override = trailer ? trailer[1].trim() : "";
  if (/^skip$/i.test(override)) return null;

  // "0.1.6.1-0.1.6.3: fix the thing" is a real note wearing a version prefix.
  // Strip the prefix rather than dropping the commit.
  const text = override || String(subject || "").trim().replace(/^v?\d+(\.\d+)+(\s*-\s*v?\d+(\.\d+)+)?\s*:\s*/, "");
  if (!text) return null;
  if (!override && NOISE.some((pattern) => pattern.test(text))) return null;

  return { hash: hash.slice(0, 8), text };
}

/** Fixed / Added / everything else, decided off the first word. */
function bucketFor(text) {
  if (/^(fix|fixed|fixes|resolve[sd]?)\b/i.test(text)) return "fixed";
  if (/^(add|added|adds|introduce[sd]?)\b/i.test(text)) return "added";
  return "changed";
}

/** "Fixed the thing" reads better in a Fixed list as "the thing". */
function stripLeadingVerb(text, bucket) {
  if (bucket === "fixed") return text.replace(/^(fix|fixed|fixes|resolved?|resolves)\b[:\s]*/i, "");
  if (bucket === "added") return text.replace(/^(add|added|adds|introduced?|introduces)\b[:\s]*/i, "");
  return text;
}

function sentence(text) {
  const trimmed = String(text || "").trim().replace(/[.\s]+$/, "");
  if (!trimmed) return "";
  return trimmed.charAt(0).toUpperCase() + trimmed.slice(1);
}

/**
 * Everything user-facing since the previous release, already bucketed.
 * `from` empty means the whole history, which only happens before the first tag.
 */
function collectNotes({ previousVersion = "", to = "HEAD" } = {}) {
  const point = previousReleasePoint(previousVersion);
  const range = point.ref ? `${point.ref}..${to}` : to;

  const raw = git([
    "log",
    range,
    "--no-merges",
    "--format=%H%x00%s%x00%b%x00%x00",
    "--",
    ...USER_FACING_PATHS
  ]);

  const commits = raw
    .split("\u0000\u0000")
    .map((chunk) => chunk.replace(/^\n/, ""))
    .filter((chunk) => chunk.trim())
    .map(parseCommit)
    .filter(Boolean);

  const buckets = { added: [], fixed: [], changed: [] };
  const seen = new Set();
  for (const commit of commits) {
    const bucket = bucketFor(commit.text);
    const line = sentence(stripLeadingVerb(commit.text, bucket));
    const key = line.toLowerCase();
    if (!line || seen.has(key)) continue;
    seen.add(key);
    buckets[bucket].push(line);
  }

  return { ...buckets, since: point.ref || "(start of history)", exactAnchor: point.exact, total: commits.length };
}

/** Flat list for changelog.json, which has one `items` array and no sections. */
function changelogItems(notes) {
  return [...notes.added, ...notes.fixed.map((line) => `Fixed ${lower(line)}`), ...notes.changed];
}

function lower(text) {
  const t = String(text || "");
  return t.charAt(0).toLowerCase() + t.slice(1);
}

/**
 * The Discord post. Deliberately not Lunar's layout - one heading, the headline
 * change gets prose, and there is no "& more!" padding. If a section is empty it
 * does not get a header.
 */
function discordMessage(version, notes, { date = new Date(), lead = "" } = {}) {
  const stamp = date.toLocaleDateString("en-GB", { day: "2-digit", month: "short", year: "numeric" });
  const out = [`# River ${version}`, `\`${stamp}\``, ""];

  if (lead) out.push(lead.trim(), "");

  const section = (heading, lines) => {
    if (!lines.length) return;
    out.push(`**${heading}**`);
    for (const line of lines) out.push(`- ${line}`);
    out.push("");
  };

  // `lead` is the hand-written headline paragraph; when it is used the Added
  // bullets underneath would repeat it, so they are shown only without a lead.
  if (!lead) section("New", notes.added);
  section("Fixed", notes.fixed);
  section("Smaller stuff", notes.changed);

  out.push("Source: <https://github.com/WyZEU/RiverClient> · Problems → 🎟│tickets");
  return out.join("\n");
}

module.exports = { collectNotes, changelogItems, discordMessage, previousReleasePoint };

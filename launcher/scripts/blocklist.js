/*
  Blocklist: accounts River refuses to serve (sign-in, friends, tester builds, the in-game
  roster). Used for hacked accounts and anyone removed from River.

    npm run blocklist                                        list everyone blocked
    npm run blocklist -- add <name> <uuid> [reason...]       block an account
    npm run blocklist -- remove <name or uuid>               let it back in

  The list is config/blocklist.json in the updates bucket, which the worker reads (see
  cloudflare-updates/src/blocklist.js). Changes apply within a minute, nothing has to be
  republished. Block by UUID: a name can be changed or handed to someone else.
*/
const { spawnSync } = require("node:child_process");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");

const bucket = "river-client-updates";
const key = "config/blocklist.json";
const launcherRoot = path.resolve(__dirname, "..");
const workerRoot = path.resolve(launcherRoot, "..", "cloudflare-updates");

function wrangler(args) {
  const bin = process.platform === "win32" ? "wrangler.cmd" : "wrangler";
  const local = [path.join(launcherRoot, "node_modules", ".bin", bin), path.join(workerRoot, "node_modules", ".bin", bin)]
    .find((candidate) => fs.existsSync(candidate));
  const [command, prefix] = local ? [local, []] : ["npx", ["wrangler"]];
  const result = spawnSync(command, [...prefix, ...args], {
    cwd: workerRoot,
    shell: process.platform === "win32",
    encoding: "utf8"
  });
  return { ok: result.status === 0, output: `${result.stdout || ""}${result.stderr || ""}` };
}

function normalizeUuid(value) {
  const hex = String(value || "").trim().replace(/-/g, "").toLowerCase();
  if (!/^[0-9a-f]{32}$/.test(hex)) return "";
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

function load() {
  const file = path.join(os.tmpdir(), `river-blocklist-${process.pid}.json`);
  const result = wrangler(["r2", "object", "get", `${bucket}/${key}`, "--file", file, "--remote"]);
  try {
    if (!result.ok) {
      // Nothing uploaded yet is the normal first run, not an error.
      if (/not found|does not exist|NoSuchKey|404/i.test(result.output)) return { blocked: [] };
      throw new Error(`Could not read the blocklist:\n${result.output.trim()}`);
    }
    const payload = JSON.parse(fs.readFileSync(file, "utf8"));
    return { blocked: Array.isArray(payload?.blocked) ? payload.blocked : [] };
  } finally {
    fs.rmSync(file, { force: true });
  }
}

function save(list) {
  const file = path.join(os.tmpdir(), `river-blocklist-${process.pid}.json`);
  fs.writeFileSync(file, `${JSON.stringify(list, null, 2)}\n`);
  try {
    const result = wrangler(["r2", "object", "put", `${bucket}/${key}`, "--file", file, "--content-type", "application/json", "--remote"]);
    if (!result.ok) throw new Error(`Could not save the blocklist:\n${result.output.trim()}`);
  } finally {
    fs.rmSync(file, { force: true });
  }
}

function print(list) {
  if (!list.blocked.length) {
    console.log("No one is blocked.");
    return;
  }
  for (const entry of list.blocked) {
    const reason = entry.reason ? `  ${entry.reason}` : "";
    console.log(`${String(entry.name || "?").padEnd(18)} ${entry.uuid}  added ${String(entry.addedAt || "").slice(0, 10)}${reason}`);
  }
}

function main() {
  const [command = "list", ...rest] = process.argv.slice(2);
  const list = load();

  if (command === "list") return print(list);

  if (command === "add") {
    const [name, rawUuid, ...reason] = rest;
    const uuid = normalizeUuid(rawUuid);
    if (!name || !uuid) throw new Error("Usage: npm run blocklist -- add <name> <uuid> [reason...]");
    if (list.blocked.some((entry) => normalizeUuid(entry.uuid) === uuid)) {
      console.log(`${name} is already blocked.`);
      return print(list);
    }
    list.blocked.push({ name, uuid, reason: reason.join(" ").trim(), addedAt: new Date().toISOString() });
    save(list);
    console.log(`Blocked ${name}. It applies within a minute: their session ends and they cannot sign in again.`);
    return print(list);
  }

  if (command === "remove") {
    const who = String(rest[0] || "").trim();
    if (!who) throw new Error("Usage: npm run blocklist -- remove <name or uuid>");
    const uuid = normalizeUuid(who);
    const kept = list.blocked.filter((entry) => uuid
      ? normalizeUuid(entry.uuid) !== uuid
      : String(entry.name || "").toLowerCase() !== who.toLowerCase());
    if (kept.length === list.blocked.length) throw new Error(`${who} is not on the blocklist.`);
    save({ blocked: kept });
    console.log(`Unblocked ${who}.`);
    return print({ blocked: kept });
  }

  throw new Error("Commands: list, add <name> <uuid> [reason...], remove <name or uuid>");
}

try {
  main();
} catch (error) {
  console.error(error.message || error);
  process.exit(1);
}

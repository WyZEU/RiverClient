/*
  Tester list: who gets the beta channel's builds.

    npm run testers                           list everyone on the R2 list
    npm run testers -- add <name> <uuid>      add someone
    npm run testers -- remove <name or uuid>  take someone off

  The list is config/testers.json in the updates bucket, which the worker reads on top of
  the older RIVER_TESTER_UUIDS secret. Changes apply on their next update check, nothing
  has to be republished. People who are only in the secret are not shown here: Cloudflare
  never lets a secret be read back.
*/
const { spawnSync } = require("node:child_process");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");

const bucket = "river-client-updates";
const key = "config/testers.json";
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
  const file = path.join(os.tmpdir(), `river-testers-${process.pid}.json`);
  const result = wrangler(["r2", "object", "get", `${bucket}/${key}`, "--file", file, "--remote"]);
  try {
    if (!result.ok) {
      // Nothing uploaded yet is the normal first run, not an error.
      if (/not found|does not exist|NoSuchKey|404/i.test(result.output)) return { testers: [] };
      throw new Error(`Could not read the tester list:\n${result.output.trim()}`);
    }
    const payload = JSON.parse(fs.readFileSync(file, "utf8"));
    return { testers: Array.isArray(payload?.testers) ? payload.testers : [] };
  } finally {
    fs.rmSync(file, { force: true });
  }
}

function save(list) {
  const file = path.join(os.tmpdir(), `river-testers-${process.pid}.json`);
  fs.writeFileSync(file, `${JSON.stringify(list, null, 2)}\n`);
  try {
    const result = wrangler(["r2", "object", "put", `${bucket}/${key}`, "--file", file, "--content-type", "application/json", "--remote"]);
    if (!result.ok) throw new Error(`Could not save the tester list:\n${result.output.trim()}`);
  } finally {
    fs.rmSync(file, { force: true });
  }
}

function print(list) {
  if (!list.testers.length) {
    console.log("No one on the R2 tester list yet (people only in the old secret are not listed).");
    return;
  }
  for (const tester of list.testers) {
    console.log(`${String(tester.name || "?").padEnd(18)} ${tester.uuid}  added ${String(tester.addedAt || "").slice(0, 10)}`);
  }
}

function main() {
  const [command = "list", ...rest] = process.argv.slice(2);
  const list = load();

  if (command === "list") return print(list);

  if (command === "add") {
    const [name, rawUuid] = rest;
    const uuid = normalizeUuid(rawUuid);
    if (!name || !uuid) throw new Error("Usage: npm run testers -- add <name> <uuid>");
    if (list.testers.some((tester) => normalizeUuid(tester.uuid) === uuid)) {
      console.log(`${name} is already a tester.`);
      return print(list);
    }
    list.testers.push({ name, uuid, addedAt: new Date().toISOString() });
    save(list);
    console.log(`Added ${name}. They also need Settings > Update channel > Beta in the launcher.`);
    return print(list);
  }

  if (command === "remove") {
    const who = String(rest[0] || "").trim();
    if (!who) throw new Error("Usage: npm run testers -- remove <name or uuid>");
    const uuid = normalizeUuid(who);
    const kept = list.testers.filter((tester) => uuid
      ? normalizeUuid(tester.uuid) !== uuid
      : String(tester.name || "").toLowerCase() !== who.toLowerCase());
    if (kept.length === list.testers.length) throw new Error(`${who} is not on the R2 tester list.`);
    save({ testers: kept });
    console.log(`Removed ${who}.`);
    return print({ testers: kept });
  }

  throw new Error("Commands: list, add <name> <uuid>, remove <name or uuid>");
}

try {
  main();
} catch (error) {
  console.error(error.message || error);
  process.exit(1);
}

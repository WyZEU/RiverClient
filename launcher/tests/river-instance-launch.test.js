const { spawn } = require("node:child_process");
const { EventEmitter, once } = require("node:events");
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const vm = require("node:vm");

const source = fs.readFileSync(path.join(__dirname, "../src/main.js"), "utf8");
function extract(name) {
  const declaration = new RegExp(`^(?:async )?function ${name}\\(`, "m");
  const start = source.search(declaration);
  assert.ok(start >= 0, name);
  return source.slice(start, source.indexOf("\n}", start) + 2);
}

function riverLaunch(t, { installed = false, staged = false } = {}) {
  const instancePath = fs.mkdtempSync(path.join(os.tmpdir(), "river-launch-test-"));
  t.after(() => fs.rmSync(instancePath, { recursive: true, force: true }));
  const jar = path.join(instancePath, "clientcore-0.3.2+26.3.jar");
  const bootstrap = path.join(instancePath, "river-bootstrap.jar");
  if (installed || staged) fs.writeFileSync(jar, "River");
  if (staged) fs.writeFileSync(bootstrap, "Bootstrap");
  const preparingMinecraft = new Error("Minecraft preparation reached");
  const context = vm.createContext({
    fs, path,
    app: { getPath: () => instancePath },
    DEFAULT_MC_VERSION: "1.21.11",
    fabricLoaderVersion: "0.19.2",
    emit: () => {},
    emitActivity: () => {},
    setLaunchStep: () => {},
    prefetchRiverModJar: async (version) => { assert.equal(version, "26.3"); return false; },
    ensureBundledClientCoreMod: () => installed ? jar : path.join(instancePath, "mods", path.basename(jar)),
    stageRiverRuntimeForInstance: () => staged ? { bootstrapJar: bootstrap, clientJar: jar } : {},
    fetchMinecraftVersionJson: async () => { throw preparingMinecraft; }
  });
  vm.runInContext([
    "isRiverLoader", "instanceRunsRiver", "isRealRuntimeJar", "prepareStandaloneMinecraftLaunch"
  ].map(extract).join("\n"), context);
  const status = { instancePath, settings: { selectedVersion: "26.3" }, selectedInstance: { loader: "fabric", river: true } };
  return { launch: () => context.prepareStandaloneMinecraftLaunch(status, null, false), preparingMinecraft };
}

test("a clean River instance stops before launching when its mod is unavailable", async (t) => {
  const { launch } = riverLaunch(t);
  const result = await launch();
  assert.equal(result.ok, false);
  assert.match(result.message, /River Client for Minecraft 26\.3/);
});

test("launch still proceeds when a matching installed mod is already available", async (t) => {
  const { launch, preparingMinecraft } = riverLaunch(t, { installed: true });
  await assert.rejects(launch, (error) => error === preparingMinecraft);
});

test("launch still proceeds when a matching client and its bootstrap can be staged", async (t) => {
  const { launch, preparingMinecraft } = riverLaunch(t, { staged: true });
  await assert.rejects(launch, (error) => error === preparingMinecraft);
});

function monitorGameProcess(child, state = "launching") {
  const activity = [];
  const context = vm.createContext({
    launchProcess: child,
    launchState: state,
    lastLaunchFailure: null,
    emit() {},
    promoteLaunchStateFromOutput() {},
    emitActivity: (entry) => activity.push(entry),
    setLaunchState: (next) => { context.launchState = next; },
    recordSessionEnd() {},
    getCrashInfo: () => ({ summary: "Crash report." }),
    nowPlaying: { stop() {} },
    emitStatus() {}
  });
  vm.runInContext(extract("attachLaunchProcess"), context);
  context.attachLaunchProcess({ instancePath: "" });
  return { context, activity };
}

test("a process that returns zero before Minecraft starts is shown as a launch failure", async () => {
  const child = spawn(process.execPath, ["-e", "process.exit(0)"]);
  const { context, activity } = monitorGameProcess(child);
  assert.deepEqual(await once(child, "exit"), [0, null]);
  assert.match(context.lastLaunchFailure?.message || "", /closed before its window opened/i);
  assert.equal(activity.at(-1)?.error, true);
  assert.equal(context.launchState, "idle");
});

test("a normal close and a requested stop do not show a startup error", () => {
  const running = new EventEmitter();
  const normal = monitorGameProcess(running, "running");
  running.emit("exit", 0);
  assert.equal(normal.context.lastLaunchFailure, null);
  assert.equal(normal.context.launchState, "idle");

  const stopped = new EventEmitter();
  const cancelled = monitorGameProcess(stopped);
  cancelled.context.launchProcess = null;
  stopped.emit("exit", 0);
  assert.equal(cancelled.context.lastLaunchFailure, null);
  assert.equal(cancelled.context.launchState, "idle");
});

test("a nonzero exit still points to the crash report", () => {
  const child = new EventEmitter();
  const { context, activity } = monitorGameProcess(child);
  child.emit("exit", 1);
  assert.match(context.lastLaunchFailure?.message || "", /code 1\. Crash report\./);
  assert.equal(activity.at(-1)?.error, true);
});

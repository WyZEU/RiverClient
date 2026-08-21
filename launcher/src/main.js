const { analyzeCrash } = require("./crash-analyzer.js");
const nowPlaying = require("./now-playing.js");
const curseforge = require("./curseforge.js");
// CurseForge key: the build embeds River's own key (from secrets.env) so users never
// enter one; a user-set key still overrides it.
const EMBEDDED_CF_KEY = process.env.RIVER_CF_KEY || "";
function effectiveCurseForgeKey(settings) { return (settings && settings.curseForgeApiKey) || EMBEDDED_CF_KEY; }
const { app, BrowserWindow, ipcMain, shell, dialog, screen, nativeImage, globalShortcut, clipboard } = require("electron");
const { spawn } = require("node:child_process");
const crypto = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");
const { pathToFileURL } = require("node:url");
const zlib = require("node:zlib");
const os = require("node:os");
const semver = require("semver");

// Last-resort guard: a stray uncaught error or rejected promise in the main process
// otherwise pops Electron's fatal "A JavaScript error occurred" dialog and quits the
// whole launcher. Log it and keep running instead - individual features already handle
// their own failures, so reaching here means something unexpected, not fatal.
function logMainError(kind, error) {
  const line = `[${new Date().toISOString()}] ${kind}: ${(error && error.stack) || error}\n`;
  try { process.stderr.write(line); } catch {}
  try { fs.appendFileSync(path.join(app.getPath("userData"), "main-errors.log"), line); } catch {}
}
process.on("uncaughtException", (error) => logMainError("uncaughtException", error));
process.on("unhandledRejection", (reason) => logMainError("unhandledRejection", reason));
let DiscordRPC = null;
try {
  DiscordRPC = require("discord-rpc");
} catch {}

let mainWindow = null;
let logWindow = null;
let launchProcess = null;
let launchState = "idle";
let launchStateTimer = null;
let lastLaunchFailure = null;
let setupRunning = false;
let currentSessionStart = null;
let currentSessionInstance = null;
const iconBackfillRunning = new Set();
let appIsQuitting = false;
let lastCpuSample = null;
let processingRvrImport = false;
let updaterRunPromise = null;
const pendingRvrImports = [];
const updaterJobPath = findUpdaterJobArgument(process.argv);
const updateFailureLogPath = findNamedArgument(process.argv, "--river-update-failed");
const isUpdaterDemo = process.argv.includes("--river-updater-demo");
const isUpdaterMode = Boolean(updaterJobPath) || process.argv.includes("--river-updater") || isUpdaterDemo;
const startupRvrPath = findRvrArgument(process.argv);
const singleInstanceLock = isUpdaterMode ? true : app.requestSingleInstanceLock();

if (!singleInstanceLock) {
  app.quit();
} else if (!isUpdaterMode) {
  app.on("second-instance", (_event, argv) => {
    const rvrPath = findRvrArgument(argv);
    focusLauncherWindow();
    if (rvrPath) queueRvrImport(rvrPath);
  });

  app.on("open-file", (event, filePath) => {
    event.preventDefault();
    if (isRvrPath(filePath)) queueRvrImport(filePath);
  });
}

function isRvrPath(value) {
  return typeof value === "string" && value.toLowerCase().endsWith(".rvr");
}

function findRvrArgument(argv = []) {
  return argv.find((arg) => {
    if (!isRvrPath(arg)) return false;
    try {
      return fs.existsSync(arg);
    } catch {
      return false;
    }
  }) || "";
}

function findUpdaterJobArgument(argv = []) {
  const prefixed = argv.find((arg) => /^--river-updater-job=/.test(String(arg || "")));
  if (prefixed) return prefixed.split("=").slice(1).join("=").trim();
  const flagIndex = argv.findIndex((arg) => String(arg || "").trim() === "--river-updater-job");
  if (flagIndex >= 0 && argv[flagIndex + 1]) return String(argv[flagIndex + 1]).trim();
  return "";
}

function findNamedArgument(argv = [], name = "") {
  const prefixed = argv.find((arg) => String(arg || "").startsWith(`${name}=`));
  if (prefixed) return String(prefixed).split("=").slice(1).join("=").trim();
  const index = argv.findIndex((arg) => String(arg || "").trim() === name);
  return index >= 0 && argv[index + 1] ? String(argv[index + 1]).trim() : "";
}

function focusLauncherWindow() {
  if (!mainWindow || mainWindow.isDestroyed()) return;
  if (mainWindow.isMinimized()) mainWindow.restore();
  mainWindow.show();
  mainWindow.focus();
}

function createUpdaterWindow() {
  mainWindow = new BrowserWindow({
    width: 520,
    height: 260,
    // A small updater has no business being resized or maximized, so lock it to a
    // fixed size and drop the maximize affordance entirely.
    resizable: false,
    maximizable: false,
    fullscreenable: false,
    backgroundColor: "#0e1218",
    title: "River Client Updater",
    icon: appIcon,
    titleBarStyle: "hidden",
    webPreferences: {
      contextIsolation: false,
      nodeIntegration: true,
      sandbox: false
    }
  });

  mainWindow.loadFile(path.join(__dirname, "renderer", "updater.html"));
}

function updaterJobFilePath() {
  return path.join(app.getPath("userData"), "updates", "updater-job.json");
}

function writeUpdaterJob(update) {
  const jobPath = updaterJobFilePath();
  const exePath = app.getPath("exe");
  fs.mkdirSync(path.dirname(jobPath), { recursive: true });
  fs.writeFileSync(jobPath, JSON.stringify({
    update,
    exePath,
    installDir: path.dirname(exePath),
    createdAt: new Date().toISOString()
  }, null, 2));
  return jobPath;
}

function readUpdaterJob(jobPath) {
  const resolved = path.resolve(jobPath || updaterJobFilePath());
  if (!fs.existsSync(resolved)) throw new Error("Updater job file was not found.");
  return JSON.parse(fs.readFileSync(resolved, "utf8"));
}

/**
 * True when running from the portable build. electron-builder sets this to the real
 * .exe the user double-clicked; the app itself runs from a throwaway temp extraction.
 */
function portableExecutableFile() {
  return String(process.env.PORTABLE_EXECUTABLE_FILE || "").trim();
}

function launchDedicatedUpdaterProcess(update) {
  // The portable build cannot self-update. It runs from a temp extraction that holds
  // only the self-extracting exe and app.asar - no icudtl.dat, no .pak, no DLLs - so
  // relaunching that exe for the updater gives Chromium no ICU data ("Invalid file
  // descriptor to ICU data received") and the updater dies as a grey window. Even if it
  // launched, it would only patch a temp folder that is deleted on exit. Send the user
  // to the new portable download instead.
  if (portableExecutableFile()) {
    const target = update?.portableUrl || update?.installerUrl || RIVER_WEBSITE_URL;
    shell.openExternal(target).catch(() => {});
    const message = `River Client ${update?.latestVersion || ""} is ready. The portable build can't update itself, so the download has been opened in your browser - replace your current River Client.exe with it.`.replace(/\s+/g, " ").trim();
    emit("launcher:log", `[update] Portable build detected; opened ${target} instead of self-updating.`);
    emitActivity({ title: "Download the new portable", detail: message, current: 1, total: 1, done: true });
    return { ok: false, message, portable: true };
  }

  const jobPath = writeUpdaterJob(update);
  const exePath = app.getPath("exe");
  const installDir = path.dirname(exePath);
  const args = [`--river-updater-job=${jobPath}`];
  const child = spawn(exePath, args, {
    cwd: installDir,
    detached: true,
    stdio: "ignore",
    windowsHide: false
  });
  child.unref();
  return { ok: true, jobPath };
}

function queueRvrImport(filePath) {
  if (!isRvrPath(filePath)) return;
  const resolvedPath = path.resolve(filePath);
  if (!pendingRvrImports.includes(resolvedPath)) pendingRvrImports.push(resolvedPath);
  flushPendingRvrImports().catch((error) => {
    emitActivity({
      title: "Import failed",
      detail: error.message || "Could not import River instance.",
      done: true,
      error: true
    });
  });
}

async function flushPendingRvrImports() {
  if (processingRvrImport || !mainWindow || mainWindow.isDestroyed()) return;
  processingRvrImport = true;
  try {
    while (pendingRvrImports.length) {
      const filePath = pendingRvrImports.shift();
      const result = await importRvrArchiveFromFile(filePath);
      emitActivity({
        title: result.ok ? "Instance imported" : "Import failed",
        detail: result.message,
        current: 1,
        total: 1,
        done: true,
        error: !result.ok
      });
      focusLauncherWindow();
    }
  } finally {
    processingRvrImport = false;
  }
}

function readLauncherPackageVersion() {
  const candidates = [
    path.join(__dirname, "..", "package.json"),
    path.join(process.resourcesPath || "", "app.asar", "package.json")
  ];
  for (const pkgPath of candidates) {
    try {
      if (!pkgPath || !fs.existsSync(pkgPath)) continue;
      const pkg = JSON.parse(fs.readFileSync(pkgPath, "utf8"));
      const v = String(pkg.riverVersion || pkg.version || "").trim();
      if (v) return v;
    } catch {}
  }
  return "1.9.4";
}

const clientModVersion = readLauncherPackageVersion();
try {
  app.setVersion(clientModVersion);
} catch {}
const clientModJarName = `clientcore-${clientModVersion}.jar`;
const launcherUserAgent = `RiverClientLauncher/${clientModVersion} (WyZ_EU)`;
const AUTH_SESSION_MS = 30 * 24 * 60 * 60 * 1000;
const AUTH_REFRESH_SKEW_MS = 5 * 60 * 1000;
const RIVER_WEBSITE_URL = "https://riverclient.xyz";
const RIVER_DISCORD_URL = "https://discord.gg/neQzwBTvp3";
const DEFAULT_DISCORD_RPC_CLIENT_ID = "1501146934486892554";
let discordRpcClient = null;
let discordRpcReady = false;
let discordRpcConnecting = false;
let discordRpcRefreshTimer = null;
let discordRpcReconnectTimer = null;
let discordRpcState = {
  supported: Boolean(DiscordRPC),
  enabled: false,
  connected: false,
  clientId: "",
  message: DiscordRPC ? "Discord Rich Presence is idle." : "discord-rpc is not installed.",
  lastUpdatedAt: 0
};

/** Minecraft versions River can target. First entry is the default/primary. */
const SUPPORTED_MC_VERSIONS = ["1.21.11", "1.21.4"];
const DEFAULT_MC_VERSION = SUPPORTED_MC_VERSIONS[0];

/**
 * The Minecraft version a clientcore jar is built for, read from a Fabric-style `+<mc>`
 * suffix in the file name (e.g. `clientcore-0.1.5.2+1.21.4.jar`). Legacy unsuffixed jars
 * (`clientcore-0.1.5.2.jar`) predate multi-version support and are the default 1.21.11 build.
 */
function clientcoreJarMcVersion(fileName) {
  const m = String(fileName || "").match(/clientcore-[\w.]*?\+(\d+\.\d+(?:\.\d+)?)\.jar(?:\.disabled)?$/i);
  return m ? m[1] : DEFAULT_MC_VERSION;
}

function clientcoreJarMatchesMc(fileName, mcVersion) {
  return clientcoreJarMcVersion(fileName) === String(mcVersion || DEFAULT_MC_VERSION);
}

/** The clientcore jar file name this launcher build ships for a given Minecraft version. */
function clientcoreJarNameFor(mcVersion) {
  const v = String(mcVersion || DEFAULT_MC_VERSION);
  return v === DEFAULT_MC_VERSION ? clientModJarName : `clientcore-${clientModVersion}+${v}.jar`;
}

/** The Minecraft version of the instance at `instancePath` (falls back to selected/default). */
function instanceMcVersion(instancePath) {
  const p = String(instancePath || "");
  if (p) {
    try {
      const match = readInstances().find((i) => String(i.path || "") === p);
      if (match && match.version) return String(match.version);
    } catch {}
  }
  try {
    const sel = readSettings().selectedVersion;
    if (sel) return String(sel);
  } catch {}
  return DEFAULT_MC_VERSION;
}

/** Prefer exact versioned jar; else any clientcore-*.jar built for `mcVersion` (name may differ from launcher semver until next restart). */
function findCoreClientJarInMods(instancePath, mcVersion = instanceMcVersion(instancePath)) {
  const modsDir = path.join(instancePath, "mods");
  const preferred = path.join(modsDir, clientcoreJarNameFor(mcVersion));
  if (fs.existsSync(preferred)) return preferred;
  try {
    if (!fs.existsSync(modsDir)) return preferred;
    for (const file of fs.readdirSync(modsDir)) {
      const n = String(file || "");
      if (/^clientcore-[\w.+-]+\.jar$/i.test(n) && clientcoreJarMatchesMc(n, mcVersion)) return path.join(modsDir, file);
    }
  } catch {}
  return preferred;
}

const CLIENTCORE_MOD_PATTERN = /^clientcore-[\w.+-]*\.jar(\.disabled)?$/i;

/** Patterns for the removed River in-game Fabric mod artifacts. */
const RIVER_INGAME_JAR_PATTERNS = [
  /^river-bootstrap-[\w.+-]*\.jar(\.disabled)?$/i,
  /^river-ingame-ui-[\w.+-]*\.jar(\.disabled)?$/i
];

/** Delete any leftover River in-game mod jars so Minecraft never loads River as a Fabric mod. */
function removeRiverInGameJars(modsDir) {
  try {
    if (!modsDir || !fs.existsSync(modsDir)) return;
    for (const file of fs.readdirSync(modsDir)) {
      if (RIVER_INGAME_JAR_PATTERNS.some((re) => re.test(String(file || "")))) {
        try {
          fs.rmSync(path.join(modsDir, file), { force: true });
          emit("launcher:log", `[launcher] Removed leftover River in-game mod from instance: ${file}`);
        } catch {}
      }
    }
  } catch {}
}

/**
 * Newest production jar in `dir` whose name matches `re` (excludes -sources/-dev), or "" if none.
 * An optional `extraFilter(fileName)` predicate narrows further (e.g. Minecraft-version match).
 */
function newestJarMatching(dir, re, extraFilter = null) {
  try {
    if (!dir || !fs.existsSync(dir)) return "";
    return fs.readdirSync(dir)
      .filter((f) => re.test(String(f || "")) && !/-(sources|dev)\.jar$/i.test(String(f || "")) && (!extraFilter || extraFilter(String(f || ""))))
      .map((f) => {
        const full = path.join(dir, f);
        return { full, mtimeMs: fs.statSync(full).mtimeMs };
      })
      .sort((a, b) => b.mtimeMs - a.mtimeMs)[0]?.full || "";
  } catch {
    return "";
  }
}

/**
 * Resolve the River in-game runtime jars used for agent-based injection (NOT installed as Fabric
 * mods). Looks in the Gradle build output first (workspace builds) then any bundled location.
 * Returns "" for any jar that is not present.
 */
function resolveRiverRuntimeJars(mcVersion = DEFAULT_MC_VERSION) {
  const root = findClientRoot();
  const bundledDirs = [
    process.resourcesPath ? path.join(process.resourcesPath, "bundled") : "",
    path.join(__dirname, "bundled"),
    path.join(app.getAppPath(), "bundled")
    // Jars inside app.asar are virtual paths the JVM cannot open as a -javaagent
    // or add to the classloader, so never resolve to one.
  ].filter(Boolean).filter((dir) => !dir.includes(`app.asar${path.sep}`) && !dir.includes("app.asar/"));
  // Each Minecraft target builds to its own Gradle output: the 1.21.11 tree at the repo
  // root, and every forked version tree under clientcore-<mc>/. Both are searched (the
  // per-version filter below picks the right jar) so a local `gradlew build` of either
  // one is picked up in dev without having to copy it into bundled/ first.
  const clientLibDirs = [
    root ? path.join(root, "build", "libs") : "",
    ...(root ? SUPPORTED_MC_VERSIONS.map((v) => path.join(root, `clientcore-${v}`, "build", "libs")) : []),
    ...bundledDirs
  ].filter(Boolean);
  const bootstrapLibDirs = [
    root ? path.join(root, "river-bootstrap", "build", "libs") : "",
    ...bundledDirs
  ].filter(Boolean);
  const findIn = (dirs, re) => {
    for (const dir of dirs) {
      const jar = newestJarMatching(dir, re);
      if (jar) return jar;
    }
    return "";
  };
  const findClientIn = (dirs) => {
    for (const dir of dirs) {
      const jar = newestJarMatching(dir, /^clientcore-[\w.+-]*\.jar$/i, (f) => clientcoreJarMatchesMc(f, mcVersion));
      if (jar) return jar;
    }
    return "";
  };
  return {
    bootstrapJar: findIn(bootstrapLibDirs, /^river-bootstrap-[\w.+-]*\.jar$/i),
    clientJar: findClientIn(clientLibDirs)
  };
}

function isRealRuntimeJar(p) {
  return Boolean(p) && !String(p).includes("app.asar") && fs.existsSync(p) && fs.statSync(p).isFile();
}

function copyRuntimeJarToInstance(sourceJar, runtimeDir) {
  if (!isRealRuntimeJar(sourceJar)) return "";
  fs.mkdirSync(runtimeDir, { recursive: true });
  const targetJar = path.join(runtimeDir, path.basename(sourceJar));
  try {
    const sourceStat = fs.statSync(sourceJar);
    const targetStat = fs.existsSync(targetJar) ? fs.statSync(targetJar) : null;
    if (!targetStat || targetStat.size !== sourceStat.size || targetStat.mtimeMs < sourceStat.mtimeMs) {
      fs.copyFileSync(sourceJar, targetJar);
      fs.utimesSync(targetJar, sourceStat.atime, sourceStat.mtime);
    }
    return targetJar;
  } catch {
    return "";
  }
}

function ensureBundledClientCoreMod(instancePath, mcVersion = instanceMcVersion(instancePath)) {
  const root = String(instancePath || "");
  if (!root) return "";
  const modsDir = path.join(root, "mods");
  fs.mkdirSync(modsDir, { recursive: true });

  // Safety: never leave a clientcore jar built for a different Minecraft version in this
  // instance. A 1.21.11 build loaded on a 1.21.4 instance (or vice versa) crashes at launch,
  // so drop any mismatched River jar before deciding what to install.
  for (const file of fs.readdirSync(modsDir)) {
    if (!CLIENTCORE_MOD_PATTERN.test(String(file || ""))) continue;
    if (!clientcoreJarMatchesMc(file, mcVersion)) {
      try { fs.rmSync(path.join(modsDir, file), { force: true }); } catch {}
      emit("launcher:log", `[launcher] Removed clientcore jar not built for ${mcVersion}: ${file}`);
    }
  }

  const resolved = resolveRiverRuntimeJars(mcVersion);
  const sourceJar = resolved.clientJar;
  if (!isRealRuntimeJar(sourceJar)) {
    // No River build for this Minecraft version ships with this launcher yet. The instance
    // still launches (Fabric + support/optimization mods); River's own mod simply stays out
    // until a matching clientcore-<ver>+<mc>.jar is bundled/published.
    return findCoreClientJarInMods(root, mcVersion);
  }

  const targetName = path.basename(sourceJar);
  const targetPath = path.join(modsDir, targetName);
  const disabledTargetPath = `${targetPath}.disabled`;

  if (fs.existsSync(disabledTargetPath)) {
    try { fs.rmSync(disabledTargetPath, { force: true }); } catch {}
  }

  // Drop stale clientcore jars for this same version, keeping only the target.
  for (const file of fs.readdirSync(modsDir)) {
    if (!CLIENTCORE_MOD_PATTERN.test(String(file || ""))) continue;
    if (!clientcoreJarMatchesMc(file, mcVersion)) continue;
    const fullPath = path.join(modsDir, file);
    if (fullPath.toLowerCase() === targetPath.toLowerCase()) continue;
    try { fs.rmSync(fullPath, { force: true }); } catch {}
  }

  try {
    const sourceStat = fs.statSync(sourceJar);
    const targetStat = fs.existsSync(targetPath) ? fs.statSync(targetPath) : null;
    if (!targetStat || targetStat.size !== sourceStat.size || targetStat.mtimeMs < sourceStat.mtimeMs) {
      fs.copyFileSync(sourceJar, targetPath);
      fs.utimesSync(targetPath, sourceStat.atime, sourceStat.mtime);
    }
  } catch {}

  const manifest = readModManifest(root);
  manifest.mods[targetName] = {
    ...(manifest.mods[targetName] || {}),
    file: targetName,
    title: "River Client",
    author: "WyZ_EU",
    source: "river",
    disabled: false
  };
  writeModManifest(root, manifest);

  return targetPath;
}

function stageRiverRuntimeForInstance(instancePath, mcVersion = instanceMcVersion(instancePath)) {
  const resolved = resolveRiverRuntimeJars(mcVersion);
  const root = String(instancePath || "");
  const runtimeDir = path.join(root, "river-runtime");
  const legacyRuntimeDir = path.join(root, ".river-runtime");
  if (!instancePath) return resolved;
  if (fs.existsSync(legacyRuntimeDir) && !fs.existsSync(runtimeDir)) {
    try {
      fs.cpSync(legacyRuntimeDir, runtimeDir, { recursive: true, force: true });
    } catch {}
  }
  const staged = {
    bootstrapJar: copyRuntimeJarToInstance(resolved.bootstrapJar, runtimeDir),
    clientJar: copyRuntimeJarToInstance(resolved.clientJar, runtimeDir)
  };
  return {
    bootstrapJar: staged.bootstrapJar || resolved.bootstrapJar,
    clientJar: staged.clientJar || resolved.clientJar
  };
}

function listManagedContentFiles(instancePath, folder) {
  try {
    const dir = path.join(String(instancePath || ""), folder);
    if (!fs.existsSync(dir)) return [];
    return fs.readdirSync(dir)
      .filter((name) => {
        const full = path.join(dir, name);
        try {
          return fs.statSync(full).isFile();
        } catch {
          return false;
        }
      });
  } catch {
    return [];
  }
}

function findCanonicalRiverInstance(version = "1.21.11", loader = "fabric", excludePath = "") {
  const instances = readInstances()
    .filter((instance) => String(instance.version || "") === String(version || "1.21.11"))
    .filter((instance) => String(instance.loader || "fabric").toLowerCase() === String(loader || "fabric").toLowerCase())
    .filter((instance) => String(instance.path || "") && String(instance.path || "") !== String(excludePath || ""));

  const scored = instances
    .map((instance) => ({
      instance,
      score: listManagedContentFiles(instance.path, "mods").length,
      riverNamed: /^river\b/i.test(String(instance.name || ""))
    }))
    .filter((entry) => entry.score > 0)
    .sort((left, right) => {
      if (left.riverNamed !== right.riverNamed) return left.riverNamed ? -1 : 1;
      if (left.score !== right.score) return right.score - left.score;
      return String(left.instance.name || "").localeCompare(String(right.instance.name || ""));
    });

  return scored[0]?.instance || null;
}

function syncBaselineFolder(sourceInstancePath, targetInstancePath, folder) {
  const sourceDir = path.join(sourceInstancePath, folder);
  const targetDir = path.join(targetInstancePath, folder);
  if (!fs.existsSync(sourceDir)) return [];
  fs.mkdirSync(targetDir, { recursive: true });
  const copied = [];
  for (const file of listManagedContentFiles(sourceInstancePath, folder)) {
    const from = path.join(sourceDir, file);
    const to = path.join(targetDir, file);
    if (fs.existsSync(to)) continue;
    fs.copyFileSync(from, to);
    copied.push(file);
  }
  return copied;
}

function syncBaselineManifest(sourceInstancePath, targetInstancePath, copiedFilesByFolder) {
  const sourceManifest = readModManifest(sourceInstancePath);
  const targetManifest = readModManifest(targetInstancePath);
  let changed = false;
  for (const [folder, files] of Object.entries(copiedFilesByFolder)) {
    if (!Array.isArray(files) || !files.length) continue;
    const contentType = folder === "mods" ? "mod" : folder === "resourcepacks" ? "resourcepack" : folder === "shaderpacks" ? "shader" : "";
    if (!contentType) continue;
    const sourceSection = manifestSection(sourceManifest, contentType);
    const targetSection = manifestSection(targetManifest, contentType);
    for (const file of files) {
      if (!sourceSection[file] || targetSection[file]) continue;
      targetSection[file] = sourceSection[file];
      changed = true;
    }
  }
  if (changed) writeModManifest(targetInstancePath, targetManifest);
  return changed;
}

function ensureRiverBaselineForInstance(instancePath, version = "1.21.11", loader = "fabric") {
  const source = findCanonicalRiverInstance(version, loader, instancePath);
  if (!source) return { ok: true, copied: [], message: "No River baseline source instance found." };
  const copiedPacks = syncBaselineFolder(source.path, instancePath, "resourcepacks");
  const copiedShaders = syncBaselineFolder(source.path, instancePath, "shaderpacks");
  syncBaselineManifest(source.path, instancePath, {
    resourcepacks: copiedPacks,
    shaderpacks: copiedShaders
  });
  const copied = [...copiedPacks, ...copiedShaders];
  return {
    ok: true,
    copied,
    message: copied.length
      ? `Synced ${copied.length} missing shared River file${copied.length === 1 ? "" : "s"} from ${source.name}.`
      : `Shared River files already matched ${source.name}.`
  };
}
const partnerDataUrl = "https://riverclient.xyz/data/partners.json";
const partnerSiteBaseUrl = "https://riverclient.xyz/";

// Minimal NBT parser for servers.dat
function parseOptionsTxt(instancePath) {
  const filePath = path.join(instancePath, "options.txt");
  const map = {};
  try {
    const lines = fs.readFileSync(filePath, "utf8").split(/\r?\n/);
    for (const line of lines) {
      const i = line.indexOf(":");
      if (i <= 0) continue;
      const key = line.slice(0, i).trim();
      const value = line.slice(i + 1).trimEnd();
      if (key) map[key] = value;
    }
  } catch {}
  return map;
}

/**
 * Reads a gzipped (or already-plain) NBT file and returns its root compound.
 *
 * Longs come back as Numbers - every field River reads from NBT is either a string or a
 * timestamp in ms, both of which sit comfortably inside a double.
 */
function parseNbtFile(filePath) {
  try {
    const raw = fs.readFileSync(filePath);
    // 0x1f8b is the gzip magic; world/server files are usually gzipped but not always.
    const data = raw[0] === 0x1f && raw[1] === 0x8b ? zlib.gunzipSync(raw) : raw;
    let offset = 0;
    const readU8  = () => data.readUInt8(offset++);
    const readI16 = () => { const v = data.readInt16BE(offset); offset += 2; return v; };
    const readU16 = () => { const v = data.readUInt16BE(offset); offset += 2; return v; };
    const readI32 = () => { const v = data.readInt32BE(offset); offset += 4; return v; };
    const readStr = () => { const l = readU16(); const s = data.toString("utf8", offset, offset + l); offset += l; return s; };
    function readPayload(type) {
      switch (type) {
        case 1: return readU8();
        case 2: return readI16();
        case 3: return readI32();
        case 4: { const v = data.readBigInt64BE(offset); offset += 8; return Number(v); }
        case 5: { const v = data.readFloatBE(offset); offset += 4; return v; }
        case 6: { const v = data.readDoubleBE(offset); offset += 8; return v; }
        case 7: { const l = readI32(); offset += l; return null; }
        case 8: return readStr();
        case 9: { const t = readU8(); const n = readI32(); const a = []; for (let i = 0; i < n; i++) a.push(readPayload(t)); return a; }
        case 10: { const o = {}; for (;;) { const t = readU8(); if (!t) break; const k = readStr(); o[k] = readPayload(t); } return o; }
        case 11: { const l = readI32(); offset += l * 4; return null; }
        case 12: { const l = readI32(); offset += l * 8; return null; }
        default: return null;
      }
    }
    const rootType = readU8();
    readStr();
    return readPayload(rootType);
  } catch { return null; }
}

function parseServersDat(filePath) {
  try {
    const root = parseNbtFile(filePath);
    if (!root || !Array.isArray(root.servers)) return [];
    return root.servers.map(s => ({ name: s.name || "", ip: s.ip || "", icon: s.icon || null }));
  } catch { return []; }
}

// Session history
function sessionHistoryPath() { return path.join(app.getPath("userData"), "session-history.json"); }
function readSessionHistory() {
  try { return JSON.parse(fs.readFileSync(sessionHistoryPath(), "utf8")); } catch { return []; }
}
function writeSession(session) {
  const list = readSessionHistory();
  list.unshift(session);
  fs.writeFileSync(sessionHistoryPath(), JSON.stringify(list.slice(0, 50), null, 2));
}

function playtimeStatsPath() {
  return path.join(app.getPath("userData"), "playtime-stats.json");
}

function readTotalPlaytimeMs() {
  try {
    const payload = JSON.parse(fs.readFileSync(playtimeStatsPath(), "utf8"));
    return Math.max(0, Math.floor(Number(payload.totalMs) || 0));
  } catch {
    const hist = readSessionHistory();
    const sum = hist.reduce((acc, s) => acc + Math.max(0, Math.floor(Number(s.durationMs) || 0)), 0);
    if (sum > 0) {
      fs.mkdirSync(path.dirname(playtimeStatsPath()), { recursive: true });
      fs.writeFileSync(
        playtimeStatsPath(),
        JSON.stringify(
          { totalMs: sum, updatedAt: new Date().toISOString(), seededFromSessionHistory: true },
          null,
          2
        )
      );
    }
    return sum;
  }
}

function addPlaytimeMs(ms) {
  const add = Math.max(0, Math.floor(Number(ms) || 0));
  if (!add) return;
  const total = readTotalPlaytimeMs() + add;
  fs.mkdirSync(path.dirname(playtimeStatsPath()), { recursive: true });
  fs.writeFileSync(
    playtimeStatsPath(),
    JSON.stringify({ totalMs: total, updatedAt: new Date().toISOString() }, null, 2)
  );
}
function recordSessionStart(instance) {
  currentSessionStart = Date.now();
  currentSessionInstance = instance ? { id: instance.id, name: instance.name, version: instance.version } : null;
}
function recordSessionEnd() {
  if (!currentSessionStart) return;
  const durationMs = Date.now() - currentSessionStart;
  writeSession({
    instanceId: currentSessionInstance?.id || "unknown",
    instanceName: currentSessionInstance?.name || "Unknown",
    instanceVersion: currentSessionInstance?.version || "",
    startTime: new Date(currentSessionStart).toISOString(),
    endTime: new Date().toISOString(),
    durationMs,
  });
  addPlaytimeMs(durationMs);
  currentSessionStart = null;
  currentSessionInstance = null;
}
let updateWatcher = null;
let updateCheckRunning = false;
let networkState = {
  online: false,
  checking: true,
  message: "Checking network...",
  checkedAt: 0
};
let launcherUpdateState = {
  checkedAt: 0,
  available: false,
  blocking: false,
  /** True when below manifest minimumVersion or manifest required flag. */
  required: false,
  latestVersion: "",
  currentVersion: "",
  minimumVersion: "",
  url: "",
  installerUrl: "",
  portableUrl: "",
  packageUrl: "",
  fileManifestUrl: "",
  appFileManifestUrl: "",
  appFileBaseUrl: "",
  fileManifestSha256: "",
  fileCount: 0,
  packageSha256: "",
  packageSize: 0,
  message: "Not checked yet."
};

const defaults = {
  selectedProfile: "dev",
  memoryMb: 4096,
  resolution: { width: 1280, height: 720 },
  closeOnLaunch: false,
  closeToTray: true,
  minimizeToTray: true,
  launchOnStartup: false,
  telemetry: false,
  instancePath: "",
  curseForgeApiKey: "",
  modFilters: {
    source: "modrinth",
    version: "1.21.11",
    loader: "fabric",
    tags: ""
  },
  selectedVersion: "1.21.11",
  offlineName: "WyZ_EU",
  microsoftClientId: "",
  javaPath: "",
  jvmArgs: "--add-modules jdk.incubator.vector -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=50 -XX:+DisableExplicitGC -XX:+AlwaysPreTouch",
  keepLauncherOpen: true,
  developerOfflineMode: false,
  autoBuildBeforeLaunch: true,
  autoInstallAfterBuild: true,
  verifyModDownloads: true,
  showConsoleOnLaunch: false,
  /** Opens the standalone game-log window alongside Minecraft on every launch. */
  showGameLogWindow: true,
  maxParallelDownloads: 3,
  uiDensity: "compact",
  lastSeenChangelogVersion: "",
  /** File name of the crash report the user dismissed, so it stays dismissed. */
  dismissedCrashReport: "",
  /** "combined" adds every other Minecraft client's tracked play time to River's; "river" shows River only. */
  playtimeDisplayMode: "combined",
  /** First-run guided tour. Set once the user finishes or skips it; replayable from Settings. */
  tutorialCompleted: false,
  /** Availability shown to friends: online | idle | dnd | invisible. */
  socialStatus: "online",
  /** Flip to Idle automatically after this many minutes without focusing the launcher. 0 disables. */
  autoIdleMinutes: 10,
  // TODO: languages. Right now every string the launcher shows is hardcoded
  // English, sitting inline in the JSX, so there is no single place to
  // translate. Needs the strings pulled out into a lookup keyed by id, a
  // "language" setting next to this one, and an English fallback whenever a
  // key is missing so a half-finished translation still renders. Czech first,
  // then whatever gets asked for most in the Discord.
  launcherTheme: "dark",
  accentColor: "#3B82F6",
  /** "auto" matches the launcher's apparent size to the display; or force a percentage. */
  uiScale: "auto",
  transparencyEffects: true,
  reduceAnimations: false,
  fullscreen: false,
  maxFps: 240,
  windowPreset: "1280x720",
  autoLogin: true,
  optimizedJvmArgs: true,
  multiThreadedRendering: true,
  nativeMemoryAllocation: true,
  clearMemoryOnExit: true,
  reduceBackgroundActivity: false,
  updateChannel: "stable",
  autoCheckUpdates: true,
  autoDownloadUpdates: false,
  betaNotifications: false,
  includeBetaVersions: false,
  backupDirectory: "",
  skipCompatibilityChecks: false,
  openLatestVersions: false,
  forceUpdateAssets: false,
  customProxy: "",
  useSystemProxy: false,
  friends: [],
  discordRichPresence: true,
  discordShowServer: true,
  discordApplicationId: "",
  socialPresenceStatus: "online",
  socialWorldAccess: "friends",
  allowFriendRequests: true,
  allowDirectMessages: true,
  friendCode: "",
  socialAddressName: "",
  trustedPublisherCertInstalled: false
};

const appIcon = path.join(__dirname, "assets", "riv3r-client.png");
// Windows groups taskbar buttons and picks their icon by AppUserModelID, not by the
// BrowserWindow icon. Without this the app inherits electron.exe's identity and shows
// the Electron logo on the taskbar and in notifications, whatever the window icon says.
if (process.platform === "win32") app.setAppUserModelId("dev.wyz.river.launcher");
const updateManifestUrl = String(process.env.RIVER_UPDATE_MANIFEST_URL || "https://updates.riverclient.xyz/latest.json").trim();

/**
 * The build's real River version (e.g. "0.1.5.2.4"), stamped by publish-update.js into a
 * bundled file. electron-builder forces app.getVersion() to package.json's 3-part semver
 * ("0.1.5"), which can never carry River's 4-part scheme - so comparing app.getVersion()
 * against a "0.1.5.2.4" manifest was always "update available", and the version never
 * advanced after installing, which read as "the updater doesn't work". Everything that
 * decides "what version am I / is there a newer one" must use this, not app.getVersion().
 */
let cachedBuildVersion = null;
function readBuildVersion() {
  if (cachedBuildVersion) return cachedBuildVersion;
  try {
    const raw = fs.readFileSync(path.join(__dirname, "config", "build-version.json"), "utf8");
    const parsed = String(JSON.parse(raw).version || "").trim();
    if (parsed) { cachedBuildVersion = parsed; return parsed; }
  } catch {}
  cachedBuildVersion = app.getVersion();
  return cachedBuildVersion;
}
// clientcore-1.21.4's fabric.mod.json requires fabricloader >=0.19.3; using an older loader
// here left 1.21.4 instances on a loader clientcore already declares itself incompatible
// with. Loader versions aren't tied to a Minecraft version, so one current version covers
// every instance.
const fabricLoaderVersion = "0.19.3";
const fabricKotlinVersion = "1.13.11+kotlin.2.3.21";

const requiredSupportMods = [
  { name: "Fabric API", query: "Fabric API", slug: "fabric-api", optional: false },
  { name: "Fabric Language Kotlin", query: "Fabric Language Kotlin", slug: "fabric-language-kotlin", optional: false }
];

function getSystemMemoryMb() {
  return Math.max(2048, Math.floor(os.totalmem() / (1024 * 1024)));
}

function getMemoryLimitMb(systemMemoryMb = getSystemMemoryMb()) {
  // Leave headroom for Windows, the GPU driver, and the launcher itself: reserve a quarter
  // of RAM, at least 4 GB. (Old code reserved a flat 2 GB, which on a 32 GB box offered up
  // to ~30 GB and left almost nothing for the OS.) Rounded down to a 256 MB step.
  const reservedMb = Math.max(4096, Math.round(systemMemoryMb * 0.25));
  const raw = Math.floor(Math.max(1024, systemMemoryMb - reservedMb) / 256) * 256;
  return Math.max(1024, raw);
}

function getRecommendedMemoryMb(systemMemoryMb = getSystemMemoryMb()) {
  // Boundaries sit a bit under the round number because os.totalmem() reports slightly less
  // than the installed RAM (a 32 GB box often reads ~32.6 GB), which otherwise dropped it a
  // tier and recommended too little.
  let recommended;
  if (systemMemoryMb >= 61440) recommended = 12288;
  else if (systemMemoryMb >= 30720) recommended = 8192;
  else if (systemMemoryMb >= 15360) recommended = 6144;
  else if (systemMemoryMb >= 7680) recommended = 4096;
  else recommended = 2048;
  return clamp(recommended, 1024, getMemoryLimitMb(systemMemoryMb));
}

/**
 * UI scale.
 *
 * Electron already honours the OS display scaling, so a 4K screen running at 150% is
 * fine. The case that breaks is a high-resolution screen at a LOW OS scale: at 4K/100%
 * the desktop is 3840 logical pixels wide, so a 1240px window is a stamp in the corner
 * with unreadable text, while the exact same build looks right on 1920x1080.
 *
 * "auto" therefore scales off the display's LOGICAL width against a 1920 baseline, which
 * is what actually decides how large the launcher appears next to everything else. The
 * curve is deliberately gentler than a straight ratio (a 1440p user wants more content on
 * screen, not simply bigger furniture) but still doubles up for 4K at 100%.
 */
const UI_SCALE_CHOICES = ["auto", "90", "100", "110", "125", "150", "175", "200"];
const BASE_MIN_WIDTH = 1040;
const BASE_MIN_HEIGHT = 660;

/**
 * Driven by the SMALLER of the two axes against a 1920x1080 baseline. Width alone would
 * hand a 3440x1440 ultrawide the same scale as a 4K panel, even though it has ordinary
 * vertical space and would end up with a minimum height taller than the work area. The
 * 0.9 floor for 100% is deliberate: a 1080p desktop with a taskbar measures ~1040 tall,
 * and that must still count as the baseline rather than being scaled down.
 */
function autoZoomForDisplay(logicalWidth, logicalHeight) {
  const ratio = Math.min(logicalWidth / 1920, logicalHeight / 1080);
  if (ratio >= 1.9) return 1.75;
  if (ratio >= 1.55) return 1.5;
  if (ratio >= 1.2) return 1.25;
  if (ratio >= 0.9) return 1;
  if (ratio >= 0.78) return 0.9;
  return 0.8;
}

function resolveZoomFactor(win, settings = readSettings()) {
  const choice = String(settings.uiScale || "auto");
  if (choice !== "auto") return Math.max(0.5, Number(choice) / 100 || 1);
  try {
    const bounds = win?.getBounds?.();
    const display = bounds ? screen.getDisplayMatching(bounds) : screen.getPrimaryDisplay();
    return autoZoomForDisplay(display.workAreaSize.width, display.workAreaSize.height);
  } catch {
    return 1;
  }
}

/**
 * Zooming shrinks the usable CSS viewport, so the minimum window size has to grow with it
 * or the layout (body has min-width and overflow:hidden) gets clipped rather than
 * scrolled. Clamped to the work area so a large scale on a small screen can't produce a
 * minimum bigger than the display itself.
 */
function applyUiScale(win = mainWindow, settings = readSettings()) {
  if (!win || win.isDestroyed()) return;
  const zoom = resolveZoomFactor(win, settings);
  try { win.webContents.setZoomFactor(zoom); } catch {}
  try {
    const area = screen.getDisplayMatching(win.getBounds()).workAreaSize;
    const minWidth = Math.min(Math.ceil(BASE_MIN_WIDTH * zoom), area.width);
    const minHeight = Math.min(Math.ceil(BASE_MIN_HEIGHT * zoom), area.height);
    win.setMinimumSize(minWidth, minHeight);
    const [width, height] = win.getSize();
    if (width < minWidth || height < minHeight) {
      win.setSize(Math.max(width, minWidth), Math.max(height, minHeight));
    }
  } catch {}
}

function createWindow() {
  // Open proportional to the display rather than at a fixed 1240x760, which is a
  // postage stamp on a 4K desktop and taller than the work area on a small laptop.
  const primary = screen.getPrimaryDisplay().workAreaSize;
  const startZoom = autoZoomForDisplay(primary.width, primary.height);
  const startWidth = Math.min(Math.round(1240 * startZoom), Math.round(primary.width * 0.9));
  const startHeight = Math.min(Math.round(760 * startZoom), Math.round(primary.height * 0.9));

  mainWindow = new BrowserWindow({
    width: startWidth,
    height: startHeight,
    minWidth: Math.min(BASE_MIN_WIDTH, primary.width),
    minHeight: Math.min(BASE_MIN_HEIGHT, primary.height),
    backgroundColor: "#070717",
    title: "River Client",
    icon: appIcon,
    titleBarStyle: "hidden",
    trafficLightPosition: { x: 16, y: 16 },
    webPreferences: {
      preload: path.join(__dirname, "preload.js"),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false
    }
  });

  mainWindow.loadFile(path.join(__dirname, "renderer", "index.html"));

  // Zoom has to be (re)applied per load - Electron resets it on navigation - and again
  // whenever the window is dragged to a different monitor, which is the whole point on a
  // mixed 4K + 1080p desk.
  mainWindow.webContents.on("did-finish-load", () => applyUiScale(mainWindow));
  let scaleMoveTimer = null;
  mainWindow.on("moved", () => {
    clearTimeout(scaleMoveTimer);
    scaleMoveTimer = setTimeout(() => applyUiScale(mainWindow), 250);
  });

  // Auto-idle: flip to Idle after the launcher has been unfocused long enough, and back
  // to Online the moment it is focused again - but only when the user is on one of those
  // two, so a deliberate Do Not Disturb or Invisible is never silently overridden.
  let idleTimer = null;
  const autoIdleEligible = (settings) => settings.socialStatus === "online" || settings.socialStatus === "idle";
  mainWindow.on("blur", () => {
    clearTimeout(idleTimer);
    const settings = readSettings();
    if (!settings.autoIdleMinutes || !autoIdleEligible(settings)) return;
    idleTimer = setTimeout(() => {
      const current = readSettings();
      if (current.socialStatus !== "online") return;
      announceSocialStatus(writeSettings({ ...current, socialStatus: "idle" })).catch(() => {});
      emitStatus();
    }, settings.autoIdleMinutes * 60 * 1000);
  });
  mainWindow.on("focus", () => {
    clearTimeout(idleTimer);
    const settings = readSettings();
    if (settings.socialStatus !== "idle") return;
    announceSocialStatus(writeSettings({ ...settings, socialStatus: "online" })).catch(() => {});
    emitStatus();
  });

  mainWindow.webContents.once("did-finish-load", () => {
    startStatusHeartbeat();
    checkNetwork().then(() => ensureFreshAuth()).then((auth) => ensureAccountSkinSeeded(auth).then(() => auth)).then(emitStatus);
    startLauncherUpdateWatcher();
    autoSetupOnBoot();
    if (updateFailureLogPath) {
      setTimeout(() => emitActivity({
        title: "Update could not be applied",
        detail: "River restored the previous launcher files. Try the update again or open a support ticket.",
        current: 1,
        total: 1,
        done: true,
        error: true,
        logPath: updateFailureLogPath
      }), 700);
    }
  });
  mainWindow.webContents.on("console-message", (_event, level, message, line, sourceId) => {
    try {
      console.log(`[renderer:${level}] ${message} (${sourceId || "unknown"}:${line || 0})`);
    } catch {}
  });
  mainWindow.webContents.on("render-process-gone", (_event, details) => {
    try {
      console.error("[launcher] Renderer process went away:", details);
    } catch {}
  });
  mainWindow.webContents.on("did-fail-load", (_event, code, description, url) => {
    try {
      console.error(`[launcher] Failed to load renderer: ${code} ${description} ${url || ""}`.trim());
    } catch {}
  });

  mainWindow.on("close", (event) => {
    if (launchProcess && !appIsQuitting) {
      event.preventDefault();
      emitActivity({
        title: "Minecraft is still running",
        detail: "Close Minecraft before exiting River Client.",
        done: true,
        error: true
      });
      if (!mainWindow.isMinimized()) mainWindow.minimize();
    }
  });
}

function settingsPath() {
  return path.join(app.getPath("userData"), "settings.json");
}

function authPath() {
  return path.join(app.getPath("userData"), "minecraft-auth.json");
}

function instancesPath() {
  return path.join(app.getPath("userData"), "instances.json");
}

function skinHistoryPath() {
  return path.join(app.getPath("userData"), "skins", "skin-history.json");
}

function skinStorageDir() {
  return path.join(app.getPath("userData"), "skins", "files");
}

function versionsPath() {
  return path.join(app.getPath("userData"), "mojang-versions.json");
}

function modManifestPath(instancePath) {
  return path.join(instancePath, "riv3r-mods.json");
}

function instanceMetaPath(instancePath) {
  return path.join(instancePath, "riv3r-instance.json");
}

function defaultInstancePath() {
  return path.join(app.getPath("appData"), "River Client", "instances", "default");
}

function instancesRootPath() {
  return path.join(app.getPath("appData"), "River Client", "instances");
}

function readInstanceMeta(instancePath) {
  try {
    return JSON.parse(fs.readFileSync(instanceMetaPath(instancePath), "utf8"));
  } catch {
    return {};
  }
}

function writeInstanceMeta(instancePath, patch) {
  const current = readInstanceMeta(instancePath);
  const next = { ...current, ...patch };
  fs.writeFileSync(instanceMetaPath(instancePath), JSON.stringify(next, null, 2));
  return next;
}

function shouldRunOptimizationSuite(instancePath) {
  return !Boolean(readInstanceMeta(instancePath).optimizationAppliedAt);
}

function readSettings() {
  try {
    return normalizeSettings(JSON.parse(fs.readFileSync(settingsPath(), "utf8")));
  } catch {
    return normalizeSettings({});
  }
}

function clearLaunchStateTimer() {
  if (launchStateTimer) {
    clearTimeout(launchStateTimer);
    launchStateTimer = null;
  }
}

function setLaunchState(nextState) {
  const normalized = ["idle", "launching", "running"].includes(String(nextState || ""))
    ? String(nextState)
    : "idle";
  clearLaunchStateTimer();
  if (launchState === normalized) return;
  launchState = normalized;
  emitStatus();
}

function scheduleRunningLaunchState(delayMs = 4500) {
  clearLaunchStateTimer();
  launchStateTimer = setTimeout(() => {
    if (launchProcess && launchState === "launching") {
      launchState = "running";
      emitStatus();
    }
  }, delayMs);
}

function promoteLaunchStateFromOutput(text) {
  if (!launchProcess || launchState !== "launching") return;
  const value = String(text || "");
  if (!value) return;
  if (/(Setting user:|Backend library: LWJGL|OpenGL Vendor:|Sound engine started|Environment: Environment\[|Created: .*atlas|Minecraft process|Rendering|Loaded \d+ mods)/i.test(value)) {
    setLaunchState("running");
  }
}

function normalizeSettings(value) {
  const merged = {
    ...defaults,
    ...value,
    resolution: {
      ...defaults.resolution,
      ...(value && value.resolution ? value.resolution : {})
    },
    modFilters: {
      ...defaults.modFilters,
      ...(value && value.modFilters ? value.modFilters : {})
    }
  };

  if (!merged.instancePath) merged.instancePath = defaultInstancePath();
  merged.memoryMb = clamp(Number(merged.memoryMb) || defaults.memoryMb, 1024, getMemoryLimitMb());
  merged.resolution.width = clamp(Number(merged.resolution.width) || defaults.resolution.width, 854, 3840);
  merged.resolution.height = clamp(Number(merged.resolution.height) || defaults.resolution.height, 480, 2160);
  merged.closeOnLaunch = Boolean(merged.closeOnLaunch);
  merged.closeToTray = merged.closeToTray !== false;
  merged.minimizeToTray = merged.minimizeToTray !== false;
  merged.launchOnStartup = Boolean(merged.launchOnStartup);
  merged.telemetry = Boolean(merged.telemetry);
  merged.curseForgeApiKey = String(merged.curseForgeApiKey || "");
  merged.modFilters.source = String(merged.modFilters.source || defaults.modFilters.source);
  merged.selectedVersion = SUPPORTED_MC_VERSIONS.includes(String(merged.selectedVersion))
    ? String(merged.selectedVersion)
    : DEFAULT_MC_VERSION;
  // The mod/content browser filters by the version the user is actually on.
  merged.modFilters.version = merged.selectedVersion;
  merged.modFilters.loader = String(merged.modFilters.loader || defaults.modFilters.loader);
  merged.modFilters.tags = String(merged.modFilters.tags || "");
  merged.offlineName = String(merged.offlineName || defaults.offlineName);
  merged.microsoftClientId = String(merged.microsoftClientId || "");
  merged.javaPath = String(merged.javaPath || "");
  merged.jvmArgs = String(merged.jvmArgs || defaults.jvmArgs || "").trim();
  merged.keepLauncherOpen = Boolean(merged.keepLauncherOpen);
  merged.developerOfflineMode = Boolean(merged.developerOfflineMode);
  merged.autoBuildBeforeLaunch = true;
  merged.autoInstallAfterBuild = true;
  merged.verifyModDownloads = Boolean(merged.verifyModDownloads);
  merged.showConsoleOnLaunch = Boolean(merged.showConsoleOnLaunch);
  merged.maxParallelDownloads = clamp(Number(merged.maxParallelDownloads) || defaults.maxParallelDownloads, 1, 8);
  merged.uiDensity = ["compact", "comfortable"].includes(merged.uiDensity) ? merged.uiDensity : defaults.uiDensity;
  merged.lastSeenChangelogVersion = String(merged.lastSeenChangelogVersion || "");
  merged.dismissedCrashReport = String(merged.dismissedCrashReport || "");
  const themes = ["dark", "light", "darker", "midnight", "amoled"];
  merged.launcherTheme = themes.includes(merged.launcherTheme) ? merged.launcherTheme : defaults.launcherTheme;
  merged.accentColor = /^#([0-9a-f]{6})$/i.test(String(merged.accentColor || "")) ? String(merged.accentColor) : defaults.accentColor;
  merged.uiScale = UI_SCALE_CHOICES.includes(String(merged.uiScale || "")) ? String(merged.uiScale) : defaults.uiScale;
  merged.socialStatus = SOCIAL_STATUS_VALUES.includes(String(merged.socialStatus || ""))
    ? String(merged.socialStatus)
    : defaults.socialStatus;
  merged.autoIdleMinutes = Math.max(0, Math.min(120, Math.floor(Number(merged.autoIdleMinutes ?? defaults.autoIdleMinutes) || 0)));
  merged.transparencyEffects = merged.transparencyEffects !== false;
  merged.reduceAnimations = Boolean(merged.reduceAnimations);
  merged.fullscreen = Boolean(merged.fullscreen);
  merged.maxFps = [60, 120, 144, 165, 240, 0].includes(Number(merged.maxFps)) ? Number(merged.maxFps) : defaults.maxFps;
  merged.windowPreset = ["1280x720", "1600x900", "1920x1080", "2560x1440"].includes(String(merged.windowPreset || "")) ? String(merged.windowPreset) : defaults.windowPreset;
  merged.autoLogin = merged.autoLogin !== false;
  merged.optimizedJvmArgs = merged.optimizedJvmArgs !== false;
  merged.multiThreadedRendering = merged.multiThreadedRendering !== false;
  merged.nativeMemoryAllocation = merged.nativeMemoryAllocation !== false;
  merged.clearMemoryOnExit = merged.clearMemoryOnExit !== false;
  merged.reduceBackgroundActivity = Boolean(merged.reduceBackgroundActivity);
  merged.updateChannel = ["stable", "beta"].includes(String(merged.updateChannel || "")) ? String(merged.updateChannel) : defaults.updateChannel;
  merged.autoCheckUpdates = merged.autoCheckUpdates !== false;
  merged.autoDownloadUpdates = Boolean(merged.autoDownloadUpdates);
  merged.betaNotifications = Boolean(merged.betaNotifications);
  merged.includeBetaVersions = Boolean(merged.includeBetaVersions);
  merged.backupDirectory = String(merged.backupDirectory || "");
  if (!merged.backupDirectory) merged.backupDirectory = path.join(app.getPath("userData"), "backups");
  merged.skipCompatibilityChecks = Boolean(merged.skipCompatibilityChecks);
  merged.openLatestVersions = Boolean(merged.openLatestVersions);
  merged.forceUpdateAssets = Boolean(merged.forceUpdateAssets);
  merged.customProxy = String(merged.customProxy || "");
  merged.useSystemProxy = Boolean(merged.useSystemProxy);
  merged.discordRichPresence = merged.discordRichPresence !== false;
  merged.discordShowServer = merged.discordShowServer !== false;
  merged.discordApplicationId = String(merged.discordApplicationId || "");
  merged.socialPresenceStatus = sanitizeSocialPresenceStatus(merged.socialPresenceStatus);
  merged.socialWorldAccess = sanitizeSocialWorldAccess(merged.socialWorldAccess);
  merged.allowFriendRequests = merged.allowFriendRequests !== false;
  merged.allowDirectMessages = merged.allowDirectMessages !== false;
  merged.trustedPublisherCertInstalled = Boolean(merged.trustedPublisherCertInstalled);
  merged.friendCode = String(merged.friendCode || "").trim().toUpperCase().replace(/[^A-Z0-9-]/g, "").slice(0, 24);
  if (!merged.friendCode) merged.friendCode = buildFriendCode();
  merged.socialAddressName = sanitizeSocialAddressName(merged.socialAddressName || "");
  merged.friends = Array.isArray(merged.friends)
    ? merged.friends.filter((f) => f && typeof f.name === "string" && String(f.name).trim()).map((f, i) => ({
      id: String(f.id || `friend-${i}-${Date.now()}`),
      name: String(f.name || "").trim().slice(0, 40),
      address: String(f.address || `${sanitizeSocialAddressName(f.name || "") || "friend"}.riverclient.xyz`).trim().slice(0, 80),
      note: String(f.note || "").trim().slice(0, 80),
      status: ["online", "busy", "offline"].includes(String(f.status || "")) ? String(f.status) : "offline"
    })).slice(0, 64)
    : [];
  return merged;
}

function buildFriendCode() {
  return `RIVER-${crypto.randomBytes(3).toString("hex").toUpperCase()}`;
}

function socialAddressRegistryPath() {
  return path.join(app.getPath("userData"), "social-addresses.json");
}

function bundledPublicCertCandidates() {
  const desktopDir = app.getPath("desktop");
  return [
    path.join(__dirname, "assets", "river-client-selfsign.cer"),
    process.resourcesPath ? path.join(process.resourcesPath, "assets", "river-client-selfsign.cer") : "",
    process.resourcesPath ? path.join(process.resourcesPath, "app.asar.unpacked", "assets", "river-client-selfsign.cer") : "",
    desktopDir ? path.join(desktopDir, "river-client-selfsign.cer") : ""
  ].filter(Boolean);
}

function findBundledPublicCert() {
  return bundledPublicCertCandidates().find((candidate) => fs.existsSync(candidate)) || "";
}

function stagedPublicCertPath() {
  return path.join(app.getPath("userData"), "certs", "river-client-selfsign.cer");
}

function materializeBundledPublicCert() {
  const source = findBundledPublicCert();
  if (!source) return "";
  const target = stagedPublicCertPath();
  fs.mkdirSync(path.dirname(target), { recursive: true });
  fs.writeFileSync(target, fs.readFileSync(source));
  return target;
}

function runHiddenPowerShell(command) {
  return new Promise((resolve, reject) => {
    const child = spawn("powershell.exe", [
      "-NoProfile",
      "-ExecutionPolicy", "Bypass",
      "-WindowStyle", "Hidden",
      "-Command", command
    ], {
      windowsHide: true,
      stdio: ["ignore", "pipe", "pipe"]
    });

    let stdout = "";
    let stderr = "";
    child.stdout.on("data", (chunk) => { stdout += chunk.toString(); });
    child.stderr.on("data", (chunk) => { stderr += chunk.toString(); });
    child.on("error", reject);
    child.on("close", (code) => {
      if (code === 0) {
        resolve({ ok: true, stdout, stderr });
        return;
      }
      reject(new Error(stderr.trim() || stdout.trim() || `PowerShell exited with code ${code}.`));
    });
  });
}

function escapePowerShellSingleQuoted(value) {
  return String(value || "").replace(/'/g, "''");
}

async function installBundledPublisherCertIfNeeded() {
  const settings = readSettings();
  if (settings.trustedPublisherCertInstalled) return { ok: true, skipped: true, message: "Publisher certificate already marked as installed." };

  const certPath = materializeBundledPublicCert();
  if (!certPath) return { ok: false, skipped: true, message: "No public River certificate (.cer) was bundled for first-launch install." };

  const escapedPath = escapePowerShellSingleQuoted(certPath);
  const script = [
    "$ErrorActionPreference = 'Stop'",
    `$certPath = '${escapedPath}'`,
    "$cert = New-Object System.Security.Cryptography.X509Certificates.X509Certificate2($certPath)",
    "$thumb = ($cert.Thumbprint -replace '\\s+', '').ToUpperInvariant()",
    "$stores = @('Cert:\\CurrentUser\\TrustedPublisher', 'Cert:\\CurrentUser\\Root')",
    "$alreadyTrusted = $true",
    "foreach ($storePath in $stores) {",
    "  $exists = Get-ChildItem -Path $storePath | Where-Object { (($_.Thumbprint -replace '\\s+', '').ToUpperInvariant()) -eq $thumb } | Select-Object -First 1",
    "  if (-not $exists) { $alreadyTrusted = $false }",
    "}",
    "if (-not $alreadyTrusted) {",
    "  Import-Certificate -FilePath $certPath -CertStoreLocation 'Cert:\\CurrentUser\\TrustedPublisher' | Out-Null",
    "  Import-Certificate -FilePath $certPath -CertStoreLocation 'Cert:\\CurrentUser\\Root' | Out-Null",
    "}",
    "Write-Output $thumb"
  ].join("; ");

  try {
    const result = await runHiddenPowerShell(script);
    writeSettings({ ...settings, trustedPublisherCertInstalled: true });
    emit("launcher:log", `[launcher] Installed River publisher certificate for the current Windows user from ${path.basename(certPath)}.`);
    return { ok: true, skipped: false, certPath, thumbprint: String(result.stdout || "").trim() };
  } catch (error) {
    emit("launcher:log", `[launcher] River publisher certificate install skipped: ${error.message}`);
    return { ok: false, skipped: false, certPath, message: error.message };
  }
}

function sanitizeSocialAddressName(value) {
  return String(value || "")
    .trim()
    .toLowerCase()
    .replace(/\s+/g, "")
    .replace(/[^a-z0-9_-]/g, "")
    .slice(0, 24);
}

function sanitizeSocialPresenceStatus(value) {
  const normalized = String(value || "").trim().toLowerCase();
  if (normalized === "dnd") return "busy";
  return ["online", "busy", "invisible"].includes(normalized) ? normalized : defaults.socialPresenceStatus;
}

function sanitizeSocialWorldAccess(value) {
  const normalized = String(value || "").trim().toLowerCase();
  return ["friends", "lan", "private"].includes(normalized) ? normalized : defaults.socialWorldAccess;
}

function readSocialAddressRegistry() {
  try {
    const payload = JSON.parse(fs.readFileSync(socialAddressRegistryPath(), "utf8"));
    return payload && typeof payload === "object" && payload.names && typeof payload.names === "object" ? payload.names : {};
  } catch {
    return {};
  }
}

function writeSocialAddressRegistry(names) {
  fs.mkdirSync(path.dirname(socialAddressRegistryPath()), { recursive: true });
  fs.writeFileSync(socialAddressRegistryPath(), JSON.stringify({ names }, null, 2));
}

function getSocialAddressOwnerKey(settings = readSettings(), auth = readAuth()) {
  const profileId = String(auth?.profile?.id || "").trim();
  if (profileId) return `profile:${profileId}`;
  return `code:${String(settings.friendCode || "").trim().toUpperCase()}`;
}

function checkSocialAddressAvailability(name, ownerKey = getSocialAddressOwnerKey()) {
  const normalized = sanitizeSocialAddressName(name);
  if (!normalized) return { normalized, available: false, reason: "Enter a name to claim an address." };
  if (normalized.length < 3) return { normalized, available: false, reason: "Use at least 3 characters." };
  const registry = readSocialAddressRegistry();
  const holder = registry[normalized];
  if (!holder || holder === ownerKey) {
    return { normalized, available: true, reason: "Available" };
  }
  return { normalized, available: false, reason: "Taken" };
}

function saveSocialAddressName(name) {
  const settings = readSettings();
  const auth = readAuth();
  const ownerKey = getSocialAddressOwnerKey(settings, auth);
  const check = checkSocialAddressAvailability(name, ownerKey);
  if (!check.available) {
    return { ok: false, message: check.reason, normalized: check.normalized };
  }

  const registry = readSocialAddressRegistry();
  const current = sanitizeSocialAddressName(settings.socialAddressName || "");
  if (current && registry[current] === ownerKey && current !== check.normalized) delete registry[current];
  registry[check.normalized] = ownerKey;
  writeSocialAddressRegistry(registry);
  writeSettings({ ...settings, socialAddressName: check.normalized });
  emitStatus();
  return { ok: true, message: `${check.normalized}.riverclient.xyz saved.`, normalized: check.normalized };
}

function readCpuUsagePercent() {
  const cpus = os.cpus();
  if (!Array.isArray(cpus) || !cpus.length) return null;
  const totals = cpus.reduce((acc, cpu) => {
    const times = cpu.times || {};
    acc.idle += Number(times.idle || 0);
    acc.total += Object.values(times).reduce((sum, value) => sum + Number(value || 0), 0);
    return acc;
  }, { idle: 0, total: 0 });
  if (!lastCpuSample) {
    lastCpuSample = totals;
    return null;
  }
  const idleDiff = totals.idle - lastCpuSample.idle;
  const totalDiff = totals.total - lastCpuSample.total;
  lastCpuSample = totals;
  if (totalDiff <= 0) return null;
  return Math.max(0, Math.min(100, Math.round((1 - idleDiff / totalDiff) * 100)));
}

function getDiscordRpcClientId(settings = readSettings()) {
  return String(process.env.RIVER_DISCORD_RPC_CLIENT_ID || DEFAULT_DISCORD_RPC_CLIENT_ID).trim();
}

function setDiscordRpcState(patch = {}) {
  discordRpcState = {
    ...discordRpcState,
    ...patch,
    lastUpdatedAt: Date.now()
  };
}

function stopDiscordRpcRefreshLoop() {
  if (discordRpcRefreshTimer) {
    clearInterval(discordRpcRefreshTimer);
    discordRpcRefreshTimer = null;
  }
}

function stopDiscordRpcReconnectLoop() {
  if (discordRpcReconnectTimer) {
    clearTimeout(discordRpcReconnectTimer);
    discordRpcReconnectTimer = null;
  }
}

function discordRpcLooksLikeClosed(error) {
  const text = String(error?.message || error || "").toLowerCase();
  return text.includes("connection closed")
    || text.includes("pipe closed")
    || text.includes("not connected")
    || text.includes("disconnected");
}

function queueDiscordRpcReconnect(delayMs = 4000, reason = "Trying to reconnect to Discord...") {
  stopDiscordRpcReconnectLoop();
  discordRpcReady = false;
  discordRpcConnecting = false;
  if (discordRpcClient) {
    try { discordRpcClient.destroy(); } catch {}
  }
  discordRpcClient = null;
  setDiscordRpcState({
    enabled: readSettings().discordRichPresence !== false,
    connected: false,
    clientId: getDiscordRpcClientId(),
    message: reason
  });
  discordRpcReconnectTimer = setTimeout(() => {
    discordRpcReconnectTimer = null;
    scheduleDiscordPresenceRefresh();
  }, delayMs);
}

function describeDiscordRpcError(error) {
  if (discordRpcLooksLikeClosed(error)) return "Discord closed the connection. River will reconnect automatically.";
  const text = String(error?.message || "").trim();
  return text || "Could not connect to Discord.";
}

async function clearDiscordRichPresence() {
  if (!discordRpcClient) return;
  try {
    await discordRpcClient.clearActivity();
  } catch {}
}

async function disconnectDiscordRpc(reason = "Discord Rich Presence is off.") {
  stopDiscordRpcRefreshLoop();
  stopDiscordRpcReconnectLoop();
  if (discordRpcClient) {
    try { await clearDiscordRichPresence(); } catch {}
    try { discordRpcClient.destroy(); } catch {}
  }
  discordRpcClient = null;
  discordRpcReady = false;
  discordRpcConnecting = false;
  setDiscordRpcState({
    enabled: false,
    connected: false,
    clientId: "",
    message: reason
  });
}

function buildDiscordPresencePayload(status = getStatus()) {
  const settings = status.settings || readSettings();
  const auth = status.auth || {};
  const instance = (status.instances || []).find((item) => item.path === settings.instancePath) || null;
  const activeName = auth?.profile?.name || settings.offlineName || "River Player";
  const bridge = status.bridgePresence || null;
  let details = "In launcher";
  let stateText = `${activeName}`;
  if (status.running) {
    switch (bridge?.state) {
      case "in_main_menu":
        details = "In main menu";
        stateText = `Fabric ${settings.selectedVersion || "1.21.11"}`;
        break;
      case "browsing_server_list":
        details = "Browsing server list";
        stateText = `Fabric ${settings.selectedVersion || "1.21.11"}`;
        break;
      case "playing_server":
        details = settings.discordShowServer !== false && bridge.serverName
          ? `Playing on ${bridge.serverName}`
          : "Playing Minecraft";
        stateText = settings.discordShowServer !== false && bridge.serverAddress
          ? bridge.serverAddress
          : `Fabric ${settings.selectedVersion || "1.21.11"}`;
        break;
      case "in_game":
        details = "In game";
        stateText = `Fabric ${settings.selectedVersion || "1.21.11"}`;
        break;
      default:
        details = `Playing ${instance?.name || "River Client"}`;
        stateText = `Fabric ${settings.selectedVersion || "1.21.11"}`;
        break;
    }
  }
  const payload = {
    details,
    state: stateText.slice(0, 128),
    instance: false,
    buttons: [
      { label: "Download River Client", url: RIVER_WEBSITE_URL },
      { label: "Join River Discord", url: RIVER_DISCORD_URL }
    ]
  };
  if (status.running && currentSessionStart) payload.startTimestamp = Math.floor(currentSessionStart / 1000);
  return payload;
}

async function pushDiscordRichPresence(status = getStatus()) {
  const settings = status.settings || readSettings();
  const clientId = getDiscordRpcClientId(settings);
  if (!DiscordRPC) {
    setDiscordRpcState({
      supported: false,
      enabled: false,
      connected: false,
      clientId: "",
      message: "discord-rpc is not available in this build."
    });
    return;
  }
  if (!settings.discordRichPresence) {
    await disconnectDiscordRpc("Discord Rich Presence is disabled.");
    return;
  }
  if (!clientId) {
    await disconnectDiscordRpc("Discord Rich Presence is not configured.");
    return;
  }
  if (!discordRpcClient && !discordRpcConnecting) {
    stopDiscordRpcReconnectLoop();
    discordRpcConnecting = true;
    DiscordRPC.register(clientId);
    const client = new DiscordRPC.Client({ transport: "ipc" });
    client.on("ready", async () => {
      discordRpcReady = true;
      discordRpcConnecting = false;
      setDiscordRpcState({
        supported: true,
        enabled: true,
        connected: true,
        clientId,
        message: "Discord Rich Presence is live."
      });
      try {
        await client.setActivity(buildDiscordPresencePayload(getStatus()));
      } catch {}
    });
    client.on("disconnected", () => {
      queueDiscordRpcReconnect(3500, "Discord closed the connection. Reconnecting...");
    });
    client.on("error", (error) => {
      queueDiscordRpcReconnect(5000, describeDiscordRpcError(error));
    });
    try {
      await client.login({ clientId });
      discordRpcClient = client;
    } catch (error) {
      discordRpcConnecting = false;
      setDiscordRpcState({
        enabled: true,
        connected: false,
        clientId,
        message: describeDiscordRpcError(error)
      });
      try { client.destroy(); } catch {}
      if (discordRpcLooksLikeClosed(error)) queueDiscordRpcReconnect(5000, "Discord closed the connection. Reconnecting...");
      return;
    }
  }
  if (!discordRpcReady || !discordRpcClient) return;
  try {
    await discordRpcClient.setActivity(buildDiscordPresencePayload(status));
    setDiscordRpcState({
      supported: true,
      enabled: true,
      connected: true,
      clientId,
      message: "Discord Rich Presence is live."
    });
  } catch (error) {
    if (discordRpcLooksLikeClosed(error)) {
      queueDiscordRpcReconnect(5000, "Discord closed the connection. Reconnecting...");
      return;
    }
    setDiscordRpcState({
      enabled: true,
      connected: false,
      clientId,
      message: describeDiscordRpcError(error)
    });
  }
}

function scheduleDiscordPresenceRefresh() {
  clearTimeout(scheduleDiscordPresenceRefresh.timer);
  scheduleDiscordPresenceRefresh.timer = setTimeout(() => {
    pushDiscordRichPresence(getStatus()).catch(() => {});
  }, 120);
}
scheduleDiscordPresenceRefresh.timer = null;

function writeSettings(next) {
  const normalized = normalizeSettings(next);
  fs.mkdirSync(path.dirname(settingsPath()), { recursive: true });
  fs.writeFileSync(settingsPath(), JSON.stringify(normalized, null, 2));
  return normalized;
}

function readInstances() {
  const settings = readSettings();
  let instances = [];
  try {
    const payload = JSON.parse(fs.readFileSync(instancesPath(), "utf8"));
    instances = Array.isArray(payload.instances) ? payload.instances : [];
  } catch {
    instances = [];
  }

  const normalized = instances.map(normalizeInstance).filter(Boolean);
  if (!normalized.some((instance) => instance.path === settings.instancePath)) {
    normalized.unshift({
      id: "default",
      name: "River Default",
      type: "default",
      version: settings.selectedVersion,
      loader: "fabric",
      path: settings.instancePath,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString()
    });
  }

  return normalized.map((instance) => ({
    ...instance,
    selected: instance.path === settings.instancePath
  }));
}

function findInstanceById(instanceId) {
  const key = String(instanceId || "").trim().toLowerCase();
  if (!key) return null;
  return readInstances().find((instance) => String(instance.id || "").trim().toLowerCase() === key) || null;
}

function resolveInstancePath(request = {}) {
  if (typeof request === "string") {
    const byId = findInstanceById(request);
    return byId?.path || "";
  }

  const instanceId = String(request?.instanceId || "").trim();
  if (instanceId) {
    const byId = findInstanceById(instanceId);
    if (byId?.path) return byId.path;
  }

  const explicitPath = String(request?.instancePath || "").trim();
  if (explicitPath) return explicitPath;

  return readSettings().instancePath;
}

function writeInstances(instances) {
  const normalized = instances.map(normalizeInstance).filter(Boolean);
  fs.mkdirSync(path.dirname(instancesPath()), { recursive: true });
  fs.writeFileSync(instancesPath(), JSON.stringify({ instances: normalized }, null, 2));
  return normalized;
}

function normalizeInstance(instance) {
  if (!instance || !instance.path) return null;
  return {
    id: String(instance.id || sanitizeFilename(instance.name || path.basename(instance.path) || `instance-${Date.now()}`)).toLowerCase(),
    name: String(instance.name || "River Instance"),
    type: String(instance.type || "custom"),
    version: String(instance.version || "1.21.11"),
    loader: String(instance.loader || "fabric").toLowerCase(),
    path: String(instance.path),
    createdAt: String(instance.createdAt || new Date().toISOString()),
    updatedAt: String(instance.updatedAt || new Date().toISOString())
  };
}

function readAuth() {
  try {
    const auth = JSON.parse(fs.readFileSync(authPath(), "utf8"));
    const normalized = normalizeAuth(auth);
    if (!auth.sessionExpiresAt && normalized.signedIn && normalized.microsoftRefreshToken) {
      fs.mkdirSync(path.dirname(authPath()), { recursive: true });
      fs.writeFileSync(authPath(), JSON.stringify(normalized, null, 2));
    }
    return normalized;
  } catch {
    return normalizeAuth({});
  }
}

function normalizeAuth(auth) {
  const expiresAt = Number(auth.expiresAt || 0);
  let sessionExpiresAt = Number(auth.sessionExpiresAt || 0);
  const hasRefreshTokenForMigration = Boolean(auth.microsoftRefreshToken && auth.profile);
  if (!sessionExpiresAt && hasRefreshTokenForMigration) sessionExpiresAt = Date.now() + AUTH_SESSION_MS;
  const hasRefreshSession = Boolean(auth.microsoftRefreshToken && auth.profile && sessionExpiresAt > Date.now());
  const hasLiveMinecraftToken = Boolean(auth.minecraftAccessToken && auth.profile && expiresAt > Date.now() + 60_000);
  return {
    signedIn: hasLiveMinecraftToken || hasRefreshSession,
    microsoftRefreshToken: String(auth.microsoftRefreshToken || ""),
    minecraftAccessToken: String(auth.minecraftAccessToken || ""),
    expiresAt,
    sessionExpiresAt,
    profile: auth.profile && auth.profile.id && auth.profile.name
      ? {
          id: String(auth.profile.id),
          name: String(auth.profile.name),
          skinUrl: String(auth.profile.skinUrl || ""),
          capeUrl: String(auth.profile.capeUrl || ""),
          capes: Array.isArray(auth.profile.capes) ? auth.profile.capes.map(normalizeCapeEntry).filter(Boolean) : []
        }
      : null,
    profileId: String(auth.profileId || "")
  };
}

function normalizeCapeEntry(entry) {
  if (!entry) return null;
  const id = String(entry.id || entry.capeId || "").trim();
  const url = String(entry.url || "").trim();
  if (!id && !url) return null;
  return {
    id,
    alias: String(entry.alias || entry.name || "Cape"),
    url,
    active: Boolean(entry.active || String(entry.state || "").toUpperCase() === "ACTIVE")
  };
}

function writeAuth(auth) {
  const normalized = normalizeAuth(auth);
  fs.mkdirSync(path.dirname(authPath()), { recursive: true });
  fs.writeFileSync(authPath(), JSON.stringify(normalized, null, 2));
  return normalized;
}

function clearAuth() {
  fs.rmSync(authPath(), { force: true });
  return normalizeAuth({});
}

function hasUsableMinecraftToken(auth) {
  return Boolean(auth && auth.signedIn && auth.minecraftAccessToken && auth.profile && Number(auth.expiresAt || 0) > Date.now() + 60_000);
}

function withAuthRefreshError(auth, error) {
  const tokenStillUsable = hasUsableMinecraftToken(auth);
  return {
    ...auth,
    signedIn: tokenStillUsable,
    refreshError: error?.message || "Microsoft session refresh failed. Sign in again."
  };
}

function resolvePartnerUrl(value) {
  const raw = String(value || "").trim();
  if (!raw) return "";
  try {
    return new URL(raw, partnerSiteBaseUrl).toString();
  } catch {
    return raw;
  }
}

function normalizePartnerServer(entry) {
  if (!entry || !entry.name) return null;
  return {
    name: String(entry.name || ""),
    type: String(entry.type || ""),
    ip: String(entry.ip || ""),
    discord: resolvePartnerUrl(entry.discord || ""),
    description: String(entry.description || ""),
    icon: resolvePartnerUrl(entry.icon || ""),
    partner: true
  };
}

function readBundledPartnerServers() {
  for (const candidate of [
    path.join(__dirname, "config", "partners.json"),
    path.join(app.getAppPath(), "config", "partners.json")
  ]) {
    try {
      const data = JSON.parse(fs.readFileSync(candidate, "utf8"));
      const list = Array.isArray(data) ? data : (data.partners || []);
      // Bundled icons are paths relative to app/renderer/, not site URLs, so they
      // must skip resolvePartnerUrl - the whole point is that they work offline.
      return list.map((entry) => {
        const normalized = normalizePartnerServer(entry);
        if (normalized) normalized.icon = String(entry.icon || "");
        return normalized;
      }).filter(Boolean);
    } catch {}
  }
  return [];
}

async function fetchPartnerServers() {
  try {
    const res = await fetch(partnerDataUrl, {
      headers: { "Accept": "application/json", "User-Agent": `RiverClientLauncher/${app.getVersion()} (WyZ_EU)` }
    });
    if (!res.ok) throw new Error(`partner feed responded ${res.status}`);
    const data = await res.json();
    const list = Array.isArray(data) ? data : (data.partners || []);
    const remote = list.map(normalizePartnerServer).filter(Boolean);
    // An empty remote feed means the site has not been redeployed yet, not that the
    // partnerships ended - fall back so a stale deploy never blanks the list.
    if (remote.length) return remote;
    return readBundledPartnerServers();
  } catch {
    // Offline, or the site is down: the bundled copy still lists our partners.
    return readBundledPartnerServers();
  }
}

async function fetchServerStatus(server) {
  const addr = encodeURIComponent(server.ip || "");
  try {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 4000);
    const res = await fetch(`https://api.mcsrvstat.us/3/${addr}`, { signal: controller.signal });
    clearTimeout(timer);
    if (!res.ok) throw new Error("api error");
    const data = await res.json();
    return {
      name: server.name || server.ip,
      ip: server.ip,
      online: Boolean(data.online),
      motd: data.motd?.clean?.join(" ") || server.description || "",
      icon: server.icon || data.icon || null,
      players: data.players ? { online: data.players.online, max: data.players.max } : null,
      version: data.version || "",
      type: server.type || "",
      discord: server.discord || "",
      partner: Boolean(server.partner)
    };
  } catch {
    return {
      name: server.name || server.ip,
      ip: server.ip,
      online: false,
      motd: server.description || "",
      icon: server.icon || null,
      players: null,
      version: "",
      type: server.type || "",
      discord: server.discord || "",
      partner: Boolean(server.partner)
    };
  }
}

function readSkinHistory() {
  try {
    const payload = JSON.parse(fs.readFileSync(skinHistoryPath(), "utf8"));
    return Array.isArray(payload.skins) ? payload.skins.map(normalizeSkinEntry).filter(Boolean) : [];
  } catch {
    return [];
  }
}

function writeSkinHistory(skins) {
  const normalized = skins.map(normalizeSkinEntry).filter(Boolean).slice(0, 24);
  fs.mkdirSync(path.dirname(skinHistoryPath()), { recursive: true });
  fs.writeFileSync(skinHistoryPath(), JSON.stringify({ skins: normalized }, null, 2));
  return normalized;
}

function normalizeSkinEntry(entry) {
  if (!entry || !entry.id || !entry.path) return null;
  return {
    id: String(entry.id),
    name: String(entry.name || "River skin"),
    path: String(entry.path),
    variant: entry.variant === "classic" ? "classic" : "slim",
    equippedAt: String(entry.equippedAt || new Date().toISOString()),
    uploadedAt: String(entry.uploadedAt || entry.equippedAt || new Date().toISOString())
  };
}

function getSkinHistory() {
  return readSkinHistory()
    .filter((entry) => fs.existsSync(entry.path))
    .sort((a, b) => new Date(b.equippedAt).getTime() - new Date(a.equippedAt).getTime())
    .slice(0, 12)
    .map((entry, index) => {
      const abs = path.normalize(entry.path);
      let previewDataUrl = "";
      try {
        previewDataUrl = skinPreviewDataUrl(abs);
      } catch {}
      const previewFileUrl = fs.existsSync(abs) ? pathToFileURL(abs).href : "";
      return {
        id: entry.id,
        name: entry.name,
        variant: entry.variant,
        equippedAt: entry.equippedAt,
        active: index === 0,
        previewDataUrl,
        previewFileUrl
      };
    });
}

/**
 * The Wardrobe only ever showed skins the user had chosen through the launcher itself
 * (readSkinHistory), so a brand new sign-in - which already has a real equipped skin on
 * Mojang's side via profile.skinUrl - showed up in Wardrobe as completely empty. This
 * downloads that skin once and registers it as the first history entry, so it appears
 * immediately without the user having to re-upload the skin they already own.
 *
 * Only runs when history is empty: once the user has made any choice of their own, this
 * must never overwrite or reorder it.
 */
async function ensureAccountSkinSeeded(auth) {
  try {
    if (!auth?.profile?.skinUrl || !auth?.profile?.id) return;
    if (readSkinHistory().length > 0) return;

    const id = `account-${auth.profile.id}`;
    const safeName = sanitizeFilename(auth.profile.name || "account-skin") || "account-skin";
    const target = path.join(skinStorageDir(), `${id}-${safeName}.png`);
    fs.mkdirSync(path.dirname(target), { recursive: true });
    await downloadFile(auth.profile.skinUrl, target, { timeoutMs: 15000 });

    const now = new Date().toISOString();
    writeSkinHistory([{
      id,
      name: auth.profile.name || "Current skin",
      path: target,
      variant: auth.profile.skinVariant === "classic" ? "classic" : "slim",
      equippedAt: now,
      uploadedAt: now
    }]);
  } catch (error) {
    emit("launcher:log", `[skins] Could not seed the account skin into Wardrobe: ${error.message}`);
  }
}

function skinPreviewDataUrl(filePath) {
  try {
    const buffer = fs.readFileSync(filePath);
    return `data:image/png;base64,${buffer.toString("base64")}`;
  } catch {
    return "";
  }
}

function clamp(value, min, max) {
  return Math.max(min, Math.min(max, value));
}

function findClientRoot() {
  const portableDir = process.env.PORTABLE_EXECUTABLE_DIR;
  const candidates = [
    process.env.CLIENTCORE_ROOT,
    portableDir,
    portableDir ? path.resolve(portableDir, "..") : null,
    portableDir ? path.resolve(portableDir, "..", "..") : null,
    portableDir ? path.resolve(portableDir, "..", "..", "..") : null,
    path.resolve(__dirname, "..", ".."),
    path.resolve(process.cwd(), ".."),
    path.resolve(process.cwd(), "..", ".."),
    path.resolve(path.dirname(process.execPath), "..", ".."),
    path.resolve(path.dirname(process.execPath), "..", "..", "..")
  ].filter(Boolean);

  return candidates.find((candidate) => {
    return fs.existsSync(path.join(candidate, "gradlew.bat")) &&
      fs.existsSync(path.join(candidate, "build.gradle.kts")) &&
      fs.existsSync(path.join(candidate, "src", "main", "resources", "fabric.mod.json"));
  }) || null;
}

/**
 * When each instance was last actually played, keyed by instance id.
 *
 * Derived from session history (a real launch that ended) rather than folder mtimes, so
 * opening a folder or editing a config never reads as "played". Session history is stored
 * newest-first, so the first hit per id is the most recent one.
 *
 * An instance that is running RIGHT NOW has no end time yet, and its previous session
 * would otherwise surface as a stale "played 2d ago" while the user is literally in game -
 * so it reports now, which the UI treats as just-played and hides.
 */
function lastPlayedByInstance() {
  const map = {};
  for (const session of readSessionHistory()) {
    const id = String((session && session.instanceId) || "");
    if (!id || map[id]) continue;
    const at = Date.parse(session.endTime || session.startTime || "");
    if (at) map[id] = at;
  }
  if (currentSessionStart && currentSessionInstance && currentSessionInstance.id) {
    map[currentSessionInstance.id] = Date.now();
  }
  return map;
}

function getStatus() {
  const settings = readSettings();
  const auth = readAuth();
  const playedAt = lastPlayedByInstance();
  const instances = readInstances().map((instance) => ({
    ...instance,
    lastPlayedAt: playedAt[instance.id] || 0
  }));
  const selectedInstance = instances.find((instance) => instance.selected) || instances[0] || null;
  const instanceManifest = path.join(settings.instancePath, "riv3r-instance.json");
  if (settings.instancePath) {
    try {
      ensureBundledClientCoreMod(settings.instancePath);
      stageRiverRuntimeForInstance(settings.instancePath);
    } catch {}
  }
  scheduleInstalledIconBackfill(settings.instancePath);

  return {
    root: null,
    standaloneReady: true,
    settings,
    systemMemoryMb: getSystemMemoryMb(),
    memoryLimitMb: getMemoryLimitMb(),
    recommendedMemoryMb: getRecommendedMemoryMb(),
    jarPath: "",
    // The launcher downloads Minecraft + Fabric on demand, so it is always ready to launch.
    jarReady: true,
    instancePath: settings.instancePath,
    instanceReady: fs.existsSync(settings.instancePath) && fs.existsSync(instanceManifest),
    running: Boolean(launchProcess),
    launchState,
    lastLaunchFailure,
    setupRunning,
    auth,
    authReady: auth.signedIn,
    mode: `Fabric ${settings.selectedVersion}`,
    version: readBuildVersion(),
    network: networkState,
    modules: [],
    versions: getVersions(settings),
    instances,
    selectedInstance,
    installedMods: getInstalledMods(settings.instancePath),
    installedResourcePacks: getInstalledContent(settings.instancePath, "resourcepack"),
    installedShaders: getInstalledContent(settings.instancePath, "shader"),
    packs: getLocalFiles(settings.instancePath, "resourcepacks", [".zip"]),
    shaders: getLocalFiles(settings.instancePath, "shaderpacks", [".zip"]),
    crashInfo: getCrashInfo(settings.instancePath),
    launcherUpdate: getLauncherUpdateState(),
    changelog: getChangelog(),
    skinHistory: getSkinHistory(),
    totalPlaytimeMs: readTotalPlaytimeMs(),
    sessionStartedAt: launchProcess && currentSessionStart ? currentSessionStart : null,
    discordRpc: discordRpcState,
    bridgePresence: null
  };
}

function getChangelog() {
  const fallback = {
    version: app.getVersion(),
    title: "River Client updated",
    items: ["River Client has been updated."]
  };

  try {
    const config = JSON.parse(fs.readFileSync(path.join(__dirname, "config", "changelog.json"), "utf8"));
    const releases = Array.isArray(config.releases) ? config.releases : [];
    return releases.find((release) => String(release.version) === readBuildVersion()) || releases[0] || fallback;
  } catch {
    return fallback;
  }
}

function getVersions(settings) {
  const images = {
    cherry: "https://www.minecraft.net/content/dam/minecraftnet/games/minecraft/screenshots/cherry-carousel1.jpg",
    mangrove: "https://www.minecraft.net/content/dam/minecraftnet/games/minecraft/screenshots/mangrove_carousel1.jpg",
    deepdark: "https://www.minecraft.net/content/dam/minecraftnet/games/minecraft/screenshots/dark-carousel1.jpg"
  };

  const selected = String((settings && settings.selectedVersion) || DEFAULT_MC_VERSION);
  const cached = readVersionManifest();
  const meta = {
    "1.21.11": {
      status: "Primary",
      description: "Current River Client target with official Mojang mappings.",
      image: images.cherry
    },
    "1.21.4": {
      status: "Supported",
      description: "Classic combat-era target. Fabric + River's optimization suite; River's own mod arrives with the 1.21.4 build.",
      image: images.mangrove
    }
  };

  return SUPPORTED_MC_VERSIONS.map((id) => {
    const cachedEntry = cached.find((version) => String(version.id) === id);
    const info = meta[id] || { status: "Supported", description: "Supported River target.", image: images.cherry };
    return {
      id,
      name: id,
      loader: "Fabric",
      type: cachedEntry ? cachedEntry.type : "release",
      releaseTime: cachedEntry ? cachedEntry.releaseTime : undefined,
      status: info.status,
      description: info.description,
      image: info.image,
      selected: id === selected
    };
  });
}

function readVersionManifest() {
  try {
    const manifest = JSON.parse(fs.readFileSync(versionsPath(), "utf8"));
    return Array.isArray(manifest.versions) ? manifest.versions : [];
  } catch {
    return [];
  }
}

function isCoreClientMod(file) {
  return CLIENTCORE_MOD_PATTERN.test(String(file || ""));
}

function listRiverRuntimeEntries(instancePath) {
  const runtimeDir = path.join(instancePath, "river-runtime");
  if (!fs.existsSync(runtimeDir)) return [];
  const runtimeMeta = [
    { pattern: /^clientcore-[\w.+-]*\.jar$/i, title: "River Client", author: "WyZ_EU" },
    { pattern: /^river-bootstrap-[\w.+-]*\.jar$/i, title: "River Bootstrap", author: "WyZ_EU" },
    { pattern: /^river-ingame-ui-[\w.+-]*\.jar$/i, title: "River In-Game UI", author: "WyZ_EU" }
  ];

  return fs.readdirSync(runtimeDir)
    .filter((file) => /\.jar$/i.test(file))
    .map((file) => {
      const info = runtimeMeta.find((entry) => entry.pattern.test(file));
      if (!info) return null;
      return {
        file,
        path: path.join(runtimeDir, file),
        disabled: false,
        required: true,
        runtime: true,
        metadata: {
          title: info.title,
          author: info.author,
          source: "river-runtime"
        },
        update: null,
        conflicts: []
      };
    })
    .filter(Boolean)
    .sort((a, b) => (a.metadata?.title || a.file).localeCompare(b.metadata?.title || b.file));
}

function getInstalledMods(instancePath) {
  const modsDir = path.join(instancePath, contentTypeInfo("mod").folder);
  const manifest = readModManifest(instancePath);
  if (!fs.existsSync(modsDir)) return [];
  return fs.readdirSync(modsDir)
    .filter((file) => contentTypeInfo("mod").extensions.some((ext) => file.toLowerCase().endsWith(ext)))
    .sort((a, b) => a.localeCompare(b))
    .map((file) => {
      const full = path.join(modsDir, file);
      const meta = manifest.mods[file] || null;
      // Prefer any icon the manifest already resolved (Modrinth), otherwise pull the
      // icon embedded in the jar so hand-dropped mods still get one.
      const iconUrl = (meta && meta.iconUrl) || getJarIconDataUrl(full) || "";
      return {
        file,
        path: full,
        disabled: file.toLowerCase().endsWith(".disabled"),
        required: isCoreClientMod(file),
        metadata: iconUrl ? { ...(meta || {}), iconUrl } : meta,
        update: manifest.updates[updateKey("mod", file)] || manifest.updates[file] || null,
        conflicts: getConflictsForInstalledFile(file, manifest)
      };
    });
}

function getInstalledContent(instancePath, contentType) {
  const info = contentTypeInfo(contentType);
  const target = path.join(instancePath, info.folder);
  const manifest = readModManifest(instancePath);
  const section = manifestSection(manifest, contentType);
  if (!fs.existsSync(target)) return [];
  return fs.readdirSync(target)
    .filter((file) => info.extensions.some((ext) => file.toLowerCase().endsWith(ext)))
    .sort((a, b) => a.localeCompare(b))
    .map((file) => ({
      file,
      path: path.join(target, file),
      disabled: false,
      required: false,
      metadata: section[file] || null,
      update: manifest.updates[updateKey(contentType, file)] || null,
      conflicts: []
    }));
}

function getLocalFiles(instancePath, folder, extensions) {
  const target = path.join(instancePath, folder);
  if (!fs.existsSync(target)) return [];
  return fs.readdirSync(target)
    .filter((file) => extensions.some((ext) => file.toLowerCase().endsWith(ext)))
    .sort((a, b) => a.localeCompare(b))
    .map((file) => ({ file, path: path.join(target, file) }));
}

function scheduleInstalledIconBackfill(instancePath) {
  if (!instancePath || iconBackfillRunning.has(instancePath)) return;
  iconBackfillRunning.add(instancePath);
  setTimeout(async () => {
    try {
      const changed = await backfillInstalledIcons(instancePath);
      if (changed) emitStatus();
    } catch (error) {
      emit("launcher:log", `[icons] Icon backfill failed: ${error.message}`);
    } finally {
      iconBackfillRunning.delete(instancePath);
    }
  }, 200);
}

async function backfillInstalledIcons(instancePath) {
  const manifest = readModManifest(instancePath);
  let changed = false;

  for (const contentType of ["mod", "resourcepack", "shader"]) {
    const info = contentTypeInfo(contentType);
    const section = manifestSection(manifest, contentType);
    const folder = path.join(instancePath, info.folder);
    if (!fs.existsSync(folder)) continue;

    for (const [file, metadata] of Object.entries(section)) {
      if (!metadata || (metadata.iconUrl && metadata.author)) continue;
      if (metadata.iconCheckedAt && Date.now() - Date.parse(metadata.iconCheckedAt) < 24 * 60 * 60 * 1000) continue;
      const project = await resolveInstalledProject(instancePath, contentType, file, metadata);
      metadata.iconCheckedAt = new Date().toISOString();
      if (!project) {
        changed = true;
        continue;
      }

      if (!metadata.iconUrl && project.icon_url) {
        const cachedIcon = await cacheProjectIcon(project.id || metadata.projectId || metadata.slug || file, project.icon_url);
        metadata.iconUrl = cachedIcon || project.icon_url;
        metadata.remoteIconUrl = project.icon_url;
      }
      metadata.projectId = metadata.projectId || project.id || "";
      metadata.slug = metadata.slug || project.slug || "";
      metadata.title = metadata.title || project.title || path.basename(file);
      metadata.author = metadata.author || await getModrinthProjectAuthor(project);
      changed = true;
      emit("launcher:log", `[mods] Updated metadata for ${metadata.title}.`);
    }
  }

  if (changed) writeModManifest(instancePath, manifest);
  return changed;
}

async function resolveInstalledProject(instancePath, contentType, file, metadata) {
  if (metadata.projectId || metadata.slug) {
    return getModrinthProject(metadata.projectId || metadata.slug);
  }

  if (contentType !== "mod") return null;
  const jarPath = path.join(instancePath, contentTypeInfo("mod").folder, file);
  const modInfo = readJarModInfo(jarPath);
  const query = modInfo.id || modInfo.name || metadata.title || path.basename(file, ".jar");
  return searchModrinthProjectForIcon(query, "mod");
}

function readJarModInfo(jarPath) {
  try {
    const fabric = JSON.parse(readZipTextFile(jarPath, "fabric.mod.json"));
    return {
      id: String(fabric.id || ""),
      name: String(fabric.name || ""),
      version: String(fabric.version || "")
    };
  } catch {}

  try {
    const quilt = JSON.parse(readZipTextFile(jarPath, "quilt.mod.json"));
    const meta = quilt.quilt_loader || {};
    return {
      id: String(meta.id || ""),
      name: String(meta.metadata?.name || ""),
      version: String(meta.version || "")
    };
  } catch {}

  return {};
}

// Mod icons read straight from the jar. Every Fabric mod points at a bundled PNG
// via fabric.mod.json "icon", so we don't need the network or a Modrinth match to
// show an icon - it works offline and for mods that were dropped in by hand. Cached
// by path+mtime+size so a full instance isn't re-unzipped on every status poll.
const jarIconCache = new Map();

function getJarIconDataUrl(jarPath) {
  let stat;
  try { stat = fs.statSync(jarPath); } catch { return ""; }
  const key = `${jarPath}:${stat.mtimeMs}:${stat.size}`;
  if (jarIconCache.has(key)) return jarIconCache.get(key);

  let result = "";
  try {
    const buffer = fs.readFileSync(jarPath);
    const metaText = readZipEntryBufferFromBuffer(buffer, "fabric.mod.json");
    if (metaText) {
      const meta = JSON.parse(metaText.toString("utf8"));
      let iconPath = "";
      if (typeof meta.icon === "string") {
        iconPath = meta.icon;
      } else if (meta.icon && typeof meta.icon === "object") {
        // Object form maps size -> path; take the largest available.
        const sizes = Object.keys(meta.icon).sort((a, b) => (parseInt(b, 10) || 0) - (parseInt(a, 10) || 0));
        iconPath = meta.icon[sizes[0]] || "";
      }
      if (iconPath) {
        const png = readZipEntryBufferFromBuffer(buffer, String(iconPath).replace(/^\/+/, ""));
        if (png && png.length) result = `data:image/png;base64,${png.toString("base64")}`;
      }
    }
  } catch {
    result = "";
  }

  // Keep the cache from growing without bound across many instance switches.
  if (jarIconCache.size > 512) jarIconCache.clear();
  jarIconCache.set(key, result);
  return result;
}

async function searchModrinthProjectForIcon(query, projectType) {
  const value = String(query || "").trim();
  if (!value) return null;
  const url = new URL("https://api.modrinth.com/v2/search");
  url.searchParams.set("query", value);
  url.searchParams.set("limit", "1");
  url.searchParams.set("facets", JSON.stringify([[`project_type:${projectType}`]]));
  const response = await fetch(url, {
    headers: { "User-Agent": `RiverClientLauncher/${app.getVersion()} (WyZ_EU)` }
  });
  if (!response.ok) return null;
  const body = await response.json();
  const hit = body.hits && body.hits[0];
  return hit && hit.project_id ? getModrinthProject(hit.project_id) : null;
}

async function cacheProjectIcon(projectKey, iconUrl) {
  try {
    const response = await fetch(iconUrl, {
      headers: { "User-Agent": `RiverClientLauncher/${app.getVersion()} (WyZ_EU)` }
    });
    if (!response.ok) return "";

    const contentType = String(response.headers.get("content-type") || "").toLowerCase();
    const ext = contentType.includes("webp") ? ".webp"
      : contentType.includes("jpeg") || contentType.includes("jpg") ? ".jpg"
      : contentType.includes("svg") ? ".svg"
      : ".png";
    const iconsDir = path.join(app.getPath("userData"), "icon-cache");
    fs.mkdirSync(iconsDir, { recursive: true });
    const target = path.join(iconsDir, `${sanitizeFilename(projectKey || crypto.randomUUID())}${ext}`);
    const buffer = Buffer.from(await response.arrayBuffer());
    fs.writeFileSync(target, buffer);
    return pathToFileUrl(target);
  } catch (error) {
    emit("launcher:log", `[icons] Could not cache icon ${iconUrl}: ${error.message}`);
    return "";
  }
}

function extractFirstImageUrl(value) {
  const text = String(value || "");
  const candidates = [
    ...Array.from(text.matchAll(/<img[^>]+src=["']([^"']+)["']/gi)).map((match) => match[1]),
    ...Array.from(text.matchAll(/!\[[^\]]*]\(([^)\s]+)[^)]*\)/g)).map((match) => match[1])
  ];
  return candidates.find((url) => {
    const normalized = String(url || "").toLowerCase();
    return normalized &&
      !normalized.includes("bisecthosting.com/partners") &&
      !normalized.includes("custom-banners") &&
      !normalized.includes("banner") &&
      !normalized.includes("promo");
  }) || "";
}

function pathToFileUrl(filePath) {
  return `file:///${path.resolve(filePath).replace(/\\/g, "/").replace(/^([A-Za-z]):/, "$1:").split("/").map((part, index) => index === 0 ? part : encodeURIComponent(part)).join("/")}`;
}

function getCrashInfo(instancePath) {
  const candidates = [
    path.join(instancePath, "crash-reports"),
    path.join(instancePath, "logs"),
    path.join(findClientRoot() || "", "run", "crash-reports"),
    path.join(findClientRoot() || "", "run", "logs")
  ].filter(Boolean);
  const files = [];
  for (const folder of candidates) {
    if (!fs.existsSync(folder)) continue;
    fs.readdirSync(folder)
      .filter((file) => file.endsWith(".txt") || file.endsWith(".log"))
      .forEach((file) => {
        const full = path.join(folder, file);
        const stat = fs.statSync(full);
        files.push({ file, path: full, modifiedAt: stat.mtimeMs });
      });
  }
  files.sort((a, b) => b.modifiedAt - a.modifiedAt);
  const latest = files[0] || null;
  if (!latest) return { latest: null, summary: "No crash reports found." };
  const text = fs.readFileSync(latest.path, "utf8").slice(0, 12000);
  const cause = text.match(/Caused by: ([^\r\n]+)/)?.[1] || text.match(/-- Head --[\s\S]*?\r?\n([^\r\n]+)/)?.[1] || "Open the latest report for details.";
  return { latest, files, summary: cause };
}

function getLogFiles(instancePath) {
  const candidates = [
    path.join(findClientRoot() || "", "run", "logs"),
    path.join(instancePath, "logs")
  ].filter(Boolean);
  const entries = [];
  const seen = new Set();

  for (const folder of candidates) {
    if (!fs.existsSync(folder)) continue;
    for (const file of fs.readdirSync(folder)) {
      if (!/\.(log|txt)$/i.test(file)) continue;
      const full = path.join(folder, file);
      if (seen.has(full)) continue;
      try {
        const stat = fs.statSync(full);
        if (!stat.isFile()) continue;
        entries.push({
          file,
          path: full,
          modifiedAt: stat.mtimeMs,
          size: stat.size
        });
        seen.add(full);
      } catch {}
    }
  }

  entries.sort((a, b) => b.modifiedAt - a.modifiedAt);
  return entries;
}

function readCrashReport(filePath) {
  const status = getStatus();
  const allowedRoots = [
    path.join(status.instancePath, "crash-reports"),
    path.join(status.instancePath, "logs"),
    path.join(findClientRoot() || "", "run", "crash-reports"),
    path.join(findClientRoot() || "", "run", "logs")
  ]
    .filter(Boolean)
    .map((value) => path.resolve(value));

  const target = path.resolve(String(filePath || ""));
  if (!target || !fs.existsSync(target)) return { ok: false, message: "Crash report file was not found." };
  if (!allowedRoots.some((root) => target.startsWith(root + path.sep) || target === root)) {
    return { ok: false, message: "That file is outside the allowed crash and log folders." };
  }

  const stat = fs.statSync(target);
  return {
    ok: true,
    file: path.basename(target),
    path: target,
    modifiedAt: stat.mtimeMs,
    text: fs.readFileSync(target, "utf8").slice(0, 50000)
  };
}

function readModManifest(instancePath) {
  try {
    const manifest = JSON.parse(fs.readFileSync(modManifestPath(instancePath), "utf8"));
    return {
      mods: manifest && manifest.mods && typeof manifest.mods === "object" ? manifest.mods : {},
      resourcepacks: manifest && manifest.resourcepacks && typeof manifest.resourcepacks === "object" ? manifest.resourcepacks : {},
      shaders: manifest && manifest.shaders && typeof manifest.shaders === "object" ? manifest.shaders : {},
      updates: manifest && manifest.updates && typeof manifest.updates === "object" ? manifest.updates : {},
      checkedAt: manifest ? manifest.checkedAt || "" : ""
    };
  } catch {
    return { mods: {}, resourcepacks: {}, shaders: {}, updates: {}, checkedAt: "" };
  }
}

function writeModManifest(instancePath, manifest) {
  fs.mkdirSync(instancePath, { recursive: true });
  const normalized = {
    mods: manifest.mods || {},
    resourcepacks: manifest.resourcepacks || {},
    shaders: manifest.shaders || {},
    updates: manifest.updates || {},
    checkedAt: manifest.checkedAt || ""
  };
  fs.writeFileSync(modManifestPath(instancePath), JSON.stringify(normalized, null, 2));
  return normalized;
}

const contentTypeDefinitions = {
  mod: {
    key: "mods",
    projectType: "mod",
    folder: "mods",
    extensions: [".jar", ".jar.disabled"],
    browserPath: "mod",
    label: "mod",
    labelPlural: "mods",
    usesLoader: true
  },
  resourcepack: {
    key: "resourcepacks",
    projectType: "resourcepack",
    folder: "resourcepacks",
    extensions: [".zip"],
    browserPath: "resourcepack",
    label: "resource pack",
    labelPlural: "resource packs",
    usesLoader: false
  },
  shader: {
    key: "shaders",
    projectType: "shader",
    folder: "shaderpacks",
    extensions: [".zip"],
    browserPath: "shader",
    label: "shader",
    labelPlural: "shaders",
    usesLoader: false
  }
};

function normalizeContentType(value) {
  const normalized = String(value || "mod").toLowerCase().replace(/[\s_-]/g, "");
  if (normalized === "resourcepack" || normalized === "resourcepacks" || normalized === "pack" || normalized === "packs") return "resourcepack";
  if (normalized === "shader" || normalized === "shaders" || normalized === "shaderpack" || normalized === "shaderpacks") return "shader";
  return "mod";
}

function contentTypeInfo(value) {
  return contentTypeDefinitions[normalizeContentType(value)];
}

function manifestSection(manifest, contentType) {
  const info = contentTypeInfo(contentType);
  if (!manifest[info.key] || typeof manifest[info.key] !== "object") manifest[info.key] = {};
  return manifest[info.key];
}

function updateKey(contentType, file) {
  return `${normalizeContentType(contentType)}:${file}`;
}

function getConflictsForInstalledFile(file, manifest) {
  const current = manifest.mods[file];
  if (!current || !Array.isArray(current.incompatibilities)) return [];
  const installed = Object.values(manifest.mods);
  return current.incompatibilities
    .map((incompatibility) => {
      const match = installed.find((mod) => mod.projectId && mod.projectId === incompatibility.projectId);
      return match ? { ...incompatibility, installedTitle: match.title, installedFile: match.file } : null;
    })
    .filter(Boolean);
}

function getProfiles(settings) {
  return [
    {
      id: "dev",
      name: "Developer Client",
      description: "Runs the local Fabric project with Gradle runClient.",
      selected: settings.selectedProfile === "dev",
      ready: Boolean(findClientRoot())
    },
    {
      id: "managed",
      name: "Managed Install",
      description: "Copies the remapped jar into the River Client instance folder.",
      selected: settings.selectedProfile === "managed",
      ready: fs.existsSync(findCoreClientJarInMods(settings.instancePath))
    }
  ];
}

function emit(channel, payload) {
  // The game-log window mirrors launcher:log so it keeps filling while the launcher is
  // hidden on launch (see settings.keepLauncherOpen) or minimised to tray.
  if (channel === "launcher:log" && logWindow && !logWindow.isDestroyed()) {
    logWindow.webContents.send(channel, payload);
  }
  if (!mainWindow || mainWindow.isDestroyed()) return;
  mainWindow.webContents.send(channel, payload);
}

/**
 * The standalone game-log window. Opens alongside Minecraft on launch so crashes are
 * readable without digging through files, and remembers its own size/position.
 */
function logWindowStatePath() {
  return path.join(app.getPath("userData"), "log-window.json");
}

function readLogWindowState() {
  try {
    const raw = JSON.parse(fs.readFileSync(logWindowStatePath(), "utf8"));
    const width = Number(raw.width);
    const height = Number(raw.height);
    if (!Number.isFinite(width) || !Number.isFinite(height)) return null;
    return {
      width: Math.max(520, Math.round(width)),
      height: Math.max(320, Math.round(height)),
      x: Number.isFinite(Number(raw.x)) ? Math.round(raw.x) : undefined,
      y: Number.isFinite(Number(raw.y)) ? Math.round(raw.y) : undefined,
      maximized: Boolean(raw.maximized)
    };
  } catch {
    return null;
  }
}

function saveLogWindowState() {
  if (!logWindow || logWindow.isDestroyed()) return;
  try {
    const maximized = logWindow.isMaximized();
    const bounds = maximized ? logWindow.getNormalBounds() : logWindow.getBounds();
    fs.mkdirSync(path.dirname(logWindowStatePath()), { recursive: true });
    fs.writeFileSync(logWindowStatePath(), JSON.stringify({ ...bounds, maximized }, null, 2));
  } catch {
    // A window that can't remember its size is not worth failing a launch over.
  }
}

function openLogWindow({ focus = false } = {}) {
  if (logWindow && !logWindow.isDestroyed()) {
    if (logWindow.isMinimized()) logWindow.restore();
    if (focus) logWindow.focus();
    return logWindow;
  }

  const saved = readLogWindowState();
  logWindow = new BrowserWindow({
    width: saved?.width ?? 900,
    height: saved?.height ?? 560,
    x: saved?.x,
    y: saved?.y,
    minWidth: 520,
    minHeight: 320,
    backgroundColor: "#0b0c10",
    title: "River Client - Game log",
    icon: appIcon,
    titleBarStyle: "hidden",
    show: false,
    // Opening on launch must never steal focus from the game starting up.
    focusable: true,
    webPreferences: {
      contextIsolation: false,
      nodeIntegration: true,
      sandbox: false
    }
  });

  if (saved?.maximized) logWindow.maximize();
  logWindow.loadFile(path.join(__dirname, "renderer", "logs.html"));
  logWindow.once("ready-to-show", () => {
    if (focus) logWindow.show();
    else logWindow.showInactive();
  });
  const pushMaximized = () => {
    if (logWindow && !logWindow.isDestroyed()) logWindow.webContents.send("logs:maximized", logWindow.isMaximized());
  };
  logWindow.on("maximize", pushMaximized);
  logWindow.on("unmaximize", pushMaximized);
  logWindow.on("resize", saveLogWindowState);
  logWindow.on("move", saveLogWindowState);
  logWindow.on("close", saveLogWindowState);
  logWindow.on("closed", () => { logWindow = null; });
  return logWindow;
}

async function compressPathsToRiverArchive(sourcePaths, destinationRvrPath) {
  const tempZip = path.join(os.tmpdir(), `river-archive-${Date.now()}.zip`);
  try {
    const literalPaths = sourcePaths.map((entry) => `'${escapePowerShell(entry)}'`).join(",");
    await runPowerShell([
      "-NoProfile",
      "-ExecutionPolicy", "Bypass",
      "-Command",
      `Compress-Archive -LiteralPath @(${literalPaths}) -DestinationPath '${escapePowerShell(tempZip)}' -CompressionLevel Fastest -Force`
    ]);
    if (!fs.existsSync(tempZip)) {
      throw new Error("Archive creation failed.");
    }
    if (fs.existsSync(destinationRvrPath)) {
      fs.rmSync(destinationRvrPath, { force: true });
    }
    fs.copyFileSync(tempZip, destinationRvrPath);
  } finally {
    try { fs.rmSync(tempZip, { force: true }); } catch {}
  }
}

async function extractRiverArchive(archivePath, destinationDir) {
  const needsTempCopy = archivePath.toLowerCase().endsWith(".rvr");
  const extractSource = needsTempCopy
    ? path.join(os.tmpdir(), `river-import-${Date.now()}.zip`)
    : archivePath;
  try {
    if (needsTempCopy) {
      fs.copyFileSync(archivePath, extractSource);
    }
    await runPowerShell([
      "-NoProfile",
      "-ExecutionPolicy", "Bypass",
      "-Command",
      `Expand-Archive -LiteralPath '${escapePowerShell(extractSource)}' -DestinationPath '${escapePowerShell(destinationDir)}' -Force`
    ]);
  } finally {
    if (needsTempCopy) {
      try { fs.rmSync(extractSource, { force: true }); } catch {}
    }
  }
}

function emitBoot(step, detail, done = false, error = false) {
  emit("launcher:boot", { step, detail, done, error });
}

function emitActivity(payload) {
  emit("launcher:activity", {
    id: "main",
    title: payload.title || "Working",
    detail: payload.detail || "",
    current: Number(payload.current || 0),
    total: Number(payload.total || 0),
    unit: payload.unit || "",
    percent: Number(payload.percent || 0),
    speed: payload.speed || "",
    eta: payload.eta || "",
    done: Boolean(payload.done),
    error: Boolean(payload.error)
  });
}

function emitStatus() {
  const status = getStatus();
  emit("launcher:status", status);
  scheduleDiscordPresenceRefresh();
}

ipcMain.handle("launcher:get-status", () => getStatus());

ipcMain.handle("launcher:get-lan-info", () => {
  try {
    const nets = os.networkInterfaces();
    const addresses = [];
    for (const name of Object.keys(nets)) {
      for (const net of nets[name] || []) {
        if (net && net.family === "IPv4" && !net.internal) {
          addresses.push({ name, address: net.address });
        }
      }
    }
    return { ok: true, addresses };
  } catch (e) {
    return { ok: false, addresses: [], message: e.message || "Could not read network interfaces." };
  }
});

ipcMain.handle("launcher:refresh-versions", async () => {
  const offline = await offlineResult("Version refresh");
  if (offline) return offline;
  const result = await refreshMojangVersions();
  emitStatus();
  return result;
});

ipcMain.handle("launcher:check-network", async () => {
  const result = await checkNetwork();
  emitStatus();
  return result;
});

ipcMain.handle("launcher:check-launcher-updates", async () => {
  const result = await checkLauncherUpdates();
  emitStatus();
  return result;
});

ipcMain.handle("launcher:install-launcher-update", async () => {
  return installLauncherUpdate();
});

ipcMain.handle("launcher:refresh-auth", async () => {
  const auth = await ensureFreshAuth();
  emitStatus();
  if (!hasUsableMinecraftToken(auth) && auth.refreshError) {
    return { ok: false, message: auth.refreshError, auth };
  }
  return { ok: true, message: auth.signedIn ? "Microsoft session refreshed." : "Not signed in.", auth };
});

ipcMain.handle("launcher:update-settings", (_event, patch) => {
  const next = writeSettings({ ...readSettings(), ...patch });
  try {
    app.setLoginItemSettings({ openAtLogin: Boolean(next.launchOnStartup) });
  } catch {}
  if (Object.prototype.hasOwnProperty.call(patch || {}, "uiScale")) applyUiScale(mainWindow, next);
  emitStatus();
  return next;
});

ipcMain.handle("launcher:pick-folder", async (_event, options = {}) => {
  const result = await dialog.showOpenDialog(mainWindow, {
    title: options.title || "Select folder",
    defaultPath: options.defaultPath || app.getPath("documents"),
    properties: ["openDirectory", "createDirectory"]
  });
  if (result.canceled || !result.filePaths?.[0]) return "";
  return result.filePaths[0];
});

ipcMain.handle("launcher:pick-file", async (_event, options = {}) => {
  const result = await dialog.showOpenDialog(mainWindow, {
    title: options.title || "Select file",
    defaultPath: options.defaultPath || app.getPath("documents"),
    properties: ["openFile"],
    filters: Array.isArray(options.filters) ? options.filters : undefined
  });
  if (result.canceled || !result.filePaths?.[0]) return "";
  return result.filePaths[0];
});

function detectExternalMinecraftInstances() {
  const appData = app.getPath("appData");
  const localAppData = process.env.LOCALAPPDATA || path.join(app.getPath("home"), "AppData", "Local");
  const home = app.getPath("home");
  const candidates = [
    { name: "Vanilla Minecraft", gameDir: path.join(appData, ".minecraft"), launcher: "Minecraft Launcher" },
    { name: "Modrinth profile", gameDir: path.join(appData, "ModrinthApp", "profiles"), launcher: "Modrinth App" },
    { name: "Modrinth profile", gameDir: path.join(appData, "com.modrinth.theseus", "profiles"), launcher: "Modrinth App" },
    { name: "Prism instance", gameDir: path.join(appData, "PrismLauncher", "instances"), launcher: "Prism Launcher" },
    { name: "Prism instance", gameDir: path.join(localAppData, "PrismLauncher", "instances"), launcher: "Prism Launcher" },
    { name: "MultiMC instance", gameDir: path.join(appData, "MultiMC", "instances"), launcher: "MultiMC" },
    { name: "PolyMC instance", gameDir: path.join(appData, "PolyMC", "instances"), launcher: "PolyMC" },
    { name: "ATLauncher instance", gameDir: path.join(appData, "ATLauncher", "instances"), launcher: "ATLauncher" },
    { name: "GDLauncher instance", gameDir: path.join(appData, "gdlauncher_next", "instances"), launcher: "GDLauncher" },
    { name: "CurseForge instance", gameDir: path.join(home, "curseforge", "minecraft", "Instances"), launcher: "CurseForge" },
    { name: "CurseForge instance", gameDir: path.join(home, "Documents", "curseforge", "minecraft", "Instances"), launcher: "CurseForge" },
    { name: "Technic modpack", gameDir: path.join(appData, ".technic", "modpacks"), launcher: "Technic" },
    { name: "XMCL instance", gameDir: path.join(appData, "xmcl", "instances"), launcher: "X Minecraft Launcher" },
    { name: "Feather instance", gameDir: path.join(appData, ".minecraft-feather"), launcher: "Feather" },
    { name: "Lunar profile", gameDir: path.join(home, ".lunarclient", "offline", "multiver"), launcher: "Lunar Client" },
    { name: "Badlion profile", gameDir: path.join(appData, ".minecraft", "badlion"), launcher: "Badlion" }
  ];
  const found = [];

  function pushIfInstance(gameDir, name, launcher) {
    if (!gameDir || !fs.existsSync(gameDir)) return;
    const markers = ["mods", "resourcepacks", "shaderpacks", "config", "options.txt"];
    if (!markers.some((entry) => fs.existsSync(path.join(gameDir, entry)))) return;
    const detected = detectInstanceVersionAndLoader(gameDir);
    const support = riverSupportsInstance(detected.version, detected.loader);
    found.push({
      name: String(name || path.basename(gameDir) || "Minecraft instance"),
      launcher: String(launcher || "Minecraft launcher"),
      gameDir,
      version: detected.version,
      loader: detected.loader,
      riverSupported: support.supported,
      riverWarning: support.reason
    });
  }

  for (const candidate of candidates) {
    if (!fs.existsSync(candidate.gameDir)) continue;
    pushIfInstance(candidate.gameDir, candidate.name, candidate.launcher);
    try {
      for (const child of fs.readdirSync(candidate.gameDir, { withFileTypes: true })) {
        if (!child.isDirectory()) continue;
        const childDir = path.join(candidate.gameDir, child.name);
        pushIfInstance(path.join(childDir, ".minecraft"), child.name, candidate.launcher);
        pushIfInstance(childDir, child.name, candidate.launcher);
      }
    } catch {}
  }

  const seen = new Set();
  return found.filter((entry) => {
    const key = path.resolve(entry.gameDir).toLowerCase();
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  }).slice(0, 16);
}

/**
 * Sums "time played" across every OTHER Minecraft installation this machine has, so River
 * can show a total across every client the user has played on, not just River. There is no
 * shared launcher API for this - Lunar, Feather, Prism, CurseForge etc. all keep their own
 * private formats - but the GAME ITSELF writes a universal per-world stats file no matter
 * which launcher started it (saves/<world>/stats/<uuid>.json, minecraft:play_time in ticks,
 * 20 ticks/sec; older worlds use the pre-1.17 key minecraft:play_one_minute). Summing that
 * across every world, in every external launcher's game directory, is the one signal that
 * actually works the same way for all of them.
 *
 * River's own playtime is tracked separately and more precisely (a wall-clock timer around
 * the actual launched process, see recordSessionStart/recordSessionEnd), so this function is
 * only ever used for the "other clients" half of the total.
 */
const EXTERNAL_PLAYTIME_CACHE_MS = 5 * 60 * 1000;
let externalPlaytimeCache = { at: 0, ms: 0 };

function sumWorldPlaytimeTicks(gameDir) {
  const savesDir = path.join(gameDir, "saves");
  if (!fs.existsSync(savesDir)) return 0;
  let ticks = 0;
  let worlds = [];
  try {
    worlds = fs.readdirSync(savesDir, { withFileTypes: true }).filter((e) => e.isDirectory());
  } catch {
    return 0;
  }
  for (const world of worlds) {
    const statsDir = path.join(savesDir, world.name, "stats");
    if (!fs.existsSync(statsDir)) continue;
    let files = [];
    try {
      files = fs.readdirSync(statsDir).filter((f) => f.toLowerCase().endsWith(".json"));
    } catch {
      continue;
    }
    for (const file of files) {
      try {
        const data = JSON.parse(fs.readFileSync(path.join(statsDir, file), "utf8"));
        const custom = data?.stats?.["minecraft:custom"];
        if (custom) {
          ticks += Number(custom["minecraft:play_time"] || custom["minecraft:play_one_minute"] || 0) || 0;
        } else if (typeof data?.["stat.playOneMinute"] === "number") {
          // Legacy pre-1.13 flat stat file.
          ticks += data["stat.playOneMinute"];
        }
      } catch {}
    }
  }
  return ticks;
}

function computeExternalPlaytimeMs() {
  const instances = detectExternalMinecraftInstances();
  const seenDirs = new Set();
  let ticks = 0;
  for (const instance of instances) {
    const key = path.resolve(instance.gameDir).toLowerCase();
    if (seenDirs.has(key)) continue;
    seenDirs.add(key);
    ticks += sumWorldPlaytimeTicks(instance.gameDir);
  }
  return Math.round(ticks * 50); // 20 ticks/sec -> 50ms/tick
}

function getExternalPlaytimeMs() {
  const now = Date.now();
  if (now - externalPlaytimeCache.at < EXTERNAL_PLAYTIME_CACHE_MS) return externalPlaytimeCache.ms;
  const ms = computeExternalPlaytimeMs();
  externalPlaytimeCache = { at: now, ms };
  return ms;
}

function copyExistingFolderContents(source, target) {
  if (!fs.existsSync(source)) return 0;
  fs.mkdirSync(target, { recursive: true });
  let copied = 0;
  for (const entry of fs.readdirSync(source)) {
    const from = path.join(source, entry);
    const to = path.join(target, entry);
    try {
      fs.cpSync(from, to, { recursive: true, force: true });
      copied += 1;
    } catch (error) {
      emit("launcher:log", `[import] Could not copy ${from}: ${error.message}`);
    }
  }
  return copied;
}

/**
 * The Minecraft version and mod loader an external instance is actually configured for.
 *
 * Every launcher stores this in its own metadata file, so this reads whichever one is
 * present rather than assuming. gameDir may be either the instance root or its nested
 * .minecraft, so both that folder and its parent are checked. Returns empty strings when
 * nothing conclusive is found - a wrong guess here is worse than "unknown", because the
 * caller uses this to decide whether River can run on the instance at all.
 */
function detectInstanceVersionAndLoader(gameDir) {
  const roots = [gameDir, path.dirname(String(gameDir || ""))].filter(Boolean);
  const readJson = (...parts) => {
    for (const root of roots) {
      const file = path.join(root, ...parts);
      try {
        if (fs.existsSync(file)) return JSON.parse(fs.readFileSync(file, "utf8"));
      } catch {}
    }
    return null;
  };
  const normalizeLoader = (value) => {
    const v = String(value || "").toLowerCase();
    if (v.includes("neoforge")) return "neoforge";
    if (v.includes("fabric")) return "fabric";
    if (v.includes("quilt")) return "quilt";
    if (v.includes("forge")) return "forge";
    if (v.includes("vanilla") || v.includes("minecraft")) return "vanilla";
    return "";
  };
  const isVersion = (v) => /^\d+\.\d+(\.\d+)?$/.test(String(v || "").trim());

  // Prism / MultiMC / PolyMC
  const mmc = readJson("mmc-pack.json");
  if (mmc && Array.isArray(mmc.components)) {
    let version = "";
    let loader = "";
    for (const component of mmc.components) {
      const uid = String((component && component.uid) || "");
      if (uid === "net.minecraft") version = String(component.version || "");
      else if (!loader) loader = normalizeLoader(uid);
    }
    if (version) return { version, loader: loader || "vanilla", source: "Prism/MultiMC" };
  }

  // CurseForge
  const cf = readJson("minecraftinstance.json");
  if (cf) {
    const base = cf.baseModLoader || {};
    const version = String(cf.gameVersion || base.minecraftVersion || "");
    const loader = normalizeLoader(base.name || base.forgeVersion || "");
    if (version) return { version, loader: loader || "vanilla", source: "CurseForge" };
  }

  // Modrinth App
  const modrinth = readJson("profile.json");
  if (modrinth) {
    const meta = modrinth.metadata || modrinth;
    const version = String(meta.game_version || "");
    const loader = normalizeLoader(meta.loader || "");
    if (version) return { version, loader: loader || "vanilla", source: "Modrinth" };
  }

  // GDLauncher
  const gd = readJson("config.json");
  if (gd && gd.loader) {
    const version = String(gd.loader.mcVersion || "");
    const loader = normalizeLoader(gd.loader.loaderType || "");
    if (version) return { version, loader: loader || "vanilla", source: "GDLauncher" };
  }

  // ATLauncher and XMCL both use instance.json, with different shapes
  const inst = readJson("instance.json");
  if (inst) {
    const runtime = inst.runtime || {};
    const launcherMeta = inst.launcher || {};
    const loaderVersion = launcherMeta.loaderVersion || {};
    const version = String(inst.id || runtime.minecraft || inst.minecraftVersion || "");
    let loader = normalizeLoader(loaderVersion.type || "");
    if (!loader) {
      if (runtime.fabricLoader) loader = "fabric";
      else if (runtime.quiltLoader) loader = "quilt";
      else if (runtime.neoForged) loader = "neoforge";
      else if (runtime.forge) loader = "forge";
    }
    if (isVersion(version)) return { version, loader: loader || "vanilla", source: "instance.json" };
  }

  // Older Prism/MultiMC kept the version in instance.cfg
  for (const root of roots) {
    const cfg = path.join(root, "instance.cfg");
    try {
      if (fs.existsSync(cfg)) {
        const text = fs.readFileSync(cfg, "utf8");
        const m = text.match(/^IntendedVersion=(.+)$/m);
        if (m && isVersion(m[1])) {
          const hasMods = fs.existsSync(path.join(root, ".minecraft", "mods")) || fs.existsSync(path.join(root, "mods"));
          return { version: m[1].trim(), loader: hasMods ? "fabric" : "vanilla", source: "instance.cfg" };
        }
      }
    } catch {}
  }

  // River's own exported/previously-imported instances carry their own descriptor.
  const river = readJson("riv3r-instance.json");
  if (river && isVersion(river.version)) {
    return { version: String(river.version), loader: normalizeLoader(river.loader) || "fabric", source: "riv3r-instance.json" };
  }

  // No launcher metadata (Modrinth App, for one, keeps profile metadata in a central
  // SQLite db rather than per-profile files). Fall back to artifacts the GAME itself
  // leaves in the instance folder, which work regardless of which launcher made it.
  for (const root of roots) {
    // Fabric names its remapped jars minecraft-<version>-<loader version>.
    try {
      for (const entry of fs.readdirSync(path.join(root, ".fabric", "remappedJars"))) {
        const m = String(entry).match(/^minecraft-(\d+\.\d+(?:\.\d+)?)-/);
        if (m) return { version: m[1], loader: "fabric", source: ".fabric/remappedJars" };
      }
    } catch {}

    // The launch line in latest.log records the version for both Fabric and Forge.
    try {
      const logFile = path.join(root, "logs", "latest.log");
      const handle = fs.openSync(logFile, "r");
      const buffer = Buffer.alloc(65536);
      const read = fs.readSync(handle, buffer, 0, buffer.length, 0);
      fs.closeSync(handle);
      const head = buffer.toString("utf8", 0, read);
      const fabric = head.match(/Loading Minecraft (\d+\.\d+(?:\.\d+)?) with Fabric Loader/);
      if (fabric) return { version: fabric[1], loader: "fabric", source: "logs/latest.log" };
      const fml = head.match(/--fml\.mcVersion,\s*(\d+\.\d+(?:\.\d+)?)/);
      if (fml) {
        return { version: fml[1], loader: /neoforge/i.test(head) ? "neoforge" : "forge", source: "logs/latest.log" };
      }
    } catch {}
  }

  // Nothing authoritative. A loader can still often be inferred, but a guessed VERSION is
  // exactly the failure this function exists to avoid, so that stays empty.
  let loader = "";
  for (const root of roots) {
    if (fs.existsSync(path.join(root, ".fabric"))) { loader = "fabric"; break; }
    try {
      const mods = fs.readdirSync(path.join(root, "mods"));
      if (mods.some((f) => /^fabric-api/i.test(f))) { loader = "fabric"; break; }
      if (mods.some((f) => /forge/i.test(f))) { loader = "forge"; break; }
    } catch {}
  }
  return { version: "", loader, source: "" };
}

/**
 * Whether River in-game can actually load on an instance. River ships clientcore builds
 * only for SUPPORTED_MC_VERSIONS, and only for Fabric - anything else still launches as
 * plain Minecraft, just without River in game.
 */
function riverSupportsInstance(version, loader) {
  const v = String(version || "");
  const l = String(loader || "").toLowerCase();
  if (!v) return { supported: false, reason: "River could not tell which Minecraft version this instance uses, so River will not load in game here." };
  if (l && l !== "fabric") return { supported: false, reason: "This instance uses " + l + ". River in game only works on Fabric, so it will not load here." };
  if (!SUPPORTED_MC_VERSIONS.includes(v)) return { supported: false, reason: "River has no in-game build for Minecraft " + v + " (supported: " + SUPPORTED_MC_VERSIONS.join(", ") + "), so River will not load in game here." };
  return { supported: true, reason: "" };
}

function importExternalMinecraftInstance(entry = {}) {
  const sourceDir = String(entry.gameDir || "").trim();
  if (!sourceDir || !fs.existsSync(sourceDir)) return { ok: false, message: "That instance folder was not found." };
  const now = new Date().toISOString();
  const id = `imported-${Date.now()}`;
  const name = sanitizeFilename(entry.name || path.basename(sourceDir) || "Imported Instance") || "Imported Instance";
  const targetPath = path.join(instancesRootPath(), id);
  fs.mkdirSync(targetPath, { recursive: true });
  for (const folder of ["mods", "resourcepacks", "shaderpacks", "config", "saves"]) {
    copyExistingFolderContents(path.join(sourceDir, folder), path.join(targetPath, folder));
  }
  for (const file of ["options.txt", "servers.dat"]) {
    const from = path.join(sourceDir, file);
    if (fs.existsSync(from)) {
      try { fs.copyFileSync(from, path.join(targetPath, file)); } catch {}
    }
  }
  // Keep whatever the instance actually is. Forcing every import to 1.21.11/fabric
  // mislabelled imports, which then drove the launcher to install the wrong Fabric
  // runtime and the wrong clientcore jar for them.
  const detected = detectInstanceVersionAndLoader(sourceDir);
  const version = String(entry.version || detected.version || "");
  const loader = String(entry.loader || detected.loader || "");
  const support = riverSupportsInstance(version, loader);

  // Only stage River where it can actually load; dropping a clientcore jar into a forge or
  // unsupported-version instance would just crash it on launch.
  if (support.supported) ensureBundledClientCoreMod(targetPath, version);
  writeModManifest(targetPath, { mods: {}, resourcepacks: {}, shaders: {}, updates: {}, checkedAt: "" });
  fs.writeFileSync(path.join(targetPath, "riv3r-instance.json"), JSON.stringify({
    name,
    version,
    loader,
    importedAt: now,
    source: entry.launcher || "external"
  }, null, 2));
  const instances = readInstances();
  const instance = { id, name, type: "imported", version, loader, path: targetPath, createdAt: now, updatedAt: now, riverSupported: support.supported };
  instances.push(instance);
  writeInstances(instances);
  emitStatus();
  const label = version ? (loader || "unknown loader") + " " + version : "an unrecognised version";
  return {
    ok: true,
    message: "Imported " + name + " (" + label + ").",
    version,
    loader,
    riverSupported: support.supported,
    warning: support.supported ? "" : support.reason
  };
}

ipcMain.handle("launcher:detect-external-instances", () => {
  try {
    return { ok: true, instances: detectExternalMinecraftInstances() };
  } catch (error) {
    return { ok: false, instances: [], message: error.message || "Could not scan launchers." };
  }
});

ipcMain.handle("launcher:import-external-instance", (_event, entry) => {
  try {
    return importExternalMinecraftInstance(entry);
  } catch (error) {
    return { ok: false, message: error.message || "Could not import that instance." };
  }
});

ipcMain.handle("launcher:get-performance-stats", async () => {
  const systemMemoryMb = getSystemMemoryMb();
  const freeMemoryMb = Math.floor(os.freemem() / (1024 * 1024));
  let vramUsedMb = null;
  let vramTotalMb = null;
  try {
    const gpu = await app.getGPUInfo("basic");
    const devices = Array.isArray(gpu?.gpuDevice) ? gpu.gpuDevice : [];
    const first = devices[0] || {};
    const maybeVram = Number(first.videoMemory || first.vram || 0);
    if (maybeVram > 0) vramTotalMb = maybeVram;
  } catch {}
  return {
    cpuUsagePercent: readCpuUsagePercent(),
    ramUsedMb: Math.max(0, systemMemoryMb - freeMemoryMb),
    ramTotalMb: systemMemoryMb,
    vramUsedMb,
    vramTotalMb,
    gameRunning: Boolean(launchProcess),
    fps: null
  };
});

ipcMain.handle("launcher:select-profile", (_event, profileId) => {
  const next = writeSettings({ ...readSettings(), selectedProfile: profileId });
  emitStatus();
  return next;
});

ipcMain.handle("launcher:select-version", (_event, versionId) => {
  const settings = readSettings();
  const next = writeSettings({
    ...settings,
    selectedVersion: versionId,
    modFilters: {
      ...settings.modFilters,
      version: versionId
    }
  });
  emitStatus();
  return next;
});

ipcMain.handle("launcher:select-instance", (_event, instanceId) => {
  const instances = readInstances();
  const instance = instances.find((item) => item.id === instanceId);
  if (!instance) return { ok: false, message: "Instance was not found." };
  writeSettings({
    ...readSettings(),
    instancePath: instance.path,
    selectedVersion: instance.version,
    modFilters: {
      ...readSettings().modFilters,
      version: instance.version,
      loader: instance.loader
    }
  });
  emitStatus();
  return { ok: true, message: `Selected ${instance.name}.` };
});

/**
 * Copies the user-facing settings (not content) from one instance into another:
 * options.txt (video/controls/keybinds), servers.dat (server list) and config/ (every
 * mod's settings, which is where River's own HUD layout and module config live).
 * Never overwrites anything already present in the target.
 */
function inheritInstanceSettings(sourcePath, targetPath) {
  const from = String(sourcePath || "");
  const to = String(targetPath || "");
  if (!from || !to || path.resolve(from) === path.resolve(to) || !fs.existsSync(from)) return [];
  const carried = [];
  for (const file of ["options.txt", "optionsof.txt", "servers.dat", "servers.dat_old"]) {
    const source = path.join(from, file);
    const target = path.join(to, file);
    if (!fs.existsSync(source) || fs.existsSync(target)) continue;
    try { fs.copyFileSync(source, target); carried.push(file); } catch {}
  }
  const sourceConfig = path.join(from, "config");
  const targetConfig = path.join(to, "config");
  if (fs.existsSync(sourceConfig) && !fs.existsSync(targetConfig)) {
    try { fs.cpSync(sourceConfig, targetConfig, { recursive: true }); carried.push("config"); } catch {}
  }
  return carried;
}

ipcMain.handle("launcher:create-instance", (_event, request) => {
  const version = String(request && request.version || readSettings().selectedVersion || "1.21.11").trim() || "1.21.11";
  const rawName = String(request && request.name || `River ${version}`).trim() || `River ${version}`;
  const now = new Date().toISOString();
  const safeName = sanitizeFilename(rawName).toLowerCase().replace(/\s+/g, "-");
  const id = `${safeName}-${Date.now()}`;
  const instancePath = path.join(instancesRootPath(), id);
  const instance = {
    id,
    name: rawName,
    type: "custom",
    version,
    loader: "fabric",
    path: instancePath,
    createdAt: now,
    updatedAt: now
  };

  fs.mkdirSync(path.join(instancePath, "mods"), { recursive: true });
  fs.mkdirSync(path.join(instancePath, "resourcepacks"), { recursive: true });
  fs.mkdirSync(path.join(instancePath, "shaderpacks"), { recursive: true });
  // Carry the user's settings over from the instance they are currently on, so a new
  // instance does not start from vanilla defaults with every keybind, video setting,
  // server and mod config (River's own HUD layout included) reset. Content is
  // deliberately NOT copied - a new instance starts with a clean mods/worlds set.
  const inherited = inheritInstanceSettings(readSettings().instancePath, instancePath);
  if (inherited.length) {
    emit("launcher:log", "[instance] Carried over " + inherited.join(", ") + " from the previous instance.");
  }
  writeInstances([instance, ...readInstances().filter((item) => item.id !== id)]);
  const settings = readSettings();
  writeSettings({
    ...settings,
    instancePath,
    selectedVersion: version,
    modFilters: {
      ...settings.modFilters,
      version,
      loader: "fabric"
    }
  });
  emitStatus();
  return { ok: true, message: `Created ${rawName}.` };
});

ipcMain.handle("launcher:duplicate-instance", (_event, request) => {
  const instanceId = typeof request === "string" ? request : String(request && request.instanceId || "");
  const requestedName = String(request && request.name || "").trim();
  const source = readInstances().find((item) => item.id === instanceId);
  if (!source) return { ok: false, message: "Instance was not found." };
  if (!fs.existsSync(source.path)) return { ok: false, message: "Source instance folder does not exist." };

  const now = new Date().toISOString();
  const baseName = requestedName || `${source.name} Copy`;
  const safeName = sanitizeFilename(baseName).toLowerCase().replace(/\s+/g, "-");
  const id = `${safeName}-${Date.now()}`;
  const instancePath = path.join(instancesRootPath(), id);
  fs.mkdirSync(path.dirname(instancePath), { recursive: true });
  fs.cpSync(source.path, instancePath, { recursive: true, force: true });

  const duplicate = {
    ...source,
    id,
    name: baseName,
    path: instancePath,
    type: source.type === "default" ? "custom" : source.type,
    createdAt: now,
    updatedAt: now
  };
  writeInstances([duplicate, ...readInstances()]);
  emitStatus();
  return { ok: true, message: `Duplicated ${source.name} as ${baseName}.`, instance: duplicate };
});

ipcMain.handle("launcher:create-preset-instance", async (_event, request) => {
  const offline = await offlineResult("Preset instance setup");
  if (offline) return offline;
  const presetId = String(request && request.presetId || "pvp");
  const version = String(request && request.version || readSettings().selectedVersion || "1.21.11");
  if (presetId !== "pvp") return { ok: false, message: "Unknown preset." };
  emitActivity({ title: "Creating River PvP", detail: `Preparing Fabric ${version} preset...`, current: 0, total: pvpPresetMods.length });
  try {
    const result = await createPvpInstance(version);
    emitActivity({ title: result.ok ? "River PvP ready" : "PvP setup failed", detail: result.message, current: pvpPresetMods.length, total: pvpPresetMods.length, done: true, error: !result.ok });
    emitStatus();
    return result;
  } catch (error) {
    emitActivity({ title: "PvP setup failed", detail: error.message, done: true, error: true });
    emitStatus();
    return { ok: false, message: error.message };
  }
});

ipcMain.handle("launcher:delete-instance", (_event, request) => {
  const instanceId = typeof request === "string" ? request : String(request && request.instanceId || "");
  const deleteFiles = typeof request === "object" && Boolean(request.deleteFiles);
  const instances = readInstances();
  const instance = instances.find((item) => item.id === instanceId);
  if (!instance) return { ok: false, message: "Instance was not found." };

  const remaining = instances.filter((item) => item.id !== instanceId);
  writeInstances(remaining);

  if (instance.selected) {
    const next = remaining[0];
    const settings = readSettings();
    writeSettings({
      ...settings,
      instancePath: next ? next.path : defaultInstancePath(),
      selectedVersion: next ? next.version : settings.selectedVersion,
      modFilters: {
        ...settings.modFilters,
        version: next ? next.version : settings.selectedVersion,
        loader: next ? next.loader : "fabric"
      }
    });
  }

  let fileMessage = "Files were kept on disk.";
  if (deleteFiles) {
    const root = path.resolve(instancesRootPath());
    const target = path.resolve(instance.path);
    if (!target.startsWith(root + path.sep)) {
      return { ok: false, message: "Instance was removed from the launcher list, but files were kept because the path is outside the managed instances folder." };
    }
    fs.rmSync(target, { recursive: true, force: true });
    fileMessage = "Files were deleted from the managed instances folder.";
  }

  emitStatus();
  return { ok: true, message: `Deleted ${instance.name}. ${fileMessage}` };
});

ipcMain.handle("launcher:repair-instance", async (_event, request) => {
  const instanceId = typeof request === "string" ? request : String(request && request.instanceId || "");
  const instances = readInstances();
  // Callers that are already operating on "the current instance" (the crash panel, for one)
  // legitimately have no id to pass, so an empty id means the selected instance rather than
  // a lookup failure - it used to fall straight through to "Instance was not found".
  const selectedPath = readSettings().instancePath || "";
  const instance = instanceId
    ? instances.find((item) => item.id === instanceId)
    : instances.find((item) => item.path && selectedPath && path.resolve(item.path) === path.resolve(selectedPath));
  if (!instance) return { ok: false, message: "Instance was not found." };
  fs.mkdirSync(path.join(instance.path, "mods"), { recursive: true });
  fs.mkdirSync(path.join(instance.path, "resourcepacks"), { recursive: true });
  fs.mkdirSync(path.join(instance.path, "shaderpacks"), { recursive: true });
  fs.mkdirSync(path.join(instance.path, "config"), { recursive: true });

  const currentSettings = readSettings();
  const previousInstancePath = currentSettings.instancePath;
  const previousVersion = currentSettings.selectedVersion;
  if (previousInstancePath !== instance.path || previousVersion !== instance.version) {
    writeSettings({
      ...currentSettings,
      instancePath: instance.path,
      selectedVersion: instance.version,
      modFilters: {
        ...currentSettings.modFilters,
        version: instance.version,
        loader: instance.loader || "fabric"
      }
    });
  }

  quarantineProblemMods(instance.path);
  quarantineProblemResourcePacks(instance.path);
  removeRiverInGameJars(path.join(instance.path, "mods"));
  ensureBundledClientCoreMod(instance.path);

  const supportInstall = await ensureRequiredSupportMods(
    instance.path,
    instance.version || readSettings().selectedVersion || "1.21.11",
    instance.loader || "fabric",
    "river-support-repair"
  );
  const optimization = await ensureOptimizationSuite(instance.path, instance.version || readSettings().selectedVersion || "1.21.11", instance.loader || "fabric", "river-optimization-repair");
  if (optimization.ok || (optimization.installed || []).length) {
    writeInstanceMeta(instance.path, { optimizationAppliedAt: new Date().toISOString() });
  }

  // Pull any newer versions of Modrinth-tracked content. Failures here (offline, a
  // project pulled from Modrinth) must never fail the whole repair - the folder and
  // support-mod work above already succeeded, so we just note it and carry on.
  emitActivity({ title: `Repairing ${instance.name}`, detail: "Checking mods for updates...", current: 0, total: 1 });
  let updated = [];
  let updateNote = "";
  try {
    const updateResult = await applyAvailableModUpdates(instance.path);
    updated = updateResult.updated || [];
    if (!updateResult.ok) updateNote = updateResult.message || "";
  } catch (error) {
    updateNote = `Update check skipped: ${error.message}`;
  }

  // Hard blockers (a `breaks` rule, a missing/wrong dependency, a duplicate mod id) are
  // things that genuinely stop the game loading. Soft conflicts are advisory only and
  // are reported separately so we never tell the user two working mods are incompatible.
  const compatibility = analyzeInstanceModCompatibility(instance.path);
  const blockers = compatibility.issues || [];
  const conflicts = compatibility.softConflicts || [];

  emitActivity({ title: `Repaired ${instance.name}`, detail: "Done.", current: 1, total: 1, done: true });
  emitStatus();

  const parts = [];
  if (updated.length) parts.push(`Updated ${updated.length} mod${updated.length === 1 ? "" : "s"}.`);
  if (blockers.length) parts.push(`${blockers.length} incompatibility issue${blockers.length === 1 ? "" : "s"} to look at.`);
  if (conflicts.length) parts.push(`${conflicts.length} soft-conflict note${conflicts.length === 1 ? "" : "s"}.`);
  if (updateNote) parts.push(updateNote);
  if (!parts.length) parts.push("Everything checks out.");

  return {
    ok: true,
    instanceName: instance.name,
    updated,
    blockers,
    conflicts,
    supportMessage: supportInstall.message,
    optimizationMessage: optimization.message,
    message: `Repaired ${instance.name}. ${parts.join(" ")}`
  };
});

/**
 * The "Fix" button: one pass over every known way a River launch can break.
 * Safe to run at any time and safe to run twice - every step is idempotent and
 * failures are collected instead of aborting the run, so one broken step can't
 * stop the rest from healing the install.
 */
ipcMain.handle("launcher:repair-all", async () => runFullRepair());

/** Renderer-side crashes (React error boundaries) so they reach the Logs view. */
ipcMain.handle("logs:window", (_event, action) => {
  if (!logWindow || logWindow.isDestroyed()) return false;
  if (action === "minimize") logWindow.minimize();
  else if (action === "maximize") logWindow.isMaximized() ? logWindow.unmaximize() : logWindow.maximize();
  else if (action === "close") logWindow.close();
  return true;
});

ipcMain.handle("logs:ready", () => {
  if (logWindow && !logWindow.isDestroyed()) logWindow.webContents.send("logs:maximized", logWindow.isMaximized());
  return true;
});

ipcMain.handle("logs:copy", (_event, text) => {
  clipboard.writeText(String(text || ""));
  return true;
});

ipcMain.handle("logs:open-folder", () => {
  const target = getStatus().instancePath || app.getPath("userData");
  shell.openPath(path.join(target, "logs"));
  return true;
});

ipcMain.handle("logs:open", () => {
  openLogWindow({ focus: true });
  return true;
});

ipcMain.handle("launcher:report-renderer-error", (_event, payload) => {
  const message = String(payload?.message || "Unknown renderer error");
  const stack = String(payload?.stack || "").split("\n").slice(0, 6).join("\n");
  emit("launcher:log", `[launcher] UI error: ${message}${stack ? `\n${stack}` : ""}`);
  return { ok: true };
});

async function runFullRepair() {
  if (launchProcess) return { ok: false, message: "Close Minecraft before running Fix." };

  const fixed = [];
  const failed = [];
  const step = async (label, fn) => {
    try {
      const note = await fn();
      if (note) fixed.push(note);
    } catch (error) {
      failed.push(`${label}: ${error.message}`);
      emit("launcher:log", `[fix] ${label} failed: ${error.message}`);
    }
  };

  let done = 0;
  const total = 9;
  const progress = (detail) => emitActivity({ title: "Fixing River Client", detail, current: ++done, total });

  const settings = readSettings();
  const instancePath = settings.instancePath;
  const version = settings.selectedVersion || "1.21.11";
  const loader = "fabric";
  emit("launcher:log", "[fix] Starting full repair...");

  // 1. Java: the #1 cause of "spawn java ENOENT".
  progress("Checking Java...");
  await step("Java", async () => {
    const current = settings.javaPath;
    if (current && (!fs.existsSync(current) || javaMajorVersion(current) < 21)) {
      writeSettings({ ...readSettings(), javaPath: "" });
      emit("launcher:log", `[fix] Cleared unusable saved Java path: ${current}`);
    }
    let java = resolveUsableJava(readSettings(), 21);
    if (!java) {
      emit("launcher:log", "[fix] No Java 21+ found. Installing Adoptium JRE 21...");
      const install = await installJavaRuntime(21);
      if (!install.ok || !install.javaPath) throw new Error(install.message || "Java install failed.");
      java = install.javaPath;
      return "Installed Java 21";
    }
    writeSettings({ ...readSettings(), javaPath: java });
    return `Java 21 ready (${javaMajorVersion(java)})`;
  });

  // 2. Instance folders.
  progress("Restoring folders...");
  await step("Folders", () => {
    if (!instancePath) throw new Error("No instance selected.");
    for (const dir of ["mods", "resourcepacks", "shaderpacks", "config"]) {
      fs.mkdirSync(path.join(instancePath, dir), { recursive: true });
    }
    return null;
  });

  // 3. Settings sanity: absurd memory or broken JVM args stop the JVM from ever starting.
  progress("Checking settings...");
  await step("Settings", () => {
    const s = readSettings();
    const patch = {};
    const limit = getMemoryLimitMb();
    const mem = Number(s.memoryMb || 0);
    if (!Number.isFinite(mem) || mem < 2048 || mem > limit) {
      patch.memoryMb = Math.min(Math.max(4096, 2048), Math.max(2048, limit));
    }
    // A malformed -Xmx/-Xms in custom args fights the launcher's own -Xmx.
    if (/(^|\s)-Xm[xs]/i.test(String(s.jvmArgs || ""))) {
      patch.jvmArgs = String(s.jvmArgs).replace(/(^|\s)-Xm[xs]\S*/gi, " ").replace(/\s+/g, " ").trim();
    }
    if (!Object.keys(patch).length) return null;
    writeSettings({ ...s, ...patch });
    return `Reset ${Object.keys(patch).join(", ")}`;
  });

  // 4. River's own jars (agent + clientcore) - stale/duplicate copies crash on load.
  progress("Reinstalling River client...");
  await step("River client", () => {
    if (!instancePath) throw new Error("No instance selected.");
    removeRiverInGameJars(path.join(instancePath, "mods"));
    const result = ensureBundledClientCoreMod(instancePath);
    if (result && result.ok === false) throw new Error(result.message || "clientcore install failed.");
    return "Reinstalled River clientcore";
  });

  // 5. Known-bad and duplicate mods / resource packs.
  progress("Removing broken mods...");
  await step("Mods", () => {
    if (!instancePath) throw new Error("No instance selected.");
    const mods = quarantineProblemMods(instancePath) || [];
    const packs = quarantineProblemResourcePacks(instancePath) || [];
    const n = (Array.isArray(mods) ? mods.length : 0) + (Array.isArray(packs) ? packs.length : 0);
    return n ? `Quarantined ${n} problem file(s)` : null;
  });

  // 6. Zero-byte / truncated downloads never re-download on their own.
  progress("Clearing corrupt downloads...");
  await step("Corrupt files", () => {
    let removed = 0;
    const sweep = (dir) => {
      if (!dir || !fs.existsSync(dir)) return;
      for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
        const full = path.join(dir, entry.name);
        if (entry.isDirectory()) sweep(full);
        else if (/\.(jar|zip)$/i.test(entry.name)) {
          try { if (fs.statSync(full).size === 0) { fs.rmSync(full, { force: true }); removed++; } } catch {}
        }
      }
    };
    const runtimeRoot = path.join(app.getPath("userData"), "minecraft-runtime");
    sweep(path.join(runtimeRoot, "libraries"));
    sweep(path.join(runtimeRoot, "versions"));
    if (instancePath) sweep(path.join(instancePath, "mods"));
    return removed ? `Deleted ${removed} corrupt file(s)` : null;
  });

  // 7. Natives: a half-extracted natives dir is a classic hard crash on startup.
  progress("Rebuilding natives...");
  await step("Natives", () => {
    const nativesDir = path.join(app.getPath("userData"), "minecraft-runtime", "natives", `${version}-${fabricLoaderVersion}`);
    if (fs.existsSync(nativesDir)) {
      fs.rmSync(nativesDir, { recursive: true, force: true });
      return "Rebuilt native libraries";
    }
    return null;
  });

  // 8. Stale lock files from a crashed session block the next launch.
  progress("Clearing stale locks...");
  await step("Locks", () => {
    if (!instancePath) return null;
    let removed = 0;
    for (const rel of ["session.lock", ".fabric/lock", "usercache.json.lock"]) {
      const full = path.join(instancePath, rel);
      try { if (fs.existsSync(full)) { fs.rmSync(full, { force: true }); removed++; } } catch {}
    }
    return removed ? `Cleared ${removed} stale lock(s)` : null;
  });

  // 9. Support + optimization mods (network; last so offline users still get the rest).
  progress("Checking required mods...");
  await step("Support mods", async () => {
    if (!instancePath) throw new Error("No instance selected.");
    const support = await ensureRequiredSupportMods(instancePath, version, loader, "river-fix");
    const optimization = await ensureOptimizationSuite(instancePath, version, loader, "river-fix");
    const n = (support.installed || []).length + (optimization.installed || []).length;
    return n ? `Installed ${n} required mod file(s)` : "Required mods present";
  });

  lastLaunchFailure = null;
  emitStatus();

  const ok = failed.length === 0;
  const message = ok
    ? `Fixed: ${fixed.join(" · ") || "everything already looked healthy"}. Try launching again.`
    : `Repaired ${fixed.length} thing(s), but ${failed.length} step(s) failed: ${failed.join(" | ")}`;
  emitActivity({ title: ok ? "River Client fixed" : "Fix finished with problems", detail: message, current: total, total, done: true, error: !ok });
  emit("launcher:log", `[fix] ${message}`);
  return { ok, message, fixed, failed };
}

ipcMain.handle("launcher:microsoft-login", async () => {
  const offline = await offlineResult("Microsoft login");
  if (offline) return offline;
  const result = await signInWithMicrosoft();
  emitStatus();
  return result;
});

ipcMain.handle("launcher:microsoft-logout", () => {
  const auth = clearAuth();
  emitStatus();
  return { ok: true, message: "Signed out.", auth };
});

ipcMain.handle("launcher:choose-skin", async (_event, variant) => {
  const auth = await ensureFreshAuth();
  if (!hasUsableMinecraftToken(auth)) return { ok: false, message: auth.refreshError || "Sign in with Microsoft before changing skins." };
  const offline = await offlineResult("Skin upload");
  if (offline) return offline;

  const selection = await dialog.showOpenDialog(mainWindow, {
    title: "Choose Minecraft skin PNG",
    properties: ["openFile"],
    filters: [{ name: "Minecraft skin PNG", extensions: ["png"] }]
  });
  if (selection.canceled || !selection.filePaths.length) return { ok: false, message: "Skin upload cancelled." };

  try {
    return await saveAndUploadSkin(selection.filePaths[0], variant, auth);
  } catch (error) {
    return { ok: false, message: error.message };
  } finally {
    emitStatus();
  }
});

ipcMain.handle("launcher:equip-skin", async (_event, skinId) => {
  const auth = await ensureFreshAuth();
  if (!hasUsableMinecraftToken(auth)) return { ok: false, message: auth.refreshError || "Sign in with Microsoft before equipping skins." };
  const offline = await offlineResult("Skin equip");
  if (offline) return offline;

  const history = readSkinHistory();
  const entry = history.find((skin) => skin.id === skinId);
  if (!entry || !fs.existsSync(entry.path)) return { ok: false, message: "That skin file is not available anymore." };

  try {
    await uploadMinecraftSkin(auth.minecraftAccessToken, entry.path, entry.variant);
    const equippedAt = new Date().toISOString();
    const next = [{ ...entry, equippedAt }, ...history.filter((skin) => skin.id !== skinId)];
    writeSkinHistory(next);
    const profile = await fetchMinecraftProfile(auth.minecraftAccessToken);
    writeAuth({ ...auth, profile });
    return { ok: true, message: `Equipped ${entry.name}.` };
  } catch (error) {
    return { ok: false, message: error.message };
  } finally {
    emitStatus();
  }
});

ipcMain.handle("launcher:remove-skin", (_event, skinId) => {
  const history = readSkinHistory();
  const entry = history.find((skin) => skin.id === skinId);
  if (!entry) return { ok: false, message: "Skin entry not found." };
  fs.rmSync(entry.path, { force: true });
  writeSkinHistory(history.filter((skin) => skin.id !== skinId));
  emitStatus();
  return { ok: true, message: `Removed ${entry.name} from saved skins.` };
});

ipcMain.handle("launcher:update-skin-entry", async (_event, patch = {}) => {
  const skinId = String(patch.skinId || "").trim();
  const history = readSkinHistory();
  const index = history.findIndex((skin) => skin.id === skinId);
  if (index < 0) return { ok: false, message: "Skin entry not found." };

  const current = history[index];
  const next = { ...current };
  if (typeof patch.name === "string") {
    next.name = patch.name.trim().slice(0, 40) || current.name;
  }
  if (patch.variant === "classic" || patch.variant === "slim") {
    next.variant = patch.variant;
  }

  const nextHistory = [...history];
  nextHistory[index] = next;
  const isActive = index === 0;

  try {
    if (isActive && next.variant !== current.variant) {
      const auth = await ensureFreshAuth();
      if (!hasUsableMinecraftToken(auth)) {
        return { ok: false, message: auth.refreshError || "Sign in with Microsoft before changing the active skin model." };
      }
      if (!fs.existsSync(next.path)) return { ok: false, message: "That skin file is not available anymore." };
      await uploadMinecraftSkin(auth.minecraftAccessToken, next.path, next.variant);
      const profile = await fetchMinecraftProfile(auth.minecraftAccessToken);
      writeAuth({ ...auth, profile });
      nextHistory[index] = { ...next, equippedAt: new Date().toISOString() };
    }
    writeSkinHistory(nextHistory);
    emitStatus();
    return { ok: true, message: "Skin updated." };
  } catch (error) {
    emitStatus();
    return { ok: false, message: error.message || "Skin update failed." };
  }
});

ipcMain.handle("launcher:export-skin", async (_event, skinId) => {
  const entry = readSkinHistory().find((skin) => skin.id === skinId);
  if (!entry || !fs.existsSync(entry.path)) return { ok: false, message: "That skin file is not available anymore." };
  const { filePath } = await dialog.showSaveDialog(mainWindow, {
    title: "Export Minecraft skin",
    defaultPath: `${sanitizeFilename(entry.name || "river-skin")}.png`,
    filters: [{ name: "PNG image", extensions: ["png"] }]
  });
  if (!filePath) return { ok: false, message: "Cancelled." };
  const target = filePath.toLowerCase().endsWith(".png") ? filePath : `${filePath}.png`;
  fs.copyFileSync(entry.path, target);
  return { ok: true, message: `Exported ${path.basename(target)}.` };
});

ipcMain.handle("launcher:equip-cape", async (_event, capeId) => {
  const auth = await ensureFreshAuth();
  if (!hasUsableMinecraftToken(auth)) return { ok: false, message: auth.refreshError || "Sign in with Microsoft before changing capes." };
  const offline = await offlineResult("Cape equip");
  if (offline) return offline;

  try {
    await setActiveMinecraftCape(auth.minecraftAccessToken, capeId);
    const profile = await fetchMinecraftProfile(auth.minecraftAccessToken);
    writeAuth({ ...auth, profile });
    return { ok: true, message: "Cape equipped." };
  } catch (error) {
    return { ok: false, message: error.message };
  } finally {
    emitStatus();
  }
});

ipcMain.handle("launcher:clear-cape", async () => {
  const auth = await ensureFreshAuth();
  if (!hasUsableMinecraftToken(auth)) return { ok: false, message: auth.refreshError || "Sign in with Microsoft before changing capes." };
  const offline = await offlineResult("Cape clear");
  if (offline) return offline;

  try {
    await clearActiveMinecraftCape(auth.minecraftAccessToken);
    const profile = await fetchMinecraftProfile(auth.minecraftAccessToken);
    writeAuth({ ...auth, profile });
    return { ok: true, message: "Cape hidden." };
  } catch (error) {
    return { ok: false, message: error.message };
  } finally {
    emitStatus();
  }
});

ipcMain.handle("launcher:window", (_event, action) => {
  if (!mainWindow) return false;
  if (action === "minimize") mainWindow.minimize();
  if (action === "maximize") {
    if (mainWindow.isMaximized()) mainWindow.unmaximize();
    else mainWindow.maximize();
  }
  if (action === "close") mainWindow.close();
  return true;
});

ipcMain.handle("launcher:get-session-history", () => {
  return readSessionHistory();
});

/**
 * River's own playtime is cheap (one number already in status), but scanning every other
 * launcher's save folders on disk is comparatively slow, so it lives behind its own
 * on-demand call instead of being folded into the frequently-polled getStatus() payload.
 */
ipcMain.handle("launcher:get-playtime-summary", async () => {
  const riverMs = readTotalPlaytimeMs();
  const externalMs = await new Promise((resolve) => {
    setImmediate(() => resolve(getExternalPlaytimeMs()));
  });
  return { riverMs, externalMs, combinedMs: riverMs + externalMs };
});

ipcMain.handle("launcher:get-recent-servers", async () => {
  const settings = readSettings();
  const instancePath = settings.instancePath;
  if (!instancePath) return [];

  const options = parseOptionsTxt(instancePath);
  const lastServer = String(options.lastServer || "").trim();

  const datPath = path.join(instancePath, "servers.dat");
  let recent = parseServersDat(datPath)
    .map((srv) => ({
      name: srv.name || srv.ip || "",
      ip: String(srv.ip || "").trim(),
      icon: srv.icon || null,
      description: "",
      type: "",
      discord: "",
      partner: false
    }))
    .filter((srv) => srv.ip);

  const seen = new Set();
  recent = recent.filter((srv) => {
    const key = srv.ip.toLowerCase();
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });

  const lastKey = lastServer.toLowerCase();
  if (lastServer && !recent.some((s) => s.ip.toLowerCase() === lastKey)) {
    recent.unshift({
      name: lastServer,
      ip: lastServer,
      icon: null,
      description: "",
      type: "Recent",
      discord: "",
      partner: false
    });
  }

  if (lastServer) {
    recent.sort((a, b) => {
      const am = a.ip.toLowerCase() === lastKey;
      const bm = b.ip.toLowerCase() === lastKey;
      if (am && !bm) return -1;
      if (!am && bm) return 1;
      return 0;
    });
  }

  const limited = recent.slice(0, 15);
  if (!limited.length) return [];

  const results = await Promise.allSettled(limited.map(fetchServerStatus));
  return results.map((result) => result.status === "fulfilled" ? result.value : null).filter(Boolean);
});

ipcMain.handle("launcher:get-news", async () => {
  try {
    const controller = new AbortController();
    setTimeout(() => controller.abort(), 4000);
    const res = await fetch("https://updates.riverclient.xyz/news.json", {
      signal: controller.signal,
      headers: { "Accept": "application/json", "User-Agent": launcherUserAgent }
    });
    if (!res.ok) return [];
    const payload = await res.json();
    return Array.isArray(payload) ? payload : [];
  } catch { return []; }
});

ipcMain.handle("launcher:read-crash-report", (_event, filePath) => {
  return readCrashReport(filePath);
});

ipcMain.handle("launcher:get-log-files", () => {
  const status = getStatus();
  return getLogFiles(status.instancePath);
});

ipcMain.handle("launcher:reset-settings", () => {
  const next = writeSettings({ ...defaults });
  emitStatus();
  return { ok: true, message: "Settings reset to defaults.", settings: next };
});

ipcMain.handle("launcher:get-storage-info", () => {
  const status = getStatus();
  function dirSize(dir) {
    if (!dir || !fs.existsSync(dir)) return 0;
    let total = 0;
    function walk(d) {
      let entries;
      try { entries = fs.readdirSync(d); } catch { return; }
      for (const e of entries) {
        const full = path.join(d, e);
        let stat;
        try { stat = fs.statSync(full); } catch { continue; }
        if (stat.isDirectory()) walk(full);
        else total += stat.size;
      }
    }
    walk(dir);
    return total;
  }
  const userData = app.getPath("userData");
  const logsDir = status.root ? path.join(status.root, "run", "logs") : null;
  const cacheDir = path.join(userData, "cache");
  return {
    logsPath: logsDir,
    instancePath: status.instancePath,
    cachePath: cacheDir,
    logsSize: dirSize(logsDir),
    instanceSize: dirSize(status.instancePath),
    cacheSize: dirSize(cacheDir)
  };
});

ipcMain.handle("launcher:clear-logs", () => {
  const status = getStatus();
  const logsDir = status.root ? path.join(status.root, "run", "logs") : null;
  if (!logsDir || !fs.existsSync(logsDir)) return { ok: false, message: "Logs folder not found." };
  let cleared = 0;
  for (const file of fs.readdirSync(logsDir)) {
    if (/\.(log|gz|txt)$/.test(file)) {
      try { fs.unlinkSync(path.join(logsDir, file)); cleared++; } catch {}
    }
  }
  return { ok: true, message: `Cleared ${cleared} log file${cleared !== 1 ? "s" : ""}.` };
});

ipcMain.handle("launcher:delete-log-file", (_event, filePath) => {
  const target = String(filePath || "");
  if (!target || !fs.existsSync(target)) return { ok: false, message: "Log file not found." };
  fs.rmSync(target, { force: true });
  return { ok: true, message: `${path.basename(target)} deleted.` };
});

ipcMain.handle("launcher:export-log-file", async (_event, filePath) => {
  const target = String(filePath || "");
  if (!target || !fs.existsSync(target)) return { ok: false, message: "Log file not found." };
  const selection = await dialog.showSaveDialog(mainWindow, {
    title: "Export log file",
    defaultPath: path.basename(target)
  });
  if (selection.canceled || !selection.filePath) return { ok: false, message: "Export cancelled." };
  fs.copyFileSync(target, selection.filePath);
  return { ok: true, message: `Saved to ${selection.filePath}.` };
});

ipcMain.handle("launcher:clear-cache", () => {
  const cacheDir = path.join(app.getPath("userData"), "cache");
  let cleared = 0;
  if (fs.existsSync(cacheDir)) {
    for (const entry of fs.readdirSync(cacheDir)) {
      try { fs.rmSync(path.join(cacheDir, entry), { recursive: true, force: true }); cleared++; } catch {}
    }
  }
  return { ok: true, message: `Cleared ${cleared} cache item${cleared !== 1 ? "s" : ""}.` };
});

ipcMain.handle("launcher:open-folder", async (_event, folder) => {
  const status = getStatus();
  const targets = {
    root: status.root,
    appdata: app.getPath("userData"),
    mods: status.root ? path.join(status.root, "build", "libs") : path.dirname(status.jarPath || ""),
    instance: status.instancePath,
    logs: status.root ? path.join(status.root, "run", "logs") : null,
    resourcepacks: path.join(status.instancePath, "resourcepacks"),
    shaderpacks: path.join(status.instancePath, "shaderpacks"),
    crashes: status.crashInfo && status.crashInfo.latest ? path.dirname(status.crashInfo.latest.path) : path.join(status.instancePath, "crash-reports")
  };

  const target = targets[folder];
  if (!target) return false;
  fs.mkdirSync(target, { recursive: true });
  await shell.openPath(target);
  return true;
});

ipcMain.handle("launcher:open-path", async (_event, targetPath) => {
  const target = String(targetPath || "");
  if (!target || !fs.existsSync(target)) return false;
  await shell.openPath(target);
  return true;
});

ipcMain.handle("launcher:launch-client", async (_event, opts = {}) => {
  const joinAddress = opts && typeof opts.joinAddress === "string" ? opts.joinAddress.trim() : "";
  if (launchProcess) return { ok: false, message: "Minecraft is already launching." };
  const failLaunch = (message, extra = {}) => {
    lastLaunchFailure = {
      message: String(message || "Minecraft could not start."),
      at: new Date().toISOString(),
      supportUrl: "https://discord.riverclient.xyz"
    };
    setLaunchState("idle");
    emitActivity({ title: "Launch failed", detail: lastLaunchFailure.message, current: 1, total: 1, done: true, error: true });
    emitStatus();
    return { ok: false, message: lastLaunchFailure.message, ...extra };
  };

  lastLaunchFailure = null;
  setLaunchState("launching");
  emitActivity({ title: "Launching River Client", detail: "Checking launcher and account...", current: 1, total: 6 });

  // Bring the game log up the instant Launch is pressed so the user watches every prep
  // step happen live, instead of the window only appearing once Minecraft finally spawns.
  if (readSettings().showGameLogWindow !== false) openLogWindow({ focus: true });

  try {
    if (setupRunning) return failLaunch("River Client is still preparing. Watch the progress panel.");
    const launcherUpdate = await checkLauncherUpdates();
    if (launcherUpdate.blocking) return failLaunch(launcherUpdate.message);

    const launchAuth = await ensureFreshAuth();
    const status = getStatus();
    const devOffline = Boolean(status.settings.developerOfflineMode);
    if (!hasUsableMinecraftToken(launchAuth) && !devOffline) {
      return failLaunch(launchAuth.refreshError || "Sign in with Microsoft before launching River Client, or enable Developer Offline Test Mode in Settings.");
    }

    emitActivity({ title: "Launching River Client", detail: "Checking installed content...", current: 2, total: 6 });
    emit("launcher:log", "[launcher] Checking the instance for incompatible or duplicate mods before launch...");
    quarantineProblemMods(status.instancePath);
    quarantineProblemResourcePacks(status.instancePath);

    emitActivity({ title: "Launching River Client", detail: "Syncing River Client files...", current: 3, total: 6 });
    const modsDir = path.join(status.instancePath, "mods");
    removeRiverInGameJars(modsDir);
    ensureBundledClientCoreMod(status.instancePath, status.settings.selectedVersion || DEFAULT_MC_VERSION);
    const baselineSync = ensureRiverBaselineForInstance(status.instancePath, status.settings.selectedVersion || "1.21.11", "fabric");
    emit("launcher:log", `[launcher] ${baselineSync.message}`);

    emitActivity({ title: "Launching River Client", detail: "Checking required dependencies...", current: 4, total: 6 });
    const supportInstall = await ensureRequiredSupportMods(status.instancePath, status.settings.selectedVersion || "1.21.11", "fabric", "river-support-launch");
    if (!supportInstall.ok && !(supportInstall.installed || []).length) {
      return failLaunch(`River could not prepare required support mods. ${supportInstall.message}`);
    }

    if (shouldRunOptimizationSuite(status.instancePath)) {
      const optimization = await ensureOptimizationSuite(status.instancePath, status.settings.selectedVersion || "1.21.11", "fabric", "river-optimization-launch");
      if (!optimization.ok && !(optimization.installed || []).length) {
        return failLaunch(`River could not prepare the optimization suite. ${optimization.message}`);
      }
      writeInstanceMeta(status.instancePath, { optimizationAppliedAt: new Date().toISOString() });
    }

    emitActivity({ title: "Launching River Client", detail: "Checking compatibility...", current: 5, total: 6 });
    const modCompatibility = analyzeInstanceModCompatibility(status.instancePath);
    if (modCompatibility.issues.length) {
      const message = `${modCompatibility.issues.length} confirmed mod compatibility issue${modCompatibility.issues.length === 1 ? "" : "s"} must be fixed before launch.`;
      return failLaunch(message, { requiresAction: "mod-incompatibility", issues: modCompatibility.issues });
    }

    writeInstanceMeta(status.instancePath, {
      name: "River Client",
      author: "WyZ_EU",
      minecraft: status.settings.selectedVersion || DEFAULT_MC_VERSION,
      loader: "0.19.2",
      installedAt: new Date().toISOString(),
      launchMode: "managed-folder"
    });
    emitActivity({ title: "Launching River Client", detail: "Starting Minecraft...", current: 6, total: 6 });
    emitStatus();
    if (joinAddress) emit("launcher:log", `[launcher] Joining server after launch: ${joinAddress}`);
    const result = await launchStandaloneMinecraft(status, joinAddress);
    return result.ok ? result : failLaunch(result.message);
  } catch (error) {
    return failLaunch(error.message || "River Client could not finish launch preparation.");
  }
});

ipcMain.handle("launcher:stop-client", async () => {
  if (!launchProcess) return { ok: false, message: "No River Client process is running." };
  launchProcess.kill();
  launchProcess = null;
  emitStatus();
  return { ok: true, message: "Stopped launch process." };
});

async function launchStandaloneMinecraft(status, joinAddress = "") {
  const freshAuth = await ensureFreshAuth();
  const authenticated = hasUsableMinecraftToken(freshAuth);
  if (!authenticated && !status.settings.developerOfflineMode) {
    return { ok: false, message: freshAuth.refreshError || "Sign in with Microsoft before launching River Client, or enable Developer Offline Test Mode in Settings." };
  }
  let launch;
  try {
    launch = await prepareStandaloneMinecraftLaunch(status, freshAuth, authenticated, joinAddress);
  } catch (error) {
    emitActivity({ title: "Preparing Minecraft", detail: error.message || "Minecraft preparation failed.", done: true, error: true });
    return { ok: false, message: `Minecraft preparation failed: ${error.message}` };
  }
  if (!launch.ok) return launch;

  // Preflight: resolve a REAL Java 21+ before spawning. Without this we'd spawn the
  // bare command "java", which dies with ENOENT on every machine that doesn't have
  // Java on PATH. Auto-install one if none exists.
  let javaPath = resolveUsableJava(status.settings, 21);
  if (!javaPath) {
    emitActivity({ title: "Preparing Minecraft", detail: "Java 21 not found. Installing it now...", current: 0, total: 4 });
    emit("launcher:log", "[launcher] No Java 21+ found on this machine. Auto-installing Adoptium JRE 21.");
    const install = await installJavaRuntime(21).catch((e) => ({ ok: false, message: e.message }));
    if (install.ok && install.javaPath) {
      javaPath = install.javaPath;
    } else {
      const msg = "River needs Java 21 and it could not be installed automatically. Use Fix, or install Java 21 (Adoptium Temurin) and launch again.";
      lastLaunchFailure = { message: msg, at: new Date().toISOString(), fixable: true, supportUrl: "https://discord.riverclient.xyz" };
      emitActivity({ title: "Cannot launch", detail: msg, done: true, error: true });
      emit("launcher:log", `[launcher] Java install failed: ${install.message || "unknown"}`);
      emitStatus();
      return { ok: false, message: msg, fixable: true };
    }
  }
  // Remember it so the next launch skips the probing entirely.
  if (status.settings.javaPath !== javaPath) {
    writeSettings({ ...readSettings(), javaPath });
    status.settings.javaPath = javaPath;
  }
  emit("launcher:log", `[launcher] Using Java: ${javaPath} (major ${javaMajorVersion(javaPath)})`);

  // java.exe is a console application: Windows gives it a console window, and closing
  // that window kills the whole process tree (the game dies with it). javaw.exe is the
  // windowless build of the same JVM, so the game runs with no terminal at all. Only
  // fall back to java.exe if javaw is missing, or the user asked to see the console.
  const launchExe = status.settings.showConsoleOnLaunch ? javaPath : (javawFor(javaPath) || javaPath);
  if (launchExe !== javaPath) emit("launcher:log", `[launcher] Launching without a console via ${path.basename(launchExe)}.`);

  try {
    launchProcess = spawn(launchExe, launch.args, {
      cwd: status.instancePath,
      windowsHide: !status.settings.showConsoleOnLaunch,
      env: {
        ...process.env
      }
    });
  } catch (spawnError) {
    const msg = `Could not start Java: ${spawnError.message}`;
    lastLaunchFailure = { message: msg, at: new Date().toISOString(), fixable: true, supportUrl: "https://discord.riverclient.xyz" };
    emitActivity({ title: "Cannot launch", detail: msg, done: true, error: true });
    emit("launcher:log", `[launcher] ${msg}`);
    setLaunchState("idle");
    emitStatus();
    return { ok: false, message: msg, fixable: true };
  }

  recordSessionStart(status.instances?.find(i => i.selected) || status.instances?.[0]);
  // Opened before the first log line so nothing is missed, and inactive so it never
  // steals focus from the game window coming up.
  if (status.settings.showGameLogWindow !== false) openLogWindow({ focus: false });
  try { nowPlaying.start(); } catch {}
  setLaunchState("launching");
  scheduleRunningLaunchState();
  emit("launcher:log", `[launcher] Starting standalone Fabric ${status.settings.selectedVersion}...`);
  emit("launcher:log", `[launcher] Game directory: ${status.instancePath}`);
  emit("launcher:log", `[launcher] Memory target: ${status.settings.memoryMb} MB`);
  if (authenticated) emit("launcher:log", `[launcher] Signed in as ${freshAuth.profile.name}`);
  else emit("launcher:log", `[launcher] Developer Offline Test Mode enabled as ${status.settings.offlineName}.`);
  attachLaunchProcess(status);
  emitActivity({ title: "River Client started", detail: "Minecraft is opening.", current: 1, total: 1, done: true });
  emitStatus();
  if (!status.settings.keepLauncherOpen || status.settings.closeOnLaunch) mainWindow.hide();
  return { ok: true, message: "Launching River Client..." };
}

function attachLaunchProcess(status) {
  if (!launchProcess) return;
  launchProcess.stdout?.on("data", (chunk) => {
    const text = chunk.toString();
    promoteLaunchStateFromOutput(text);
    emit("launcher:log", text);
  });
  launchProcess.stderr?.on("data", (chunk) => {
    const text = chunk.toString();
    promoteLaunchStateFromOutput(text);
    emit("launcher:log", text);
  });
  launchProcess.on("error", (error) => {
    emit("launcher:log", `[launcher] Failed to launch: ${error.message}`);
    lastLaunchFailure = {
      message: error.code === "ENOENT"
        ? "Minecraft could not start: Java could not be found. Click Fix to install it automatically."
        : `Minecraft could not start: ${error.message}`,
      at: new Date().toISOString(),
      fixable: true,
      supportUrl: "https://discord.riverclient.xyz"
    };
    emitActivity({ title: "Launch failed", detail: lastLaunchFailure.message, current: 1, total: 1, done: true, error: true });
    launchProcess = null;
    setLaunchState("idle");
    emitStatus();
  });
  launchProcess.on("exit", (code) => {
    emit("launcher:log", `[launcher] Minecraft process exited with code ${code ?? "unknown"}.`);
    recordSessionEnd();
    if (code !== 0 && code !== null) {
      const crash = getCrashInfo(status.instancePath);
      lastLaunchFailure = {
        message: `Minecraft exited with code ${code}. ${crash.summary || "Check Logs for the crash details."}`.trim(),
        at: new Date().toISOString(),
        fixable: true,
        supportUrl: "https://discord.riverclient.xyz"
      };
      emitActivity({ title: "Minecraft closed with an error", detail: lastLaunchFailure.message, current: 1, total: 1, done: true, error: true });
    }
    launchProcess = null;
    // The now-playing feed only exists for the in-game module, so it stops with the game.
    try { nowPlaying.stop(); } catch {}
    setLaunchState("idle");
    emitStatus();
  });
}

ipcMain.handle("launcher:microsoft-status", () => ({
  available: true,
  clientConfigured: Boolean(getMicrosoftClientId()),
  auth: readAuth(),
  message: getMicrosoftClientId()
    ? "Microsoft sign-in is ready."
    : "River Client login is not configured in the launcher build yet."
}));

ipcMain.handle("launcher:search-mods", async (_event, request) => {
  const offline = await offlineResult("Mod search");
  if (offline) return { ...offline, results: [] };

  const settings = readSettings();
  const filters = {
    ...settings.modFilters,
    ...request
  };
  writeSettings({ ...settings, modFilters: filters });

  if (filters.source === "curseforge") {
    const cfKey = effectiveCurseForgeKey(settings);
    if (!cfKey) {
      return { ok: true, source: "curseforge", requiresKey: true, url: curseForgeSearchUrl(filters), results: [] };
    }
    return searchCurseForge(filters, cfKey, settings.instancePath);
  }

  return searchModrinth(filters, settings.instancePath);
});

ipcMain.handle("launcher:get-modrinth-project", async (_event, request) => {
  const offline = await offlineResult("Modrinth project");
  if (offline) return { ...offline, project: null, versions: [] };

  const settings = readSettings();
  return getModrinthProjectDetails({
    projectIdOrSlug: request.projectId || request.slug || request.projectIdOrSlug,
    contentType: request.contentType || "mod",
    gameVersion: request.gameVersion || request.version || settings.selectedVersion,
    loader: request.loader || "fabric"
  });
});

ipcMain.handle("launcher:install-publisher-cert", async () => {
  const result = await installBundledPublisherCertIfNeeded();
  emitStatus();
  return result;
});

ipcMain.handle("launcher:download-mod", async (_event, mod) => {
  try {
    const offline = await offlineResult("Mod download");
    if (offline) return offline;

    const settings = readSettings();

    if (mod.source === "curseforge") {
      const cfKey = effectiveCurseForgeKey(settings);
      if (!cfKey) {
        return { ok: false, message: "CurseForge is unavailable right now." };
      }
      const result = await installCurseForgeMod({
        mod,
        instancePath: settings.instancePath,
        apiKey: cfKey
      });
      emitStatus();
      return result;
    }

    if (mod.source !== "modrinth") {
      return { ok: false, message: "Unsupported mod source." };
    }

    let version = null;
    if (mod.versionId) version = await getModrinthVersionById(mod.versionId);
    const contentType = normalizeContentType(mod.contentType || mod.projectType || "mod");
    if (version) {
      const direct = await installModrinthVersion({
        projectIdOrSlug: mod.projectId || mod.slug,
        title: mod.title,
        version,
        gameVersion: mod.gameVersion || settings.selectedVersion,
        loader: mod.loader || "fabric",
        instancePath: settings.instancePath,
        reason: "selected",
        visited: new Set(),
        contentType
      });
      emitActivity({
        title: direct.ok ? "Download complete" : "Download failed",
        detail: direct.message || mod.title || "Download finished.",
        current: 1,
        total: 1,
        done: true,
        error: !direct.ok
      });
      if (!direct.ok) return direct;
      emitStatus();
      return direct;
    }

    const result = await installModrinthProject({
      projectIdOrSlug: mod.projectId || mod.slug,
      title: mod.title,
      gameVersion: mod.gameVersion || settings.selectedVersion,
      loader: mod.loader || "fabric",
      instancePath: settings.instancePath,
      reason: "selected",
      visited: new Set(),
      contentType
    });
    emitActivity({
      title: result.ok ? "Download complete" : "Download failed",
      detail: result.message || mod.title || "Download finished.",
      current: 1,
      total: 1,
      done: true,
      error: !result.ok
    });
    if (!result.ok) return result;

    emitStatus();
    return result;
  } catch (error) {
    const message = error?.code === "EPERM"
      ? "River could not replace that file because Windows is still using it. Close Minecraft and anything else touching this instance, then try again."
      : (error?.message || "Mod download failed.");
    emit("launcher:log", `[mods] Download failed: ${message}`);
    emitActivity({
      title: "Download failed",
      detail: message,
      current: 1,
      total: 1,
      done: true,
      error: true
    });
    return { ok: false, message };
  }
});

ipcMain.handle("launcher:set-mod-enabled", (_event, request, enabledArg) => {
  const payload = typeof request === "object" && request !== null
    ? request
    : { file: request, enabled: enabledArg };
  const file = String(payload.file || "").trim();
  // The renderer passes the desired state as the second arg (setModEnabled(request,
  // enabled)); fall back to it when the request object doesn't carry `enabled`,
  // otherwise enabling a disabled mod always read false and did nothing.
  const enabled = typeof payload.enabled === "boolean" ? payload.enabled : Boolean(enabledArg);
  if (isCoreClientMod(file)) return { ok: false, message: "River Client Core is required and cannot be disabled." };
  const instancePath = resolveInstancePath(payload);
  if (!instancePath) return { ok: false, message: "Instance path was not found." };
  const modsDir = path.join(instancePath, "mods");
  const current = path.join(modsDir, file);
  if (!fs.existsSync(current)) return { ok: false, message: "Mod file was not found." };
  const targetName = enabled
    ? path.basename(current).replace(/\.disabled$/i, "")
    : `${path.basename(current).replace(/\.disabled$/i, "")}.disabled`;
  const target = path.join(modsDir, targetName);
  if (current !== target) fs.renameSync(current, target);

  const manifest = readModManifest(instancePath);
  if (manifest.mods[file]) {
    manifest.mods[targetName] = { ...manifest.mods[file], file: targetName, disabled: !enabled };
    delete manifest.mods[file];
  }
  if (manifest.updates[file]) {
    manifest.updates[targetName] = manifest.updates[file];
    delete manifest.updates[file];
  }
  const namespacedUpdateKey = updateKey("mod", file);
  if (manifest.updates[namespacedUpdateKey]) {
    manifest.updates[updateKey("mod", targetName)] = {
      ...manifest.updates[namespacedUpdateKey],
      file: targetName
    };
    delete manifest.updates[namespacedUpdateKey];
  }
  writeModManifest(instancePath, manifest);
  emitStatus();
  return { ok: true, message: `${targetName} ${enabled ? "enabled" : "disabled"}.` };
});

ipcMain.handle("launcher:remove-mod", (_event, request) => {
  const payload = typeof request === "object" && request !== null ? request : { file: request };
  const file = String(payload.file || "").trim();
  if (isCoreClientMod(file)) return { ok: false, message: "River Client Core is required and cannot be removed." };
  const instancePath = resolveInstancePath(payload);
  if (!instancePath) return { ok: false, message: "Instance path was not found." };
  const modsDir = path.join(instancePath, "mods");
  const target = path.join(modsDir, file);
  if (!fs.existsSync(target)) return { ok: false, message: "Mod file was not found." };
  fs.rmSync(target, { force: true });
  const manifest = readModManifest(instancePath);
  delete manifest.mods[file];
  delete manifest.updates[file];
  delete manifest.updates[updateKey("mod", file)];
  writeModManifest(instancePath, manifest);
  emitStatus();
  return { ok: true, message: `Removed ${file}.` };
});

ipcMain.handle("launcher:remove-content", (_event, request = {}) => {
  const contentType = normalizeContentType(request.contentType || "mod");
  const info = contentTypeInfo(contentType);
  const file = String(request.file || "").trim();
  if (!file) return { ok: false, message: `No ${info.label} file was selected.` };
  if (contentType === "mod" && isCoreClientMod(file)) {
    return { ok: false, message: "River Client's in-game mod is required and cannot be removed." };
  }
  const instancePath = resolveInstancePath(request);
  if (!instancePath) return { ok: false, message: "Instance path was not found." };
  const target = path.join(instancePath, info.folder, file);
  if (!fs.existsSync(target)) return { ok: false, message: `${info.label} file was not found.` };
  fs.rmSync(target, { force: true });
  const manifest = readModManifest(instancePath);
  delete manifestSection(manifest, contentType)[file];
  delete manifest.updates[updateKey(contentType, file)];
  if (contentType === "mod") delete manifest.updates[file];
  writeModManifest(instancePath, manifest);
  emitStatus();
  return { ok: true, message: `Removed ${file}.` };
});

ipcMain.handle("launcher:sync-client-settings", () => {
  const settings = readSettings();
  const configDir = path.join(settings.instancePath, "config");
  fs.mkdirSync(configDir, { recursive: true });
  fs.writeFileSync(path.join(configDir, "clientcore-launcher.json"), JSON.stringify({
    name: "River Client",
    author: "WyZ_EU",
    version: settings.selectedVersion,
    syncedAt: new Date().toISOString(),
    memoryMb: settings.memoryMb,
    resolution: settings.resolution
  }, null, 2));
  return { ok: true, message: "Synced launcher settings into the selected instance config folder." };
});

async function importModrinthProfileFromFilePicker() {
  const offline = await offlineResult("Modrinth profile import");
  if (offline) return offline;

  const settings = readSettings();
  const selection = await dialog.showOpenDialog(mainWindow, {
    title: "Import Modrinth profile",
    properties: ["openFile"],
    filters: [
      { name: "Modrinth profile or modpack", extensions: ["mrpack", "json"] },
      { name: "All files", extensions: ["*"] }
    ]
  });
  if (selection.canceled || !selection.filePaths.length) return { ok: false, message: "Modrinth import cancelled." };

  try {
    const profile = readModrinthProfileExport(selection.filePaths[0]);
    emitActivity({ title: "Importing profile", detail: `Reading ${profile.name}...`, current: 0, total: profile.files.length });
    const targetVersion = profile.minecraftVersion || settings.selectedVersion;
    let installVersion = settings.selectedVersion;
    let updatedClientVersion = false;

    if (targetVersion && targetVersion !== settings.selectedVersion) {
      const response = await dialog.showMessageBox(mainWindow, {
        type: "question",
        buttons: ["Update River version", "Keep current version"],
        defaultId: 0,
        cancelId: 1,
        title: "Update Minecraft version?",
        message: `${profile.name} targets Minecraft ${targetVersion}. River is currently set to ${settings.selectedVersion}.`,
        detail: "Some imported mods may be incompatible with the current version. Update River to the profile version before importing?"
      });

      if (response.response === 0) {
        const next = writeSettings({
          ...settings,
          selectedVersion: targetVersion,
          modFilters: {
            ...settings.modFilters,
            version: targetVersion,
            loader: profile.loader || settings.modFilters.loader || "fabric"
          }
        });
        installVersion = next.selectedVersion;
        updatedClientVersion = true;
      }
    }

    const result = await importModrinthProfile(profile, {
      instancePath: readSettings().instancePath,
      gameVersion: installVersion,
      loader: profile.loader || "fabric",
      allowCurrentVersionFallback: !updatedClientVersion && targetVersion !== installVersion
    });

    emitActivity({ title: result.ok ? "Profile imported" : "Import failed", detail: result.message, current: profile.files.length, total: profile.files.length, done: true, error: !result.ok });
    emitStatus();
    return {
      ...result,
      updatedClientVersion,
      message: `${result.message}${updatedClientVersion ? ` River was updated to ${installVersion}.` : ""}`
    };
  } catch (error) {
    emitActivity({ title: "Import failed", detail: error.message, done: true, error: true });
    emitStatus();
    return { ok: false, message: error.message };
  }
}

ipcMain.handle("launcher:import-modrinth-profile", importModrinthProfileFromFilePicker);
ipcMain.handle("launcher:import-modpack-file", importModrinthProfileFromFilePicker);

ipcMain.handle("launcher:check-mod-updates", async (_event, request = {}) => {
  const offline = await offlineResult("Mod update check");
  if (offline) return offline;

  const instancePath = resolveInstancePath(request);
  if (!instancePath) return { ok: false, message: "Instance path was not found." };
  const result = await checkModUpdates(instancePath);
  emitStatus();
  return result;
});

ipcMain.handle("launcher:update-all-mods", async (_event, request = {}) => {
  const offline = await offlineResult("Update all");
  if (offline) return offline;

  const instancePath = resolveInstancePath(request);
  if (!instancePath) return { ok: false, message: "Instance path was not found." };
  emitActivity({
    title: "Updating content",
    detail: "Checking installed mods, packs, and shaders for updates...",
    current: 0,
    total: 1
  });

  const result = await applyAvailableModUpdates(instancePath);
  if (!result.ok) {
    emitActivity({
      title: "Update failed",
      detail: result.message || "Could not update content.",
      current: 1,
      total: 1,
      done: true,
      error: true
    });
    return result;
  }

  emitStatus();
  emitActivity({
    title: "Updating content",
    detail: result.message,
    current: 1,
    total: 1,
    done: true
  });
  return result;
});

ipcMain.handle("launcher:open-external", async (_event, url) => {
  await shell.openExternal(url);
  return true;
});

ipcMain.handle("launcher:update-instance", (_event, patch) => {
  const instances = readInstances();
  const idx = instances.findIndex(i => i.id === patch.id);
  if (idx < 0) return { ok: false, message: "Instance not found." };
  instances[idx] = {
    ...instances[idx],
    name: String(patch.name || instances[idx].name).trim() || instances[idx].name,
    version: String(patch.version || instances[idx].version).trim() || instances[idx].version,
    notes: String(patch.notes || ""),
    updatedAt: new Date().toISOString()
  };
  writeInstances(instances);
  // If this is the currently selected instance, keep selectedVersion in sync
  const settings = readSettings();
  if (settings.instancePath === instances[idx].path) {
    writeSettings({ ...settings, selectedVersion: instances[idx].version });
  }
  emitStatus();
  return { ok: true, message: "Instance updated." };
});

const RVR_FORMAT_ID = "river.rvr";
const RVR_FORMAT_VERSION = 1;

function countRvrContentFiles(stagingDir) {
  const counts = { mods: 0, resourcepacks: 0, shaders: 0 };
  for (const contentType of ["mod", "resourcepack", "shader"]) {
    const info = contentTypeInfo(contentType);
    const folder = path.join(stagingDir, info.folder);
    if (!fs.existsSync(folder)) continue;
    counts[info.key] = fs.readdirSync(folder)
      .filter((file) => info.extensions.some((ext) => file.toLowerCase().endsWith(ext)))
      .length;
  }
  return counts;
}

function copyRvrContentFolder(instancePath, stagingDir, contentType) {
  const info = contentTypeInfo(contentType);
  const source = path.join(instancePath, info.folder);
  const target = path.join(stagingDir, info.folder);
  if (!fs.existsSync(source)) return 0;
  fs.mkdirSync(target, { recursive: true });
  let copied = 0;
  for (const file of fs.readdirSync(source)) {
    if (!info.extensions.some((ext) => file.toLowerCase().endsWith(ext))) continue;
    fs.copyFileSync(path.join(source, file), path.join(target, file));
    copied += 1;
  }
  return copied;
}

function buildCompactContentIndex(instancePath, manifest) {
  const sections = {
    mods: getInstalledMods(instancePath).map((entry) => ({
      file: entry.file,
      disabled: Boolean(entry.disabled),
      title: entry.metadata?.title || path.basename(entry.file, path.extname(entry.file)),
      projectId: entry.metadata?.projectId || "",
      version: entry.metadata?.version || "",
      loader: entry.metadata?.loader || "",
      gameVersion: entry.metadata?.gameVersion || ""
    })),
    resourcepacks: getInstalledContent(instancePath, "resourcepack").map((entry) => ({
      file: entry.file,
      title: entry.metadata?.title || path.basename(entry.file, path.extname(entry.file)),
      projectId: entry.metadata?.projectId || "",
      version: entry.metadata?.version || ""
    })),
    shaders: getInstalledContent(instancePath, "shader").map((entry) => ({
      file: entry.file,
      title: entry.metadata?.title || path.basename(entry.file, path.extname(entry.file)),
      projectId: entry.metadata?.projectId || "",
      version: entry.metadata?.version || ""
    }))
  };

  return {
    mods: sections.mods,
    resourcepacks: sections.resourcepacks,
    shaders: sections.shaders,
    totals: {
      mods: sections.mods.length,
      resourcepacks: sections.resourcepacks.length,
      shaders: sections.shaders.length
    },
    manifestSnapshot: manifest
  };
}

function buildRvrStagingDir(instance) {
  const stagingDir = path.join(os.tmpdir(), `river-export-${instance.id}-${Date.now()}`);
  fs.mkdirSync(stagingDir, { recursive: true });

  const manifestPath = modManifestPath(instance.path);
  const manifest = readModManifest(instance.path);
  if (fs.existsSync(manifestPath)) {
    fs.copyFileSync(manifestPath, path.join(stagingDir, "riv3r-mods.json"));
  } else {
    fs.writeFileSync(path.join(stagingDir, "riv3r-mods.json"), JSON.stringify(manifest, null, 2));
  }

  const instanceMetaPath = path.join(instance.path, "riv3r-instance.json");
  let instanceMeta = {
    name: instance.name,
    version: instance.version || "1.21.11",
    loader: instance.loader || "fabric"
  };
  if (fs.existsSync(instanceMetaPath)) {
    try {
      instanceMeta = { ...instanceMeta, ...JSON.parse(fs.readFileSync(instanceMetaPath, "utf8")) };
    } catch {}
  }
  fs.writeFileSync(path.join(stagingDir, "instance.json"), JSON.stringify(instanceMeta, null, 2));

  const contentIndex = buildCompactContentIndex(instance.path, manifest);
  fs.writeFileSync(path.join(stagingDir, "content-index.json"), JSON.stringify(contentIndex, null, 2));

  const lightweightSettings = {};
  for (const relative of ["options.txt", path.join("config", "yacl.json5"), path.join("config", "noisium.json")]) {
    const source = path.join(instance.path, relative);
    if (!fs.existsSync(source)) continue;
    try {
      lightweightSettings[relative.replace(/\\/g, "/")] = fs.readFileSync(source, "utf8");
    } catch {}
  }
  fs.writeFileSync(path.join(stagingDir, "instance-settings.json"), JSON.stringify(lightweightSettings, null, 2));

  const riverManifest = {
    format: RVR_FORMAT_ID,
    version: RVR_FORMAT_VERSION,
    exportedAt: new Date().toISOString(),
    instance: {
      name: instance.name,
      version: instance.version || instanceMeta.version || "1.21.11",
      loader: instance.loader || instanceMeta.loader || "fabric"
    },
    exportMode: "compact",
    contents: contentIndex.totals
  };
  fs.writeFileSync(path.join(stagingDir, "river-manifest.json"), JSON.stringify(riverManifest, null, 2));
  return { stagingDir, riverManifest };
}

function readRvrManifest(rootPath) {
  const manifestPath = path.join(rootPath, "river-manifest.json");
  if (!fs.existsSync(manifestPath)) return null;
  try {
    const payload = JSON.parse(fs.readFileSync(manifestPath, "utf8"));
    if (payload?.format !== RVR_FORMAT_ID) return null;
    return payload;
  } catch {
    return null;
  }
}

function resolveRvrRootPath(extractedPath) {
  const direct = readRvrManifest(extractedPath);
  if (direct) return { rootPath: extractedPath, manifest: direct };
  const children = fs.readdirSync(extractedPath);
  if (children.length === 1) {
    const nested = path.join(extractedPath, children[0]);
    if (fs.statSync(nested).isDirectory()) {
      const nestedManifest = readRvrManifest(nested);
      if (nestedManifest) return { rootPath: nested, manifest: nestedManifest };
    }
  }
  return { rootPath: extractedPath, manifest: null };
}

function importRvrArchive(rootPath, manifest, fallbackName) {
  const instanceMetaPath = path.join(rootPath, "instance.json");
  let instanceMeta = manifest?.instance || {};
  if (fs.existsSync(instanceMetaPath)) {
    try {
      instanceMeta = { ...instanceMeta, ...JSON.parse(fs.readFileSync(instanceMetaPath, "utf8")) };
    } catch {}
  }

  const baseName = sanitizeFilename(instanceMeta.name || fallbackName || "Imported River");
  const destId = `imported-${baseName.toLowerCase().replace(/\s+/g, "-")}-${Date.now()}`;
  const destPath = path.join(instancesRootPath(), destId);
  fs.mkdirSync(destPath, { recursive: true });

  for (const contentType of ["mod", "resourcepack", "shader"]) {
    const info = contentTypeInfo(contentType);
    const source = path.join(rootPath, info.folder);
    const target = path.join(destPath, info.folder);
    if (!fs.existsSync(source)) continue;
    fs.mkdirSync(target, { recursive: true });
    for (const file of fs.readdirSync(source)) {
      if (!info.extensions.some((ext) => file.toLowerCase().endsWith(ext))) continue;
      fs.copyFileSync(path.join(source, file), path.join(target, file));
    }
  }

  const manifestSource = path.join(rootPath, "riv3r-mods.json");
  if (fs.existsSync(manifestSource)) {
    fs.copyFileSync(manifestSource, modManifestPath(destPath));
  } else {
    writeModManifest(destPath, { mods: {}, resourcepacks: {}, shaders: {}, updates: {}, checkedAt: "" });
  }

  const compactContentIndexPath = path.join(rootPath, "content-index.json");
  if (fs.existsSync(compactContentIndexPath)) {
    fs.copyFileSync(compactContentIndexPath, path.join(destPath, "riv3r-content.json"));
  }

  fs.writeFileSync(path.join(destPath, "riv3r-instance.json"), JSON.stringify({
    name: baseName,
    version: instanceMeta.version || manifest?.instance?.version || "1.21.11",
    loader: instanceMeta.loader || manifest?.instance?.loader || "fabric",
    importedAt: new Date().toISOString(),
    sourceFormat: RVR_FORMAT_ID
  }, null, 2));

  fs.mkdirSync(path.join(destPath, "config"), { recursive: true });
  const settingsSnapshotPath = path.join(rootPath, "instance-settings.json");
  if (fs.existsSync(settingsSnapshotPath)) {
    try {
      const snapshot = JSON.parse(fs.readFileSync(settingsSnapshotPath, "utf8"));
      for (const [relativePath, content] of Object.entries(snapshot || {})) {
        if (typeof content !== "string") continue;
        const targetPath = path.join(destPath, relativePath);
        fs.mkdirSync(path.dirname(targetPath), { recursive: true });
        fs.writeFileSync(targetPath, content, "utf8");
      }
    } catch {}
  }
  // .rvr archives are River exports, so fabric/1.21.11 is a safe fallback for older files
  // written before instances carried a version - but flag support the same way external
  // imports do, so an archive from a version River no longer ships is still called out.
  const rvrVersion = instanceMeta.version || manifest?.instance?.version || "1.21.11";
  const rvrLoader = instanceMeta.loader || manifest?.instance?.loader || "fabric";
  return {
    id: destId,
    name: baseName,
    type: "imported",
    version: rvrVersion,
    loader: rvrLoader,
    riverSupported: riverSupportsInstance(rvrVersion, rvrLoader).supported,
    path: destPath
  };
}

/** Total bytes under a folder. Bounded so a huge world can never stall the UI. */
function directorySizeBytes(dir, budget = { files: 20000 }) {
  let total = 0;
  const walk = (current) => {
    if (budget.files <= 0) return;
    let entries = [];
    try { entries = fs.readdirSync(current, { withFileTypes: true }); } catch { return; }
    for (const entry of entries) {
      if (budget.files <= 0) return;
      const full = path.join(current, entry.name);
      if (entry.isDirectory()) walk(full);
      else {
        budget.files -= 1;
        try { total += fs.statSync(full).size; } catch {}
      }
    }
  };
  walk(dir);
  return total;
}

/**
 * The worlds in an instance, as the launcher can present them without opening the game:
 * the real in-game name (from level.dat, which is not always the folder name), when it was
 * last played, how big it is, and the world icon Minecraft renders in its own world list.
 */
function getInstanceWorlds(instancePath) {
  const savesDir = path.join(String(instancePath || ""), "saves");
  let entries = [];
  try { entries = fs.readdirSync(savesDir, { withFileTypes: true }); } catch { return []; }
  const worlds = [];
  for (const entry of entries) {
    if (!entry.isDirectory()) continue;
    const folder = entry.name;
    const worldPath = path.join(savesDir, folder);
    const levelDat = path.join(worldPath, "level.dat");
    let name = folder;
    let lastPlayed = 0;
    let gameMode = "";
    let hardcore = false;
    const root = fs.existsSync(levelDat) ? parseNbtFile(levelDat) : null;
    const data = root && root.Data ? root.Data : null;
    if (data) {
      if (data.LevelName) name = String(data.LevelName);
      if (data.LastPlayed) lastPlayed = Number(data.LastPlayed) || 0;
      hardcore = Boolean(data.hardcore);
      const modes = ["Survival", "Creative", "Adventure", "Spectator"];
      if (typeof data.GameType === "number") gameMode = modes[data.GameType] || "";
    }
    if (!lastPlayed) {
      try { lastPlayed = fs.statSync(fs.existsSync(levelDat) ? levelDat : worldPath).mtimeMs; } catch {}
    }
    let icon = "";
    const iconPath = path.join(worldPath, "icon.png");
    try {
      const stat = fs.statSync(iconPath);
      // World icons are 64x64 thumbnails, so inlining them is cheap. Guard anyway.
      if (stat.size > 0 && stat.size < 512 * 1024) {
        icon = "data:image/png;base64," + fs.readFileSync(iconPath).toString("base64");
      }
    } catch {}
    worlds.push({
      folder,
      name,
      path: worldPath,
      lastPlayed,
      gameMode,
      hardcore,
      icon,
      sizeBytes: directorySizeBytes(worldPath)
    });
  }
  return worlds.sort((a, b) => b.lastPlayed - a.lastPlayed);
}

/** Instance subfolders the UI is allowed to open, so a renderer string can never escape it. */
const INSTANCE_SUBFOLDERS = ["", "mods", "saves", "config", "resourcepacks", "shaderpacks", "screenshots", "logs", "crash-reports"];

/**
 * Everything the instance page needs, for ANY instance - not just the selected one, so
 * browsing an instance never forces the user to switch to it first.
 */
ipcMain.handle("launcher:get-instance-details", (_event, request) => {
  const instanceId = typeof request === "string" ? request : String((request && request.instanceId) || "");
  const instance = readInstances().find((item) => item.id === instanceId);
  if (!instance) return { ok: false, message: "Instance was not found." };
  if (!fs.existsSync(instance.path)) return { ok: false, message: "That instance folder no longer exists." };

  const support = riverSupportsInstance(instance.version, instance.loader);
  const countFiles = (folder) => {
    try { return fs.readdirSync(path.join(instance.path, folder)).length; } catch { return 0; }
  };
  return {
    ok: true,
    instance: {
      ...instance,
      riverSupported: instance.riverSupported !== false && support.supported,
      riverWarning: support.reason,
      lastPlayedAt: lastPlayedByInstance()[instance.id] || 0
    },
    mods: getInstalledMods(instance.path),
    resourcepacks: getInstalledContent(instance.path, "resourcepack"),
    shaders: getInstalledContent(instance.path, "shader"),
    worlds: getInstanceWorlds(instance.path),
    counts: {
      screenshots: countFiles("screenshots"),
      crashReports: countFiles("crash-reports")
    },
    selected: path.resolve(instance.path) === path.resolve(readSettings().instancePath || "")
  };
});

ipcMain.handle("launcher:open-instance-path", (_event, request) => {
  const instanceId = String((request && request.instanceId) || "");
  const sub = String((request && request.sub) || "");
  if (!INSTANCE_SUBFOLDERS.includes(sub)) return { ok: false, message: "Unknown folder." };
  const instance = readInstances().find((item) => item.id === instanceId);
  if (!instance) return { ok: false, message: "Instance was not found." };
  const target = sub ? path.join(instance.path, sub) : instance.path;
  try { fs.mkdirSync(target, { recursive: true }); } catch {}
  shell.openPath(target);
  return { ok: true };
});

ipcMain.handle("launcher:delete-world", (_event, request) => {
  const instanceId = String((request && request.instanceId) || "");
  const folder = String((request && request.folder) || "");
  const instance = readInstances().find((item) => item.id === instanceId);
  if (!instance) return { ok: false, message: "Instance was not found." };
  // Resolve and re-check containment: the folder name arrives from the renderer, so it
  // must not be able to point anywhere outside this instance's saves directory.
  const savesDir = path.resolve(instance.path, "saves");
  const target = path.resolve(savesDir, folder);
  if (!target.startsWith(savesDir + path.sep) || !fs.existsSync(target)) {
    return { ok: false, message: "That world was not found." };
  }
  try {
    fs.rmSync(target, { recursive: true, force: true });
    emitStatus();
    return { ok: true, message: "Deleted " + folder + "." };
  } catch (error) {
    return { ok: false, message: error.message || "Could not delete that world." };
  }
});

/**
 * Works out what changing an instance to [targetVersion] would do to its content, WITHOUT
 * touching anything. Every Modrinth-tracked mod/pack/shader is checked for a build that
 * supports the target; anything without one is planned for disabling rather than deletion,
 * so nothing the user installed is ever destroyed by a version switch.
 *
 * Hand-dropped files have no Modrinth project to query, so they cannot be verified. They
 * are planned for disabling too (a jar built for another version usually hard-crashes the
 * game) but reported separately, because "we could not check this" is a different claim
 * from "this definitely has no build".
 */
async function planInstanceVersionChange(instancePath, targetVersion, loader = "fabric") {
  const manifest = readModManifest(instancePath);
  const plan = { update: [], keep: [], disable: [], unverified: [] };
  const tasks = [];

  for (const contentType of ["mod", "resourcepack", "shader"]) {
    const info = contentTypeInfo(contentType);
    const folder = path.join(instancePath, info.folder);
    let files = [];
    try { files = fs.readdirSync(folder); } catch { continue; }
    const section = manifestSection(manifest, contentType);
    for (const file of files) {
      const lower = file.toLowerCase();
      if (!info.extensions.some((ext) => lower.endsWith(ext))) continue;
      // Already off, or River's own jar (the launcher swaps that per version itself).
      if (lower.endsWith(".disabled") || isCoreClientMod(file)) continue;
      const item = section[file];
      if (!item || item.source !== "modrinth" || !item.projectId) {
        plan.unverified.push({ contentType, file, title: (item && item.title) || file });
        continue;
      }
      tasks.push({ contentType, file, item });
    }
  }

  let cursor = 0;
  const workers = Array.from({ length: Math.min(6, Math.max(1, tasks.length)) }, async () => {
    while (cursor < tasks.length) {
      const { contentType, file, item } = tasks[cursor++];
      const match = await getModrinthVersion(item.projectId, targetVersion, item.loader || loader, contentType);
      const entry = {
        contentType,
        file,
        title: item.title || file,
        projectId: item.projectId,
        loader: item.loader || loader,
        currentVersionNumber: item.versionNumber || ""
      };
      if (!match) plan.disable.push(entry);
      else if (match.id === item.versionId) plan.keep.push(entry);
      else plan.update.push({ ...entry, version: match, newVersionNumber: match.version_number });
    }
  });
  await Promise.all(workers);
  return plan;
}

/** Renames a content file to .disabled in place, keeping its manifest entry in step. */
function disableContentFile(instancePath, contentType, file) {
  const info = contentTypeInfo(contentType);
  const folder = path.join(instancePath, info.folder);
  const current = path.join(folder, file);
  if (!fs.existsSync(current)) return false;
  const targetName = file + ".disabled";
  try { fs.renameSync(current, path.join(folder, targetName)); } catch { return false; }
  const manifest = readModManifest(instancePath);
  const section = manifestSection(manifest, contentType);
  if (section[file]) {
    section[targetName] = { ...section[file], file: targetName, disabled: true };
    delete section[file];
    writeModManifest(instancePath, manifest);
  }
  return true;
}

/** What changing an instance to another Minecraft version would do. Read-only. */
ipcMain.handle("launcher:preview-version-change", async (_event, request) => {
  const offline = await offlineResult("Version change check");
  if (offline) return offline;
  const instanceId = String((request && request.instanceId) || "");
  const version = String((request && request.version) || "");
  const instance = readInstances().find((item) => item.id === instanceId);
  if (!instance) return { ok: false, message: "Instance was not found." };
  if (!SUPPORTED_MC_VERSIONS.includes(version)) return { ok: false, message: "River does not support Minecraft " + version + "." };
  if (version === instance.version) return { ok: false, message: "That instance is already on " + version + "." };

  emitActivity({ title: "Checking mods", detail: "Looking for " + version + " builds...", current: 0, total: 1 });
  const plan = await planInstanceVersionChange(instance.path, version, instance.loader || "fabric");
  emitActivity({ title: "Checking mods", detail: "Done.", current: 1, total: 1, done: true });
  return { ok: true, from: instance.version, to: version, plan };
});

/**
 * Applies a version change: updates every mod that has a build for the new version,
 * disables (never deletes) the ones that do not, and swaps River's own jar to the
 * matching build.
 */
ipcMain.handle("launcher:change-instance-version", async (_event, request) => {
  const offline = await offlineResult("Version change");
  if (offline) return offline;
  const instanceId = String((request && request.instanceId) || "");
  const version = String((request && request.version) || "");
  const instances = readInstances();
  const index = instances.findIndex((item) => item.id === instanceId);
  if (index < 0) return { ok: false, message: "Instance was not found." };
  if (!SUPPORTED_MC_VERSIONS.includes(version)) return { ok: false, message: "River does not support Minecraft " + version + "." };
  const instance = instances[index];
  const loader = instance.loader || "fabric";

  const plan = await planInstanceVersionChange(instance.path, version, loader);
  const updated = [];
  const disabled = [];
  const failed = [];

  const total = plan.update.length + plan.disable.length + plan.unverified.length;
  let done = 0;
  const step = (detail) => emitActivity({ title: "Switching to " + version, detail, current: done, total: Math.max(1, total) });

  for (const entry of plan.update) {
    step("Updating " + entry.title + "...");
    try {
      const result = await installModrinthVersion({
        projectIdOrSlug: entry.projectId,
        title: entry.title,
        version: entry.version,
        gameVersion: version,
        loader: entry.loader,
        instancePath: instance.path,
        reason: "river-version-change",
        visited: new Set(),
        contentType: entry.contentType
      });
      if (result && result.ok) {
        // The new file lands alongside the old one, so retire the previous jar.
        const info = contentTypeInfo(entry.contentType);
        const oldPath = path.join(instance.path, info.folder, entry.file);
        const replaced = Array.isArray(result.installed) && result.installed.includes(entry.file);
        if (fs.existsSync(oldPath) && !replaced) {
          try { fs.rmSync(oldPath, { force: true }); } catch {}
        }
        updated.push(entry.title);
      } else {
        failed.push(entry.title);
      }
    } catch { failed.push(entry.title); }
    done++;
  }

  for (const entry of [...plan.disable, ...plan.unverified]) {
    step("Disabling " + entry.title + "...");
    if (disableContentFile(instance.path, entry.contentType, entry.file)) disabled.push(entry.title);
    done++;
  }

  // Point the instance at the new version before staging River's jar, so the per-version
  // resolver picks the right clientcore build (and strips the old one).
  instances[index] = { ...instance, version, updatedAt: new Date().toISOString() };
  writeInstances(instances);

  const settings = readSettings();
  if (settings.instancePath && path.resolve(settings.instancePath) === path.resolve(instance.path)) {
    writeSettings({
      ...settings,
      selectedVersion: version,
      modFilters: { ...settings.modFilters, version }
    });
  }
  ensureBundledClientCoreMod(instance.path, version);

  emitActivity({ title: "Switched to " + version, detail: "Done.", current: 1, total: 1, done: true });
  emitStatus();

  const parts = [];
  if (updated.length) parts.push("updated " + updated.length);
  if (disabled.length) parts.push("disabled " + disabled.length);
  if (plan.keep.length) parts.push(plan.keep.length + " already compatible");
  if (failed.length) parts.push(failed.length + " failed");
  return {
    ok: true,
    message: "Now on " + version + (parts.length ? " (" + parts.join(", ") + ")." : "."),
    updated,
    disabled,
    failed,
    kept: plan.keep.map((entry) => entry.title)
  };
});


// Instance export / import
ipcMain.handle("launcher:export-instance", async (_event, instanceId) => {
  const instances = readInstances();
  const instance = instances.find(i => i.id === instanceId);
  if (!instance) return { ok: false, message: "Instance not found." };
  if (!fs.existsSync(instance.path)) return { ok: false, message: "Instance folder does not exist." };

  const { filePath } = await dialog.showSaveDialog(mainWindow, {
    title: "Export River instance",
    defaultPath: `${sanitizeFilename(instance.name)}.rvr`,
    filters: [{ name: "River Instance", extensions: ["rvr"] }]
  });
  if (!filePath) return { ok: false, message: "Cancelled." };

  const exportPath = filePath.toLowerCase().endsWith(".rvr") ? filePath : `${filePath}.rvr`;
  let stagingDir = "";
  try {
    emitActivity({ title: "Exporting instance", detail: `Reading ${instance.name}...`, current: 0, total: 4 });
    const staged = buildRvrStagingDir(instance);
    stagingDir = staged.stagingDir;
    emitActivity({ title: "Exporting instance", detail: "Writing compact manifest...", current: 1, total: 4 });
    const archiveContents = fs.readdirSync(stagingDir).map((entry) => path.join(stagingDir, entry));
    emitActivity({ title: "Exporting instance", detail: "Compressing archive...", current: 2, total: 4 });
    await compressPathsToRiverArchive(archiveContents, exportPath);
    const totalFiles = Object.values(staged.riverManifest.contents).reduce((sum, count) => sum + Number(count || 0), 0);
    emitActivity({ title: "Exporting instance", detail: "Finalizing compact export...", current: 3, total: 4 });
    const exportSizeKb = Math.max(1, Math.round(fs.statSync(exportPath).size / 1024));
    emitActivity({ title: "Exporting instance", detail: "Done.", current: 4, total: 4, done: true, percent: 100 });
    return {
      ok: true,
      message: `Exported ${totalFiles} content name${totalFiles === 1 ? "" : "s"} to ${path.basename(exportPath)} (${exportSizeKb} KB).`
    };
  } catch (error) {
    return { ok: false, message: `Export failed: ${error.message}` };
  } finally {
    if (stagingDir) {
      try { fs.rmSync(stagingDir, { recursive: true, force: true }); } catch {}
    }
  }
});

async function importRvrArchiveFromFile(archivePath) {
  if (!isRvrPath(archivePath) || !fs.existsSync(archivePath)) {
    return { ok: false, message: "That file is not a valid River .rvr export." };
  }
  const baseName = path.basename(archivePath, path.extname(archivePath));
  const extractRoot = path.join(os.tmpdir(), `river-import-${Date.now()}`);

  fs.mkdirSync(extractRoot, { recursive: true });
  emitActivity({ title: "Importing instance", detail: "Unpacking River archive...", current: 0, total: 1 });
  try {
    await extractRiverArchive(archivePath, extractRoot);

    const resolved = resolveRvrRootPath(extractRoot);
    if (!resolved.manifest) {
      return { ok: false, message: "That file is not a valid River .rvr export." };
    }

    const imported = importRvrArchive(resolved.rootPath, resolved.manifest, baseName);
    const now = new Date().toISOString();
    const instance = {
      ...imported,
      createdAt: now,
      updatedAt: now
    };
    const instances = readInstances();
    instances.push(instance);
    writeInstances(instances);
    emitStatus();
    emitActivity({ title: "Importing instance", detail: "Done.", current: 1, total: 1, done: true });
    return { ok: true, message: `Imported as "${instance.name}". Select it in Instances.` };
  } catch (error) {
    return { ok: false, message: `Import failed: ${error.message}` };
  } finally {
    try { fs.rmSync(extractRoot, { recursive: true, force: true }); } catch {}
  }
}

ipcMain.handle("launcher:import-instance", async () => {
  const { filePaths } = await dialog.showOpenDialog(mainWindow, {
    title: "Import River instance",
    filters: [{ name: "River Instance", extensions: ["rvr"] }],
    properties: ["openFile"]
  });
  if (!filePaths?.length) return { ok: false, message: "Cancelled." };
  return importRvrArchiveFromFile(filePaths[0]);
});

// Java auto-installer
ipcMain.handle("launcher:install-java", async (_event, majorVersion = 21) => installJavaRuntime(majorVersion));

/**
 * Reads the major version of a Java executable (0 when it can't be run).
 * "21.0.3" -> 21, and the legacy "1.8.0_401" -> 8.
 */
function javaMajorVersion(javaPath) {
  try {
    const { spawnSync } = require("node:child_process");
    const result = spawnSync(javaPath, ["-version"], { encoding: "utf8", timeout: 6000 });
    const text = `${result.stderr || ""}${result.stdout || ""}`;
    const m = text.match(/version "(\d+)(?:\.(\d+))?/);
    if (!m) return 0;
    const major = Number(m[1]);
    return major === 1 ? Number(m[2] || 0) : major;
  } catch {
    return 0;
  }
}

/**
 * The javaw.exe sitting next to a given java.exe, or "" when there isn't one.
 * javaw is the same JVM without a console window, which is what the game should run on.
 */
function javawFor(javaPath) {
  try {
    const raw = String(javaPath || "");
    if (!raw || raw === "java") {
      // Bare "java" from PATH: look for javaw next to whatever java resolves to.
      return "";
    }
    const candidate = path.join(path.dirname(raw), "javaw.exe");
    return fs.existsSync(candidate) ? candidate : "";
  } catch {
    return "";
  }
}

/**
 * Finds a real Java >= minMajor on this machine: the saved path, our own
 * auto-installed runtime, the common vendor install roots, JAVA_HOME, then PATH.
 * Returns an absolute path (or the bare "java" only if PATH actually resolves),
 * or null when nothing usable exists. Never returns a path that can't run.
 */
function resolveUsableJava(settings, minMajor = 21) {
  const candidates = [];
  if (settings?.javaPath) candidates.push(settings.javaPath);

  // Our own auto-installed runtime.
  const bundledJre = path.join(app.getPath("userData"), "java-runtimes");
  if (fs.existsSync(bundledJre)) {
    const walk = (dir, depth = 0) => {
      if (depth > 4) return;
      let entries = [];
      try { entries = fs.readdirSync(dir); } catch { return; }
      for (const e of entries) {
        const full = path.join(dir, e);
        if (e.toLowerCase() === "java.exe") candidates.push(full);
        else { try { if (fs.statSync(full).isDirectory()) walk(full, depth + 1); } catch {} }
      }
    };
    walk(bundledJre);
  }

  // Common vendor install roots.
  for (const root of [
    "C:\\Program Files\\Eclipse Adoptium",
    "C:\\Program Files\\Java",
    "C:\\Program Files\\Microsoft\\jdk",
    "C:\\Program Files\\Zulu",
    "C:\\Program Files\\Amazon Corretto",
    "C:\\Program Files (x86)\\Eclipse Adoptium",
    "C:\\Program Files (x86)\\Java"
  ]) {
    if (!fs.existsSync(root)) continue;
    try {
      for (const dir of fs.readdirSync(root)) {
        const jexe = path.join(root, dir, "bin", "java.exe");
        if (fs.existsSync(jexe)) candidates.push(jexe);
      }
    } catch {}
  }

  if (process.env.JAVA_HOME) candidates.push(path.join(process.env.JAVA_HOME, "bin", "java.exe"));
  // Last resort: whatever is on PATH. Only used if it actually runs.
  candidates.push("java");

  for (const cand of candidates) {
    if (cand !== "java" && !fs.existsSync(cand)) continue;
    if (javaMajorVersion(cand) >= minMajor) return cand;
  }
  return null;
}

/**
 * Downloads and unpacks an Adoptium JRE into userData, then saves it as the
 * active runtime. Prefers the .zip package: it extracts with no admin rights and
 * no msiexec, so it works for users who can't elevate.
 */
async function installJavaRuntime(majorVersion = 21) {
  const arch = process.arch === "x64" ? "x64" : "x86";
  const apiUrl = `https://api.adoptium.net/v3/assets/latest/${majorVersion}/hotspot?os=windows&architecture=${arch}&image_type=jre`;
  emitActivity({ title: "Installing Java", detail: `Fetching Java ${majorVersion} metadata...`, current: 0, total: 4 });

  let controller = new AbortController();
  let timeout = setTimeout(() => controller.abort(), 8000);
  let meta;
  try {
    const res = await fetch(apiUrl, { signal: controller.signal, headers: { "User-Agent": `RiverClientLauncher/${app.getVersion()}` } });
    clearTimeout(timeout);
    if (!res.ok) throw new Error(`Adoptium API returned ${res.status}`);
    const list = await res.json();
    meta = Array.isArray(list) && list[0];
    if (!meta?.binary?.installer?.link && !meta?.binary?.package?.link) throw new Error("No download link found in Adoptium response.");
  } catch (e) {
    clearTimeout(timeout);
    return { ok: false, message: `Could not fetch Java metadata: ${e.message}` };
  }

  // Prefer the .zip package: extracts with no admin rights and no msiexec.
  const downloadUrl = meta.binary.package?.link || meta.binary.installer?.link;
  const fileName = path.basename(new URL(downloadUrl).pathname);
  const javaDir = path.join(app.getPath("userData"), "java-runtimes");
  fs.mkdirSync(javaDir, { recursive: true });
  const downloadPath = path.join(javaDir, fileName);

  emitActivity({ title: "Installing Java", detail: `Downloading ${fileName}...`, current: 1, total: 4 });
  const dlRes = await fetch(downloadUrl, { headers: { "User-Agent": `RiverClientLauncher/${app.getVersion()}` } });
  if (!dlRes.ok || !dlRes.body) return { ok: false, message: `Java download failed with ${dlRes.status}.` };

  const totalBytes = Number(dlRes.headers.get("content-length") || 0);
  let downloaded = 0;
  const reader = dlRes.body.getReader();
  const output = fs.createWriteStream(downloadPath);
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    downloaded += value.byteLength;
    output.write(Buffer.from(value));
    if (totalBytes > 0) emitActivity({ title: "Installing Java", detail: `${formatBytes(downloaded)} / ${formatBytes(totalBytes)}`, current: downloaded, total: totalBytes, unit: "bytes" });
  }
  await new Promise((res, rej) => output.end(e => e ? rej(e) : res()));

  emitActivity({ title: "Installing Java", detail: "Extracting JRE...", current: 3, total: 4 });
  const extractDir = path.join(javaDir, `jre-${majorVersion}`);
  fs.rmSync(extractDir, { recursive: true, force: true });
  fs.mkdirSync(extractDir, { recursive: true });

  if (fileName.endsWith(".msi") || fileName.endsWith(".exe")) {
    // Run installer silently
    await runPowerShell(["-NoProfile", "-ExecutionPolicy", "Bypass", "-Command",
      `Start-Process -FilePath '${escapePowerShell(downloadPath)}' -ArgumentList '/s INSTALLDIR="${escapePowerShell(extractDir)}"' -Wait`
    ]).catch(() => {});
  } else {
    await runPowerShell(["-NoProfile", "-ExecutionPolicy", "Bypass", "-Command",
      `Expand-Archive -LiteralPath '${escapePowerShell(downloadPath)}' -DestinationPath '${escapePowerShell(extractDir)}' -Force`
    ]);
  }

  // Find java.exe inside extracted dir
  const findJava = (dir, depth = 0) => {
    if (depth > 4) return null;
    for (const entry of fs.readdirSync(dir)) {
      const full = path.join(dir, entry);
      if (entry === "java.exe") return full;
      if (fs.statSync(full).isDirectory()) { const found = findJava(full, depth + 1); if (found) return found; }
    }
    return null;
  };
  const javaExe = findJava(extractDir);
  if (!javaExe) return { ok: false, message: "Java installed but java.exe was not found in the extracted directory." };

  writeSettings({ ...readSettings(), javaPath: javaExe });
  emitStatus();
  emitActivity({ title: "Installing Java", detail: `Java ${majorVersion} ready.`, current: 4, total: 4, done: true });
  fs.rmSync(downloadPath, { force: true });
  return { ok: true, message: `Java ${majorVersion} installed and set as the active runtime.`, javaPath: javaExe };
}

// Profile switcher (multiple Microsoft accounts)
function profilesPath() { return path.join(app.getPath("userData"), "profiles.json"); }
function readProfiles() {
  try { return JSON.parse(fs.readFileSync(profilesPath(), "utf8")); } catch { return []; }
}
function writeProfiles(profiles) {
  fs.writeFileSync(profilesPath(), JSON.stringify(profiles, null, 2));
}

ipcMain.handle("launcher:get-profiles", () => {
  const auth = readAuth();
  return { profiles: readProfiles(), activeId: auth.profileId || null };
});

ipcMain.handle("launcher:save-profile", async () => {
  const auth = readAuth();
  if (!auth.profile?.id) return { ok: false, message: "Not signed in." };
  const profiles = readProfiles();
  const existing = profiles.findIndex(p => p.id === auth.profile.id);
  const entry = {
    id: auth.profile.id,
    name: auth.profile.name,
    savedAt: new Date().toISOString(),
    auth
  };
  if (existing >= 0) profiles[existing] = entry;
  else profiles.push(entry);
  writeProfiles(profiles);
  writeAuth({ ...auth, profileId: auth.profile.id });
  emitStatus();
  return { ok: true, message: `Saved profile for ${auth.profile.name}.` };
});

ipcMain.handle("launcher:switch-profile", async (_event, profileId) => {
  const profiles = readProfiles();
  const profile = profiles.find(p => p.id === profileId);
  if (!profile) return { ok: false, message: "Profile not found." };
  const auth = writeAuth({ ...profile.auth, profileId: profileId });
  await ensureAccountSkinSeeded(auth);
  emitStatus();
  return { ok: true, message: `Switched to ${profile.name}.` };
});

ipcMain.handle("launcher:remove-profile", (_event, profileId) => {
  const profiles = readProfiles().filter(p => p.id !== profileId);
  writeProfiles(profiles);
  return { ok: true, message: "Profile removed." };
});

// Quick launch
ipcMain.handle("launcher:quick-launch", async () => {
  // Select the most recently used instance, then launch
  const history = readSessionHistory();
  const instances = readInstances();
  let instanceId = null;

  if (history.length > 0) {
    const lastId = history[0].instanceId;
    if (instances.find(i => i.id === lastId)) instanceId = lastId;
  }
  if (!instanceId && instances.length > 0) instanceId = instances[0].id;
  if (!instanceId) return { ok: false, message: "No instances found. Create one first." };

  // Select it then launch
  const settings = readSettings();
  const instance = instances.find(i => i.id === instanceId);
  writeSettings({ ...settings, instancePath: instance.path, selectedVersion: instance.version });
  emitStatus();

  // Small delay for status to propagate, then launch
  await new Promise(r => setTimeout(r, 300));
  const launchResult = await (async () => {
    const currentStatus = getStatus();
    return launchStandaloneMinecraft(currentStatus);
  })().catch(e => ({ ok: false, message: e.message }));
  return launchResult;
});

const presenceRosterUrl = String(
  process.env.RIVER_PRESENCE_URL || "https://updates.riverclient.xyz/presence/roster"
).trim();

/**
 * Live presence for the friends list.
 *
 * Returns the friends array with a real `status` rather than the stored placeholder.
 * "Online" here means River-active - the in-game client posts presence while you play,
 * so a friend sitting in the launcher is correctly not online. Failures degrade to
 * "unknown" rather than lying that everyone is offline.
 */
const riverSocialBase = String(process.env.RIVER_SOCIAL_URL || "https://updates.riverclient.xyz/social");
let riverSessionCache = { token: "", expiresAt: 0 };

/**
 * Signs the launcher in to River using this account's Mojang-signed certificate.
 *
 * Same proof the in-game client uses: River verifies Mojang's signature offline (it cannot
 * call Mojang - Cloudflare's egress is blocked) and we prove possession by signing a
 * one-time nonce. Used here to prove tester status when fetching beta builds.
 */
async function riverSignIn() {
  if (riverSessionCache.token && riverSessionCache.expiresAt > Date.now() + 60_000) {
    return riverSessionCache.token;
  }
  const auth = await ensureFreshAuth();
  if (!auth?.minecraftAccessToken || !auth?.profile?.id) return "";

  const stripPem = (pem) => Buffer.from(String(pem).replace(/-----[^-]+-----/g, "").replace(/\s+/g, ""), "base64");
  try {
    const certRes = await fetch("https://api.minecraftservices.com/player/certificates", {
      method: "POST",
      headers: { Authorization: `Bearer ${auth.minecraftAccessToken}` }
    });
    if (!certRes.ok) return "";
    const cert = await certRes.json();

    const begin = await (await fetch(`${riverSocialBase}/auth/begin`, {
      method: "POST", headers: { "Content-Type": "application/json" }, body: "{}"
    })).json();
    if (!begin?.nonce) return "";

    const privateKey = crypto.createPrivateKey({
      key: stripPem(cert.keyPair.privateKey), format: "der", type: "pkcs8"
    });
    const nonceSignature = crypto
      .sign("sha256", Buffer.from(begin.nonce, "utf8"), privateKey)
      .toString("base64");

    const complete = await (await fetch(`${riverSocialBase}/auth/complete`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        name: auth.profile.name,
        uuid: auth.profile.id,
        nonce: begin.nonce,
        publicKey: stripPem(cert.keyPair.publicKey).toString("base64"),
        publicKeySignature: cert.publicKeySignatureV2,
        expiresAt: cert.expiresAt,
        nonceSignature
      })
    })).json();

    if (!complete?.ok || !complete.token) return "";
    riverSessionCache = { token: complete.token, expiresAt: Number(complete.expiresAt) || 0 };
    return complete.token;
  } catch {
    return "";
  }
}

/** One authenticated call to the River social backend. */
async function riverSocialCall(route, body = {}) {
  const token = await riverSignIn();
  if (!token) return { ok: false, message: "Could not verify your Minecraft account." };
  try {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 8000);
    const response = await fetch(`${riverSocialBase}${route}`, {
      method: "POST",
      signal: controller.signal,
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${token}`,
        "User-Agent": launcherUserAgent
      },
      body: JSON.stringify(body)
    });
    clearTimeout(timer);
    return await response.json();
  } catch {
    return { ok: false, message: "River is unreachable." };
  }
}

/**
 * Friends, requests, chat and moderation for the launcher, on the same verified backend
 * the in-game client uses - so both surfaces show one friends list rather than the local
 * settings.friends list this panel used to keep to itself.
 */
ipcMain.handle("launcher:river-social", async (_event, request = {}) => {
  const action = String(request.action || "");
  const payload = request.payload || {};

  switch (action) {
    case "roster": {
      const settings = readSettings();
      return riverSocialCall("/presence", {
        status: settings.socialStatus || "online",
        shareServer: false
      });
    }
    case "add": {
      // Mojang cannot be reached from the worker, but it can from here - so resolve the
      // name locally and file the request by UUID. That also lets you add someone who has
      // never installed River; it simply waits for their first sign-in.
      const name = String(payload.name || "").trim();
      if (!name) return { ok: false, message: "Enter a username." };
      const skin = await resolveMojangProfile(name);
      if (!skin) return { ok: false, message: `No Minecraft account called "${name}".` };
      return riverSocialCall("/friends/request", { name: skin.name, uuid: skin.id });
    }
    case "accept": return riverSocialCall("/friends/accept", { uuid: payload.uuid });
    case "decline": return riverSocialCall("/friends/decline", { uuid: payload.uuid });
    case "remove": return riverSocialCall("/friends/remove", { uuid: payload.uuid });
    case "block": return riverSocialCall("/block", { uuid: payload.uuid });
    case "unblock": return riverSocialCall("/unblock", { uuid: payload.uuid });
    case "blocked": return riverSocialCall("/blocked", {});
    case "history": return riverSocialCall("/dm/history", { uuid: payload.uuid, limit: payload.limit });
    case "send": return riverSocialCall("/dm/send", { uuid: payload.uuid, text: payload.text });
    default: return { ok: false, message: `Unknown social action: ${action}` };
  }
});

/** Username to {id, name} via Mojang. Cached alongside skins; reachable from the user's PC. */
async function resolveMojangProfile(name) {
  const key = String(name || "").trim().toLowerCase();
  if (!key) return null;
  try {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 6000);
    const response = await fetch(`https://api.mojang.com/users/profiles/minecraft/${encodeURIComponent(key)}`, {
      signal: controller.signal
    });
    clearTimeout(timer);
    if (!response.ok) return null;
    const payload = await response.json();
    return payload?.id ? { id: payload.id, name: payload.name || name } : null;
  } catch {
    return null;
  }
}

const SOCIAL_STATUS_VALUES = ["online", "idle", "dnd", "invisible"];
const presenceStatusUrl = presenceRosterUrl.replace(/\/roster$/, "/status");

/**
 * Tells the roster how this player wants to appear.
 *
 * Without this the launcher only ever READ presence - the in-game client was the sole
 * announcer - so a status picked in the launcher could never reach anyone. Invisible is
 * still sent because the backend treats it as a removal, which is what actually makes
 * you disappear rather than just relabelling you.
 */
async function announceSocialStatus(settings = readSettings()) {
  const profile = readAuth()?.profile;
  if (!profile?.id) return { ok: false, message: "Not signed in." };
  const status = SOCIAL_STATUS_VALUES.includes(settings.socialStatus) ? settings.socialStatus : "online";
  try {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 6000);
    await fetch(presenceStatusUrl, {
      method: "POST",
      signal: controller.signal,
      headers: {
        "Content-Type": "application/json",
        "User-Agent": `RiverClientLauncher/${app.getVersion()} (WyZ_EU)`
      },
      body: JSON.stringify({ uuid: profile.id, name: profile.name, status })
    });
    clearTimeout(timer);
    return { ok: true, status };
  } catch {
    return { ok: false, message: "Could not reach River presence." };
  }
}

let statusHeartbeat = null;
function startStatusHeartbeat() {
  clearInterval(statusHeartbeat);
  // Entries expire after 30s server-side, so re-announce comfortably inside that.
  statusHeartbeat = setInterval(() => { announceSocialStatus().catch(() => {}); }, 20000);
  announceSocialStatus().catch(() => {});
}

// The social-address feature was fully implemented (checkSocialAddressAvailability /
// saveSocialAddressName) and exposed in preload, but these two handlers were never
// registered - so every call from the UI would have rejected with "No handler registered".
ipcMain.handle("launcher:check-social-address", (_event, name) => {
  return checkSocialAddressAvailability(String(name || ""));
});

ipcMain.handle("launcher:set-social-address", (_event, name) => {
  return saveSocialAddressName(String(name || ""));
});

ipcMain.handle("launcher:set-social-status", async (_event, status) => {
  const next = writeSettings({ ...readSettings(), socialStatus: String(status || "online") });
  const result = await announceSocialStatus(next);
  emitStatus();
  return { ok: result.ok, status: next.socialStatus, message: result.message || "" };
});

/**
 * Resolves a username to their skin texture through Mojang's own API, so friend avatars
 * are real heads instead of a letter. Deliberately not crafatar or similar: those are
 * blocked on some networks and would leave a broken image, which is exactly why
 * PlayerHead crops the texture itself. Cached because Mojang rate-limits by IP.
 */
const skinUrlCache = new Map();
const SKIN_CACHE_MS = 60 * 60 * 1000;

ipcMain.handle("launcher:get-player-skin", async (_event, name) => {
  const key = String(name || "").trim().toLowerCase();
  if (!key) return { ok: false, url: "" };

  const cached = skinUrlCache.get(key);
  if (cached && Date.now() - cached.at < SKIN_CACHE_MS) return { ok: true, url: cached.url };

  try {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 6000);
    const profileRes = await fetch(`https://api.mojang.com/users/profiles/minecraft/${encodeURIComponent(key)}`, {
      signal: controller.signal
    });
    if (!profileRes.ok) { clearTimeout(timer); return { ok: false, url: "" }; }
    const profile = await profileRes.json();
    const textureRes = await fetch(`https://sessionserver.mojang.com/session/minecraft/profile/${profile.id}`, {
      signal: controller.signal
    });
    clearTimeout(timer);
    if (!textureRes.ok) return { ok: false, url: "" };
    const payload = await textureRes.json();
    const encoded = payload?.properties?.find((p) => p.name === "textures")?.value || "";
    const decoded = JSON.parse(Buffer.from(encoded, "base64").toString("utf8"));
    const url = decoded?.textures?.SKIN?.url || "";
    if (url) skinUrlCache.set(key, { url, at: Date.now() });
    return { ok: Boolean(url), url };
  } catch {
    return { ok: false, url: "" };
  }
});

ipcMain.handle("launcher:get-friend-presence", async () => {
  const friends = readSettings().friends || [];
  if (!friends.length) return { ok: true, players: [], reachable: true };

  try {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 6000);
    const res = await fetch(presenceRosterUrl, {
      method: "POST",
      signal: controller.signal,
      headers: {
        "Content-Type": "application/json",
        "User-Agent": `RiverClientLauncher/${app.getVersion()} (WyZ_EU)`
      },
      body: JSON.stringify({ names: friends.map((f) => f.name).filter(Boolean) })
    });
    clearTimeout(timer);
    if (!res.ok) return { ok: false, players: [], reachable: false };
    const data = await res.json();
    // Carry the announced status through rather than flattening to a boolean, so idle
    // and do-not-disturb are visible to friends instead of just reading "online".
    const byName = new Map(
      (Array.isArray(data?.players) ? data.players : [])
        .filter((p) => p && p.online)
        .map((p) => [String(p.name || "").toLowerCase(), String(p.status || "online")])
    );
    return {
      ok: true,
      reachable: true,
      players: friends.map((f) => ({
        id: f.id,
        name: f.name,
        status: byName.get(f.name.toLowerCase()) || "offline"
      }))
    };
  } catch {
    // Offline or the endpoint is not deployed yet: say so instead of guessing.
    return { ok: false, players: [], reachable: false };
  }
});

ipcMain.handle("launcher:analyze-crash", async () => {
  const status = getStatus();
  const info = status.crashInfo;
  if (!info || !info.latest) return null;
  // getCrashInfo ranks crash-reports and logs together by mtime, and latest.log is
  // always the newest file - so pick the newest actual crash report, which is the
  // only thing carrying the "Mixins in Stacktrace" block the analysis needs.
  const report = (info.files || []).find((f) => /crash-.*.txt$/i.test(f.file)) || info.latest;
  try {
    const crashText = fs.readFileSync(report.path, "utf8");
    let logText = "";
    try {
      logText = fs.readFileSync(path.join(status.instancePath, "logs", "latest.log"), "utf8");
    } catch {}
    const analysis = analyzeCrash(crashText, logText, status.installedMods);
    if (!analysis) return null;
    return { ...analysis, report };
  } catch {
    return null;
  }
});

ipcMain.handle("launcher:get-partners", async () => {
  const partners = await fetchPartnerServers();
  if (!partners.length) return [];
  // Resolve live status too: the raw feed carries no online/player data, so without
  // this every partner renders as "Offline" no matter what the server is doing.
  const results = await Promise.allSettled(partners.map(fetchServerStatus));
  return results.map((result, index) =>
    result.status === "fulfilled" ? result.value : partners[index]
  );
});

async function autoSetupOnBoot() {
  if (setupRunning) return;
  setupRunning = true;
  emitStatus();
  emitBoot("Preparing River Client", "Checking project and build files...");

  try {
    emitBoot("Checking launcher updates", "Looking for a newer River Client build...");
    const update = await checkLauncherUpdates();
    if (update.blocking) {
      emitStatus();
      const headline = update.required ? "Update required" : "Update available";
      emitBoot(
        headline,
        `${update.message} Use Install update in the dialog when you are ready.`,
        true,
        false
      );
      return;
    }
    emitBoot("Up to date", update.message || "River Client is up to date.");

    emitBoot("Loading versions", "Refreshing Mojang version manifest...");
    await refreshMojangVersions();

    const bootSettings = readSettings();
    removeRiverInGameJars(path.join(bootSettings.instancePath, "mods"));
    emitBoot("Installing support mods", "Making sure Fabric API and Fabric Language Kotlin are present...");
    const supportInstall = await ensureRequiredSupportMods(
      bootSettings.instancePath,
      bootSettings.selectedVersion || "1.21.11",
      bootSettings.modFilters?.loader || "fabric",
      "river-support-boot"
    );
    if (!supportInstall.ok && !(supportInstall.installed || []).length) {
      emitBoot("Support mod install failed", supportInstall.message, true, true);
      return;
    }

    if (shouldRunOptimizationSuite(bootSettings.instancePath)) {
      emitBoot("Optimizing instance", "Installing River performance mods for this instance...");
      const optimization = await ensureOptimizationSuite(
        bootSettings.instancePath,
        bootSettings.selectedVersion || "1.21.11",
        bootSettings.modFilters?.loader || "fabric",
        "river-optimization-boot"
      );
      if (!optimization.ok && !(optimization.installed || []).length) {
        emitBoot("Optimization failed", optimization.message, true, true);
        return;
      }
      writeInstanceMeta(bootSettings.instancePath, { optimizationAppliedAt: new Date().toISOString() });
    } else {
      emitBoot("Optimization already applied", "River already optimized this instance once. Skipping.");
    }

    // Mod-update availability is informational and never launch-critical, so it must not
    // gate "Ready" - awaiting a full Modrinth scan of a fully-optimized instance (30-50 mods)
    // was stalling the boot for up to ~2 minutes. Fire it in the background instead, skip it
    // when a scan ran recently, and let results surface via emitStatus when it finishes.
    emitBoot("Ready", "River Client is ready to launch Minecraft.", true, false);
    maybeCheckModUpdatesInBackground(readSettings().instancePath);
  } catch (error) {
    emitBoot("Setup failed", error.message, true, true);
  } finally {
    setupRunning = false;
    emitStatus();
  }
}

async function refreshMojangVersions() {
  try {
    const response = await fetch("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json", {
      headers: { "User-Agent": launcherUserAgent }
    });
    if (!response.ok) return { ok: false, message: `Mojang version manifest failed with ${response.status}.` };
    const manifest = await response.json();
    const versions = (manifest.versions || []).map((version) => ({
      id: version.id,
      type: version.type,
      url: version.url,
      time: version.time,
      releaseTime: version.releaseTime
    }));
    fs.mkdirSync(path.dirname(versionsPath()), { recursive: true });
    fs.writeFileSync(versionsPath(), JSON.stringify({
      latest: manifest.latest,
      refreshedAt: new Date().toISOString(),
      versions
    }, null, 2));
    emit("launcher:log", `[launcher] Loaded ${versions.length} Minecraft versions from Mojang.`);
    return { ok: true, message: `Loaded ${versions.length} Minecraft versions.`, count: versions.length };
  } catch (error) {
    return { ok: false, message: `Could not refresh versions: ${error.message}` };
  }
}

async function signInWithMicrosoft() {
  const clientId = getMicrosoftClientId();
  if (!clientId) {
    return { ok: false, message: "River Client login is not configured yet. Add your app client id in launcher/src/config/riv3r-auth.json before shipping." };
  }

  try {
    emit("launcher:log", "[auth] Starting Microsoft device-code sign-in...");
    emit("launcher:auth", { type: "stage", title: "Microsoft sign-in", detail: "Requesting a device code..." });
    const device = await requestMicrosoftDeviceCode(clientId);
    emit("launcher:auth", {
      type: "device-code",
      userCode: device.user_code,
      verificationUri: device.verification_uri
    });
    if (device.verification_uri) await shell.openExternal(device.verification_uri);

    emit("launcher:auth", {
      type: "stage",
      title: "Microsoft sign-in",
      detail: "Waiting for Microsoft to accept the login...",
      userCode: device.user_code,
      verificationUri: device.verification_uri
    });
    const microsoft = await pollMicrosoftToken(clientId, device);
    emit("launcher:auth", { type: "stage", title: "Xbox Live", detail: "Microsoft login accepted. Connecting Xbox Live..." });
    const xbl = await authenticateXboxLive(microsoft.access_token);
    emit("launcher:auth", { type: "stage", title: "Xbox Security", detail: "Authorizing Minecraft services..." });
    const xsts = await authorizeXsts(xbl.Token);
    emit("launcher:auth", { type: "stage", title: "Minecraft", detail: "Getting a Minecraft access token..." });
    const minecraft = await authenticateMinecraft(xsts);
    emit("launcher:auth", { type: "stage", title: "Minecraft ownership", detail: "Checking Java Edition ownership..." });
    await assertMinecraftOwnership(minecraft.access_token);
    emit("launcher:auth", { type: "stage", title: "Minecraft profile", detail: "Loading player profile..." });
    const profile = await fetchMinecraftProfile(minecraft.access_token);
    const auth = writeAuth({
      microsoftRefreshToken: microsoft.refresh_token,
      minecraftAccessToken: minecraft.access_token,
      expiresAt: Date.now() + (Number(minecraft.expires_in || 0) * 1000),
      sessionExpiresAt: Date.now() + AUTH_SESSION_MS,
      profile
    });
    await ensureAccountSkinSeeded(auth);

    emit("launcher:log", `[auth] Signed in as ${profile.name}.`);
    emit("launcher:auth", { type: "done", title: "Signed in", detail: `Signed in as ${profile.name}.` });
    return { ok: true, message: `Signed in as ${profile.name}.`, auth };
  } catch (error) {
    emit("launcher:log", `[auth] Sign-in failed: ${error.message}`);
    emit("launcher:auth", { type: "error", title: "Login failed", detail: error.message });
    return { ok: false, message: error.message };
  }
}

async function refreshMicrosoftSession(auth = readAuth()) {
  const clientId = getMicrosoftClientId();
  if (!clientId) throw new Error("River Client login is not configured.");
  if (!auth.microsoftRefreshToken) throw new Error("Microsoft session expired. Sign in again.");
  if (auth.sessionExpiresAt && Number(auth.sessionExpiresAt) <= Date.now()) {
    clearAuth();
    throw new Error("Microsoft session expired. Sign in again.");
  }

  emit("launcher:log", "[auth] Refreshing Microsoft session...");
  const microsoft = await refreshMicrosoftToken(clientId, auth.microsoftRefreshToken);
  const xbl = await authenticateXboxLive(microsoft.access_token);
  const xsts = await authorizeXsts(xbl.Token);
  const minecraft = await authenticateMinecraft(xsts);
  await assertMinecraftOwnership(minecraft.access_token);
  const profile = await fetchMinecraftProfile(minecraft.access_token);
  return writeAuth({
    ...auth,
    microsoftRefreshToken: microsoft.refresh_token || auth.microsoftRefreshToken,
    minecraftAccessToken: minecraft.access_token,
    expiresAt: Date.now() + (Number(minecraft.expires_in || 0) * 1000),
    sessionExpiresAt: Date.now() + AUTH_SESSION_MS,
    profile
  });
}

async function ensureFreshAuth() {
  const auth = readAuth();
  if (!auth.signedIn) return auth;
  if (auth.minecraftAccessToken && auth.expiresAt > Date.now() + AUTH_REFRESH_SKEW_MS) return auth;
  try {
    const refreshed = await refreshMicrosoftSession(auth);
    emitStatus();
    return refreshed;
  } catch (error) {
    emit("launcher:log", `[auth] Session refresh failed: ${error.message}`);
    emitStatus();
    return withAuthRefreshError(auth, error);
  }
}

function getMicrosoftClientId() {
  const envClientId = String(process.env.RIV3R_MICROSOFT_CLIENT_ID || "").trim();
  if (envClientId) return envClientId;

  try {
    const config = JSON.parse(fs.readFileSync(path.join(__dirname, "config", "riv3r-auth.json"), "utf8"));
    const bundledClientId = String(config.microsoftClientId || "").trim();
    if (bundledClientId) return bundledClientId;
  } catch {
    // Fall through to local settings for developer builds.
  }

  return readSettings().microsoftClientId.trim();
}

async function requestMicrosoftDeviceCode(clientId) {
  const body = new URLSearchParams({
    client_id: clientId,
    scope: "XboxLive.signin offline_access"
  });
  const response = await fetch("https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body
  });
  const payload = await response.json();
  if (!response.ok) throw new Error(payload.error_description || "Microsoft device-code request failed.");
  return payload;
}

async function pollMicrosoftToken(clientId, device) {
  const started = Date.now();
  const expiresAt = started + Number(device.expires_in || 900) * 1000;
  const interval = Math.max(5, Number(device.interval || 5));

  while (Date.now() < expiresAt) {
    await delay(interval * 1000);
    const response = await fetch("https://login.microsoftonline.com/consumers/oauth2/v2.0/token", {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        grant_type: "urn:ietf:params:oauth:grant-type:device_code",
        client_id: clientId,
        device_code: device.device_code
      })
    });
    const payload = await response.json();
    if (response.ok) return payload;
    if (payload.error === "authorization_pending") continue;
    if (payload.error === "slow_down") await delay(5000);
    else throw new Error(payload.error_description || payload.error || "Microsoft token request failed.");
  }

  throw new Error("Microsoft sign-in expired. Start login again.");
}

async function refreshMicrosoftToken(clientId, refreshToken) {
  const response = await fetch("https://login.microsoftonline.com/consumers/oauth2/v2.0/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "refresh_token",
      client_id: clientId,
      refresh_token: refreshToken,
      scope: "XboxLive.signin offline_access"
    })
  });
  const payload = await response.json();
  if (!response.ok) throw new Error(payload.error_description || payload.error || "Microsoft session refresh failed.");
  return payload;
}

async function authenticateXboxLive(accessToken) {
  const response = await fetch("https://user.auth.xboxlive.com/user/authenticate", {
    method: "POST",
    headers: { "Content-Type": "application/json", "Accept": "application/json" },
    body: JSON.stringify({
      Properties: {
        AuthMethod: "RPS",
        SiteName: "user.auth.xboxlive.com",
        RpsTicket: `d=${accessToken}`
      },
      RelyingParty: "http://auth.xboxlive.com",
      TokenType: "JWT"
    })
  });
  const payload = await response.json();
  if (!response.ok) throw new Error(payload.XErr ? `Xbox Live auth failed: ${describeXboxError(payload.XErr)}` : responseMessage(payload, "Xbox Live auth failed."));
  return payload;
}

async function authorizeXsts(userToken) {
  const response = await fetch("https://xsts.auth.xboxlive.com/xsts/authorize", {
    method: "POST",
    headers: { "Content-Type": "application/json", "Accept": "application/json" },
    body: JSON.stringify({
      Properties: {
        SandboxId: "RETAIL",
        UserTokens: [userToken]
      },
      RelyingParty: "rp://api.minecraftservices.com/",
      TokenType: "JWT"
    })
  });
  const payload = await response.json();
  if (!response.ok) {
    throw new Error(payload.XErr ? `XSTS auth failed: ${describeXboxError(payload.XErr)}` : responseMessage(payload, "XSTS auth failed."));
  }
  return payload;
}

async function authenticateMinecraft(xsts) {
  const uhs = xsts.DisplayClaims && xsts.DisplayClaims.xui && xsts.DisplayClaims.xui[0]
    ? xsts.DisplayClaims.xui[0].uhs
    : "";
  const response = await fetch("https://api.minecraftservices.com/authentication/login_with_xbox", {
    method: "POST",
    headers: { "Content-Type": "application/json", "Accept": "application/json" },
    body: JSON.stringify({
      identityToken: `XBL3.0 x=${uhs};${xsts.Token}`
    })
  });
  const payload = await response.json();
  if (!response.ok) throw new Error(responseMessage(payload, "Minecraft authentication failed."));
  return payload;
}

async function assertMinecraftOwnership(accessToken) {
  const response = await fetch("https://api.minecraftservices.com/entitlements/mcstore", {
    headers: { "Authorization": `Bearer ${accessToken}` }
  });
  const payload = await response.json();
  if (!response.ok) throw new Error(responseMessage(payload, "Could not verify Minecraft ownership."));
  const ownsGame = (payload.items || []).some((item) => item.name === "game_minecraft" || item.name === "product_minecraft");
  if (!ownsGame) throw new Error("This Microsoft account does not own Minecraft: Java Edition.");
}

async function fetchMinecraftProfile(accessToken) {
  const response = await fetch("https://api.minecraftservices.com/minecraft/profile", {
    headers: { "Authorization": `Bearer ${accessToken}` }
  });
  const payload = await response.json();
  if (!response.ok) throw new Error(responseMessage(payload, "Could not load Minecraft profile."));
  const skin = Array.isArray(payload.skins) ? payload.skins.find((item) => item.state === "ACTIVE") || payload.skins[0] : null;
  const capes = Array.isArray(payload.capes) ? payload.capes.map(normalizeCapeEntry).filter(Boolean) : [];
  const cape = capes.find((item) => item.active) || null;
  return {
    id: payload.id,
    name: payload.name,
    skinUrl: skin && skin.url ? skin.url : "",
    skinVariant: skin && String(skin.variant || "").toUpperCase() === "SLIM" ? "slim" : "classic",
    capeUrl: cape && cape.url ? cape.url : "",
    capes
  };
}

function responseMessage(payload, fallback) {
  const message = payload.errorMessage || payload.message || payload.error_description || payload.error || fallback;
  if (String(message).toLowerCase().includes("invalid app registration")) {
    return "River Client's Microsoft app registration is not approved for Minecraft Java Edition services yet. Submit the app for Mojang's Java Edition Game Service API review: https://aka.ms/mce-reviewappid";
  }
  return message;
}

function describeXboxError(value) {
  const code = Number(value);
  if (code === 2148916233) return "2148916233, this Microsoft account does not have an Xbox profile. Open xbox.com once and finish profile setup.";
  if (code === 2148916235) return "2148916235, Xbox Live is not available in this account's region.";
  if (code === 2148916236) return "2148916236, this account needs adult verification on Xbox.";
  if (code === 2148916237) return "2148916237, this account needs adult verification on Xbox.";
  if (code === 2148916238) return "2148916238, this account is under 18 and needs family settings changed.";
  return String(value);
}

function delay(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function checkNetwork() {
  networkState = {
    ...networkState,
    checking: true,
    message: "Checking network..."
  };

  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 2500);
  try {
    const response = await fetch("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json", {
      method: "GET",
      signal: controller.signal,
      headers: { "Accept": "application/json" }
    });
    networkState = {
      online: response.ok,
      checking: false,
      message: response.ok ? "Online services connected." : `Network check failed with ${response.status}.`,
      checkedAt: Date.now()
    };
  } catch {
    networkState = {
      online: false,
      checking: false,
      message: "No network connection. Online features are paused.",
      checkedAt: Date.now()
    };
  } finally {
    clearTimeout(timeout);
  }

  return networkState;
}

async function offlineResult(feature) {
  if (!networkState.online || Date.now() - networkState.checkedAt > 5000) {
    await checkNetwork();
    emitStatus();
  }
  if (networkState.online) return null;
  return { ok: false, message: `No network connection. ${feature} is paused.` };
}

function getLauncherUpdateState() {
  return launcherUpdateState;
}

function startLauncherUpdateWatcher() {
  if (updateWatcher) clearInterval(updateWatcher);

  const watch = async () => {
    if (updateCheckRunning) return;
    updateCheckRunning = true;
    const wasBlocking = launcherUpdateState.blocking;
    const previousVersion = launcherUpdateState.latestVersion;
    try {
      const next = await checkLauncherUpdates();
      if (next.blocking || wasBlocking !== next.blocking || previousVersion !== next.latestVersion) {
        emitStatus();
      }
    } finally {
      updateCheckRunning = false;
    }
  };

  watch();
  updateWatcher = setInterval(watch, 15000);
}

async function checkLauncherUpdates() {
  const currentVersion = readBuildVersion();
  try {
    const bundledManifest = readBundledUpdateManifest();
    const remoteManifest = await fetchRemoteUpdateManifest();
    const manifest = selectLauncherUpdateManifest(currentVersion, bundledManifest, remoteManifest);
    if (!manifest) {
      launcherUpdateState = {
        checkedAt: Date.now(),
        available: false,
        blocking: false,
        required: false,
        latestVersion: "",
        currentVersion,
        minimumVersion: "",
      url: "",
      installerUrl: "",
      portableUrl: "",
      packageUrl: "",
      fileManifestUrl: "",
      appFileManifestUrl: "",
      appFileBaseUrl: "",
      fileManifestSha256: "",
      fileCount: 0,
      packageSha256: "",
      packageSize: 0,
      message: "No public update metadata found yet."
      };
      return launcherUpdateState;
    }
    const payload = manifest.payload;
    const latestVersion = String(payload.version || "");
    const minimumVersion = String(payload.minimumVersion || payload.minVersion || "");
    const available = Boolean(latestVersion && compareVersions(latestVersion, currentVersion) > 0);
    const belowMinimum = Boolean(minimumVersion && compareVersions(minimumVersion, currentVersion) > 0);
    const required = belowMinimum || Boolean(payload.required && available);
    const blocking = available || required;
    const installerUrl = resolveUpdateUrl(payload.installerUrl || payload.url || "", updateManifestUrl);
    const portableUrl = resolveUpdateUrl(payload.portableUrl || "", updateManifestUrl);
    const packageUrl = resolveUpdateUrl(payload.packageUrl || payload.appPackageUrl || "", updateManifestUrl);
    const fileManifestUrl = resolveUpdateUrl(payload.fileManifestUrl || payload.filesetUrl || "", updateManifestUrl);
    const appFileManifestUrl = resolveUpdateUrl(payload.appFileManifestUrl || "", updateManifestUrl);
    const appFileBaseUrl = resolveUpdateUrl(payload.appFileBaseUrl || "", updateManifestUrl);
    const packageFile = payload.files && (payload.files.package || payload.files.appPackage);
    const fileManifestFile = payload.files && (payload.files.fileManifest || payload.files.fileset);
    const pageUrl = resolveUpdateUrl(payload.pageUrl || payload.homepage || "https://riverclient.xyz/", updateManifestUrl);
    launcherUpdateState = {
      checkedAt: Date.now(),
      available,
      blocking,
      required,
      latestVersion,
      currentVersion,
      minimumVersion,
      url: appFileManifestUrl || fileManifestUrl || packageUrl || installerUrl || pageUrl,
      installerUrl,
      portableUrl,
      packageUrl,
      fileManifestUrl,
      appFileManifestUrl,
      appFileBaseUrl,
      fileManifestSha256: String(fileManifestFile?.sha256 || payload.fileManifestSha256 || ""),
      fileCount: Number(payload.fileCount || fileManifestFile?.count || 0),
      packageSha256: String(packageFile?.sha256 || payload.packageSha256 || ""),
      packageSize: Number(packageFile?.size || payload.packageSize || 0),
      message: blocking
        ? `River Client ${latestVersion || minimumVersion} is ready to install.`
        : "River Client is up to date."
    };
  } catch (error) {
    launcherUpdateState = {
      checkedAt: Date.now(),
      available: false,
      blocking: false,
      required: false,
      latestVersion: "",
      currentVersion,
      minimumVersion: "",
      url: "",
      installerUrl: "",
      portableUrl: "",
      packageUrl: "",
      fileManifestUrl: "",
      appFileManifestUrl: "",
      appFileBaseUrl: "",
      fileManifestSha256: "",
      fileCount: 0,
      packageSha256: "",
      packageSize: 0,
      message: `Update check failed: ${error.message}`
    };
  }
  return launcherUpdateState;
}

function readBundledUpdateManifest() {
  const candidates = [
    path.join(__dirname, "config", "update-manifest.json"),
    path.join(process.resourcesPath || "", "app.asar", "config", "update-manifest.json")
  ];

  for (const candidate of candidates) {
    try {
      if (!candidate || !fs.existsSync(candidate)) continue;
      return { url: pathToFileURL(candidate).href, payload: JSON.parse(fs.readFileSync(candidate, "utf8")) };
    } catch {}
  }

  return null;
}

function parsePublishedTime(payload) {
  const value = Date.parse(String(payload?.publishedAt || payload?.published_at || "").trim());
  return Number.isFinite(value) ? value : 0;
}

function selectLauncherUpdateManifest(currentVersion, bundledManifest, remoteManifest) {
  if (!bundledManifest) return remoteManifest;
  if (!remoteManifest) return bundledManifest;

  const bundledVersion = String(bundledManifest.payload?.version || "");
  const remoteVersion = String(remoteManifest.payload?.version || "");
  const bundledPublishedAt = parsePublishedTime(bundledManifest.payload);
  const remotePublishedAt = parsePublishedTime(remoteManifest.payload);

  if (bundledVersion && bundledVersion === currentVersion) {
    if (remoteVersion && compareVersions(remoteVersion, bundledVersion) > 0 && remotePublishedAt > bundledPublishedAt) {
      return remoteManifest;
    }
    return bundledManifest;
  }

  if (remoteVersion && compareVersions(remoteVersion, bundledVersion) > 0) return remoteManifest;
  if (bundledPublishedAt >= remotePublishedAt) return bundledManifest;
  return remoteManifest;
}

/**
 * Tester manifest, if this account is on the list and opted into the beta channel.
 *
 * Returns null for everyone else, so the caller falls straight through to the normal
 * stable manifest - not being a tester is an ordinary outcome, not an error.
 */
async function fetchTesterUpdateManifest() {
  if (readSettings().updateChannel !== "beta") return null;
  const token = await riverSignIn();
  if (!token) return null;

  const url = updateManifestUrl.replace(/\/latest\.json$/, "/beta.json");
  let timeout = null;
  try {
    const controller = new AbortController();
    timeout = setTimeout(() => controller.abort(), 5000);
    const response = await fetch(url, {
      signal: controller.signal,
      headers: {
        "Accept": "application/json",
        "User-Agent": launcherUserAgent,
        "Authorization": `Bearer ${token}`
      }
    });
    if (!response.ok) {
      if (response.status === 403) emit("launcher:log", "[update] This account is not on the River tester list.");
      return null;
    }
    return { url, payload: await response.json() };
  } catch {
    return null;
  } finally {
    if (timeout) clearTimeout(timeout);
  }
}

async function fetchRemoteUpdateManifest() {
  // Testers on the beta channel get their manifest first; everyone else, and any failure,
  // falls through to the public one below.
  const tester = await fetchTesterUpdateManifest();
  if (tester) return tester;

  const candidates = [
    updateManifestUrl,
    "https://updates.riverclient.xyz/latest.json",
    "https://riverclient.xyz/releases/latest.json",
    "https://riverclient.xyz/version.json"
  ].filter((value, index, self) => value && self.indexOf(value) === index);

  for (const url of candidates) {
    let timeout = null;
    try {
      const controller = new AbortController();
      timeout = setTimeout(() => controller.abort(), 4000);
      const response = await fetch(url, {
        signal: controller.signal,
        headers: { "Accept": "application/json", "User-Agent": launcherUserAgent }
      });
      if (!response.ok) continue;
      return { url, payload: await response.json() };
    } catch {
      // Try the next update endpoint.
    } finally {
      if (timeout) clearTimeout(timeout);
    }
  }

  return null;
}

function resolveUpdateUrl(value, baseUrl) {
  const raw = String(value || "").trim();
  if (!raw) return "";
  try {
    return new URL(raw, baseUrl).toString();
  } catch {
    return raw;
  }
}

function compareVersions(left, right) {
  const a = String(left || "0").split(/[.-]/).map((part) => Number.parseInt(part, 10) || 0);
  const b = String(right || "0").split(/[.-]/).map((part) => Number.parseInt(part, 10) || 0);
  const length = Math.max(a.length, b.length);
  for (let index = 0; index < length; index += 1) {
    const diff = (a[index] || 0) - (b[index] || 0);
    if (diff !== 0) return diff;
  }
  return 0;
}

/**
 * Creates a clean staging directory for an update.
 *
 * Windows hands out directory handles to antivirus scanners, Explorer preview, and any
 * still-dying updater process, and while one is open a recursive delete or mkdir fails
 * with EPERM/EBUSY - which used to abort the whole update ("EPERM, Permission denied").
 * So: let Node retry with backoff, then fall back to a brand new directory nobody can be
 * holding, and only give up if even that fails. Also sweeps staging dirs left behind by
 * earlier runs so they can't pile up or hold locks.
 */
async function prepareUpdateStagingDir(preferredDir) {
  const stagingRoot = path.dirname(preferredDir);
  try {
    fs.mkdirSync(stagingRoot, { recursive: true });
    // Drop leftovers from previous update attempts (best effort - a locked one is fine,
    // we just move on and use a different directory below).
    for (const entry of fs.readdirSync(stagingRoot)) {
      const full = path.join(stagingRoot, entry);
      if (full === preferredDir) continue;
      try { fs.rmSync(full, { recursive: true, force: true, maxRetries: 2, retryDelay: 100 }); } catch {}
    }
  } catch {}

  let lastError = null;
  for (let attempt = 1; attempt <= 4; attempt += 1) {
    try {
      // maxRetries/retryDelay make Node itself retry EBUSY/ENOTEMPTY/EPERM internally.
      fs.rmSync(preferredDir, { recursive: true, force: true, maxRetries: 5, retryDelay: 200 });
      fs.mkdirSync(preferredDir, { recursive: true });
      return preferredDir;
    } catch (error) {
      lastError = error;
      if (!["EPERM", "EBUSY", "EACCES", "ENOTEMPTY"].includes(String(error?.code || ""))) throw error;
      await sleep(Math.min(1200, 200 * attempt));
    }
  }

  // Something is holding that exact path. Use a fresh one instead of failing the update.
  for (let attempt = 1; attempt <= 3; attempt += 1) {
    const fallback = `${preferredDir}-${crypto.randomBytes(3).toString("hex")}`;
    try {
      fs.mkdirSync(fallback, { recursive: true });
      emit("launcher:log", `[update] Staging folder was locked; using ${path.basename(fallback)} instead.`);
      return fallback;
    } catch (error) {
      lastError = error;
    }
  }

  const detail = lastError?.code === "EPERM" || lastError?.code === "EACCES"
    ? "Windows blocked River from preparing the update folder. This is usually antivirus or Controlled Folder Access holding the folder open. Close River Client, then try again."
    : (lastError?.message || "Could not prepare the update folder.");
  throw new Error(detail);
}

async function installLauncherUpdate(forcedUpdate = null) {
  const update = forcedUpdate || await checkLauncherUpdates();
  const updaterJob = isUpdaterMode ? readUpdaterJob(updaterJobPath) : null;
  if (!forcedUpdate) emitStatus();

  if (!update.blocking) return { ok: true, message: "River Client is already up to date." };
  if (!app.isPackaged) return { ok: false, message: "Package updates only run in the installed launcher build." };
  if (!isUpdaterMode) {
    try {
      launchDedicatedUpdaterProcess(update);
      appIsQuitting = true;
      setTimeout(() => {
        try { app.quit(); } catch {}
      }, 120);
      return { ok: true, message: "River Client Updater is starting." };
    } catch (error) {
      return { ok: false, message: `Could not start River Client Updater: ${error.message}` };
    }
  }
  const differentialManifestUrl = update.fileManifestUrl || update.appFileManifestUrl || "";
  const differentialBaseUrl = update.appFileBaseUrl || "";

  if (!update.packageUrl && !differentialManifestUrl) {
    return {
      ok: false,
      message: "This update was published without a smart file manifest or app package. Publish the release again, then try again."
    };
  }

  const targetVersion = update.latestVersion || update.minimumVersion;
  // Stage the download, extraction, apply script and rollback under the OS temp dir
  // (%LOCALAPPDATA%\Temp) rather than %APPDATA%\Roaming. Roaming sits inside the folders
  // Windows Controlled Folder Access (ransomware protection) guards, and CFA hard-blocks
  // writes there with EPERM that no retry or fresh-folder fallback can beat - which is the
  // recurring "EPERM, Permission denied ... \updates\<ver>\staging" failure. Temp is
  // explicitly outside CFA, is the same volume as the install dir (so the apply copy never
  // crosses drives), and is where every other temp operation in this app already stages.
  // The app<->updater handoff file (updaterJobFilePath) stays in userData and is unaffected.
  const updateRoot = path.join(os.tmpdir(), "river-client-updates");
  const versionDir = path.join(updateRoot, sanitizeFilename(targetVersion || "latest"));
  const packageFile = path.join(versionDir, "river-client-app.zip");
  // May be swapped for a fresh path if Windows has this one locked (see prepareUpdateStagingDir).
  let extractDir = path.join(versionDir, "staging", `${Date.now()}-${process.pid}`);

  fs.mkdirSync(versionDir, { recursive: true });
  const exePath = String(updaterJob?.exePath || app.getPath("exe"));
  const installDir = String(updaterJob?.installDir || path.dirname(exePath));

  // Most installs are per-user (%LOCALAPPDATA%) and never need this, but if River was
  // installed somewhere the current user can't write to (e.g. Program Files), relaunch
  // the updater elevated via a native UAC prompt before touching any install files.
  if (!canWriteToInstallDir(installDir)) {
    await relaunchUpdaterElevated(updaterJobPath);
    setTimeout(() => { try { app.exit(0); } catch {} }, 120);
    return { ok: true, message: "Requesting administrator permission to continue the update." };
  }

  let applyMode = "package";
  let releaseManifestPath = "";

  if (differentialManifestUrl) {
    try {
      emit("launcher:log", `[update] Fetching River Client ${targetVersion} file manifest...`);
      const remoteFileManifest = await fetchRemoteJson(differentialManifestUrl);
      if (update.fileManifestSha256) {
        verifyJsonSha256(remoteFileManifest, update.fileManifestSha256);
      }
      const normalizedFileManifest = {
        ...remoteFileManifest,
        files: Array.isArray(remoteFileManifest?.files)
          ? remoteFileManifest.files
            .map((entry) => ({
              ...entry,
              path: normalizeUpdateFilePath(entry?.path || ""),
              url: String(entry?.url || "").trim()
                || (differentialBaseUrl
                  ? new URL(normalizeUpdateFilePath(entry?.path || ""), differentialBaseUrl).toString()
                  : "")
            }))
            .filter((entry) => entry.path && entry.url)
          : []
      };
      const filePlan = buildDifferentialUpdatePlan(normalizedFileManifest, installDir);
      releaseManifestPath = path.join(versionDir, "release-files.json");
      extractDir = await prepareUpdateStagingDir(extractDir);
      fs.writeFileSync(releaseManifestPath, JSON.stringify({
        version: targetVersion,
        files: filePlan.files.map((entry) => ({ path: entry.path })),
        changedFiles: filePlan.downloads.map((entry) => entry.path)
      }, null, 2));
      await downloadChangedUpdateFiles(filePlan, extractDir, targetVersion);
      applyMode = "delta";
    } catch (error) {
      emit("launcher:log", `[update] Smart update fallback: ${error.message}`);
    }
  }

  if (applyMode !== "delta") {
    if (!update.packageUrl) {
      return { ok: false, message: "Smart update was unavailable and no full app package was published for fallback." };
    }
    emitActivity({ title: "Updating River Client", detail: `Starting ${targetVersion} download...`, current: 0, total: update.packageSize || 0, unit: "bytes" });
    emit("launcher:log", `[update] Downloading River Client ${targetVersion} package...`);
    await downloadUpdatePackage(update.packageUrl, packageFile, {
      totalBytes: update.packageSize,
      version: targetVersion
    });
    emitActivity({ title: "Updating River Client", detail: "Verifying update package...", current: 2, total: 5 });
    if (update.packageSha256) verifyFileSha256(packageFile, update.packageSha256);

    emitActivity({ title: "Updating River Client", detail: "Unpacking update package...", current: 3, total: 5 });
    extractDir = await prepareUpdateStagingDir(extractDir);
    await runPowerShell([
      "-NoProfile",
      "-ExecutionPolicy", "Bypass",
      "-Command",
      `Expand-Archive -LiteralPath '${escapePowerShell(packageFile)}' -DestinationPath '${escapePowerShell(extractDir)}' -Force`
    ]);
  }

  if (path.resolve(installDir) === path.resolve(extractDir)) {
    throw new Error(`Updater resolved the install folder to its own staging directory (${installDir}).`);
  }

  /** Must match electron-builder `win.executableName` + .exe (the app inside the update zip). */
  const packagedMainExe = path.join(installDir, "RiverClient.exe");
  const relaunchExe = packagedMainExe;
  const relaunchFallbackExe = path.join(installDir, path.basename(exePath));
  const scriptPath = path.join(versionDir, "apply-update.ps1");
  const bootstrapPath = path.join(versionDir, "apply-update.cmd");
  const logPath = path.join(versionDir, "apply-update.log");
  const script = buildApplyUpdateScript({
    pid: process.pid,
    sourceDir: extractDir,
    installDir,
    relaunchExe,
    relaunchFallbackExe,
    logPath,
    updateRoot,
    mode: applyMode,
    manifestPath: releaseManifestPath
  });

  const vbsPath = path.join(versionDir, "apply-update-launch.vbs");
  fs.writeFileSync(scriptPath, script, "utf8");
  fs.writeFileSync(bootstrapPath, buildApplyUpdateBootstrap({ scriptPath, logPath }), "utf8");
  fs.writeFileSync(vbsPath, buildApplyUpdateVbs({ bootstrapPath }), "utf8");
  emitActivity({
    title: "Updating River Client",
    detail: applyMode === "delta"
      ? "Applying downloaded files and cleaning old launcher resources..."
      : "Applying changed files and removing files no longer used...",
    current: 4,
    total: 5
  });
  emit("launcher:log", "[update] Applying changed files, deleting removed files, and restarting River Client...");

  const wscriptExe = path.join(process.env.SystemRoot || "C:\\Windows", "System32", "wscript.exe");
  spawn(wscriptExe, ["//B", "//Nologo", vbsPath], {
    detached: true,
    stdio: "ignore",
    windowsHide: true
  }).unref();

  emit("launcher:log", "[update] Update handoff complete. Closing River Client so the updated build can relaunch.");
  try {
    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.hide();
      mainWindow.destroy();
    }
  } catch {}
  setTimeout(() => {
    try { app.exit(0); } catch {}
    try { process.exit(0); } catch {}
  }, 120);
  try { app.quit(); } catch {}
  return { ok: true, message: "River Client is restarting to finish the update." };
}

/** Preview-only: animates a fake update so the updater window can be inspected without a real release. */
function runUpdaterDemo() {
  if (!mainWindow || mainWindow.isDestroyed()) return;
  mainWindow.webContents.once("did-finish-load", () => {
    const totalBytes = 84 * 1024 * 1024;
    const stepBytes = totalBytes * 0.08;
    let downloaded = 0;
    emitActivity({ title: "Downloading update", detail: "River Client 1.21.11", current: 0, total: totalBytes, unit: "bytes", percent: 0, speed: "0 B/s", eta: "Calculating..." });
    const timer = setInterval(() => {
      downloaded = Math.min(totalBytes, downloaded + stepBytes);
      if (downloaded >= totalBytes) {
        clearInterval(timer);
        emitActivity({ title: "Applying update", detail: "Updating launcher files...", current: 4, total: 5 });
        setTimeout(() => emitActivity({ title: "Update complete", detail: "River Client will reopen automatically.", current: 1, total: 1, done: true }), 900);
        return;
      }
      emitActivity({
        title: "Downloading update",
        detail: `${formatBytes(downloaded)} / ${formatBytes(totalBytes)} downloaded`,
        current: downloaded, total: totalBytes, unit: "bytes",
        percent: (downloaded / totalBytes) * 100,
        speed: `${formatBytes(stepBytes * 2.5)}/s`,
        eta: `${Math.max(1, Math.ceil(((totalBytes - downloaded) / stepBytes) * 0.4))}s`
      });
    }, 400);
  });
}

async function runUpdaterMode() {
  if (updaterRunPromise) return updaterRunPromise;
  updaterRunPromise = runUpdaterModeOnce();
  try {
    return await updaterRunPromise;
  } finally {
    updaterRunPromise = null;
  }
}

async function runUpdaterModeOnce() {
  emitActivity({
    title: "River Client Updater",
    detail: "Preparing updater job...",
    current: 0,
    total: 1
  });
  try {
    const job = readUpdaterJob(updaterJobPath);
    const installDir = String(job?.installDir || "").trim();
    if (installDir && fs.existsSync(installDir)) process.chdir(installDir);
    const update = job?.update || null;
    if (!update) throw new Error("Updater job did not include release data.");
    emitActivity({
      title: "River Client Updater",
      detail: `Ready to install River Client ${update.latestVersion || update.minimumVersion || ""}`.trim(),
      current: 0,
      total: 1
    });
    await installLauncherUpdate(update);
  } catch (error) {
    emitActivity({
      title: "River Client Updater",
      detail: describeUpdateError(error),
      current: 1,
      total: 1,
      done: true,
      error: true
    });
  }
}

/**
 * Turns raw fs/OS errors into something a player can act on. Windows errors arrive with
 * `\\?\`-prefixed paths repeated twice, which is unreadable in the updater window.
 */
function describeUpdateError(error) {
  const code = String(error?.code || "");
  if (code === "EPERM" || code === "EACCES") {
    return "Windows blocked River from writing the update files. Close River Client and Minecraft, then click Retry. If it keeps failing, allow River Client through your antivirus or Controlled Folder Access.";
  }
  if (code === "EBUSY") {
    return "The update files are still in use. Close River Client and Minecraft, then click Retry.";
  }
  if (code === "ENOSPC") return "There is not enough disk space to install the update.";
  if (code === "ENOTEMPTY") return "River could not clear the old update files. Click Retry.";
  const raw = String(error?.message || "Updater failed before install could start.");
  // Strip the Windows extended-length prefix and any duplicated quoted path.
  return raw.replace(/\\\\\?\\/g, "").replace(/\s*'[^']*'\s*$/, "").trim() || "Updater failed before install could start.";
}

ipcMain.handle("launcher:retry-updater", async () => {
  if (!isUpdaterMode) return { ok: false };
  await runUpdaterMode();
  return { ok: true };
});

async function fetchRemoteJson(url) {
  const response = await fetch(url, {
    headers: {
      "Accept": "application/json",
      "User-Agent": launcherUserAgent
    }
  });
  if (!response.ok) throw new Error(`Request failed with ${response.status}.`);
  return response.json();
}

function verifyJsonSha256(payload, expected) {
  const actual = crypto.createHash("sha256").update(JSON.stringify(payload)).digest("hex");
  if (actual.toLowerCase() !== String(expected).toLowerCase()) {
    throw new Error("Update file manifest checksum did not match.");
  }
}

function normalizeUpdateFilePath(value) {
  return String(value || "").replace(/\\/g, "/").replace(/^\/+/, "");
}

function listRelativeFiles(root) {
  const files = [];
  if (!root || !fs.existsSync(root)) return files;
  const stack = [root];
  while (stack.length) {
    const current = stack.pop();
    for (const entry of fs.readdirSync(current, { withFileTypes: true })) {
      const full = path.join(current, entry.name);
      if (entry.isDirectory()) {
        stack.push(full);
        continue;
      }
      files.push(normalizeUpdateFilePath(path.relative(root, full)));
    }
  }
  return files.sort((left, right) => left.localeCompare(right));
}

function buildDifferentialUpdatePlan(fileManifest, installDir) {
  const files = Array.isArray(fileManifest?.files)
    ? fileManifest.files
      .map((entry) => ({
        path: normalizeUpdateFilePath(entry.path),
        size: Number(entry.size || 0),
        sha256: String(entry.sha256 || "").trim().toLowerCase(),
        url: String(entry.url || "").trim()
      }))
      .filter((entry) => entry.path && entry.url)
    : [];

  const downloads = [];
  for (const entry of files) {
    const localPath = path.join(installDir, ...entry.path.split("/"));
    if (!fs.existsSync(localPath)) {
      downloads.push(entry);
      continue;
    }
    const localSize = Number(fs.statSync(localPath).size || 0);
    if (entry.size > 0 && localSize !== entry.size) {
      downloads.push(entry);
      continue;
    }
    if (entry.sha256) {
      const localSha = crypto.createHash("sha256").update(fs.readFileSync(localPath)).digest("hex").toLowerCase();
      if (localSha !== entry.sha256) downloads.push(entry);
    }
  }

  return {
    version: String(fileManifest?.version || ""),
    files,
    downloads,
    totalBytes: downloads.reduce((sum, entry) => sum + Number(entry.size || 0), 0),
    existingFiles: listRelativeFiles(installDir)
  };
}

async function downloadChangedUpdateFiles(plan, extractDir, targetVersion) {
  if (!plan.downloads.length) {
    emitActivity({
      title: "Updating River Client",
      detail: `River Client ${targetVersion} has no changed files to download.`,
      current: 0,
      total: 1
    });
    return;
  }

  let downloadedBase = 0;
  for (let index = 0; index < plan.downloads.length; index += 1) {
    const file = plan.downloads[index];
    const target = path.join(extractDir, ...file.path.split("/"));
    fs.mkdirSync(path.dirname(target), { recursive: true });
    emit("launcher:log", `[update] Downloading changed file ${index + 1}/${plan.downloads.length}: ${file.path}`);
    await downloadFileWithProgress(file.url, target, {
      totalBytes: file.size,
      onProgress: ({ downloaded, total, speed, eta }) => {
        const grandTotal = plan.totalBytes || total || 0;
        const current = downloadedBase + downloaded;
        emitActivity({
          title: "Updating River Client",
          // Keep the raw file path in the log only; the window shows a clean count.
          detail: `Downloading update files (${index + 1} of ${plan.downloads.length})`,
          current,
          total: grandTotal,
          unit: "bytes",
          percent: grandTotal > 0 ? (current / grandTotal) * 100 : 0,
          speed: `${formatBytes(speed)}/s`,
          eta: grandTotal > 0 ? formatDuration(eta) : "Calculating..."
        });
      }
    });
    if (file.sha256) verifyFileSha256(target, file.sha256);
    downloadedBase += Number(file.size || 0);
  }
}

async function downloadUpdatePackage(url, target, options = {}) {
  await downloadFileWithProgress(url, target, {
    totalBytes: options.totalBytes,
    onProgress: ({ downloaded, total, speed, eta }) => {
      emitActivity({
        title: "Downloading update",
        detail: total > 0
          ? `${formatBytes(downloaded)} / ${formatBytes(total)} downloaded`
          : `${formatBytes(downloaded)} downloaded`,
        current: downloaded,
        total,
        unit: "bytes",
        percent: total > 0 ? (downloaded / total) * 100 : 0,
        speed: `${formatBytes(speed)}/s`,
        eta: total > 0 ? formatDuration(eta) : "Calculating..."
      });
    }
  });
}

async function downloadFileWithProgress(url, target, options = {}) {
  const response = await fetch(url, {
    headers: { "User-Agent": `RiverClientLauncher/${app.getVersion()} (WyZ_EU)` }
  });
  if (!response.ok || !response.body) throw new Error(`Update package download failed with ${response.status}.`);
  const temp = `${target}.download`;
  fs.rmSync(temp, { force: true });

  const total = Number(options.totalBytes || response.headers.get("content-length") || 0);
  const startedAt = Date.now();
  let downloaded = 0;
  let lastEmit = 0;
  const reader = response.body.getReader();
  const output = fs.createWriteStream(temp);

  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      downloaded += value.byteLength;
      if (!output.write(Buffer.from(value))) {
        await new Promise((resolve) => output.once("drain", resolve));
      }

      const now = Date.now();
      if (now - lastEmit >= 250 || (total && downloaded >= total)) {
        lastEmit = now;
        const elapsedSeconds = Math.max(0.001, (now - startedAt) / 1000);
        const bytesPerSecond = downloaded / elapsedSeconds;
        const remaining = total > 0 ? Math.max(0, total - downloaded) : 0;
        const etaSeconds = total > 0 && bytesPerSecond > 0 ? remaining / bytesPerSecond : 0;
        options.onProgress?.({
          downloaded,
          total,
          speed: bytesPerSecond,
          eta: etaSeconds
        });
      }
    }
    await new Promise((resolve, reject) => {
      const handleError = (error) => {
        output.off("close", handleClose);
        reject(error);
      };
      const handleClose = () => {
        output.off("error", handleError);
        resolve();
      };
      output.once("error", handleError);
      output.once("close", handleClose);
      output.end();
    });
  } catch (error) {
    output.destroy();
    fs.rmSync(temp, { force: true });
    throw error;
  }
  await finalizeDownloadedTempFile(temp, target);
}

async function finalizeDownloadedTempFile(temp, target) {
  let lastError = null;
  for (let attempt = 1; attempt <= 8; attempt += 1) {
    try {
      if (fs.existsSync(target)) fs.rmSync(target, { force: true });
      fs.renameSync(temp, target);
      return;
    } catch (error) {
      lastError = error;
      if (!["EPERM", "EBUSY", "EACCES", "ENOTEMPTY"].includes(String(error?.code || ""))) break;
      await sleep(Math.min(1200, 150 * attempt));
    }
  }

  for (let attempt = 1; attempt <= 4; attempt += 1) {
    try {
      if (fs.existsSync(target)) fs.rmSync(target, { force: true });
      fs.copyFileSync(temp, target);
      fs.rmSync(temp, { force: true });
      return;
    } catch (error) {
      lastError = error;
      if (!["EPERM", "EBUSY", "EACCES", "ENOTEMPTY"].includes(String(error?.code || ""))) break;
      await sleep(Math.min(1500, 250 * attempt));
    }
  }

  throw lastError || new Error(`Could not move downloaded file into place: ${target}`);
}

function formatBytes(bytes) {
  const value = Number(bytes || 0);
  if (value < 1024) return `${Math.max(0, Math.round(value))} B`;
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`;
  if (value < 1024 * 1024 * 1024) return `${(value / 1024 / 1024).toFixed(1)} MB`;
  return `${(value / 1024 / 1024 / 1024).toFixed(2)} GB`;
}

function formatDuration(seconds) {
  const value = Math.max(0, Math.round(Number(seconds || 0)));
  if (value < 1) return "Less than 1s";
  if (value < 60) return `${value}s`;
  const minutes = Math.floor(value / 60);
  const remainder = value % 60;
  if (minutes < 60) return remainder ? `${minutes}m ${remainder}s` : `${minutes}m`;
  const hours = Math.floor(minutes / 60);
  const minuteRemainder = minutes % 60;
  return minuteRemainder ? `${hours}h ${minuteRemainder}m` : `${hours}h`;
}

function verifyFileSha256(file, expected) {
  const actual = crypto.createHash("sha256").update(fs.readFileSync(file)).digest("hex");
  if (actual.toLowerCase() !== String(expected).toLowerCase()) {
    fs.rmSync(file, { force: true });
    throw new Error("Update package checksum did not match. The download was deleted.");
  }
}

function buildApplyUpdateScript({ pid, sourceDir, installDir, relaunchExe, relaunchFallbackExe, logPath, updateRoot, mode = "package", manifestPath = "" }) {
  return [
    "$ErrorActionPreference = 'Stop'",
    `$host.SetShouldExit(0)`,
    "Start-Sleep -Seconds 2",
    `Wait-Process -Id ${Number(pid)} -ErrorAction SilentlyContinue`,
    "$deadline = (Get-Date).AddSeconds(30)",
    "while ((Get-Process -Name 'RiverClient' -ErrorAction SilentlyContinue) -and (Get-Date) -lt $deadline) { Start-Sleep -Milliseconds 500 }",
    "Start-Sleep -Seconds 1",
    `$source = '${escapePowerShell(sourceDir)}'`,
    `$target = '${escapePowerShell(installDir)}'`,
    `$log = '${escapePowerShell(logPath)}'`,
    `$mode = '${escapePowerShell(mode)}'`,
    `$manifestPath = '${escapePowerShell(manifestPath)}'`,
    `$primary = '${escapePowerShell(relaunchExe)}'`,
    `$fallback = '${escapePowerShell(relaunchFallbackExe)}'`,
    `$backup = Join-Path '${escapePowerShell(updateRoot)}' 'rollback'`,
    "New-Item -ItemType Directory -Force -Path (Split-Path -Parent $log) | Out-Null",
    "Set-Content -Path $log -Value ('Update script started: ' + (Get-Date).ToString('s')) -Encoding utf8",
    "function Copy-WithRetry([string]$from, [string]$to) {",
    "  $lastError = $null",
    "  for ($attempt = 1; $attempt -le 10; $attempt++) {",
    "    try { Copy-Item -LiteralPath $from -Destination $to -Force -ErrorAction Stop; return }",
    "    catch { $lastError = $_; Start-Sleep -Milliseconds ([Math]::Min(2500, 250 * $attempt)) }",
    "  }",
    "  throw $lastError",
    "}",
    // If the zip extracted into a single subdirectory, use that as the source
    // so files land directly in the install dir rather than in a subfolder.
    "$children = @(Get-ChildItem -LiteralPath $source -Force)",
    "if ($children.Count -eq 1 -and $children[0].PSIsContainer) { $source = $children[0].FullName }",
    "Add-Content -Path $log -Value \"Source: $source\"",
    "Add-Content -Path $log -Value \"Target: $target\"",
    "Remove-Item -LiteralPath $backup -Recurse -Force -ErrorAction SilentlyContinue",
    "New-Item -ItemType Directory -Force -Path $backup | Out-Null",
    "try {",
    "if ($mode -eq 'delta' -and (Test-Path -LiteralPath $manifestPath)) {",
    "  Add-Content -Path $log -Value \"Applying smart differential update...\"",
    "  $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json",
    "  foreach ($entry in @($manifest.changedFiles)) {",
    "    $relative = [string]$entry",
    "    if ([string]::IsNullOrWhiteSpace($relative)) { continue }",
    "    $parts = $relative -split '/'",
    "    $sourceFile = Join-Path $source ([System.IO.Path]::Combine($parts))",
    "    $targetFile = Join-Path $target ([System.IO.Path]::Combine($parts))",
    "    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $targetFile) | Out-Null",
    "    if (Test-Path -LiteralPath $targetFile) {",
    "      $backupFile = Join-Path $backup ([System.IO.Path]::Combine($parts))",
    "      New-Item -ItemType Directory -Force -Path (Split-Path -Parent $backupFile) | Out-Null",
    "      Copy-WithRetry $targetFile $backupFile",
    "    }",
    "    Copy-WithRetry $sourceFile $targetFile",
    "    Add-Content -Path $log -Value (\"Copied changed file: \" + $relative)",
    "  }",
    "  $resourceAllowed = @(@($manifest.files | ForEach-Object { [string]$_.path }) | Where-Object { $_ -like 'resources/*' })",
    "  $localeAllowed = @(@($manifest.files | ForEach-Object { [string]$_.path }) | Where-Object { $_ -like 'locales/*' })",
    "  foreach ($scope in @(@{ Root = 'resources'; Allowed = $resourceAllowed }, @{ Root = 'locales'; Allowed = $localeAllowed })) {",
    "    $scopeRoot = Join-Path $target $scope.Root",
    "    if (-not (Test-Path -LiteralPath $scopeRoot)) { continue }",
    "    Get-ChildItem -LiteralPath $scopeRoot -File -Recurse -ErrorAction SilentlyContinue | ForEach-Object {",
    "      $relative = ($_.FullName.Substring($target.Length).TrimStart('\\') -replace '\\\\','/')",
    "      if ($scope.Allowed -notcontains $relative) {",
    "        Remove-Item -LiteralPath $_.FullName -Force -ErrorAction SilentlyContinue",
    "        Add-Content -Path $log -Value (\"Removed old file: \" + $relative)",
    "      }",
    "    }",
    "    Get-ChildItem -LiteralPath $scopeRoot -Directory -Recurse -ErrorAction SilentlyContinue | Sort-Object FullName -Descending | ForEach-Object {",
    "      if (-not (Get-ChildItem -LiteralPath $_.FullName -Force -ErrorAction SilentlyContinue | Select-Object -First 1)) {",
    "        Remove-Item -LiteralPath $_.FullName -Force -ErrorAction SilentlyContinue",
    "      }",
    "    }",
    "  }",
    "} else {",
    "  $resourcesSource = Join-Path $source 'resources'",
    "  $resourcesTarget = Join-Path $target 'resources'",
    "  if (Test-Path -LiteralPath $resourcesSource) {",
    "    Add-Content -Path $log -Value \"Mirroring resources directory...\"",
    "    robocopy $resourcesSource $resourcesTarget /MIR /R:8 /W:2 /NFL /NDL /NJH /NJS /NP | Add-Content -Path $log",
    "    $code = $LASTEXITCODE",
    "    if ($code -gt 7) { Add-Content -Path $log -Value \"resources robocopy exit code: $code\"; throw \"resources copy failed with code $code\" }",
    "  }",
    "  $localesSource = Join-Path $source 'locales'",
    "  $localesTarget = Join-Path $target 'locales'",
    "  if (Test-Path -LiteralPath $localesSource) {",
    "    Add-Content -Path $log -Value \"Mirroring locales directory...\"",
    "    robocopy $localesSource $localesTarget /MIR /R:8 /W:2 /NFL /NDL /NJH /NJS /NP | Add-Content -Path $log",
    "    $code = $LASTEXITCODE",
    "    if ($code -gt 7) { Add-Content -Path $log -Value \"locales robocopy exit code: $code\"; throw \"locales copy failed with code $code\" }",
    "  }",
    "  Add-Content -Path $log -Value \"Copying top-level launcher files...\"",
    "  Get-ChildItem -LiteralPath $source -Force | Where-Object { -not $_.PSIsContainer } | ForEach-Object {",
    "    $dest = Join-Path $target $_.Name",
    "    Copy-Item -LiteralPath $_.FullName -Destination $dest -Force",
    "    Add-Content -Path $log -Value (\"Copied file: \" + $_.Name)",
    "  }",
    "}",
    "} catch {",
    "  $failure = $_.Exception.Message",
    "  Add-Content -Path $log -Value (\"Update failed: \" + $failure)",
    "  if ($mode -eq 'delta' -and (Test-Path -LiteralPath $manifestPath)) {",
    "    $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json",
    "    foreach ($entry in @($manifest.changedFiles)) {",
    "      $relative = [string]$entry",
    "      if ([string]::IsNullOrWhiteSpace($relative)) { continue }",
    "      $parts = $relative -split '/'",
    "      $backupFile = Join-Path $backup ([System.IO.Path]::Combine($parts))",
    "      $targetFile = Join-Path $target ([System.IO.Path]::Combine($parts))",
    "      if (Test-Path -LiteralPath $backupFile) { Copy-WithRetry $backupFile $targetFile } else { Remove-Item -LiteralPath $targetFile -Force -ErrorAction SilentlyContinue }",
    "    }",
    "    Add-Content -Path $log -Value 'Previous launcher files restored.'",
    "  }",
    "  $restart = if (Test-Path -LiteralPath $primary) { $primary } else { $fallback }",
    "  if (Test-Path -LiteralPath $restart) { Start-Process -FilePath $restart -ArgumentList @('--river-update-failed=' + $log) -WorkingDirectory $target -WindowStyle Normal }",
    "  exit 1",
    "}",
    "$start = if (Test-Path -LiteralPath $primary) { $primary } elseif (Test-Path -LiteralPath $fallback) { $fallback } else { throw \"No executable found after update. Expected $primary or $fallback.\" }",
    "Add-Content -Path $log -Value \"Relaunch: $start\"",
    "Start-Sleep -Seconds 1",
    "Start-Process -FilePath $start -WorkingDirectory $target -WindowStyle Normal",
    "Add-Content -Path $log -Value 'Relaunch started.'",
    "Start-Sleep -Seconds 3",
    `Get-ChildItem -LiteralPath '${escapePowerShell(updateRoot)}' -Directory -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -Skip 2 | Remove-Item -Recurse -Force -ErrorAction SilentlyContinue`
  ].join("\r\n");
}

// Launch the update bootstrap with zero visible window. Node's windowsHide is
// silently dropped when a process is spawned detached (which we need so the helper
// outlives the launcher), so a cmd/powershell console flashes on screen. wscript.exe
// is a GUI host with no console of its own, and WScript.Shell.Run with window style 0
// starts cmd fully hidden and independent - so nothing appears while the update
// applies and relaunches.
function buildApplyUpdateVbs({ bootstrapPath }) {
  const cmd = String(bootstrapPath).replace(/"/g, '""');
  return `CreateObject("WScript.Shell").Run "cmd /d /s /c ""${cmd}""", 0, False\r\n`;
}

function buildApplyUpdateBootstrap({ scriptPath, logPath }) {
  // The bootstrap must NOT redirect into the same file the PowerShell script writes:
  // cmd holds the redirect target open, so the script's first Set-Content on it fails
  // ("used by another process") and, with $ErrorActionPreference='Stop', the whole
  // update aborts before copying a single file. Give the bootstrap its own log.
  const bootLog = String(logPath).replace(/\.log$/i, "") + "-boot.log";
  const esc = (value) => String(value).replace(/"/g, "\"\"");
  return [
    "@echo off",
    `set \"LOG=${esc(bootLog)}\"`,
    "if not exist \"%~dp0\" exit /b 1",
    "echo Update bootstrap started: %DATE% %TIME%>>\"%LOG%\"",
    `powershell.exe -NoProfile -ExecutionPolicy Bypass -File "${esc(scriptPath)}" >>"%LOG%" 2>&1`,
    // `echo ... %ERRORLEVEL%>>"file"` makes cmd read the digit as a stream handle
    // (`1>>file`), swallowing the code. Stash it first, and keep a space before `>>`.
    "set \"RC=%ERRORLEVEL%\"",
    "echo Update bootstrap finished with exit code %RC% >>\"%LOG%\"",
    "exit /b %RC%"
  ].join("\r\n");
}

function runPowerShell(args) {
  return new Promise((resolve, reject) => {
    const child = spawn("powershell.exe", args, { windowsHide: true });
    let stderr = "";
    child.stderr.on("data", (chunk) => { stderr += chunk.toString(); });
    child.on("error", reject);
    child.on("exit", (code) => {
      if (code === 0) resolve();
      else reject(new Error(stderr.trim() || `PowerShell failed with code ${code}.`));
    });
  });
}

function escapePowerShell(value) {
  return String(value).replace(/'/g, "''");
}

/** Probes for write access by creating and removing a throwaway file, without touching real install files. */
function canWriteToInstallDir(installDir) {
  const probe = path.join(installDir, `.river-write-check-${process.pid}-${Date.now()}.tmp`);
  try {
    fs.writeFileSync(probe, "ok");
    fs.rmSync(probe, { force: true });
    return true;
  } catch (error) {
    if (error?.code === "EPERM" || error?.code === "EACCES") return false;
    throw error;
  }
}

/**
 * Relaunches the updater as a new elevated process via a native UAC prompt, carrying
 * the same job file forward so the elevated copy resumes the same update. Throws
 * (surfacing as an update failure in the UI) if the user cancels the UAC prompt.
 */
async function relaunchUpdaterElevated(jobPath) {
  emitActivity({
    title: "River Client Updater",
    detail: "Requesting administrator permission...",
    current: 0,
    total: 1
  });
  const exePath = app.getPath("exe");
  const args = [`--river-updater-job=${jobPath}`];
  const argList = args.map((arg) => `'${escapePowerShell(arg)}'`).join(",");
  await runPowerShell([
    "-NoProfile",
    "-ExecutionPolicy", "Bypass",
    "-Command",
    `Start-Process -FilePath '${escapePowerShell(exePath)}' -ArgumentList @(${argList}) -Verb RunAs`
  ]);
}

function parseJvmArgs(value) {
  const input = String(value || "").trim();
  if (!input) return [];
  const matches = input.match(/"[^"]*"|'[^']*'|[^\s]+/g) || [];
  return matches
    .map((part) => part.replace(/^["']|["']$/g, ""))
    .filter(Boolean);
}

function ensureRequiredJvmArgs(args) {
  const merged = [...args];
  const joined = merged.join(" ");
  if (!joined.includes("jdk.incubator.vector")) {
    merged.unshift("--add-modules", "jdk.incubator.vector");
  }
  return merged;
}

async function saveAndUploadSkin(sourcePath, variant, auth) {
  const normalizedVariant = variant === "classic" ? "classic" : "slim";
  const stat = fs.statSync(sourcePath);
  if (stat.size > 1024 * 1024) throw new Error("Skin PNG is too large. Use a normal Minecraft skin file.");

  const id = `${Date.now()}-${Math.random().toString(16).slice(2)}`;
  const safeName = sanitizeFilename(path.basename(sourcePath, ".png")) || "riv3r-skin";
  const target = path.join(skinStorageDir(), `${id}-${safeName}.png`);
  fs.mkdirSync(path.dirname(target), { recursive: true });
  fs.copyFileSync(sourcePath, target);

  await uploadMinecraftSkin(auth.minecraftAccessToken, target, normalizedVariant);
  const equippedAt = new Date().toISOString();
  const entry = {
    id,
    name: safeName,
    path: target,
    variant: normalizedVariant,
    equippedAt,
    uploadedAt: equippedAt
  };
  writeSkinHistory([entry, ...readSkinHistory().filter((skin) => skin.path !== target)]);
  const profile = await fetchMinecraftProfile(auth.minecraftAccessToken);
  writeAuth({ ...auth, profile });
  return { ok: true, message: `Uploaded and equipped ${safeName}.` };
}

async function uploadMinecraftSkin(accessToken, filePath, variant) {
  const buffer = fs.readFileSync(filePath);
  const form = new FormData();
  form.append("variant", variant === "slim" ? "slim" : "classic");
  form.append("file", new Blob([buffer], { type: "image/png" }), path.basename(filePath));

  const response = await fetch("https://api.minecraftservices.com/minecraft/profile/skins", {
    method: "POST",
    headers: { "Authorization": `Bearer ${accessToken}` },
    body: form
  });

  if (!response.ok) {
    let message = `Skin upload failed with ${response.status}.`;
    try {
      const payload = await response.json();
      message = payload.errorMessage || payload.error || message;
    } catch {
      const text = await response.text().catch(() => "");
      if (text) message = text.slice(0, 180);
    }
    throw new Error(message);
  }
}

async function setActiveMinecraftCape(accessToken, capeId) {
  const response = await fetch("https://api.minecraftservices.com/minecraft/profile/capes/active", {
    method: "PUT",
    headers: {
      "Authorization": `Bearer ${accessToken}`,
      "Content-Type": "application/json",
      "Accept": "application/json"
    },
    body: JSON.stringify({ capeId: String(capeId || "") })
  });

  if (!response.ok) {
    let message = `Cape equip failed with ${response.status}.`;
    try {
      const payload = await response.json();
      message = payload.errorMessage || payload.error || message;
    } catch {
      const text = await response.text().catch(() => "");
      if (text) message = text.slice(0, 180);
    }
    throw new Error(message);
  }
}

async function clearActiveMinecraftCape(accessToken) {
  const response = await fetch("https://api.minecraftservices.com/minecraft/profile/capes/active", {
    method: "DELETE",
    headers: {
      "Authorization": `Bearer ${accessToken}`,
      "Accept": "application/json"
    }
  });

  if (!response.ok) {
    let message = `Cape clear failed with ${response.status}.`;
    try {
      const payload = await response.json();
      message = payload.errorMessage || payload.error || message;
    } catch {
      const text = await response.text().catch(() => "");
      if (text) message = text.slice(0, 180);
    }
    throw new Error(message);
  }
}

async function prepareStandaloneMinecraftLaunch(status, auth, authenticated, joinAddress = "") {
  const versionId = status.settings.selectedVersion || "1.21.11";
  const runtimeRoot = path.join(app.getPath("userData"), "minecraft-runtime");
  const librariesDir = path.join(runtimeRoot, "libraries");
  const versionsDir = path.join(runtimeRoot, "versions");
  const assetsDir = path.join(runtimeRoot, "assets");
  const nativesDir = path.join(runtimeRoot, "natives", `${versionId}-${fabricLoaderVersion}`);
  fs.mkdirSync(librariesDir, { recursive: true });
  fs.mkdirSync(versionsDir, { recursive: true });
  fs.mkdirSync(assetsDir, { recursive: true });
  fs.mkdirSync(nativesDir, { recursive: true });

  emitActivity({ title: "Preparing Minecraft", detail: `Loading Minecraft ${versionId} metadata...`, current: 1, total: 7 });
  const vanilla = await fetchMinecraftVersionJson(versionId);
  const fabric = await fetchFabricProfileJson(versionId, fabricLoaderVersion);
  const versionJar = path.join(versionsDir, versionId, `${versionId}.jar`);
  fs.mkdirSync(path.dirname(versionJar), { recursive: true });

  emitActivity({ title: "Preparing Minecraft", detail: "Downloading client jar...", current: 2, total: 7 });
  await downloadIfMissing(vanilla.downloads?.client?.url, versionJar, vanilla.downloads?.client?.sha1);

  emitActivity({ title: "Preparing Minecraft", detail: "Downloading libraries...", current: 3, total: 7 });
  const classpath = [versionJar];
  const libraries = mergeLibraries(vanilla.libraries, fabric.libraries);
  for (const library of libraries) {
    if (!isLibraryAllowed(library)) continue;
    const artifact = library.downloads?.artifact || mavenArtifact(library);
    if (artifact?.url && artifact.path) {
      const target = path.join(librariesDir, artifact.path);
      await downloadIfMissing(artifact.url, target, artifact.sha1);
      classpath.push(target);
    }
    const native = library.downloads?.classifiers?.[nativeClassifierKey()];
    if (native?.url && native.path) {
      const target = path.join(librariesDir, native.path);
      await downloadIfMissing(native.url, target, native.sha1);
      await expandNativeJar(target, nativesDir);
    }
  }

  emitActivity({ title: "Preparing Minecraft", detail: "Downloading assets...", current: 4, total: 7 });
  await prepareAssets(vanilla.assetIndex, assetsDir);

  emitActivity({ title: "Preparing Minecraft", detail: "Building launch command...", current: 5, total: 7 });
  const profile = authenticated ? auth.profile : {
    name: status.settings.offlineName || "Player",
    id: offlineUuid(status.settings.offlineName || "Player")
  };
  const replacements = {
    auth_player_name: profile.name,
    version_name: `River-${versionId}`,
    game_directory: status.instancePath,
    assets_root: assetsDir,
    assets_index_name: vanilla.assets || versionId,
    auth_uuid: String(profile.id || "").replace(/-/g, ""),
    auth_access_token: authenticated ? auth.minecraftAccessToken : "0",
    clientid: "river-client",
    auth_xuid: authenticated ? auth.xuid || "0" : "0",
    user_type: authenticated ? "msa" : "legacy",
    version_type: "release",
    natives_directory: nativesDir,
    launcher_name: "River Client",
    launcher_version: app.getVersion(),
    classpath: classpath.join(path.delimiter),
    resolution_width: String(status.settings.resolution?.width || 1280),
    resolution_height: String(status.settings.resolution?.height || 720)
  };
  // Prefer the normal Fabric mod path for River Client by keeping clientcore inside the instance
  // mods folder. The runtime injection path stays only as a fallback when the mod jar is absent.
  const mainClass = fabric.mainClass || vanilla.mainClass;
  if (!mainClass) return { ok: false, message: "Minecraft launch metadata did not include a main class." };
  const gameArgs = buildMinecraftArguments(vanilla, fabric, replacements);
  const join = String(joinAddress || "").trim();
  if (join) {
    gameArgs.push("--quickPlayMultiplayer", join);
  }

  const installedClientMod = ensureBundledClientCoreMod(status.instancePath, versionId);
  const river = stageRiverRuntimeForInstance(status.instancePath, versionId);
  // A -javaagent / classloader jar must be a real file the spawned JVM can open. Paths inside
  // app.asar are virtual (readable only through Electron's fs shim) and make the JVM die with
  // "agent library failed Agent_OnLoad: instrument / Error opening zip file or JAR manifest missing".
  const riverInjection = !fs.existsSync(installedClientMod) && isRealRuntimeJar(river.bootstrapJar) && isRealRuntimeJar(river.clientJar);
  if (fs.existsSync(installedClientMod)) {
    emit("launcher:log", `[launcher] River Client will load from the instance mods folder (${path.basename(installedClientMod)}).`);
  } else if (riverInjection) {
    emit("launcher:log", `[launcher] River in-game injection enabled (agent: ${path.basename(river.bootstrapJar)}, client: ${path.basename(river.clientJar)}).`);
  } else {
    emit("launcher:log", "[launcher] River in-game jars not found — launching plain Fabric. Run `gradlew build` to produce the agent + client jars.");
  }
  const runtimeClasspath = replacements.classpath;

  const javaArgs = [
    `-Xmx${Math.max(1024, Number(status.settings.memoryMb || 4096))}M`,
    ...ensureRequiredJvmArgs(parseJvmArgs(status.settings.jvmArgs)),
    `-Djava.library.path=${nativesDir}`,
    "-Dminecraft.launcher.brand=river-client",
    `-Dminecraft.launcher.version=${app.getVersion()}`,
    ...(riverInjection ? [`-javaagent:${river.bootstrapJar}`, `-Driver.client.jar=${river.clientJar}`] : []),
    "-cp",
    runtimeClasspath,
    mainClass,
    ...gameArgs
  ];

  emitActivity({ title: "Preparing Minecraft", detail: "Runtime ready.", current: 7, total: 7, done: true });
  return { ok: true, javaPath: status.settings.javaPath || "java", args: javaArgs };
}

async function fetchMinecraftVersionJson(versionId) {
  const manifest = await fetchJson("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json");
  const version = (manifest.versions || []).find((item) => item.id === versionId);
  if (!version?.url) throw new Error(`Minecraft ${versionId} was not found in Mojang's version manifest.`);
  return fetchJson(version.url);
}

async function fetchFabricProfileJson(versionId, loaderVersion) {
  return fetchJson(`https://meta.fabricmc.net/v2/versions/loader/${encodeURIComponent(versionId)}/${encodeURIComponent(loaderVersion)}/profile/json`);
}

async function fetchJson(url) {
  const response = await fetchWithTimeout(url, {
    headers: { "User-Agent": `RiverClientLauncher/${app.getVersion()} (WyZ_EU)` }
  }, 45000);
  if (!response.ok) throw new Error(`Request failed with ${response.status}: ${url}`);
  return response.json();
}

async function downloadIfMissing(url, target, sha1 = "", options = {}) {
  if (!url) return;
  // trustExisting skips the read-and-SHA1-the-whole-file check for callers whose target
  // path already IS the hash (asset objects live at objects/<hash-prefix>/<hash>), where
  // existence at that exact path already proves correctness. Without this, a large asset
  // set (Minecraft ships ~4500+ objects) means synchronously reading and hashing every
  // single already-present file on every launch, which is slow enough to look like a
  // hang even though nothing is actually being downloaded.
  if (fs.existsSync(target) && (options.trustExisting || !sha1 || fileSha1(target) === sha1)) return;
  fs.mkdirSync(path.dirname(target), { recursive: true });
  const attempts = Math.max(1, Number(options.attempts || 3));
  let lastError = null;
  for (let attempt = 1; attempt <= attempts; attempt += 1) {
    try {
      await downloadFile(url, target, options);
      if (sha1 && fileSha1(target) !== sha1) {
        fs.rmSync(target, { force: true });
        throw new Error(`Downloaded file failed checksum: ${path.basename(target)}`);
      }
      return;
    } catch (error) {
      lastError = error;
      fs.rmSync(target, { force: true });
      if (attempt < attempts) await sleep(350 * attempt);
    }
  }
  throw lastError || new Error(`Could not download ${path.basename(target)}`);
}

function fileSha1(file) {
  return crypto.createHash("sha1").update(fs.readFileSync(file)).digest("hex");
}

/**
 * Merges a vanilla version's library list with a Fabric profile's, de-duplicated by
 * groupId:artifactId:classifier (ignoring version only) with Fabric's copy winning on
 * overlap.
 *
 * Some Minecraft versions (1.21.4, not 1.21.11) declare their own org.ow2.asm:asm in the
 * vanilla library list. Naively concatenating both lists then puts two different versions
 * of the same ASM classes on the classpath, which Fabric Loader's own duplicate-class
 * check refuses to boot with ("duplicate ASM classes found on classpath") - it doesn't
 * matter which one "wins" at the file level, only that exactly one copy of each artifact
 * ends up on the classpath. Fabric's own libraries (loader, ASM, its own Mixin/
 * access-widener toolchain) are the ones the loader was actually built against, so they
 * take precedence over anything vanilla declares for the same artifact.
 *
 * The classifier must stay part of the key: some libraries (e.g. com.mojang:jtracy) list
 * one plain artifact entry plus separate natives-windows/-macos/-linux entries under the
 * SAME groupId:artifactId - those are different files, not version duplicates, and
 * collapsing them by groupId:artifactId alone silently drops the plain artifact (which is
 * what actually broke 1.21.4 boot the first time this function shipped: RenderSystem
 * couldn't find com.mojang.jtracy.TracyClient because only a natives-* jar survived).
 */
function mergeLibraries(vanillaLibraries, fabricLibraries) {
  const byCoordinate = new Map();
  const coordinateOf = (library) => {
    const parts = String(library?.name || "").split(":");
    if (parts.length < 2) return null;
    const [group, artifact, , classifier] = parts;
    return classifier ? `${group}:${artifact}:${classifier}` : `${group}:${artifact}`;
  };
  for (const library of vanillaLibraries || []) {
    const key = coordinateOf(library);
    byCoordinate.set(key || Symbol(), library);
  }
  for (const library of fabricLibraries || []) {
    const key = coordinateOf(library);
    byCoordinate.set(key || Symbol(), library);
  }
  return [...byCoordinate.values()];
}

function mavenArtifact(library) {
  if (!library.name) return null;
  const [group, artifact, version] = String(library.name).split(":");
  if (!group || !artifact || !version) return null;
  const jar = `${artifact}-${version}.jar`;
  const artifactPath = `${group.replace(/\./g, "/")}/${artifact}/${version}/${jar}`;
  const baseUrl = String(library.url || "https://libraries.minecraft.net/").replace(/\/?$/, "/");
  return { path: artifactPath, url: `${baseUrl}${artifactPath}` };
}

function nativeClassifierKey() {
  if (process.platform === "win32") return "natives-windows";
  if (process.platform === "darwin") return "natives-macos";
  return "natives-linux";
}

function isLibraryAllowed(library) {
  if (!Array.isArray(library.rules)) return true;
  let allowed = false;
  for (const rule of library.rules) {
    if (!ruleMatches(rule)) continue;
    allowed = rule.action === "allow";
  }
  return allowed;
}

function ruleMatches(rule) {
  if (rule.os) {
    const wanted = rule.os.name;
    const current = process.platform === "win32" ? "windows" : process.platform === "darwin" ? "osx" : "linux";
    if (wanted && wanted !== current) return false;
  }
  if (rule.features) {
    const features = {
      is_demo_user: false,
      has_custom_resolution: true,
      has_quick_plays_support: false,
      is_quick_play_singleplayer: false,
      is_quick_play_multiplayer: false,
      is_quick_play_realms: false
    };
    return Object.entries(rule.features).every(([key, value]) => Boolean(features[key]) === Boolean(value));
  }
  return true;
}

async function expandNativeJar(jarFile, nativesDir) {
  await runPowerShell([
    "-NoProfile",
    "-ExecutionPolicy", "Bypass",
    "-Command",
    `Expand-Archive -LiteralPath '${escapePowerShell(jarFile)}' -DestinationPath '${escapePowerShell(nativesDir)}' -Force`
  ]);
}

async function prepareAssets(assetIndex, assetsDir) {
  if (!assetIndex?.url) return;
  const indexesDir = path.join(assetsDir, "indexes");
  const objectsDir = path.join(assetsDir, "objects");
  fs.mkdirSync(indexesDir, { recursive: true });
  fs.mkdirSync(objectsDir, { recursive: true });
  const indexPath = path.join(indexesDir, `${assetIndex.id || "assets"}.json`);
  emitActivity({ title: "Preparing assets", detail: "Downloading asset index...", current: 0, total: 1 });
  await downloadIfMissing(assetIndex.url, indexPath, assetIndex.sha1, { timeoutMs: 45000 });
  const index = JSON.parse(fs.readFileSync(indexPath, "utf8"));
  const objects = Object.values(index.objects || {}).filter((object) => object?.hash);
  const concurrency = Math.max(2, Math.min(16, Number(readSettings().maxParallelDownloads || 8)));
  let done = 0;
  let next = 0;
  let lastEmit = 0;
  const emitProgress = (force = false) => {
    const now = Date.now();
    if (!force && now - lastEmit < 500 && done < objects.length) return;
    lastEmit = now;
    emitActivity({
      title: "Preparing assets",
      detail: `${done} / ${objects.length} assets ready...`,
      current: done,
      total: objects.length
    });
  };

  async function worker() {
    while (next < objects.length) {
      const object = objects[next];
      next += 1;
      if (!object?.hash) {
        done += 1;
        emitProgress();
        continue;
      }
      const prefix = object.hash.slice(0, 2);
      const target = path.join(objectsDir, prefix, object.hash);
      await downloadIfMissing(`https://resources.download.minecraft.net/${prefix}/${object.hash}`, target, object.hash, {
        timeoutMs: 45000,
        attempts: 3,
        trustExisting: true
      });
      done += 1;
      emitProgress();
    }
  }
  emitProgress(true);
  await Promise.all(Array.from({ length: Math.min(concurrency, objects.length || 1) }, () => worker()));
  emitActivity({ title: "Preparing assets", detail: `${objects.length} / ${objects.length} assets ready.`, current: objects.length, total: objects.length });
}

function buildMinecraftArguments(vanilla, fabric, replacements) {
  const raw = [
    ...argumentValues(vanilla.arguments?.game || vanilla.minecraftArguments || []),
    ...argumentValues(fabric.arguments?.game || [])
  ];
  return raw.map((arg) => replaceVariables(arg, replacements)).filter(Boolean);
}

function argumentValues(args) {
  if (typeof args === "string") return args.split(" ");
  const values = [];
  for (const entry of args || []) {
    if (typeof entry === "string") values.push(entry);
    else if (entry && (!entry.rules || entry.rules.some(ruleMatches))) {
      if (Array.isArray(entry.value)) values.push(...entry.value);
      else if (entry.value) values.push(entry.value);
    }
  }
  return values;
}

function replaceVariables(value, replacements) {
  return String(value || "").replace(/\$\{([^}]+)}/g, (_match, key) => replacements[key] ?? "");
}

function offlineUuid(name) {
  return crypto.createHash("md5").update(`OfflinePlayer:${name}`).digest("hex");
}

async function searchModrinth(filters, instancePath = "") {
  const query = String(filters.query || "").trim();
  const version = String(filters.version || "1.21.11").trim();
  const loader = String(filters.loader || "fabric").trim().toLowerCase();
  const contentType = normalizeContentType(filters.contentType || filters.projectType || "mod");
  const info = contentTypeInfo(contentType);
  const tags = parseTags(filters.tags);
  const facets = [[`project_type:${info.projectType}`], [`versions:${version}`]];

  if (info.usesLoader && loader) facets.push([`categories:${loader}`]);
  tags.forEach((tag) => facets.push([`categories:${tag}`]));

  const url = new URL("https://api.modrinth.com/v2/search");
  url.searchParams.set("query", query);
  url.searchParams.set("facets", JSON.stringify(facets));
  url.searchParams.set("limit", "24");
  url.searchParams.set("index", "downloads");

  const response = await fetch(url, {
    headers: {
      "User-Agent": launcherUserAgent
    }
  });

  if (!response.ok) return { ok: false, message: `Modrinth search failed with ${response.status}.`, results: [] };
  const body = await response.json();
  const manifest = instancePath ? readModManifest(instancePath) : { mods: {}, resourcepacks: {}, shaders: {} };
  return {
    ok: true,
    source: "modrinth",
    contentType,
    url: `https://modrinth.com/${info.browserPath}s?q=${encodeURIComponent(query)}`,
    results: (body.hits || []).map((hit) => ({
      source: "modrinth",
      contentType,
      projectType: info.projectType,
      projectId: hit.project_id,
      slug: hit.slug,
      title: hit.title,
      description: hit.description,
      iconUrl: hit.icon_url,
      gallery: Array.isArray(hit.gallery) ? hit.gallery : [],
      downloads: hit.downloads,
      follows: hit.follows,
      author: hit.author,
      gameVersion: version,
      loader,
      url: `https://modrinth.com/${info.browserPath}/${hit.slug}`,
      installed: isModrinthProjectInstalled(hit.project_id, hit.slug, manifest, contentType),
      conflict: contentType === "mod" ? getKnownSearchConflict(hit.project_id, manifest) : null
    }))
  };
}

function isModrinthProjectInstalled(projectId, slug, manifest, contentType = "mod") {
  const normalizedProjectId = String(projectId || "");
  const normalizedSlug = String(slug || "").toLowerCase();
  const section = manifestSection(manifest, contentType);
  return Object.values(section || {}).some((item) => {
    if (!item) return false;
    if (normalizedProjectId && item.projectId === normalizedProjectId) return true;
    return normalizedSlug && String(item.slug || "").toLowerCase() === normalizedSlug;
  });
}

function getKnownSearchConflict(projectId, manifest) {
  const installed = Object.values(manifest.mods || {});
  const conflict = installed.find((mod) => Array.isArray(mod.incompatibilities) && mod.incompatibilities.some((entry) => entry.projectId === projectId));
  if (!conflict) return null;
  return {
    installedTitle: conflict.title,
    message: `Known incompatible with installed mod ${conflict.title}.`
  };
}

async function getModrinthVersion(projectIdOrSlug, gameVersion, loader, contentType = "mod") {
  const info = contentTypeInfo(contentType);
  const url = new URL(`https://api.modrinth.com/v2/project/${encodeURIComponent(projectIdOrSlug)}/version`);
  url.searchParams.set("game_versions", JSON.stringify([gameVersion]));
  if (info.usesLoader && loader) url.searchParams.set("loaders", JSON.stringify([loader]));

  try {
    const response = await fetch(url, {
      headers: { "User-Agent": launcherUserAgent },
      // One slow/hung project lookup must not stall the whole scan.
      signal: AbortSignal.timeout(8000)
    });
    if (!response.ok) return null;
    const versions = await response.json();
    return versions.find((version) => version.version_type === "release") || versions[0] || null;
  } catch {
    return null;
  }
}

async function getModrinthProjectDetails({ projectIdOrSlug, contentType, gameVersion, loader }) {
  const info = contentTypeInfo(contentType);
  const project = await getModrinthProject(projectIdOrSlug);
  if (!project) return { ok: false, message: "Could not load that Modrinth project." };
  const cachedIconUrl = project.icon_url ? await cacheProjectIcon(project.id || projectIdOrSlug, project.icon_url) : "";
  const bodyImageRemoteUrl = extractFirstImageUrl(project.body);
  const cachedBodyImageUrl = bodyImageRemoteUrl ? await cacheProjectIcon(`${project.id || projectIdOrSlug}-body`, bodyImageRemoteUrl) : "";

  const url = new URL(`https://api.modrinth.com/v2/project/${encodeURIComponent(project.id || projectIdOrSlug)}/version`);
  if (gameVersion) url.searchParams.set("game_versions", JSON.stringify([gameVersion]));
  if (info.usesLoader && loader) url.searchParams.set("loaders", JSON.stringify([loader]));
  const response = await fetch(url, { headers: { "User-Agent": launcherUserAgent } });
  const versions = response.ok ? await response.json() : [];

  return {
    ok: true,
    source: "modrinth",
    contentType: normalizeContentType(contentType),
    project: {
      id: project.id,
      slug: project.slug,
      title: project.title,
      description: project.description,
      body: project.body || "",
      author: await getModrinthProjectAuthor(project),
      iconUrl: cachedIconUrl || project.icon_url || "",
      bodyImageUrl: cachedBodyImageUrl || bodyImageRemoteUrl || "",
      gallery: Array.isArray(project.gallery) ? project.gallery : [],
      categories: Array.isArray(project.categories) ? project.categories : [],
      clientSide: project.client_side || "unknown",
      serverSide: project.server_side || "unknown",
      license: project.license?.id || project.license?.name || "",
      publishedAt: project.published || "",
      updatedAt: project.updated || "",
      downloads: project.downloads || 0,
      follows: project.followers || 0,
      url: `https://modrinth.com/${info.browserPath}/${project.slug || project.id}`
    },
    versions: versions.slice(0, 40).map((version) => ({
      id: version.id,
      projectId: version.project_id,
      name: version.name,
      versionNumber: version.version_number,
      versionType: version.version_type,
      datePublished: version.date_published,
      downloads: version.downloads || 0,
      gameVersions: version.game_versions || [],
      loaders: version.loaders || [],
      changelog: version.changelog || "",
      dependencies: (version.dependencies || []).map((dependency) => ({
        projectId: dependency.project_id || "",
        versionId: dependency.version_id || "",
        fileName: dependency.file_name || "",
        dependencyType: dependency.dependency_type || "required"
      })),
      files: (version.files || []).map((file) => ({
        filename: file.filename,
        primary: Boolean(file.primary),
        size: file.size || 0
      }))
    }))
  };
}

async function getModrinthVersionById(versionId) {
  const response = await fetch(`https://api.modrinth.com/v2/version/${encodeURIComponent(versionId)}`, {
    headers: {
      "User-Agent": launcherUserAgent
    }
  });
  if (!response.ok) return null;
  return response.json();
}

async function getModrinthProject(projectIdOrSlug) {
  const response = await fetch(`https://api.modrinth.com/v2/project/${encodeURIComponent(projectIdOrSlug)}`, {
    headers: {
      "User-Agent": launcherUserAgent
    }
  });
  if (!response.ok) return null;
  return response.json();
}

async function getModrinthProjectAuthor(project) {
  if (!project || !project.team) return "";
  try {
    const response = await fetch(`https://api.modrinth.com/v2/team/${encodeURIComponent(project.team)}/members`, {
      headers: { "User-Agent": launcherUserAgent }
    });
    if (!response.ok) return "";
    const members = await response.json();
    const owner = members.find((member) => member.role === "Owner") || members[0];
    return owner?.user?.username || "";
  } catch {
    return "";
  }
}

async function installModrinthProject({ projectIdOrSlug, title, gameVersion, loader, instancePath, reason, visited, contentType = "mod" }) {
  const info = contentTypeInfo(contentType);
  const version = await getModrinthVersion(projectIdOrSlug, gameVersion, loader, contentType);
  if (!version) return { ok: false, message: `No ${gameVersion} ${info.label} file found for ${title}.` };
  return installModrinthVersion({ projectIdOrSlug, title, version, gameVersion, loader, instancePath, reason, visited, contentType });
}

const pvpPresetMods = [
  { name: "AppleSkin", query: "AppleSkin", slug: "appleskin", optional: false },
  { name: "Chunky", query: "Chunky", slug: "chunky", optional: false },
  { name: "Combat Hitboxes", query: "Combat Hitboxes", slug: "combat-hitboxes", optional: false },
  { name: "Consumable Optimizer", query: "Consumable Optimizer", slug: "consumable-optimizer", optional: false },
  { name: "Controlling", query: "Controlling", slug: "controlling", optional: false },
  { name: "Crosshair Addons", query: "Crosshair Addons", slug: "crosshair-addons", optional: false },
  { name: "Elytra Pitch", query: "Elytra Pitch", slug: "elytra-pitch", optional: false },
  { name: "Flashback", query: "Flashback", slug: "flashback", optional: true },
  { name: "Hero Bot", query: "Hero Bot", slug: "hero-bot", optional: true },
  { name: "Impactful", query: "Impactful", slug: "impactful", optional: true },
  { name: "Konkrete", query: "Konkrete", slug: "konkrete", optional: false },
  { name: "Marlow's Crystal Optimizer", query: "Marlow Crystal Optimizer", slug: "marlows-crystal-optimizer", optional: true },
  { name: "Mouse Tweaks", query: "Mouse Tweaks", slug: "mouse-tweaks", optional: false },
  { name: "Natural Motion Blur", query: "Natural Motion Blur", slug: "natural-motion-blur", optional: true },
  { name: "No Darkness Effect", query: "No Darkness Effect", slug: "no-darkness-effect", optional: true },
  { name: "No Fog", query: "No Fog", slug: "no-fog", optional: true },
  { name: "Noisium", query: "Noisium", slug: "noisium", optional: false },
  { name: "NoWheel", query: "NoWheel", slug: "no-wheel", optional: true },
  { name: "NoSignGUI", query: "NoSignGUI", slug: "nosigngui", optional: true },
  { name: "Renderscale", query: "Renderscale", slug: "renderscale", optional: false },
  { name: "Riding Mouse Fix", query: "Riding Mouse Fix", slug: "riding-mouse-fix", optional: true },
  { name: "Shield Fixes", query: "Shield Fixes", slug: "shield-fixes", optional: false },
  { name: "Shield Statuses", query: "Shield Statuses", slug: "shield-statuses", optional: true },
  { name: "Shulker Box Tooltip", query: "Shulker Box Tooltip", slug: "shulkerboxtooltip", optional: false },
  { name: "Slyde", query: "Slyde", slug: "slyde", optional: false },
  { name: "Simple Voice Chat", query: "Simple Voice Chat", slug: "simple-voice-chat", optional: false },
  { name: "Status Effect Timer", query: "Status Effect Timer", slug: "status-effect-timer", optional: false },
  { name: "Super Fast Math", query: "Super Fast Math", slug: "super-fast-math", optional: false },
  { name: "Uku's Armor HUD", query: "Uku Armor HUD", slug: "ukus-armor-hud", optional: false }
];

const optimizationSuiteMods = [
  { name: "Sodium", query: "Sodium", slug: "sodium", optional: false },
  { name: "Lithium", query: "Lithium", slug: "lithium", optional: false },
  { name: "FerriteCore", query: "FerriteCore", slug: "ferrite-core", optional: false },
  { name: "ImmediatelyFast", query: "ImmediatelyFast", slug: "immediatelyfast", optional: false },
  { name: "Noisium", query: "Noisium", slug: "noisium", optional: false },
  { name: "FastQuit", query: "FastQuit", slug: "fastquit", optional: false },
  { name: "Entity Culling", query: "Entity Culling", slug: "entityculling", optional: false },
  // LOD renderer: chunks beyond the real render distance draw as simplified low-detail
  // terrain (cheap, roughly "one colour per block"), so the horizon stays filled at long
  // distances - and fast travel shows distant LOD terrain instead of void while real
  // chunks catch up. Purely visual, no gameplay advantage. The 1.21.11 build is a beta;
  // the resolver prefers releases but falls back to the newest build (see line ~6609).
  { name: "Distant Horizons", query: "Distant Horizons", slug: "distanthorizons", optional: false }
];

const incompatibleManagedMods = [
  {
    title: "Krypton",
    pattern: /^krypton-[\w.+-]+\.jar$/i,
    reason: "It crashes River 1.21.11 with Controlify on startup."
  }
];

const duplicateManagedModGroups = [
  { title: "Sodium", pattern: /^sodium-fabric-[\w.+-]+\.jar$/i },
  { title: "Entity Culling", pattern: /^entityculling-fabric-[\w.+-]+\.jar$/i },
  { title: "Fabric Language Kotlin", pattern: /^fabric-language-kotlin-[\w.+-]+\.jar$/i },
  { title: "Simple Voice Chat", pattern: /^voicechat-fabric-[\w.+-]+\.jar$/i }
];

const knownDependencyAliases = {
  "cloth-config": ["cloth-config2"],
  "cloth-config2": ["cloth-config"],
  "owo": ["owo-lib", "owo-impl"],
  "owo-lib": ["owo"],
  "yacl": ["yet-another-config-lib", "yet-another-config-lib-v3", "yet_another_config_lib_v3"],
  "yet-another-config-lib": ["yet-another-config-lib-v3", "yet_another_config_lib_v3", "yacl"],
  "yet-another-config-lib-v3": ["yet-another-config-lib", "yet_another_config_lib_v3", "yacl"],
  "yet_another_config_lib_v3": ["yet-another-config-lib", "yet-another-config-lib-v3", "yacl"]
};

function analyzeInstanceModCompatibility(instancePath) {
  const modsDir = path.join(instancePath, "mods");
  if (!fs.existsSync(modsDir)) return { issues: [], softConflicts: [] };

  const mods = fs.readdirSync(modsDir)
    .filter((file) => file.toLowerCase().endsWith(".jar"))
    .map((file) => readFabricModMetadata(path.join(modsDir, file), file))
    .filter(Boolean);
  const knownMods = expandProvidedMods([...mods, ...mods.flatMap((mod) => mod.nestedMods || [])]);

  const issues = [];
  const softConflicts = [];
  const byId = new Map();
  for (const mod of knownMods) {
    if (!mod.id) continue;
    for (const key of aliasKeysForId(mod.id)) {
      if (!byId.has(key)) byId.set(key, []);
      byId.get(key).push(mod);
    }
  }

  for (const [id, entries] of byId.entries()) {
    const topLevelEntries = entries.filter((entry) => !entry.isNested && !entry.isProvided);
    if (topLevelEntries.length <= 1) continue;
    issues.push({
      type: "duplicate-mod-id",
      title: `Duplicate mod id: ${id}`,
      message: `Only one active jar can provide "${id}".`,
      mods: dedupePublicMods(topLevelEntries)
    });
  }

  const builtinIds = new Set(["java", "minecraft", "fabricloader", "fabric"].flatMap((id) => [...aliasKeysForId(id)]));
  for (const mod of mods) {
    // Fabric semantics are the whole point here. `breaks` is a HARD incompatibility:
    // the loader refuses to start if the named mod is present. `conflicts` is only a
    // SOFT warning - the game still loads and usually runs fine. Treating a soft
    // conflict as a hard blocker is exactly the "these two mods can't run together"
    // false alarm we must never raise for mods that actually work side by side, so
    // conflicts are collected as advisory notes and never block a launch.
    for (const kind of ["breaks", "conflicts"]) {
      for (const rule of compatibilityRuleEntries(mod.metadata?.[kind], kind)) {
        const target = resolveCandidatesForId(rule.id, byId);
        if (!target?.length) continue;
        // Fabric loader activates exactly ONE copy of a mod id - the newest, preferring a
        // top-level jar over a nested/embedded fallback. So a `breaks fabric-api <0.140`
        // rule must be judged against the copy that will actually load, not every embedded
        // one: Distant Horizons ships an old fabric-api (0.139) nested inside its jar, and
        // checking that dormant copy produced a phantom "Sodium conflicts with Fabric API".
        const effective = effectiveLoadedCandidate(target);
        for (const other of effective ? [effective] : target) {
          // Only a definite version match counts. An unknown result (null) is left alone
          // so an unparseable range can never invent an incompatibility out of thin air.
          if (versionRequirementMatches(rule.range, other.version) !== true) continue;
          const rangeText = rule.range && rule.range !== "*" ? ` ${rule.range}` : "";
          if (kind === "breaks") {
            issues.push({
              type: "declared-break",
              title: `${mod.name} can't run with ${other.name}`,
              message: `${mod.name} declares it breaks ${rule.id}${rangeText}, so one of the two has to be removed.`,
              mods: [publicModInfo(mod), publicModInfo(other)]
            });
          } else {
            softConflicts.push({
              type: "soft-conflict",
              title: `${mod.name} and ${other.name} note a soft conflict`,
              message: `${mod.name} lists a soft conflict with ${rule.id}${rangeText}. They usually still run together fine - just something to watch if either one misbehaves.`,
              mods: [publicModInfo(mod), publicModInfo(other)]
            });
          }
        }
      }
    }

    for (const rule of compatibilityRuleEntries(mod.metadata?.depends, "depends")) {
      const candidates = resolveCandidatesForId(rule.id, byId);
      const hasBuiltin = [...aliasKeysForId(rule.id)].some((key) => builtinIds.has(key));
      if (hasBuiltin || candidates.length) {
        if (!candidates.length) continue;
        const anyCompatible = candidates.some((candidate) => versionRequirementMatches(rule.range, candidate.version) !== false);
        if (anyCompatible) continue;
        issues.push({
          type: "bad-dependency-version",
          title: `${mod.name} needs a different ${rule.id}`,
          message: `${mod.name} requires ${rule.id}${rule.range && rule.range !== "*" ? ` ${rule.range}` : ""}.`,
          mods: [publicModInfo(mod), ...dedupePublicMods(candidates)]
        });
        continue;
      }

      issues.push({
        type: "missing-dependency",
        title: `${mod.name} is missing ${rule.id}`,
        message: `${mod.name} requires ${rule.id}${rule.range && rule.range !== "*" ? ` ${rule.range}` : ""}, but it is not active in this instance.`,
        mods: [publicModInfo(mod)],
        missing: { id: rule.id, range: rule.range || "*" }
      });
    }
  }

  return {
    issues: dedupeCompatibilityIssues(issues).slice(0, 24),
    softConflicts: dedupeCompatibilityIssues(softConflicts).slice(0, 24)
  };
}

function publicModInfo(mod) {
  return {
    file: mod.file,
    id: mod.id,
    name: mod.name || mod.id || mod.file,
    version: mod.version || ""
  };
}

function dedupePublicMods(mods) {
  const seen = new Set();
  const result = [];
  for (const mod of mods.map(publicModInfo)) {
    const key = `${mod.file}::${mod.id}::${mod.version}`;
    if (seen.has(key)) continue;
    seen.add(key);
    result.push(mod);
  }
  return result;
}

function expandProvidedMods(mods) {
  const result = [];
  for (const mod of mods) {
    result.push(mod);
    const provides = Array.isArray(mod.metadata?.provides) ? mod.metadata.provides : [];
    for (const provided of provides) {
      const id = String(provided || "").trim();
      if (!id) continue;
      result.push({
        ...mod,
        id,
        name: mod.name || id,
        isProvided: true
      });
    }
  }
  return result;
}

function canonicalModId(value) {
  return String(value || "")
    .trim()
    .toLowerCase()
    .replace(/[\s_]+/g, "-")
    .replace(/-+/g, "-");
}

function aliasKeysForId(id) {
  const pending = [canonicalModId(id)];
  const seen = new Set();
  while (pending.length) {
    const current = pending.shift();
    if (!current || seen.has(current)) continue;
    seen.add(current);
    for (const alias of knownDependencyAliases[current] || []) {
      const normalized = canonicalModId(alias);
      if (normalized && !seen.has(normalized)) pending.push(normalized);
    }
  }
  return seen;
}

/**
 * The single copy of a mod id Fabric loader would actually activate: highest version wins,
 * and at equal versions a real top-level jar beats a nested/embedded fallback. Returns null
 * for an empty list. Used so `breaks`/`conflicts` rules are judged against the loaded copy,
 * not dormant embedded ones (see the DH/fabric-api phantom conflict).
 */
function effectiveLoadedCandidate(candidates) {
  if (!candidates?.length) return null;
  return candidates.reduce((best, cur) => {
    if (!best) return cur;
    const byVer = compareVersions(cur.version, best.version);
    if (byVer > 0) return cur;
    if (byVer < 0) return best;
    const curNested = cur.isNested || cur.isProvided;
    const bestNested = best.isNested || best.isProvided;
    return bestNested && !curNested ? cur : best;
  }, null);
}

function resolveCandidatesForId(id, byId) {
  const result = [];
  const seen = new Set();
  for (const key of aliasKeysForId(id)) {
    for (const mod of byId.get(key) || []) {
      const modKey = `${mod.file}::${mod.id}::${mod.version}::${mod.isNested ? 1 : 0}::${mod.isProvided ? 1 : 0}`;
      if (seen.has(modKey)) continue;
      seen.add(modKey);
      result.push(mod);
    }
  }
  return result;
}

function compatibilityRuleEntries(value, kind) {
  if (!value || typeof value !== "object" || Array.isArray(value)) return [];
  return Object.entries(value)
    .map(([id, range]) => ({ id: String(id || "").trim(), range: normalizeVersionRange(range), kind }))
    .filter((entry) => entry.id && !entry.id.startsWith("$"));
}

function normalizeVersionRange(value) {
  if (Array.isArray(value)) return value.map(normalizeVersionRange).filter(Boolean).join(" || ") || "*";
  if (value && typeof value === "object") return "*";
  return String(value || "*").trim() || "*";
}

function dedupeCompatibilityIssues(issues) {
  const seen = new Set();
  const result = [];
  for (const issue of issues) {
    const key = [
      issue.type,
      issue.title,
      (issue.mods || []).map((mod) => mod.file).sort().join("|"),
      issue.missing?.id || ""
    ].join("::");
    if (seen.has(key)) continue;
    seen.add(key);
    result.push(issue);
  }
  return result;
}

function readFabricModMetadata(jarPath, file) {
  try {
    const jarBuffer = fs.readFileSync(jarPath);
    const raw = readZipEntryBufferFromBuffer(jarBuffer, "fabric.mod.json");
    if (!raw) return null;
    const metadata = JSON.parse(raw.toString("utf8"));
    const id = String(metadata.id || "").trim();
    if (!id) return null;
    const nested = [];
    for (const entry of Array.isArray(metadata.jars) ? metadata.jars : []) {
      const nestedFile = String(entry?.file || "").replace(/\\/g, "/");
      if (!nestedFile) continue;
      try {
        const nestedJar = readZipEntryBufferFromBuffer(jarBuffer, nestedFile);
        const nestedRaw = nestedJar ? readZipEntryBufferFromBuffer(nestedJar, "fabric.mod.json") : null;
        if (!nestedRaw) continue;
        const nestedMetadata = JSON.parse(nestedRaw.toString("utf8"));
        const nestedId = String(nestedMetadata.id || "").trim();
        if (!nestedId) continue;
        nested.push({
          file,
          path: jarPath,
          metadata: nestedMetadata,
          id: nestedId,
          name: String(nestedMetadata.name || nestedId),
          version: String(nestedMetadata.version || ""),
          isNested: true
        });
      } catch {}
    }
    return {
      file,
      path: jarPath,
      metadata,
      id,
      name: String(metadata.name || id),
      version: String(metadata.version || ""),
      nestedMods: nested
    };
  } catch (error) {
    emit("launcher:log", `[launcher] Could not read fabric.mod.json from ${file}: ${error.message}`);
    return null;
  }
}

function readZipEntryBuffer(zipPath, wantedName) {
  return readZipEntryBufferFromBuffer(fs.readFileSync(zipPath), wantedName);
}

function readZipEntryBufferFromBuffer(buffer, wantedName) {
  const eocdSig = 0x06054b50;
  const centralSig = 0x02014b50;
  const localSig = 0x04034b50;
  let eocd = -1;
  for (let index = buffer.length - 22; index >= Math.max(0, buffer.length - 66000); index -= 1) {
    if (buffer.readUInt32LE(index) === eocdSig) {
      eocd = index;
      break;
    }
  }
  if (eocd < 0) return null;

  const entries = buffer.readUInt16LE(eocd + 10);
  let offset = buffer.readUInt32LE(eocd + 16);
  for (let entryIndex = 0; entryIndex < entries && offset + 46 <= buffer.length; entryIndex += 1) {
    if (buffer.readUInt32LE(offset) !== centralSig) break;
    const method = buffer.readUInt16LE(offset + 10);
    const compressedSize = buffer.readUInt32LE(offset + 20);
    const nameLength = buffer.readUInt16LE(offset + 28);
    const extraLength = buffer.readUInt16LE(offset + 30);
    const commentLength = buffer.readUInt16LE(offset + 32);
    const localOffset = buffer.readUInt32LE(offset + 42);
    const name = buffer.slice(offset + 46, offset + 46 + nameLength).toString("utf8").replace(/\\/g, "/");
    offset += 46 + nameLength + extraLength + commentLength;
    if (name !== wantedName) continue;
    if (buffer.readUInt32LE(localOffset) !== localSig) return null;
    const localNameLength = buffer.readUInt16LE(localOffset + 26);
    const localExtraLength = buffer.readUInt16LE(localOffset + 28);
    const dataStart = localOffset + 30 + localNameLength + localExtraLength;
    const compressed = buffer.slice(dataStart, dataStart + compressedSize);
    if (method === 0) return compressed;
    if (method === 8) return zlib.inflateRawSync(compressed);
    throw new Error(`Unsupported zip compression method ${method}`);
  }
  return null;
}

function versionRequirementMatches(range, version) {
  const raw = String(range || "*").trim().replace(/,/g, " ");
  if (!raw || raw === "*") return true;
  const semverVersion = toSemverVersion(version);
  const semverRange = toSemverRange(raw);
  if (semverVersion && semverRange) {
    return semver.satisfies(semverVersion, semverRange, { loose: true, includePrerelease: true });
  }

  const exactRange = raw.replace(/^=/, "").trim();
  if (exactRange && String(version || "").trim() === exactRange) return true;
  if (/[*xX]|[<>=^~]| - /.test(raw)) return null;

  const fallbackVersion = comparableVersionParts(version);
  const fallbackWanted = comparableVersionParts(raw);
  if (!fallbackVersion || !fallbackWanted) return null;
  return compareVersions(fallbackVersion.join("."), fallbackWanted.join(".")) === 0;
}

function toSemverVersion(value) {
  const raw = String(value || "").trim().replace(/^v/i, "");
  if (!raw) return null;
  const valid = semver.valid(raw, { loose: true });
  if (valid) return valid;
  const coerced = semver.coerce(raw, { loose: true });
  return coerced ? coerced.version : null;
}

function toSemverRange(value) {
  const raw = String(value || "").trim().replace(/,/g, " ");
  if (!raw || raw === "*") return "*";
  const valid = semver.validRange(raw, { loose: true });
  if (valid) return valid;
  const expanded = raw
    .split("||")
    .map((part) => part.trim())
    .filter(Boolean)
    .map((part) => part.replace(/(^|[\s|])=(?=\d)/g, "$1"))
    .join(" || ");
  return semver.validRange(expanded, { loose: true }) || null;
}

function comparableVersionParts(value) {
  const cleaned = String(value || "").split("+")[0].trim();
  if (!/^\d+(?:[.-]\d+)*$/.test(cleaned)) return null;
  return cleaned.split(/[.-]/).map((part) => Number.parseInt(part, 10));
}

const incompatibleManagedResourcePacks = [
  {
    title: "Barebones PVP Overlay",
    fileName: "Barebones PVP Overlay.zip",
    reason: "Its pack metadata is invalid for 1.21.11 and it spams resource reload errors."
  },
  {
    title: "SwightV3",
    fileName: "SwightV3.zip",
    reason: "Its pack metadata is invalid for 1.21.11 and it spams resource reload errors."
  },
  {
    title: "MaceBreach Density Pack",
    fileName: "MaceBreach-DensityPack1.21.5.zip",
    reason: "It is marked incompatible in options and causes repeated pack compatibility errors."
  }
];

function quarantineProblemMods(instancePath) {
  const modsDir = path.join(instancePath, "mods");
  if (!fs.existsSync(modsDir)) return { changed: [], disabled: [], deduped: [] };

  const changed = [];
  const disabled = [];
  const deduped = [];
  const files = fs.readdirSync(modsDir);

  for (const file of files) {
    if (!file.toLowerCase().endsWith('.jar')) continue;
    const match = incompatibleManagedMods.find((entry) => entry.pattern.test(file));
    if (!match) continue;
    const from = path.join(modsDir, file);
    const to = path.join(modsDir, `${file}.disabled`);
    try {
      if (fs.existsSync(to)) fs.rmSync(to, { force: true });
      fs.renameSync(from, to);
      disabled.push({ file, title: match.title, reason: match.reason });
      changed.push(file);
      emit("launcher:log", `[launcher] Disabled incompatible mod ${file}. ${match.reason}`);
    } catch (error) {
      emit("launcher:log", `[launcher] Could not disable incompatible mod ${file}: ${error.message}`);
    }
  }

  for (const group of duplicateManagedModGroups) {
    const activeMatches = fs.readdirSync(modsDir)
      .filter((file) => file.toLowerCase().endsWith('.jar') && group.pattern.test(file))
      .map((file) => ({
        file,
        fullPath: path.join(modsDir, file),
        mtimeMs: (() => {
          try {
            return fs.statSync(path.join(modsDir, file)).mtimeMs;
          } catch {
            return 0;
          }
        })()
      }))
      .sort((a, b) => b.mtimeMs - a.mtimeMs || a.file.localeCompare(b.file));

    if (activeMatches.length <= 1) continue;
    const keep = activeMatches[0];
    for (const entry of activeMatches.slice(1)) {
      const disabledPath = `${entry.fullPath}.disabled`;
      try {
        if (fs.existsSync(disabledPath)) fs.rmSync(disabledPath, { force: true });
        fs.renameSync(entry.fullPath, disabledPath);
        deduped.push({ title: group.title, kept: keep.file, disabled: entry.file });
        changed.push(entry.file);
        emit("launcher:log", `[launcher] Disabled duplicate ${group.title} jar ${entry.file}; keeping ${keep.file}.`);
      } catch (error) {
        emit("launcher:log", `[launcher] Could not disable duplicate ${group.title} jar ${entry.file}: ${error.message}`);
      }
    }
  }

  return { changed, disabled, deduped };
}

function removePackReference(optionValue, targetFileName) {
  const token = `file/${targetFileName}`.replace(/\\/g, "\\\\").replace(/"/g, '\\"');
  return String(optionValue || "").replace(new RegExp(`,?\"${token}\"`, "g"), "").replace(/\[,/g, "[").replace(/,,/g, ",");
}

function quarantineProblemResourcePacks(instancePath) {
  const packsDir = path.join(instancePath, "resourcepacks");
  const optionsPath = path.join(instancePath, "options.txt");
  const changed = [];
  const disabled = [];

  if (fs.existsSync(packsDir)) {
    for (const pack of incompatibleManagedResourcePacks) {
      const activePath = path.join(packsDir, pack.fileName);
      const disabledPath = `${activePath}.disabled`;
      if (!fs.existsSync(activePath)) continue;
      try {
        if (fs.existsSync(disabledPath)) fs.rmSync(disabledPath, { force: true });
        fs.renameSync(activePath, disabledPath);
        disabled.push({ file: pack.fileName, title: pack.title, reason: pack.reason });
        changed.push(pack.fileName);
        emit("launcher:log", `[launcher] Disabled incompatible resource pack ${pack.fileName}. ${pack.reason}`);
      } catch (error) {
        emit("launcher:log", `[launcher] Could not disable incompatible resource pack ${pack.fileName}: ${error.message}`);
      }
    }
  }

  if (fs.existsSync(optionsPath)) {
    try {
      let options = fs.readFileSync(optionsPath, "utf8");
      let updated = options;
      for (const pack of incompatibleManagedResourcePacks) {
        updated = updated.replace(
          /^resourcePacks:(.*)$/m,
          (_match, value) => `resourcePacks:${removePackReference(value, pack.fileName)}`
        );
        updated = updated.replace(
          /^incompatibleResourcePacks:(.*)$/m,
          (_match, value) => `incompatibleResourcePacks:${removePackReference(value, pack.fileName)}`
        );
      }
      if (updated !== options) {
        fs.writeFileSync(optionsPath, updated);
        changed.push("options.txt");
        emit("launcher:log", "[launcher] Removed incompatible resource packs from the active Minecraft resource pack list.");
      }
    } catch (error) {
      emit("launcher:log", `[launcher] Could not update resource pack options: ${error.message}`);
    }
  }

  return { changed, disabled };
}

async function ensureOptimizationSuite(instancePath, gameVersion, loader = "fabric", reason = "river-optimization") {
  // River auto-installs ONLY clientcore (that is what makes it River). Silently
  // pulling in Sodium and a suite of other mods on launch/boot/repair annoyed people
  // and made instances feel hijacked - so the suite is now opt-in through the mod
  // browser instead. The list and installer below are kept for a future explicit
  // "install performance pack" button, but nothing calls into them automatically.
  if (reason !== "river-optimization-preset-explicit") {
    return { ok: true, installed: [], skipped: [], message: "Optimization suite is opt-in." };
  }
  const installed = [];
  const skipped = [];
  const visited = new Set();
  for (let index = 0; index < optimizationSuiteMods.length; index += 1) {
    const mod = optimizationSuiteMods[index];
    emitActivity({
      title: "Optimizing River instance",
      detail: `Installing ${mod.name}...`,
      current: index + 1,
      total: optimizationSuiteMods.length
    });
    emit("launcher:log", `[optimize] Installing ${mod.name} for ${gameVersion}/${loader}...`);
    const projectIdOrSlug = await resolveModrinthProjectId(mod.slug, mod.query);
    if (!projectIdOrSlug) {
      skipped.push(`${mod.name} not found`);
      emit("launcher:log", `[optimize] Could not resolve ${mod.name} on Modrinth.`);
      continue;
    }
    if (isManagedProjectExplicitlyDisabled(instancePath, projectIdOrSlug, mod.slug)) {
      skipped.push(`${mod.name}: disabled by user`);
      emit("launcher:log", `[optimize] ${mod.name} stays disabled because the user turned it off.`);
      continue;
    }
    const result = await installModrinthProject({
      projectIdOrSlug,
      title: mod.name,
      gameVersion,
      loader,
      instancePath,
      reason,
      visited,
      contentType: "mod"
    });
    if (result.ok) {
      installed.push(...(result.installed || []));
      emit("launcher:log", `[optimize] ${mod.name} ready.`);
    } else {
      skipped.push(`${mod.name}: ${result.message}`);
      emit("launcher:log", `[optimize] ${mod.name} skipped: ${result.message}`);
    }
  }
  return {
    ok: skipped.length < optimizationSuiteMods.length,
    installed,
    skipped,
    message: `Optimization suite installed ${installed.length} file${installed.length === 1 ? "" : "s"}${skipped.length ? ` with ${skipped.length} skipped item${skipped.length === 1 ? "" : "s"}` : ""}.`
  };
}

async function ensureRequiredSupportMods(instancePath, gameVersion, loader = "fabric", reason = "river-support") {
  const installed = [];
  const skipped = [];
  const visited = new Set();
  for (let index = 0; index < requiredSupportMods.length; index += 1) {
    const mod = requiredSupportMods[index];
    emitActivity({
      title: "Preparing River support mods",
      detail: `Installing ${mod.name}...`,
      current: index + 1,
      total: requiredSupportMods.length
    });
    emit("launcher:log", `[support] Installing ${mod.name} for ${gameVersion}/${loader}...`);
    const projectIdOrSlug = await resolveModrinthProjectId(mod.slug, mod.query);
    if (!projectIdOrSlug) {
      skipped.push(`${mod.name} not found`);
      emit("launcher:log", `[support] Could not resolve ${mod.name} on Modrinth.`);
      continue;
    }
    const result = await installModrinthProject({
      projectIdOrSlug,
      title: mod.name,
      gameVersion,
      loader,
      instancePath,
      reason,
      visited,
      contentType: "mod"
    });
    if (result.ok) {
      installed.push(...(result.installed || []));
      emit("launcher:log", `[support] ${mod.name} ready.`);
    } else {
      skipped.push(`${mod.name}: ${result.message}`);
      emit("launcher:log", `[support] ${mod.name} skipped: ${result.message}`);
    }
  }
  return {
    ok: skipped.length < requiredSupportMods.length,
    installed,
    skipped,
    message: `Support mods installed ${installed.length} file${installed.length === 1 ? "" : "s"}${skipped.length ? ` with ${skipped.length} skipped item${skipped.length === 1 ? "" : "s"}` : ""}.`
  };
}

async function createPvpInstance(gameVersion) {
  const id = `riv3r-pvp-${gameVersion.replace(/[^a-z0-9_.-]/gi, "-")}`;
  const instancePath = path.join(instancesRootPath(), id);
  const now = new Date().toISOString();
  const instance = {
    id,
    name: `River PvP ${gameVersion}`,
    type: "pvp",
    version: gameVersion,
    loader: "fabric",
    path: instancePath,
    createdAt: now,
    updatedAt: now
  };

  const existing = readInstances().filter((item) => item.id !== id);
  writeInstances([instance, ...existing]);
  writeSettings({
    ...readSettings(),
    instancePath,
    selectedVersion: gameVersion,
    modFilters: {
      ...readSettings().modFilters,
      version: gameVersion,
      loader: "fabric"
    }
  });

  fs.mkdirSync(path.join(instancePath, "mods"), { recursive: true });
  fs.mkdirSync(path.join(instancePath, "resourcepacks"), { recursive: true });
  fs.mkdirSync(path.join(instancePath, "shaderpacks"), { recursive: true });

  const optimization = await ensureOptimizationSuite(instancePath, gameVersion, "fabric", "river-optimization-preset");
  if (optimization.ok || (optimization.installed || []).length) {
    writeInstanceMeta(instancePath, { optimizationAppliedAt: new Date().toISOString() });
  }
  emit("launcher:log", `[optimize] ${optimization.message}`);

  const installed = [];
  const skipped = [];
  const visited = new Set();
  for (let index = 0; index < pvpPresetMods.length; index += 1) {
    const mod = pvpPresetMods[index];
    emitActivity({ title: "Creating River PvP", detail: `Installing ${mod.name}${mod.optional ? " (optional)" : ""}...`, current: index + 1, total: pvpPresetMods.length });
    emit("launcher:log", `[preset] Installing ${mod.name}${mod.optional ? " (optional)" : ""}...`);
    const projectIdOrSlug = await resolveModrinthProjectId(mod.slug, mod.query);
    if (!projectIdOrSlug) {
      skipped.push(`${mod.name} not found`);
      if (!mod.optional) emit("launcher:log", `[preset] Required mod was not found on Modrinth: ${mod.name}`);
      continue;
    }
    const result = await installModrinthProject({
      projectIdOrSlug,
      title: mod.name,
      gameVersion,
      loader: "fabric",
      instancePath,
      reason: mod.optional ? "pvp-preset-optional" : "pvp-preset",
      visited
    });
    if (result.ok) installed.push(...(result.installed || []));
    else {
      skipped.push(`${mod.name}: ${result.message}`);
      if (!mod.optional) emit("launcher:log", `[preset] Required mod skipped: ${mod.name}: ${result.message}`);
    }
  }

  return {
    ok: true,
    message: `Created ${instance.name} with ${installed.length} installed file${installed.length === 1 ? "" : "s"}${skipped.length ? ` and ${skipped.length} skipped mod${skipped.length === 1 ? "" : "s"}` : ""}. ${optimization.message}`,
    installed,
    skipped: [...optimization.skipped, ...skipped]
  };
}

async function resolveModrinthProjectId(slug, query) {
  const direct = slug ? await getModrinthProject(slug) : null;
  if (direct && direct.id) return direct.id;
  const url = new URL("https://api.modrinth.com/v2/search");
  url.searchParams.set("query", query);
  url.searchParams.set("limit", "1");
  url.searchParams.set("facets", JSON.stringify([["project_type:mod"]]));
  const response = await fetch(url, {
    headers: { "User-Agent": launcherUserAgent }
  });
  if (!response.ok) return "";
  const body = await response.json();
  const hit = body.hits && body.hits[0];
  return hit ? hit.project_id : "";
}

async function installModrinthVersion({ projectIdOrSlug, title, version, gameVersion, loader, instancePath, reason, replaceFile = "", visited, contentType = "mod" }) {
  const info = contentTypeInfo(contentType);
  const sectionName = info.key;
  const projectId = version.project_id || projectIdOrSlug;
  if (!visited) visited = new Set();
  if (visited.has(`${sectionName}:${projectId}`)) return { ok: true, message: `Skipped already handled ${info.label} ${title}.`, installed: [] };
  visited.add(`${sectionName}:${projectId}`);

  const project = await getModrinthProject(projectId);
  const resolvedContentType = normalizeContentType(contentType || (project ? project.project_type : "mod"));
  const resolvedInfo = contentTypeInfo(resolvedContentType);
  const resolvedSectionName = resolvedInfo.key;

  const manifest = readModManifest(instancePath);
  const section = manifestSection(manifest, resolvedContentType);
  let existingProjectEntry = Object.entries(section).find(([, item]) => item && item.projectId === projectId);
  if (resolvedContentType === "mod" && existingProjectEntry?.[1]?.disabled) {
    if (reason !== "dependency") return { ok: true, message: `${title} stays disabled.`, installed: [] };
    const [disabledFile, disabledMetadata] = existingProjectEntry;
    const enabledFile = disabledFile.replace(/\.disabled$/i, "");
    const disabledPath = path.join(instancePath, resolvedInfo.folder, disabledFile);
    const enabledPath = path.join(instancePath, resolvedInfo.folder, enabledFile);
    if (fs.existsSync(disabledPath) && disabledPath !== enabledPath) {
      if (fs.existsSync(enabledPath)) fs.rmSync(enabledPath, { force: true });
      fs.renameSync(disabledPath, enabledPath);
    }
    delete section[disabledFile];
    section[enabledFile] = { ...disabledMetadata, file: enabledFile, disabled: false };
    delete manifest.updates[updateKey("mod", disabledFile)];
    writeModManifest(instancePath, manifest);
    existingProjectEntry = [enabledFile, section[enabledFile]];
    emit("launcher:log", `[mods] Re-enabled required dependency ${title}.`);
  }
  const alreadyInstalled = Object.values(section).find((item) => item.projectId === projectId && item.versionId === version.id && !item.disabled);
  if (alreadyInstalled) return { ok: true, message: `${title} is already installed.`, installed: [] };
  let replaceTarget = replaceFile;
  if (!replaceTarget && existingProjectEntry) {
    replaceTarget = existingProjectEntry[0];
  }
  if (resolvedContentType === "mod") {
    const conflict = findIncomingConflict(projectId, version, manifest);
    if (conflict) {
      return { ok: false, message: `${title} is incompatible with installed mod ${conflict.installedTitle}. Remove the conflicting mod first.` };
    }
  }

  const dependencyResults = [];
  if (resolvedContentType === "mod") {
    for (const dependency of version.dependencies || []) {
      if (dependency.dependency_type !== "required") continue;
      const dependencyVersion = dependency.version_id
        ? await getModrinthVersionById(dependency.version_id)
        : await getModrinthVersion(dependency.project_id, gameVersion, loader, "mod");
      if (!dependencyVersion) return { ok: false, message: `Missing required dependency for ${title}.` };

      const dependencyProject = dependency.project_id
        ? await getModrinthProject(dependency.project_id)
        : await getModrinthProject(dependencyVersion.project_id);
      const dependencyTitle = dependencyProject ? dependencyProject.title : dependencyVersion.name;
      const installed = await installModrinthVersion({
        projectIdOrSlug: dependencyVersion.project_id,
        title: dependencyTitle,
        version: dependencyVersion,
        gameVersion,
        loader,
        instancePath,
        reason: "dependency",
        visited,
        contentType: "mod"
      });
      if (!installed.ok) return installed;
      dependencyResults.push(...(installed.installed || []));
    }
  }

  const file = version.files.find((candidate) => candidate.primary) || version.files[0];
  if (!file || !file.url) return { ok: false, message: `No downloadable file found for ${title}.` };

  const contentDir = path.join(instancePath, resolvedInfo.folder);
  fs.mkdirSync(contentDir, { recursive: true });
  if (replaceTarget) {
    fs.rmSync(path.join(contentDir, replaceTarget), { force: true });
    delete manifestSection(manifest, resolvedContentType)[replaceTarget];
    delete manifest.updates[updateKey(resolvedContentType, replaceTarget)];
    if (resolvedContentType === "mod") delete manifest.updates[replaceTarget];
  }

  const target = path.join(contentDir, sanitizeFilename(file.filename || `${projectId}${resolvedInfo.extensions[0]}`));
  const targetFile = path.basename(target);
  emit("launcher:log", `[${resolvedSectionName}] Downloading ${file.filename || title}...`);
  emitActivity({ title: `Downloading ${resolvedInfo.label}`, detail: file.filename || title, current: 0, total: 1 });
  await downloadFile(file.url, target);

  const incompatibilities = resolvedContentType === "mod" ? await resolveIncompatibilities(version) : [];
  const cachedIconUrl = project && project.icon_url ? await cacheProjectIcon(project.id || projectId, project.icon_url) : "";
  const author = project ? await getModrinthProjectAuthor(project) : "";
  const nextManifest = readModManifest(instancePath);
  const nextSection = manifestSection(nextManifest, resolvedContentType);
  if (replaceTarget) {
    delete nextSection[replaceTarget];
    delete nextManifest.updates[updateKey(resolvedContentType, replaceTarget)];
    if (resolvedContentType === "mod") delete nextManifest.updates[replaceTarget];
  }
  nextSection[targetFile] = {
    source: "modrinth",
    contentType: resolvedContentType,
    file: targetFile,
    title: project ? project.title : title,
    slug: project ? project.slug : "",
    iconUrl: cachedIconUrl || (project ? project.icon_url : ""),
    remoteIconUrl: project ? project.icon_url : "",
    author,
    gallery: project && Array.isArray(project.gallery) ? project.gallery : [],
    projectId,
    versionId: version.id,
    versionNumber: version.version_number,
    gameVersion,
    loader,
    installedAt: new Date().toISOString(),
    reason,
    dependencies: (version.dependencies || [])
      .filter((dependency) => dependency.dependency_type === "required")
      .map((dependency) => ({ projectId: dependency.project_id || "", versionId: dependency.version_id || "" })),
    incompatibilities
  };
  delete nextManifest.updates[updateKey(resolvedContentType, targetFile)];
  if (resolvedContentType === "mod") delete nextManifest.updates[targetFile];
  writeModManifest(instancePath, nextManifest);

  emit("launcher:log", `[${resolvedSectionName}] Installed ${targetFile} to ${contentDir}`);
  const installed = [...dependencyResults, targetFile];
  emitActivity({
    title: `${resolvedInfo.label} ready`,
    detail: `${project ? project.title : title} installed.`,
    current: 1,
    total: 1,
    done: true
  });
  return { ok: true, message: `Installed ${installed.length} file${installed.length === 1 ? "" : "s"} including dependencies.`, installed };
}

function readModrinthProfileExport(filePath) {
  const ext = path.extname(filePath).toLowerCase();
  const raw = ext === ".mrpack"
    ? readZipTextFile(filePath, "modrinth.index.json")
    : fs.readFileSync(filePath, "utf8");
  const index = JSON.parse(raw);
  if (!index || !Array.isArray(index.files)) throw new Error("That file is not a valid Modrinth profile export.");

  const dependencies = index.dependencies || {};
  const loader = dependencies["fabric-loader"] ? "fabric" : dependencies["quilt-loader"] ? "quilt" : "fabric";
  return {
    name: String(index.name || path.basename(filePath)),
    versionId: String(index.versionId || ""),
    minecraftVersion: String(dependencies.minecraft || ""),
    loader,
    files: index.files
      .filter((file) => file && file.path && String(file.path).toLowerCase().startsWith("mods/"))
      .map((file) => ({
        path: String(file.path).replaceAll("\\", "/"),
        downloads: Array.isArray(file.downloads) ? file.downloads.filter(Boolean).map(String) : [],
        hashes: file.hashes && typeof file.hashes === "object" ? file.hashes : {},
        env: file.env && typeof file.env === "object" ? file.env : {}
      }))
  };
}

function readZipTextFile(filePath, wantedName) {
  const buffer = fs.readFileSync(filePath);
  const end = findEndOfCentralDirectory(buffer);
  if (end < 0) throw new Error("Could not read Modrinth pack archive.");

  const entryCount = buffer.readUInt16LE(end + 10);
  const centralSize = buffer.readUInt32LE(end + 12);
  const centralOffset = buffer.readUInt32LE(end + 16);
  let offset = centralOffset;
  const centralEnd = centralOffset + centralSize;

  for (let i = 0; i < entryCount && offset < centralEnd; i += 1) {
    if (buffer.readUInt32LE(offset) !== 0x02014b50) break;
    const method = buffer.readUInt16LE(offset + 10);
    const compressedSize = buffer.readUInt32LE(offset + 20);
    const nameLength = buffer.readUInt16LE(offset + 28);
    const extraLength = buffer.readUInt16LE(offset + 30);
    const commentLength = buffer.readUInt16LE(offset + 32);
    const localHeaderOffset = buffer.readUInt32LE(offset + 42);
    const name = buffer.subarray(offset + 46, offset + 46 + nameLength).toString("utf8");

    if (name === wantedName) {
      if (buffer.readUInt32LE(localHeaderOffset) !== 0x04034b50) throw new Error("Modrinth pack archive has an invalid local header.");
      const localNameLength = buffer.readUInt16LE(localHeaderOffset + 26);
      const localExtraLength = buffer.readUInt16LE(localHeaderOffset + 28);
      const dataStart = localHeaderOffset + 30 + localNameLength + localExtraLength;
      const compressed = buffer.subarray(dataStart, dataStart + compressedSize);
      if (method === 0) return compressed.toString("utf8");
      if (method === 8) return zlib.inflateRawSync(compressed).toString("utf8");
      throw new Error("Modrinth pack uses an unsupported ZIP compression method.");
    }

    offset += 46 + nameLength + extraLength + commentLength;
  }

  throw new Error("This archive does not contain modrinth.index.json.");
}

function findEndOfCentralDirectory(buffer) {
  const min = Math.max(0, buffer.length - 0xffff - 22);
  for (let offset = buffer.length - 22; offset >= min; offset -= 1) {
    if (buffer.readUInt32LE(offset) === 0x06054b50) return offset;
  }
  return -1;
}

async function importModrinthProfile(profile, options) {
  const files = profile.files || [];
  if (!files.length) return { ok: false, message: `${profile.name} has no mods to import.` };

  const installed = [];
  const skipped = [];
  const visited = new Set();
  for (let index = 0; index < files.length; index += 1) {
    const file = files[index];
    const filename = path.basename(file.path);
    emitActivity({ title: "Importing profile", detail: `Resolving ${filename}...`, current: index + 1, total: files.length });
    const versionId = getModrinthVersionIdFromDownload(file.downloads[0] || "");
    let version = versionId ? await getModrinthVersionById(versionId) : null;
    let project = version ? await getModrinthProject(version.project_id) : null;
    let title = project ? project.title : filename;

    if (version && !isModrinthVersionCompatible(version, options.gameVersion, options.loader)) {
      if (!options.allowCurrentVersionFallback) {
        skipped.push(`${title} (${filename})`);
        continue;
      }

      const fallback = await getModrinthVersion(version.project_id, options.gameVersion, options.loader);
      if (!fallback) {
        skipped.push(`${title} (${filename})`);
        continue;
      }
      version = fallback;
      project = await getModrinthProject(version.project_id);
      title = project ? project.title : title;
    }

    if (version && project) {
      emitActivity({ title: "Importing profile", detail: `Downloading ${project.title || filename}...`, current: index + 1, total: files.length });
      const result = await installModrinthVersion({
        projectIdOrSlug: project.id || version.project_id,
        title,
        version,
        gameVersion: options.gameVersion,
        loader: options.loader,
        instancePath: options.instancePath,
        reason: "profile-import",
        visited
      });
      if (!result.ok) return result;
      installed.push(...(result.installed || []));
      continue;
    }

    if (!file.downloads.length) {
      skipped.push(filename);
      continue;
    }

    const target = path.join(options.instancePath, sanitizeProfilePath(file.path));
    fs.mkdirSync(path.dirname(target), { recursive: true });
    emit("launcher:log", `[mods] Importing ${filename} from ${profile.name}...`);
    emitActivity({ title: "Importing profile", detail: `Downloading ${filename}...`, current: index + 1, total: files.length });
    await downloadFile(file.downloads[0], target);
    installed.push(path.basename(target));
  }

  return {
    ok: true,
    message: `Imported ${installed.length} file${installed.length === 1 ? "" : "s"} from ${profile.name}${skipped.length ? ` and skipped ${skipped.length} incompatible file${skipped.length === 1 ? "" : "s"}` : ""}.`,
    installed,
    skipped
  };
}

function getModrinthVersionIdFromDownload(url) {
  const match = String(url || "").match(/\/versions\/([^/]+)\//);
  return match ? decodeURIComponent(match[1]) : "";
}

function isModrinthVersionCompatible(version, gameVersion, loader) {
  const gameVersions = Array.isArray(version.game_versions) ? version.game_versions : [];
  const loaders = Array.isArray(version.loaders) ? version.loaders.map((item) => String(item).toLowerCase()) : [];
  return gameVersions.includes(gameVersion) && loaders.includes(String(loader).toLowerCase());
}

function sanitizeProfilePath(profilePath) {
  const parts = String(profilePath || "")
    .replaceAll("\\", "/")
    .split("/")
    .filter((part) => part && part !== "." && part !== "..")
    .map(sanitizeFilename);
  if (!parts.length || parts[0] !== "mods") parts.unshift("mods");
  return path.join(...parts);
}

function findIncomingConflict(projectId, version, manifest) {
  const installed = Object.values(manifest.mods);
  const incomingIncompatibilities = (version.dependencies || [])
    .filter((dependency) => dependency.dependency_type === "incompatible")
    .map((dependency) => dependency.project_id)
    .filter(Boolean);
  const direct = installed.find((mod) => incomingIncompatibilities.includes(mod.projectId));
  if (direct) return { installedTitle: direct.title, installedFile: direct.file };

  return installed.find((mod) => {
    return Array.isArray(mod.incompatibilities) && mod.incompatibilities.some((entry) => entry.projectId === projectId);
  });
}

function isManagedProjectExplicitlyDisabled(instancePath, projectIdOrSlug, slug = "") {
  const manifest = readModManifest(instancePath);
  const projectKey = String(projectIdOrSlug || "").trim();
  const slugKey = String(slug || "").trim().toLowerCase();
  return Object.values(manifest.mods || {}).some((entry) => {
    if (!entry || !entry.disabled) return false;
    const entryProjectId = String(entry.projectId || "").trim();
    const entrySlug = String(entry.slug || "").trim().toLowerCase();
    return (projectKey && entryProjectId === projectKey) || (slugKey && entrySlug === slugKey);
  });
}

async function resolveIncompatibilities(version) {
  const entries = (version.dependencies || []).filter((dependency) => dependency.dependency_type === "incompatible");
  const resolved = [];
  for (const entry of entries) {
    if (!entry.project_id) continue;
    const project = await getModrinthProject(entry.project_id);
    resolved.push({
      projectId: entry.project_id,
      versionId: entry.version_id || "",
      title: project ? project.title : entry.project_id
    });
  }
  return resolved;
}

/** Skip a fresh scan if one ran within this window (background boot checks only). */
const MOD_UPDATE_CHECK_TTL_MS = 6 * 60 * 60 * 1000;

/**
 * Runs a mod-update scan off the launch path: skips when the last scan is still fresh, and
 * never rejects into the caller. The manual "check for updates" button still calls
 * checkModUpdates directly, so it always forces a fresh scan.
 */
function maybeCheckModUpdatesInBackground(instancePath) {
  try {
    const last = Date.parse(readModManifest(instancePath)?.checkedAt || "") || 0;
    if (Date.now() - last < MOD_UPDATE_CHECK_TTL_MS) return;
  } catch {}
  checkModUpdates(instancePath).then(() => emitStatus()).catch(() => {});
}

async function checkModUpdates(instancePath) {
  const manifest = readModManifest(instancePath);
  const updates = {};
  const tasks = [];
  for (const contentType of ["mod", "resourcepack", "shader"]) {
    const section = manifestSection(manifest, contentType);
    for (const [file, item] of Object.entries(section)) {
      if (item.source !== "modrinth" || !item.projectId) continue;
      tasks.push({ contentType, file, item });
    }
  }

  let cursor = 0;
  const workers = Array.from({ length: Math.min(6, Math.max(1, tasks.length)) }, async () => {
    while (cursor < tasks.length) {
      const task = tasks[cursor++];
      const { contentType, file, item } = task;
      const latest = await getModrinthVersion(item.projectId, item.gameVersion, item.loader, contentType);
      if (!latest) continue;
      updates[updateKey(contentType, file)] = {
        contentType,
        file,
        available: latest.id !== item.versionId,
        currentVersionId: item.versionId,
        currentVersionNumber: item.versionNumber,
        latestVersionId: latest.id,
        latestVersionNumber: latest.version_number,
        version: latest,
        checkedAt: new Date().toISOString()
      };
    }
  });
  await Promise.all(workers);
  manifest.updates = updates;
  manifest.checkedAt = new Date().toISOString();
  writeModManifest(instancePath, manifest);
  const count = Object.values(updates).filter((update) => update.available).length;
  return { ok: true, message: `${count} content update${count === 1 ? "" : "s"} available.`, count };
}

/**
 * Fetches every Modrinth-tracked mod/pack/shader that has a newer file and installs it,
 * replacing the old jar in place. Returns the titles it updated. Shared by the "Update
 * all" button and the Repair action so both apply updates the exact same way. Progress
 * is streamed through emitActivity; the caller owns the surrounding begin/end framing.
 */
async function applyAvailableModUpdates(instancePath) {
  const check = await checkModUpdates(instancePath);
  if (!check.ok) return { ok: false, message: check.message || "Could not check for updates.", updated: [] };

  const manifest = readModManifest(instancePath);
  const updates = Object.entries(manifest.updates).filter(([, update]) => update && update.available);
  if (!updates.length) return { ok: true, message: "All content is already up to date.", updated: [] };

  const updated = [];
  for (const [entryKey, update] of updates) {
    const contentType = normalizeContentType(update.contentType || entryKey.split(":")[0] || "mod");
    const file = update.file || entryKey.split(":").slice(1).join(":") || entryKey;
    const current = manifestSection(manifest, contentType)[file];
    if (!current) continue;
    emitActivity({
      title: "Updating content",
      detail: `Installing ${current.title || file}...`,
      current: updated.length,
      total: updates.length
    });
    const install = await installModrinthVersion({
      projectIdOrSlug: current.projectId,
      title: current.title,
      version: update.version,
      gameVersion: current.gameVersion,
      loader: current.loader,
      instancePath,
      reason: "update",
      replaceFile: file,
      visited: new Set(),
      contentType
    });
    if (!install.ok) {
      return { ok: false, message: install.message || `Could not update ${current.title || file}.`, updated };
    }
    updated.push(current.title || file);
  }

  await checkModUpdates(instancePath);
  return { ok: true, message: `Updated ${updated.length} content item${updated.length === 1 ? "" : "s"}.`, updated };
}

async function searchCurseForge(filters, apiKey, instancePath = "") {
  const version = String(filters.version || "1.21.11").trim();
  const loader = String(filters.loader || "fabric").trim().toLowerCase();
  const contentType = normalizeContentType(filters.contentType || filters.projectType || "mod");
  const info = contentTypeInfo(contentType);

  const res = await curseforge.search({
    query: String(filters.query || "").trim(),
    version,
    loader,
    contentType,
    apiKey
  });

  if (!res.ok) {
    return { ok: false, message: `CurseForge search failed with ${res.status}. Check your API key.`, results: [] };
  }

  const manifest = instancePath ? readModManifest(instancePath) : { mods: {}, resourcepacks: {}, shaders: {} };
  const section = manifestSection(manifest, contentType);
  return {
    ok: true,
    source: "curseforge",
    contentType,
    url: curseForgeSearchUrl(filters),
    results: res.data.map((mod) => ({
      source: "curseforge",
      contentType,
      projectType: info.projectType,
      id: mod.id,
      projectId: String(mod.id),
      slug: mod.slug,
      title: mod.name,
      description: mod.summary,
      iconUrl: mod.logo ? mod.logo.thumbnailUrl : "",
      gallery: [],
      downloads: mod.downloadCount || 0,
      follows: 0,
      author: mod.authors && mod.authors[0] ? mod.authors[0].name : "CurseForge",
      gameVersion: version,
      loader,
      url: mod.links && mod.links.websiteUrl ? mod.links.websiteUrl : curseForgeSearchUrl(filters),
      installed: Object.values(section).some((item) => item && item.source === "curseforge" && String(item.projectId) === String(mod.id))
    }))
  };
}

/**
 * Installs a CurseForge file into the instance and records it in the manifest the
 * same way Modrinth installs are, so update checks and removal treat both alike.
 */
async function installCurseForgeMod({ mod, instancePath, apiKey }) {
  const contentType = normalizeContentType(mod.contentType || "mod");
  const info = contentTypeInfo(contentType);
  const version = String(mod.gameVersion || "1.21.11");
  const loader = String(mod.loader || "fabric");

  const resolved = await curseforge.resolveFile({
    modId: mod.id || mod.projectId,
    version,
    loader,
    contentType,
    apiKey
  });
  if (!resolved.ok) return { ok: false, message: resolved.message };

  const file = resolved.file;
  const contentDir = path.join(instancePath, info.folder);
  fs.mkdirSync(contentDir, { recursive: true });
  const targetFile = sanitizeFilename(file.fileName);
  const target = path.join(contentDir, targetFile);

  emitActivity({ title: `Installing ${info.label}`, detail: mod.title, current: 0, total: 1 });
  await downloadFile(file.downloadUrl, target);

  const manifest = readModManifest(instancePath);
  const section = manifestSection(manifest, contentType);
  // Replace any older file from the same project so versions don't stack up.
  for (const [existingFile, item] of Object.entries(section)) {
    if (item && item.source === "curseforge" && String(item.projectId) === String(mod.id) && existingFile !== targetFile) {
      fs.rmSync(path.join(contentDir, existingFile), { force: true });
      delete section[existingFile];
      delete manifest.updates[updateKey(contentType, existingFile)];
    }
  }

  const cachedIconUrl = mod.iconUrl ? await cacheProjectIcon(`cf-${mod.id}`, mod.iconUrl) : "";
  section[targetFile] = {
    source: "curseforge",
    contentType,
    file: targetFile,
    title: mod.title,
    slug: mod.slug || "",
    iconUrl: cachedIconUrl || mod.iconUrl || "",
    remoteIconUrl: mod.iconUrl || "",
    author: mod.author || "CurseForge",
    gallery: [],
    projectId: String(mod.id),
    versionId: String(file.id),
    versionNumber: file.displayName || "",
    gameVersion: version,
    loader,
    installedAt: new Date().toISOString(),
    reason: "user",
    dependencies: [],
    incompatibilities: []
  };
  delete manifest.updates[updateKey(contentType, targetFile)];
  writeModManifest(instancePath, manifest);

  emit("launcher:log", `[${info.key}] Installed ${targetFile} from CurseForge`);
  emitActivity({ title: `${info.label} ready`, detail: `${mod.title} installed.`, current: 1, total: 1, done: true });
  return { ok: true, message: `Installed ${mod.title}.`, installed: [targetFile] };
}

function curseForgeSearchUrl(filters) {
  const query = encodeURIComponent(String(filters.query || ""));
  const version = encodeURIComponent(String(filters.version || "1.21.11"));
  return `https://www.curseforge.com/minecraft/search?class=mc-mods&search=${query}&version=${version}`;
}

function parseTags(value) {
  return String(value || "")
    .split(",")
    .map((tag) => tag.trim().toLowerCase())
    .filter(Boolean);
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function fetchWithTimeout(url, options = {}, timeoutMs = 45000) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    return await fetch(url, { ...options, signal: controller.signal });
  } catch (error) {
    if (error?.name === "AbortError") throw new Error(`Request timed out: ${url}`);
    throw error;
  } finally {
    clearTimeout(timer);
  }
}

async function downloadFile(url, target, options = {}) {
  const temp = `${target}.download`;
  fs.rmSync(temp, { force: true });
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), Number(options.timeoutMs || 45000));
  try {
    const response = await fetch(url, {
      headers: {
        "User-Agent": launcherUserAgent
      },
      signal: controller.signal
    });
    if (!response.ok) throw new Error(`Download failed with ${response.status}`);
    const buffer = Buffer.from(await response.arrayBuffer());
    fs.writeFileSync(temp, buffer);
    try {
      await finalizeDownloadedTempFile(temp, target);
    } catch (error) {
      if (error?.code === "EPERM") {
        throw new Error(`River could not replace ${path.basename(target)} because Windows is still using it.`);
      }
      throw error;
    }
  } catch (error) {
    fs.rmSync(temp, { force: true });
    if (error?.name === "AbortError") throw new Error(`Download timed out: ${url}`);
    throw error;
  } finally {
    clearTimeout(timer);
  }
}

function sanitizeFilename(value) {
  return String(value).replace(/[<>:"/\\|?*]/g, "_");
}

function shutdownRunningClient(reason = "River Client launcher closed.") {
  if (!launchProcess) return;
  try {
    emit("launcher:log", `[launcher] ${reason} Stopping Minecraft.`);
  } catch {}
  try {
    launchProcess.kill();
  } catch {}
  recordSessionEnd();
  launchProcess = null;
}

if (singleInstanceLock) {
  app.whenReady().then(() => {
    if (isUpdaterMode) {
      createUpdaterWindow();
      if (isUpdaterDemo) runUpdaterDemo();
      else runUpdaterMode();
      return;
    }
    if (!discordRpcRefreshTimer) {
      discordRpcRefreshTimer = setInterval(() => scheduleDiscordPresenceRefresh(), 15000);
    }
    createWindow();
    if (startupRvrPath) queueRvrImport(startupRvrPath);
    scheduleDiscordPresenceRefresh();
  });
}

app.on("activate", () => {
  if (BrowserWindow.getAllWindows().length === 0) {
    if (isUpdaterMode) createUpdaterWindow();
    else createWindow();
  }
});

app.on("window-all-closed", () => {
  if (updateWatcher) {
    clearInterval(updateWatcher);
    updateWatcher = null;
  }
  stopDiscordRpcRefreshLoop();
  if (appIsQuitting) shutdownRunningClient("Launcher exited.");
  if (process.platform !== "darwin") app.quit();
});

app.on("will-quit", () => {
  try {
    globalShortcut.unregisterAll();
  } catch {}
});

app.on("before-quit", (event) => {
  if (launchProcess) {
    event.preventDefault();
    emitActivity({
      title: "Minecraft is still running",
      detail: "Close Minecraft before quitting River Client.",
      done: true,
      error: true
    });
    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.show();
      mainWindow.focus();
    }
    return;
  }
  appIsQuitting = true;
  stopDiscordRpcRefreshLoop();
  disconnectDiscordRpc("Discord Rich Presence stopped.").catch(() => {});
  shutdownRunningClient("Launcher is quitting.");
});

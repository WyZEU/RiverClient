"use strict";

const { spawn } = require("node:child_process");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");

/**
 * Now-playing feed for the in-game module.
 *
 * Reads the Windows media session (SMTC) rather than the Spotify Web API: no login,
 * no API key. It prefers Spotify when Spotify is running, so a YouTube tab cannot
 * take the module over, and falls back to any other media session otherwise.
 *
 * Published through the same file bridge the game already reads, so the client needs
 * no network access of its own.
 */

const POLL_MS = 1000;
const SCRIPT = "now-playing.ps1";

let timer = null;
let running = false;
let lastWritten = "";

function bridgeDir() {
  const appData = process.env.APPDATA || path.join(os.homedir(), "AppData", "Roaming");
  return path.join(appData, "River Client", "bridge");
}

// A path inside app.asar exists to fs but cannot be spawned or read by an external
// process (powershell/the exe). asarUnpack puts these files in app.asar.unpacked, so
// rewrite any app.asar path to the unpacked one before we ever hand it to spawn.
function unpacked(p) {
  return String(p || "").replace(/([\\/])app\.asar([\\/])/, "$1app.asar.unpacked$2");
}

function scriptPath() {
  for (const candidate of [
    unpacked(path.join(__dirname, SCRIPT)),
    process.resourcesPath ? path.join(process.resourcesPath, "app.asar.unpacked", "app", SCRIPT) : "",
    path.join(__dirname, SCRIPT)
  ]) {
    // Skip anything still inside app.asar - existsSync lies about those.
    if (candidate && !candidate.includes(`app.asar${path.sep}`) && fs.existsSync(candidate)) return candidate;
  }
  return null;
}

function helperPath() {
  for (const candidate of [
    unpacked(path.join(__dirname, "np-helper.exe")),
    process.resourcesPath ? path.join(process.resourcesPath, "app.asar.unpacked", "app", "np-helper.exe") : "",
    process.resourcesPath ? path.join(process.resourcesPath, "np-helper.exe") : "",
    path.join(__dirname, "np-helper.exe")
  ]) {
    if (candidate && !candidate.includes(`app.asar${path.sep}`) && fs.existsSync(candidate)) return candidate;
  }
  return null;
}

/**
 * Reads the media session. Prefers the native helper (metadata AND album art);
 * falls back to the PowerShell script (metadata only) if the exe isn't present.
 */
function readOnce() {
  return new Promise((resolve) => {
    const exe = helperPath();
    if (exe) {
      // The helper writes the cover to a stable path in the bridge dir; the game
      // loads it as a texture. Writing to a fixed file (not a data URL in JSON)
      // keeps the JSON small and lets the game cache the texture by mtime.
      const artOut = path.join(bridgeDir(), "nowplaying-art.png");
      try { fs.mkdirSync(bridgeDir(), { recursive: true }); } catch {}
      const child = spawn(exe, [artOut], { windowsHide: true });
      let out = "";
      const done = setTimeout(() => { try { child.kill(); } catch {} resolve(null); }, 5000);
      child.stdout.on("data", (d) => { out += d.toString(); });
      child.on("error", () => { clearTimeout(done); resolve(null); });
      child.on("close", () => {
        clearTimeout(done);
        try { resolve(JSON.parse(out.trim())); } catch { resolve(null); }
      });
      return;
    }

    const script = scriptPath();
    if (!script) return resolve(null);
    const child = spawn(
      "powershell.exe",
      ["-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", script],
      { windowsHide: true }
    );
    let out = "";
    const done = setTimeout(() => { try { child.kill(); } catch {} resolve(null); }, 5000);
    child.stdout.on("data", (d) => { out += d.toString(); });
    child.on("error", () => { clearTimeout(done); resolve(null); });
    child.on("close", () => {
      clearTimeout(done);
      try { resolve(JSON.parse(out.trim())); } catch { resolve(null); }
    });
  });
}

/**
 * The game writes nowplaying-command.json when a media control is clicked in-game.
 * We run the helper's control mode to actually skip/pause via the media session,
 * then delete the command so it fires once.
 */
function drainCommand() {
  const exe = helperPath();
  if (!exe) return;
  const cmdFile = path.join(bridgeDir(), "nowplaying-command.json");
  let action = "";
  try {
    if (!fs.existsSync(cmdFile)) return;
    const cmd = JSON.parse(fs.readFileSync(cmdFile, "utf8"));
    action = String(cmd.action || "");
    fs.rmSync(cmdFile, { force: true });
  } catch { return; }
  if (!["next", "prev", "playpause"].includes(action)) return;
  try {
    const child = spawn(exe, ["--do", action], { windowsHide: true });
    // Without an error listener a failed spawn is emitted as an uncaught exception,
    // which crashes the whole launcher main process. Swallow it - a media control
    // that can't run should just do nothing.
    child.on("error", () => {});
  } catch {}
}

async function tick() {
  drainCommand();
  const payload = (await readOnce()) || { playing: false };
  // Write every second (position advances even when the track is unchanged) so the
  // in-game timer and bar stay live. Skip the write only when nothing at all changed
  // AND the track is paused, to avoid pointless churn while idle.
  const key = JSON.stringify(payload);
  if (key === lastWritten && !payload.playing) return;
  lastWritten = key;
  try {
    const dir = bridgeDir();
    fs.mkdirSync(dir, { recursive: true });
    fs.writeFileSync(path.join(dir, "nowplaying.json"), key);
  } catch {
    // Bridge unavailable (permissions, disk); the module just shows nothing.
  }
}

function start() {
  if (running || process.platform !== "win32") return;
  running = true;
  tick();
  timer = setInterval(tick, POLL_MS);
}

function stop() {
  running = false;
  if (timer) clearInterval(timer);
  timer = null;
  try {
    fs.rmSync(path.join(bridgeDir(), "nowplaying.json"), { force: true });
    fs.rmSync(path.join(bridgeDir(), "nowplaying-command.json"), { force: true });
  } catch {}
  lastWritten = "";
}

module.exports = { start, stop, readOnce };

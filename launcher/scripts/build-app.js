const esbuild = require("esbuild");
const fs = require("node:fs");
const path = require("node:path");
const { spawnSync } = require("node:child_process");

const root = path.resolve(__dirname, "..");
const out = path.join(root, "app");

fs.rmSync(out, { recursive: true, force: true });
fs.mkdirSync(path.join(out, "renderer"), { recursive: true });
fs.cpSync(path.join(root, "src", "assets"), path.join(out, "assets"), { recursive: true });
fs.cpSync(path.join(root, "src", "config"), path.join(out, "config"), { recursive: true });
fs.cpSync(path.join(root, "src", "bundled"), path.join(out, "bundled"), { recursive: true });
// PowerShell helper for the now-playing feed: a data file, not something esbuild bundles.
fs.copyFileSync(path.join(root, "src", "now-playing.ps1"), path.join(out, "now-playing.ps1"));
// Now-playing native helper: extracts media-session metadata AND album art (the .ps1
// fallback cannot get artwork). Recompile from source when csc is available so the
// binary stays in sync; otherwise ship the checked-in prebuilt exe as-is.
compileNowPlayingHelper(path.join(root, "src"), path.join(out, "np-helper.exe"));

function buildTailwind(input, outfile) {
  const cli = path.join(root, "node_modules", "@tailwindcss", "cli", "dist", "index.mjs");
  const result = spawnSync(
    process.execPath,
    [cli, "--input", input, "--output", outfile, "--minify"],
    { cwd: root, stdio: "inherit" }
  );
  if (result.status !== 0) {
    throw new Error(`Tailwind build failed with exit code ${result.status}`);
  }
}

function compileNowPlayingHelper(srcDir, outExe) {
  const csc = "C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe";
  const wm = "C:\Windows\System32\WinMetadata";
  const facBase = "C:\Program Files (x86)\Reference Assemblies\Microsoft\Framework\.NETFramework";
  const prebuilt = path.join(srcDir, "np-helper.exe");
  if (process.platform !== "win32" || !fs.existsSync(csc)) {
    if (fs.existsSync(prebuilt)) { fs.copyFileSync(prebuilt, outExe); console.log("np-helper: used prebuilt exe (no csc)"); }
    return;
  }
  let fac = "";
  try { for (const v of fs.readdirSync(facBase)) { const f = path.join(facBase, v, "Facades"); if (fs.existsSync(path.join(f, "System.Runtime.dll"))) { fac = f; break; } } } catch {}
  if (!fac) { if (fs.existsSync(prebuilt)) fs.copyFileSync(prebuilt, outExe); return; }
  const args = ["/nologo","/target:exe","/platform:x64","/out:"+outExe,
    "/reference:"+path.join(wm,"Windows.Media.winmd"),
    "/reference:"+path.join(wm,"Windows.Storage.winmd"),
    "/reference:"+path.join(wm,"Windows.Foundation.winmd"),
    "/reference:"+path.join(fac,"System.Runtime.dll"),
    "/reference:"+path.join(fac,"System.Threading.dll"),
    "/reference:"+path.join(fac,"System.IO.dll"),
    path.join(srcDir, "np-helper.cs")];
  const r = spawnSync(csc, args, { stdio: "pipe" });
  if (r.status === 0 && fs.existsSync(outExe)) { console.log("np-helper: compiled from source"); fs.copyFileSync(outExe, prebuilt); }
  else if (fs.existsSync(prebuilt)) { fs.copyFileSync(prebuilt, outExe); console.log("np-helper: compile failed, used prebuilt"); }
}


// Embed the CurseForge API key from secrets.env so users never enter one. CurseForge
// requires each app to use its own key; this bakes River's in at build time. Absent
// key just means CurseForge search shows its no-key state.
function readEmbeddedCfKey() {
  for (const name of ["secrets.env", "secrects.env"]) {
    try {
      const t = fs.readFileSync(path.join(root, name), "utf8");
      const m = t.match(/^CURSEFORGE_API_KEY=(.+)$/m);
      if (m) return m[1].trim();
    } catch {}
  }
  return process.env.CURSEFORGE_API_KEY || "";
}
const embeddedCfKey = readEmbeddedCfKey();

async function bundle() {
  await esbuild.build({
    entryPoints: [path.join(root, "src", "main.js")],
    bundle: true,
    platform: "node",
    target: "node22",
    minify: false,
    sourcemap: false,
    define: { "process.env.RIVER_CF_KEY": JSON.stringify(embeddedCfKey) },
    outfile: path.join(out, "main.js"),
    external: ["electron"]
  });

  await esbuild.build({
    entryPoints: [path.join(root, "src", "preload.js")],
    bundle: true,
    platform: "node",
    target: "node22",
    minify: false,
    sourcemap: false,
    outfile: path.join(out, "preload.js"),
    external: ["electron"]
  });

  await esbuild.build({
    entryPoints: [path.join(root, "src", "renderer-react", "main.jsx")],
    bundle: true,
    platform: "browser",
    target: "chrome132",
    minify: true,
    sourcemap: false,
    jsx: "automatic",
    outfile: path.join(out, "renderer", "renderer.js")
  });

  // Standalone updater window: its own React entry, bundled like the main renderer.
  await esbuild.build({
    entryPoints: [path.join(root, "src", "renderer-react", "updater.jsx")],
    bundle: true,
    platform: "browser",
    target: "chrome132",
    minify: true,
    sourcemap: false,
    jsx: "automatic",
    external: ["electron"],
    outfile: path.join(out, "renderer", "updater.js")
  });

  // Standalone game-log window: its own React entry, bundled like the updater.
  await esbuild.build({
    entryPoints: [path.join(root, "src", "renderer-react", "logs.jsx")],
    bundle: true,
    platform: "browser",
    target: "chrome132",
    minify: true,
    sourcemap: false,
    jsx: "automatic",
    external: ["electron"],
    outfile: path.join(out, "renderer", "logs.js")
  });

  const html = fs.readFileSync(path.join(root, "src", "renderer-react", "index.html"), "utf8")
    .replace(/\s+/g, " ")
    .replace(/> </g, "><")
    .trim();
  fs.writeFileSync(path.join(out, "renderer", "index.html"), html);

  const minifyCss = (source) => source
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .replace(/\s+/g, " ")
    .replace(/\s*([{}:;,>])\s*/g, "$1")
    .trim();

  // styles.css is a Tailwind v4 entry (@import "tailwindcss"), so it has to go
  // through the compiler - the old regex "minifier" would emit it verbatim and the
  // app would render with no utilities at all.
  buildTailwind(
    path.join(root, "src", "renderer-react", "styles.css"),
    path.join(out, "renderer", "styles.css")
  );

  const updaterCss = minifyCss(fs.readFileSync(path.join(root, "src", "renderer-react", "updater.css"), "utf8"));
  fs.writeFileSync(path.join(out, "renderer", "updater.css"), updaterCss);

  const logsCss = minifyCss(fs.readFileSync(path.join(root, "src", "renderer-react", "logs.css"), "utf8"));
  fs.writeFileSync(path.join(out, "renderer", "logs.css"), logsCss);

  // Minimal mount shell for the bundled updater React app (loaded via loadFile, not a URL).
  const updaterShell = "<!doctype html><html lang=\"en\"><head><meta charset=\"UTF-8\" />"
    + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\" />"
    + "<title>River Client Updater</title>"
    + "<link rel=\"stylesheet\" href=\"./updater.css\" /></head>"
    + "<body><div id=\"root\"></div><script src=\"./updater.js\"></script></body></html>";
  fs.writeFileSync(path.join(out, "renderer", "updater.html"), updaterShell);

  const logsShell = "<!doctype html><html lang=\"en\"><head><meta charset=\"UTF-8\" />"
    + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\" />"
    + "<title>River Client - Game log</title>"
    + "<link rel=\"stylesheet\" href=\"./logs.css\" /></head>"
    + "<body><div id=\"root\"></div><script src=\"./logs.js\"></script></body></html>";
  fs.writeFileSync(path.join(out, "renderer", "logs.html"), logsShell);
}

bundle().catch((error) => {
  console.error(error);
  process.exit(1);
});

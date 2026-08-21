const { spawn, spawnSync } = require("node:child_process");
const crypto = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");

const launcherRoot = path.resolve(__dirname, "..");
const repoRoot = path.resolve(launcherRoot, "..");
const siteRoot = path.join(repoRoot, "riv3r-site");
const nextSiteRoot = path.join(repoRoot, "riv3r-next");
const nextPublicRoot = path.join(nextSiteRoot, "public");
const nextOutRoot = path.join(nextSiteRoot, "out");
const liveSiteBase = "https://riverclient.xyz";
const publicUpdateBase = "https://updates.riverclient.xyz";
const workerRoot = path.join(repoRoot, "cloudflare-updates");
const bucket = "river-client-updates";
const packageJson = JSON.parse(fs.readFileSync(path.join(launcherRoot, "package.json"), "utf8"));
const appVersion = packageJson.riverVersion || packageJson.version;
const version = process.env.RIVER_PUBLISH_VERSION || appVersion;
const flags = parseFlags();
const releaseDraft = readReleaseDraft();
// A tester build gets its own notes rather than replaying the last public release's
// changelog, which would otherwise show testers the previous version's "what's new".
const changelog = flags.beta
  ? {
    version,
    title: `River Client ${version} (tester build)`,
    summary: "Tester build. Not a public release - expect rough edges and please report anything broken.",
    items: ["Tester build for the River beta channel."]
  }
  : (releaseDraft ? releaseDraftChangelog(releaseDraft) : readChangelog());

loadCloudflareToken();

function parseFlags() {
  const argv = process.argv.slice(2);
  const has = (name) => argv.includes(name);
  const fast = has("--fast") || process.env.RIVER_PUBLISH_FAST === "1";
  return {
    skipBuild: has("--skip-build") || process.env.RIVER_PUBLISH_SKIP_BUILD === "1" || fast,
    skipPages: has("--skip-pages") || process.env.RIVER_PUBLISH_SKIP_PAGES === "1" || fast,
    forceBuild: has("--force-build") || process.env.RIVER_PUBLISH_FORCE_BUILD === "1",
    // Tester release: the manifest lands at beta.json (which the worker only serves to
    // allow-listed accounts) and the public installer at the bucket root is left alone,
    // so nobody on the stable channel is dragged onto a test build.
    beta: has("--beta") || has("--channel=beta") || process.env.RIVER_PUBLISH_BETA === "1",
    uploadConcurrency: Math.max(1, Number(process.env.RIVER_PUBLISH_UPLOAD_CONCURRENCY || 4))
  };
}

function loadCloudflareToken() {
  const pickToken = (value) => String(value || "").trim().replace(/^["']|["']$/g, "");
  const pickValue = (value) => String(value || "").trim().replace(/^["']|["']$/g, "");
  if (process.env.CLOUDFLARE_TOKEN_WITH_PERMS) {
    process.env.CLOUDFLARE_API_TOKEN = pickToken(process.env.CLOUDFLARE_TOKEN_WITH_PERMS);
    return;
  }
  const searchRoots = [launcherRoot, repoRoot];
  let fallbackToken = "";
  let fallbackApiKey = "";
  let fallbackEmail = "";
  for (const root of searchRoots) {
    for (const name of ["secrets.env", "secrects.env"]) {
      const file = path.join(root, name);
      if (!fs.existsSync(file)) continue;
      const text = fs.readFileSync(file, "utf8");
      const withPerms = text.match(/^CLOUDFLARE_TOKEN_WITH_PERMS=(.+)$/m);
      if (withPerms) {
        process.env.CLOUDFLARE_API_TOKEN = pickToken(withPerms[1]);
        return;
      }
      const apiToken = text.match(/^CLOUDFLARE_API_TOKEN=(.+)$/m);
      if (apiToken && !fallbackToken) fallbackToken = pickToken(apiToken[1]);
      const legacyToken = text.match(/^CLOUDFLARE_TOKEN=(.+)$/m);
      if (legacyToken && !fallbackToken) fallbackToken = pickToken(legacyToken[1]);
      const apiKey = text.match(/^CLOUDFLARE_API_KEY=(.+)$/m);
      if (apiKey && !fallbackApiKey) fallbackApiKey = pickValue(apiKey[1]);
      const email = text.match(/^CLOUDFLARE_EMAIL=(.+)$/m);
      if (email && !fallbackEmail) fallbackEmail = pickValue(email[1]);
    }
  }
  if (fallbackToken) {
    process.env.CLOUDFLARE_API_TOKEN = fallbackToken;
    return;
  }
  if (process.env.CLOUDFLARE_API_TOKEN) {
    process.env.CLOUDFLARE_API_TOKEN = pickToken(process.env.CLOUDFLARE_API_TOKEN);
    return;
  }
  if (fallbackApiKey && fallbackEmail) {
    process.env.CLOUDFLARE_API_KEY = fallbackApiKey;
    process.env.CLOUDFLARE_EMAIL = fallbackEmail;
    return;
  }
  if (process.env.CLOUDFLARE_API_KEY && process.env.CLOUDFLARE_EMAIL) {
    process.env.CLOUDFLARE_API_KEY = pickValue(process.env.CLOUDFLARE_API_KEY);
    process.env.CLOUDFLARE_EMAIL = pickValue(process.env.CLOUDFLARE_EMAIL);
  }
}

function logStep(label) {
  const started = Date.now();
  console.log(`\n[publish] ${label}`);
  return () => {
    const seconds = ((Date.now() - started) / 1000).toFixed(1);
    console.log(`[publish] ${label} done in ${seconds}s`);
  };
}

function run(command, args, options = {}) {
  const result = spawnSync(command, args, {
    cwd: options.cwd || repoRoot,
    shell: process.platform === "win32",
    encoding: "utf8",
    stdio: options.capture ? "pipe" : "inherit",
    env: process.env
  });
  if (result.status !== 0 && !options.allowFailure) {
    if (options.capture && result.stderr) process.stderr.write(result.stderr);
    throw new Error(`${command} ${args.join(" ")} failed with code ${result.status}`);
  }
  return `${result.stdout || ""}${result.stderr || ""}`;
}

function runAsync(command, args, options = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, {
      cwd: options.cwd || repoRoot,
      shell: process.platform === "win32",
      env: process.env,
      stdio: options.capture ? ["ignore", "pipe", "pipe"] : "inherit"
    });
    let output = "";
    if (options.capture) {
      child.stdout?.on("data", (chunk) => { output += chunk.toString(); });
      child.stderr?.on("data", (chunk) => { output += chunk.toString(); });
    }
    child.on("error", reject);
    child.on("close", (code) => {
      if (code !== 0 && !options.allowFailure) {
        reject(new Error(`${command} ${args.join(" ")} failed with code ${code}${output ? `\n${output}` : ""}`));
        return;
      }
      resolve(output);
    });
  });
}

function wranglerCommand() {
  const candidates = [
    path.join(launcherRoot, "node_modules", ".bin", process.platform === "win32" ? "wrangler.cmd" : "wrangler"),
    path.join(workerRoot, "node_modules", ".bin", process.platform === "win32" ? "wrangler.cmd" : "wrangler")
  ];
  for (const candidate of candidates) {
    if (fs.existsSync(candidate)) {
      return { command: candidate, prefixArgs: [] };
    }
  }
  return { command: "npx", prefixArgs: ["wrangler"] };
}

function runCloudflare(args, options = {}) {
  const { command, prefixArgs } = wranglerCommand();
  const output = run(command, [...prefixArgs, ...args], { ...options, capture: true, allowFailure: true });
  if (output.includes("code: 10042") || output.includes("Please enable R2")) {
    throw new Error("Cloudflare R2 is not enabled on this account yet. Open the Cloudflare dashboard, enable R2, then rerun npm run publish:update.");
  }
  if (output.includes("Authentication error") || output.includes("Invalid access token")) {
    throw new Error(`Cloudflare auth failed while running: ${[...prefixArgs, ...args].join(" ")}\n\n${output.trim()}`);
  }
  if (!options.quiet) process.stdout.write(output);
  return output;
}

async function runCloudflareAsync(args, options = {}) {
  const { command, prefixArgs } = wranglerCommand();
  const output = await runAsync(command, [...prefixArgs, ...args], { ...options, capture: true, allowFailure: true });
  if (output.includes("code: 10042") || output.includes("Please enable R2")) {
    throw new Error("Cloudflare R2 is not enabled on this account yet. Open the Cloudflare dashboard, enable R2, then rerun npm run publish:update.");
  }
  if (output.includes("Authentication error") || output.includes("Invalid access token")) {
    throw new Error(`Cloudflare auth failed while running: ${[...prefixArgs, ...args].join(" ")}\n\n${output.trim()}`);
  }
  if (!options.quiet) process.stdout.write(output);
  return output;
}

async function runCloudflarePool(tasks, concurrency) {
  const queue = [...tasks];
  const errors = [];
  async function worker() {
    while (queue.length) {
      const task = queue.shift();
      if (!task) return;
      try {
        await runCloudflareAsync(task.args, { cwd: task.cwd, allowFailure: task.allowFailure, quiet: true });
        console.log(`[publish] uploaded ${task.label}`);
      } catch (error) {
        errors.push(`${task.label}: ${error.message}`);
      }
    }
  }
  await Promise.all(Array.from({ length: concurrency }, () => worker()));
  if (errors.length) throw new Error(errors.join("\n"));
}

function sha256(file) {
  return crypto.createHash("sha256").update(fs.readFileSync(file)).digest("hex");
}

function fileSize(file) {
  return fs.statSync(file).size;
}

function normalizeReleasePath(value) {
  return String(value || "").replace(/\\/g, "/").replace(/^\/+/, "");
}

function contentTypeForFile(file) {
  const ext = path.extname(file).toLowerCase();
  switch (ext) {
    case ".json": return "application/json; charset=utf-8";
    case ".js": return "application/javascript; charset=utf-8";
    case ".html": return "text/html; charset=utf-8";
    case ".css": return "text/css; charset=utf-8";
    case ".svg": return "image/svg+xml";
    case ".png": return "image/png";
    case ".jpg":
    case ".jpeg": return "image/jpeg";
    case ".ico": return "image/x-icon";
    case ".dll": return "application/x-msdownload";
    case ".exe": return "application/vnd.microsoft.portable-executable";
    case ".pak": return "application/octet-stream";
    case ".bin": return "application/octet-stream";
    default: return "application/octet-stream";
  }
}

function collectFilesRecursive(root) {
  const files = [];
  const stack = [root];
  while (stack.length) {
    const current = stack.pop();
    for (const entry of fs.readdirSync(current, { withFileTypes: true })) {
      const fullPath = path.join(current, entry.name);
      if (entry.isDirectory()) {
        stack.push(fullPath);
        continue;
      }
      files.push(fullPath);
    }
  }
  return files.sort((left, right) => left.localeCompare(right));
}

function buildReleaseFileManifest(unpackedRoot, workerUrl, releaseKey) {
  const baseUrl = `${workerUrl.replace(/\/$/, "")}/downloads/${releaseKey}/app`;
  const files = collectFilesRecursive(unpackedRoot).map((fullPath) => {
    const relative = normalizeReleasePath(path.relative(unpackedRoot, fullPath));
    return {
      path: relative,
      size: fileSize(fullPath),
      sha256: sha256(fullPath),
      contentType: contentTypeForFile(fullPath),
      url: `${baseUrl}/${relative.split("/").map(encodeURIComponent).join("/")}`
    };
  });

  return {
    version,
    generatedAt: new Date().toISOString(),
    files
  };
}

function newestMtimeInDir(dir) {
  let newest = 0;
  const stack = [dir];
  while (stack.length) {
    const current = stack.pop();
    let entries = [];
    try {
      entries = fs.readdirSync(current, { withFileTypes: true });
    } catch {
      continue;
    }
    for (const entry of entries) {
      const fullPath = path.join(current, entry.name);
      if (entry.isDirectory()) {
        stack.push(fullPath);
        continue;
      }
      const mtime = fs.statSync(fullPath).mtimeMs;
      if (mtime > newest) newest = mtime;
    }
  }
  return newest;
}

// electron-builder names local dist files from package.json's own frozen semver "version"
// field (it has no idea about appVersion/riverVersion, which carry the real 4-part River
// version for beta/hotfix builds). Local artifact names must never embed that version -
// the installer already avoided this (River-Client-Setup.exe, no version in the name);
// the portable target's artifactName in package.json matches that same version-less
// pattern. The real version only enters via the R2 upload key/path below (releaseKey).
function artifactPaths() {
  return {
    installer: path.join(launcherRoot, "dist", "River-Client-Setup.exe"),
    portable: path.join(launcherRoot, "dist", "River-Client-Portable.exe"),
    unpacked: path.join(launcherRoot, "dist", "win-unpacked"),
    appPackage: path.join(launcherRoot, "dist", `River-Client-App-${version}.zip`)
  };
}

function artifactsReady(paths) {
  return Object.values(paths).every((target) => fs.existsSync(target));
}

function artifactsCurrent(paths) {
  if (!fs.existsSync(paths.unpacked)) return false;
  const sourceTimes = [
    newestMtimeInDir(path.join(launcherRoot, "src")),
    newestMtimeInDir(path.join(launcherRoot, "scripts")),
    fs.statSync(path.join(launcherRoot, "package.json")).mtimeMs
  ];
  return newestMtimeInDir(paths.unpacked) >= Math.max(...sourceTimes);
}

function createAppPackage(paths) {
  const { unpacked, appPackage } = paths;
  if (!fs.existsSync(unpacked)) throw new Error(`Unpacked app missing: ${unpacked}`);

  const sourceMtime = newestMtimeInDir(unpacked);
  if (fs.existsSync(appPackage)) {
    const zipMtime = fs.statSync(appPackage).mtimeMs;
    if (zipMtime >= sourceMtime) {
      console.log(`[publish] reusing existing app package ${path.basename(appPackage)}`);
      return appPackage;
    }
  }

  fs.rmSync(appPackage, { force: true });
  run("powershell.exe", [
    "-NoProfile",
    "-ExecutionPolicy", "Bypass",
    "-Command",
    `Compress-Archive -Path '${unpacked.replace(/'/g, "''")}\\*' -DestinationPath '${appPackage.replace(/'/g, "''")}' -CompressionLevel Fastest -Force`
  ], { cwd: launcherRoot });
  if (!fs.existsSync(appPackage)) throw new Error(`App package missing: ${appPackage}`);
  return appPackage;
}

function ensureDir(dir) {
  fs.mkdirSync(dir, { recursive: true });
}

function copyIfExists(source, destination) {
  if (!fs.existsSync(source)) return false;
  ensureDir(path.dirname(destination));
  fs.copyFileSync(source, destination);
  return true;
}

function syncSiteAssets() {
  // Screenshots are no longer synced from the legacy riv3r-site folder - riv3r-next owns
  // its own screenshot set directly (see riv3r-next/src/lib/data.ts) and this used to
  // silently overwrite current screenshots with riv3r-site's stale ones on every publish.
  const assetTargets = [
    ["assets/riv3r-client.png", "assets/riv3r-client.png"],
    ["data/partners.json", "data/partners.json"]
  ];
  const done = logStep("sync site assets");
  for (const [remotePath, localPath] of assetTargets) {
    const nextTarget = path.join(nextPublicRoot, localPath);
    const legacyTarget = path.join(siteRoot, localPath);
    const localSource = path.join(siteRoot, localPath);
    if (copyIfExists(localSource, nextTarget)) {
      console.log(`[publish] copied ${localPath} from riv3r-site`);
      continue;
    }
    try {
      run("powershell.exe", [
        "-NoProfile",
        "-ExecutionPolicy", "Bypass",
        "-Command",
        `$ProgressPreference='SilentlyContinue'; Invoke-WebRequest -Uri '${liveSiteBase}/${remotePath}' -OutFile '${nextTarget.replace(/'/g, "''")}' -UseBasicParsing`
      ], { allowFailure: true });
      if (fs.existsSync(nextTarget)) {
        copyIfExists(nextTarget, legacyTarget);
        console.log(`[publish] downloaded ${localPath}`);
      }
    } catch {}
  }
  done();
}

function writeSiteManifests(manifest) {
  const versionPayload = {
    name: "River Client",
    version,
    url: manifest.installerUrl,
    notes: manifest.notes
  };
  const manifestJson = JSON.stringify(manifest, null, 2);
  const versionJson = JSON.stringify(versionPayload, null, 2);

  for (const root of [siteRoot, nextPublicRoot]) {
    const releasesDir = path.join(root, "releases");
    ensureDir(releasesDir);
    fs.writeFileSync(path.join(releasesDir, "latest.json"), manifestJson);
    fs.writeFileSync(path.join(root, "version.json"), versionJson);
  }
}

function buildNextSite() {
  const done = logStep("build riv3r-next site");
  run("npm", ["install"], { cwd: nextSiteRoot });
  run("npm", ["run", "build"], { cwd: nextSiteRoot });
  if (!fs.existsSync(path.join(nextOutRoot, "index.html"))) {
    throw new Error(`riv3r-next build missing output at ${nextOutRoot}`);
  }
  done();
}

function readChangelog() {
  try {
    const file = path.join(launcherRoot, "src", "config", "changelog.json");
    const config = JSON.parse(fs.readFileSync(file, "utf8"));
    const releases = Array.isArray(config.releases) ? config.releases : [];
    return releases.find((release) => String(release.version) === version) || releases[0] || null;
  } catch {
    return null;
  }
}

function readReleaseDraft() {
  const file = path.join(launcherRoot, "release-draft.json");
  if (!fs.existsSync(file)) return null;
  try {
    return JSON.parse(fs.readFileSync(file, "utf8"));
  } catch (error) {
    throw new Error(`Release draft is not valid JSON: ${error.message}`);
  }
}

function releaseDraftChangelog(draft) {
  return {
    version: String(draft.version || "").trim(),
    title: String(draft.title || "").trim(),
    summary: String(draft.summary || "").trim(),
    items: Array.isArray(draft.items) ? draft.items.map((item) => String(item || "").trim()).filter(Boolean) : []
  };
}

function validateReleaseDraft(draft) {
  if (!draft) return;
  if (String(draft.version || "").trim() !== String(version)) {
    throw new Error(`Release draft version ${draft.version || "missing"} does not match package version ${version}. Run npm run release:draft -- --version ${version} --force.`);
  }
  const publicText = JSON.stringify(draft);
  if (/replace this/i.test(publicText)) {
    throw new Error("Release draft still contains placeholder text. Edit launcher/release-draft.json before publishing.");
  }
  if (draft.approved !== true) {
    throw new Error("Release draft is not approved. Edit launcher/release-draft.json, review every message, then set approved to true.");
  }
  const normalized = releaseDraftChangelog(draft);
  if (!normalized.title || !normalized.summary || !normalized.items.length) {
    throw new Error("Release draft needs a title, summary, and at least one finished change.");
  }
  for (const key of ["announcement", "release", "devLog"]) {
    const message = draft.discord?.[key];
    if (!String(message?.title || "").trim() || !String(message?.message || "").trim()) {
      throw new Error(`Release draft Discord ${key} message needs a title and message.`);
    }
  }
}

function writeReleaseMessages(draft) {
  if (!draft) return;
  const sections = [
    ["ANNOUNCEMENT", draft.discord.announcement],
    ["RELEASES", draft.discord.release],
    ["DEV LOG", draft.discord.devLog]
  ];
  const text = sections
    .map(([label, message]) => `${label}\n${message.title}\n\n${message.message}`)
    .join("\n\n----------------------------------------\n\n");
  const output = path.join(launcherRoot, "dist", `River-Client-${version}-Discord-Messages.txt`);
  ensureDir(path.dirname(output));
  fs.writeFileSync(output, `${text}\n`, "utf8");
}

function applyReleaseDraftToChangelog(draft) {
  if (!draft) return;
  const file = path.join(launcherRoot, "src", "config", "changelog.json");
  const config = readJson(file, { releases: [] });
  const release = releaseDraftChangelog(draft);
  const releases = Array.isArray(config.releases) ? config.releases.filter((item) => String(item.version) !== version) : [];
  releases.unshift(release);
  writeJson(file, { ...config, releases });
}

/**
 * Refreshes the update manifest baked into the launcher (src/config/update-manifest.json)
 * to this release. Without this it was frozen at an ancient version, so a shipped build
 * carried a wrong "installed baseline" and, if the live manifest fetch ever timed out,
 * the update check fell back to that stale version and never offered the update. Writing
 * the current manifest here means every build ships knowing its own version, and the
 * next release always compares cleanly newer.
 */
function writeBundledUpdateManifest(manifest) {
  const file = path.join(launcherRoot, "src", "config", "update-manifest.json");
  writeJson(file, manifest);
}

function syncWorkerFallback(manifest) {
  const file = path.join(workerRoot, "src", "worker.js");
  const source = fs.readFileSync(file, "utf8");
  const pattern = /const latestManifest = [\s\S]*?;\r?\n\r?\nexport default/;
  const replacement = `const latestManifest = ${JSON.stringify(manifest, null, 2)};\n\nexport default`;
  if (!pattern.test(source)) throw new Error("Could not locate latestManifest in the update Worker.");
  fs.writeFileSync(file, source.replace(pattern, replacement), "utf8");
}

function readJson(file, fallback) {
  try {
    return JSON.parse(fs.readFileSync(file, "utf8"));
  } catch {
    return fallback;
  }
}

function writeJson(file, payload) {
  ensureDir(path.dirname(file));
  fs.writeFileSync(file, JSON.stringify(payload, null, 2));
}

async function main() {
  // The release draft is the gate for a PUBLIC launch: an approved changelog plus the
  // Discord announcement, release and dev-log posts. A tester build is announced to
  // nobody, so demanding all that for every beta would just push people into approving
  // drafts they do not mean - and the draft must stay pinned to the last real release,
  // since publishing it is what those Discord messages describe.
  if (flags.beta) {
    console.log(`[publish] beta channel: skipping release-draft approval and Discord messages`);
  } else {
    validateReleaseDraft(releaseDraft);
    applyReleaseDraftToChangelog(releaseDraft);
    writeReleaseMessages(releaseDraft);
  }
  if (!process.env.CLOUDFLARE_API_TOKEN && !(process.env.CLOUDFLARE_API_KEY && process.env.CLOUDFLARE_EMAIL)) {
    throw new Error("Cloudflare auth missing. Put CLOUDFLARE_TOKEN_WITH_PERMS, CLOUDFLARE_API_TOKEN, or CLOUDFLARE_API_KEY + CLOUDFLARE_EMAIL in secrets.env.");
  }

  const paths = artifactPaths();
  const ready = artifactsReady(paths);
  const current = ready && artifactsCurrent(paths);
  // Bake the real 4-part River version into the build. electron-builder forces app version
  // to package.json's semver ("0.1.5"), so app.getVersion() can never carry "0.1.5.2.4" -
  // which made the launcher compare a frozen 0.1.5 against every manifest and perpetually
  // "need an update" that never advanced. The app reads its own version from this file
  // instead (see readBuildVersion in main.js). Written for stable AND beta, always before
  // the build so it is bundled.
  const buildVersionPath = path.join(launcherRoot, "src", "config", "build-version.json");
  fs.mkdirSync(path.dirname(buildVersionPath), { recursive: true });
  fs.writeFileSync(buildVersionPath, JSON.stringify({
    version,
    channel: flags.beta ? "beta" : "stable",
    builtAt: new Date().toISOString()
  }, null, 2));
  console.log(`[publish] stamped build version ${version} (${flags.beta ? "beta" : "stable"})`);

  const shouldBuild = !flags.skipBuild || flags.forceBuild || !ready || !current;
  if (shouldBuild) {
    if (flags.skipBuild && !current) console.log("[publish] launcher source changed after the last dist build; rebuilding to prevent an old UI release");
    const done = logStep("build dist artifacts");
    run("npm", ["run", "dist"], { cwd: launcherRoot });
    done();
  } else {
    console.log("[publish] skipping dist build because artifacts already exist (use --force-build to rebuild)");
  }

  const doneZip = logStep("package win-unpacked zip");
  const appPackage = createAppPackage(paths);
  doneZip();

  if (!fs.existsSync(paths.installer)) throw new Error(`Installer missing: ${paths.installer}`);
  if (!fs.existsSync(paths.portable)) throw new Error(`Portable exe missing: ${paths.portable}`);

  runCloudflare(["r2", "bucket", "create", bucket], { allowFailure: true, quiet: true });
  const workerUrl = process.env.RIVER_UPDATE_BASE_URL || publicUpdateBase;

  const releaseKey = `releases/${version}`;
  const fileManifest = buildReleaseFileManifest(paths.unpacked, workerUrl, releaseKey);
  const fileManifestPath = path.join(launcherRoot, "dist", `River-Client-Files-${version}.json`);
  writeJson(fileManifestPath, fileManifest);
  const fileManifestSha256 = crypto.createHash("sha256").update(JSON.stringify(fileManifest)).digest("hex");

  const uploadTasks = [
    { label: "installer", args: ["r2", "object", "put", `${bucket}/${releaseKey}/River-Client-Setup.exe`, "--file", paths.installer, "--content-type", "application/vnd.microsoft.portable-executable", "--remote"] },
    { label: "portable", args: ["r2", "object", "put", `${bucket}/${releaseKey}/River-Client-Portable-${version}.exe`, "--file", paths.portable, "--content-type", "application/vnd.microsoft.portable-executable", "--remote"] },
    { label: "app package", args: ["r2", "object", "put", `${bucket}/${releaseKey}/River-Client-App-${version}.zip`, "--file", appPackage, "--content-type", "application/zip", "--remote"] },
    { label: "file manifest", args: ["r2", "object", "put", `${bucket}/${releaseKey}/file-manifest.json`, "--file", fileManifestPath, "--content-type", "application/json", "--remote"] }
  ];

  // River-Client-Setup.exe at the bucket root is what the website hands to the public, so
  // a tester build must never replace it. Versioned files above are safe: they live under
  // their own release key and are only reachable from a manifest.
  if (!flags.beta) {
    uploadTasks.push({
      label: "latest installer",
      args: ["r2", "object", "put", `${bucket}/River-Client-Setup.exe`, "--file", paths.installer, "--content-type", "application/vnd.microsoft.portable-executable", "--remote"]
    });
  }

  for (const entry of fileManifest.files) {
    const fullPath = path.join(paths.unpacked, ...entry.path.split("/"));
    uploadTasks.push({
      label: `app/${entry.path}`,
      args: ["r2", "object", "put", `${bucket}/${releaseKey}/app/${entry.path}`, "--file", fullPath, "--content-type", entry.contentType, "--remote"]
    });
  }

  const doneUpload = logStep(`upload release files (${flags.uploadConcurrency} at a time)`);
  await runCloudflarePool(uploadTasks, flags.uploadConcurrency);
  doneUpload();

  const doneManifest = logStep("write and upload manifest");
  const manifest = {
    name: "River Client",
    version,
    minimumVersion: version,
    required: false,
    publishedAt: new Date().toISOString(),
    pageUrl: "https://riverclient.xyz/",
    // Stable points at the rolling root installer; beta must point at its own versioned
    // copy, because a beta publish deliberately never overwrites that root file - so
    // leaving this as-is would hand testers the last STABLE installer instead.
    installerUrl: flags.beta
      ? `${publicUpdateBase}/downloads/${releaseKey}/River-Client-Setup.exe`
      : `${publicUpdateBase}/downloads/River-Client-Setup.exe`,
    portableUrl: `${publicUpdateBase}/downloads/${releaseKey}/River-Client-Portable-${version}.exe`,
    packageUrl: `${publicUpdateBase}/downloads/${releaseKey}/River-Client-App-${version}.zip`,
    fileManifestUrl: `${publicUpdateBase}/downloads/${releaseKey}/file-manifest.json`,
    appFileManifestUrl: `${publicUpdateBase}/downloads/${releaseKey}/file-manifest.json`,
    appFileBaseUrl: `${publicUpdateBase}/downloads/${releaseKey}/app/`,
    fileCount: fileManifest.files.length,
    files: {
      installer: {
        name: "River-Client-Setup.exe",
        size: fileSize(paths.installer),
        sha256: sha256(paths.installer)
      },
      portable: {
        name: `River-Client-Portable-${version}.exe`,
        size: fileSize(paths.portable),
        sha256: sha256(paths.portable)
      },
      package: {
        name: `River-Client-App-${version}.zip`,
        size: fileSize(appPackage),
        sha256: sha256(appPackage)
      },
      fileManifest: {
        name: "file-manifest.json",
        size: fileSize(fileManifestPath),
        sha256: fileManifestSha256,
        count: fileManifest.files.length
      }
    },
    changelog,
    notes: changelog ? changelog.summary : "Latest required River Client launcher build."
  };

  writeSiteManifests(manifest);
  // These three all speak to stable users: the website's download assets, the worker's
  // built-in fallback manifest, and the baseline compiled into future builds. A tester
  // release must leave every one of them pointing at the last public version.
  if (flags.beta) {
    console.log("[publish] beta channel: leaving site assets, worker fallback and bundled manifest on the stable release");
  } else {
    syncSiteAssets();
    syncWorkerFallback(manifest);
    writeBundledUpdateManifest(manifest);
  }

  const doneWorker = logStep("deploy update worker");
  runCloudflare(["deploy"], { cwd: workerRoot });
  doneWorker();

  const manifestFile = path.join(nextPublicRoot, "releases", "latest.json");
  const manifestKey = flags.beta ? "beta.json" : "latest.json";
  await runCloudflareAsync(["r2", "object", "put", `${bucket}/${manifestKey}`, "--file", manifestFile, "--content-type", "application/json", "--remote"]);
  doneManifest();

  if (flags.beta) {
    console.log("[publish] beta channel: skipping the public website deploy");
  } else if (!flags.skipPages) {
    buildNextSite();
    const donePages = logStep("deploy riv3r-next site to Cloudflare Pages");
    runCloudflare(["pages", "deploy", nextOutRoot, "--project-name=riverclient", "--branch=main", "--commit-dirty=true"], { allowFailure: true });
    donePages();
  } else {
    console.log("[publish] skipping Cloudflare Pages deploy");
  }

  console.log(`\nPublished River Client ${version}${flags.beta ? " (testers only)" : ""}`);
  console.log(`Manifest: ${workerUrl.replace(/\/$/, "")}/${manifestKey}`);
  if (flags.beta) {
    console.log("Only accounts in RIVER_TESTER_UUIDS can download this. Stable users are unaffected.");
  }
  console.log("Tips: use npm run publish:update:fast after a normal dist build, or pass --skip-build / --skip-pages.");
}

main().catch((error) => {
  console.error(error.message || error);
  process.exit(1);
});

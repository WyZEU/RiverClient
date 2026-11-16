"use strict";

const fs = require("node:fs");
const path = require("node:path");
const crypto = require("node:crypto");
const { parseArgs } = require("node:util");
const { buildBlockmap, BLOCKMAP_MIN_BYTES } = require("./blockmap");
const { assert, assertCommit, safePath, regularFile, json, digestFile, verifyArtifact, extractZip } = require("./release-artifact");
const { signManifest, verifyManifest, PUBLIC_KEY_B64, canonicalize } = require("../src/update-signature");
const { verifyLinuxArtifact } = require("./linux-release-artifact");
const config = require("../update-publishing.json");

const hash = (bytes) => crypto.createHash("sha256").update(bytes).digest("hex");
const writeJson = (file, value) => fs.writeFileSync(file, JSON.stringify(value));
const objectUrl = (key) => `${config.publicBaseUrl}/downloads/${key.split("/").map(encodeURIComponent).join("/")}`;
const versionPattern = /^(0|[1-9]\d*)(\.(0|[1-9]\d*)){2,3}$/;
const releaseVersionPattern = /^(0|[1-9]\d*)(\.(0|[1-9]\d*)){2}$/;
function compareVersions(a, b) {
  assert(versionPattern.test(a) && versionPattern.test(b), "Invalid numeric release version.");
  const aa = a.split(".").map(Number), bb = b.split(".").map(Number);
  for (let i = 0; i < Math.max(aa.length, bb.length); i++) {
    if ((aa[i] || 0) !== (bb[i] || 0)) return (aa[i] || 0) > (bb[i] || 0) ? 1 : -1;
  }
  return 0;
}
function releaseKeyFor(version, commit, channel) {
  assertCommit(commit);
  assert(releaseVersionPattern.test(version) && ["stable", "beta"].includes(channel), "Publication requires canonical MAJOR.MINOR.PATCH and a valid channel.");
  // Non-numeric directories are ignored by standalone-mod policy until the final
  // stable publication marker is written. A failed upload cannot advance that policy.
  const folder = `${version}${channel === "beta" ? "-beta" : ""}-${commit.slice(0, 12)}`;
  assert(folder.length <= 50, "Release prefix exceeds the Worker's public-download contract.");
  return `releases/${folder}`;
}
function validateNotes(notes, version) {
  assert(notes?.version === version && notes.approved === true, "Release notes must be approved and match the artifact version.");
  for (const field of ["title", "summary"]) assert(typeof notes[field] === "string" && notes[field].trim() && notes[field].length <= 4000, `Missing release ${field}.`);
  assert(Array.isArray(notes.items) && notes.items.length > 0 && notes.items.length <= 100 && notes.items.every((s) => typeof s === "string" && s.trim() && s.length <= 4000), "Invalid release-note items.");
  return { version, title: notes.title, summary: notes.summary, items: notes.items };
}

async function prepareSignedUpdate({ artifactDirectory, linuxArtifactDirectory, outputDirectory, expectedCommit, notes, minimumVersion,
  channel = "stable", publishedAt, required = false }) {
  assert(!fs.existsSync(outputDirectory), "Publication output already exists; retain it for retries or choose a new directory.");
  const artifact = await verifyArtifact(artifactDirectory, expectedCommit);
  const version = artifact.info.version;
  const linux = await verifyLinuxArtifact(linuxArtifactDirectory, expectedCommit, version, artifact.info.runtime.minecraftVersions);
  const changelog = validateNotes(notes, version);
  assert(compareVersions(minimumVersion, version) <= 0, "Minimum version is newer than the release.");
  assert(typeof required === "boolean", "required must be a boolean.");
  assert(typeof publishedAt === "string" && Number.isFinite(Date.parse(publishedAt)) && new Date(publishedAt).toISOString() === publishedAt, "An explicit canonical UTC publication timestamp is required.");
  const releaseKey = releaseKeyFor(version, expectedCommit, channel);
  fs.mkdirSync(outputDirectory, { recursive: true });
  try {
    await extractZip(path.join(artifactDirectory, "River-Client-App.zip"), path.join(outputDirectory, "app"), artifact.files);
    const runtime = json(path.join(outputDirectory, "app/resources/bundled/river-build-info.json"));
    assert(canonicalize(runtime) === canonicalize(artifact.info.runtime), "Packaged runtime provenance differs from build evidence.");
    // The Minecraft versions not bundled in the app; extractZip checks each one's size and hash.
    await extractZip(path.join(artifactDirectory, "River-Client-Mods.zip"), path.join(outputDirectory, "mods"), artifact.mods);
    const objects = [];
    async function addObject(key, file, contentType = "application/octet-stream") {
      const location = regularFile(outputDirectory, file);
      const digest = await digestFile(location);
      const object = { key, file, ...digest, contentType };
      objects.push(object);
      return object;
    }
    const files = [];
    fs.mkdirSync(path.join(outputDirectory, "metadata"));
    for (const [name, entry] of artifact.files) {
      const key = `${releaseKey}/app/${name}`;
      const object = await addObject(key, `app/${name}`);
      assert(object.sha256 === entry.sha256 && object.size === entry.size, `Changed extracted file: ${name}`);
      const file = { path: name, size: object.size, sha256: object.sha256, url: objectUrl(key) };
      // Blockmaps are optional; bound their existing in-memory chunker for very
      // large future files. Whole-file downloads remain hash-verified.
      if (entry.size >= BLOCKMAP_MIN_BYTES && entry.size <= 512 * 1024 ** 2) {
        const mapFile = `metadata/blockmap-${files.length}.json`;
        writeJson(path.join(outputDirectory, mapFile), buildBlockmap(path.join(outputDirectory, "app", ...name.split("/"))));
        const mapKey = `${key}.blockmap.json`;
        await addObject(mapKey, mapFile, "application/json");
        file.blockmapUrl = objectUrl(mapKey);
      }
      files.push(file);
    }
    fs.mkdirSync(path.join(outputDirectory, "assets"));
    const distribution = {};
    for (const [kind, input, output] of [
      ["installer", "River-Client-Setup.exe", "River-Client-Setup.exe"],
      ["portable", "River-Client-Portable.exe", `River-Client-Portable-${version}.exe`],
      ["package", "River-Client-App.zip", `River-Client-App-${version}.zip`]
    ]) {
      fs.copyFileSync(path.join(artifactDirectory, input), path.join(outputDirectory, "assets", output));
      const key = `${releaseKey}/${output}`;
      const object = await addObject(key, `assets/${output}`, kind === "package" ? "application/zip" : "application/vnd.microsoft.portable-executable");
      assert(object.sha256 === artifact.hashes.get(input).sha256, `Distribution file changed: ${input}`);
      distribution[kind] = { name: output, size: object.size, sha256: object.sha256, url: objectUrl(key) };
    }
    const mods = [];
    const linuxName = `River-Client-Linux-${version}.tar.gz`;
    fs.copyFileSync(path.join(linuxArtifactDirectory, "River-Client-Linux.tar.gz"), path.join(outputDirectory, "assets", linuxName));
    const linuxKey = `${releaseKey}/${linuxName}`;
    const linuxObject = await addObject(linuxKey, `assets/${linuxName}`, "application/gzip");
    assert(linuxObject.sha256 === linux.archive.sha256 && linuxObject.size === linux.archive.size, "Linux distribution changed during preparation.");
    distribution.linux = { name: linuxName, size: linuxObject.size, sha256: linuxObject.sha256, url: objectUrl(linuxKey) };
    for (const minecraft of [...runtime.minecraftVersions].sort((a, b) => {
      const aa = a.split(".").map(Number), bb = b.split(".").map(Number);
      for (let i = 0; i < Math.max(aa.length, bb.length); i++) if ((aa[i] || 0) !== (bb[i] || 0)) return (bb[i] || 0) - (aa[i] || 0);
      return 0;
    })) {
      const name = `clientcore-${version}+${minecraft}.jar`;
      const key = `${releaseKey}/mod/${name}`;
      const source = artifact.mods.has(name) ? `mods/${name}` : `app/resources/bundled/${name}`;
      const object = await addObject(key, source, "application/java-archive");
      mods.push({ minecraft, name, size: object.size, sha256: object.sha256, url: objectUrl(key) });
    }
    const fileManifest = { version, generatedAt: publishedAt, files };
    // Existing clients verify SHA-256(JSON.stringify(parsedManifest)). Compact JSON
    // without a trailing newline makes that identical to the stored object's hash.
    writeJson(path.join(outputDirectory, "metadata/file-manifest.json"), fileManifest);
    const fileObject = await addObject(`${releaseKey}/file-manifest.json`, "metadata/file-manifest.json", "application/json");
    const manifest = {
      name: "River Client", version, sourceCommit: expectedCommit, channel, minimumVersion, required, publishedAt,
      pageUrl: config.pageUrl,
      installerUrl: distribution.installer.url, portableUrl: distribution.portable.url,
      linuxUrl: distribution.linux.url,
      packageUrl: distribution.package.url, packageParts: [],
      fileManifestUrl: objectUrl(fileObject.key), appFileManifestUrl: objectUrl(fileObject.key),
      appFileBaseUrl: `${objectUrl(`${releaseKey}/app`)}/`, fileCount: files.length,
      files: Object.fromEntries(Object.entries(distribution).map(([kind, { url, ...file }]) => [kind, file])),
      mods, changelog, notes: changelog.summary
    };
    manifest.files.fileManifest = { name: "file-manifest.json", size: fileObject.size, sha256: fileObject.sha256, count: files.length };
    writeJson(path.join(outputDirectory, "manifest.unsigned.json"), manifest);
    const plan = { schemaVersion: 1, version, sourceCommit: expectedCommit, channel, releaseKey,
      manifestSha256: hash(JSON.stringify(manifest)), objects };
    writeJson(path.join(outputDirectory, "publication-plan.json"), plan);
    await validatePrepared(outputDirectory);
    return plan;
  } catch (error) {
    fs.rmSync(outputDirectory, { recursive: true, force: true });
    throw error;
  }
}

async function validatePrepared(directory) {
  const plan = json(regularFile(directory, "publication-plan.json"));
  const manifest = json(regularFile(directory, "manifest.unsigned.json"));
  assert(plan.schemaVersion === 1 && plan.releaseKey === releaseKeyFor(plan.version, plan.sourceCommit, plan.channel), "Invalid publication plan.");
  assert(manifest.sourceCommit === plan.sourceCommit && manifest.channel === plan.channel && manifest.version === plan.version && !manifest.signature, "Publication manifest/plan mismatch.");
  assert(hash(JSON.stringify(manifest)) === plan.manifestSha256, "Prepared manifest changed.");
  assert(compareVersions(manifest.minimumVersion, manifest.version) <= 0, "Invalid minimum version.");
  assert(Array.isArray(plan.objects) && plan.objects.length > 0 && plan.objects.length <= 30000, "Invalid publication objects.");
  const keys = new Set();
  const byUrl = new Map();
  for (const object of plan.objects) {
    safePath(object.key);
    assert(object.key.startsWith(`${plan.releaseKey}/`) && !keys.has(object.key.toLowerCase()), "Unexpected or duplicate publication key.");
    keys.add(object.key.toLowerCase());
    const actual = await digestFile(regularFile(directory, object.file));
    assert(actual.size === object.size && actual.sha256 === object.sha256, `Prepared file changed: ${object.file}`);
    byUrl.set(objectUrl(object.key), object);
  }
  function linked(url, metadata) {
    const object = byUrl.get(url);
    assert(object && metadata && metadata.sha256 === object.sha256 && metadata.size === object.size, `Unverified manifest reference: ${url}`);
    return object;
  }
  for (const kind of ["installer", "portable", "package", "linux"]) linked(manifest[`${kind}Url`], manifest.files[kind]);
  assert(manifest.linuxUrl === objectUrl(`${plan.releaseKey}/River-Client-Linux-${plan.version}.tar.gz`), "Invalid Linux release URL.");
  const fileObject = linked(manifest.fileManifestUrl, manifest.files.fileManifest);
  assert(manifest.appFileManifestUrl === manifest.fileManifestUrl && manifest.appFileBaseUrl === `${objectUrl(`${plan.releaseKey}/app`)}/`, "Invalid differential URLs.");
  const files = json(regularFile(directory, fileObject.file));
  assert(files.version === manifest.version && files.files.length === manifest.fileCount, "File-manifest version/count mismatch.");
  assert(hash(JSON.stringify(files)) === manifest.files.fileManifest.sha256, "File manifest is incompatible with installed clients.");
  for (const file of files.files) {
    safePath(file.path);
    assert(file.url === objectUrl(`${plan.releaseKey}/app/${file.path}`), "Unexpected app file URL.");
    linked(file.url, file);
    if (file.blockmapUrl) assert(byUrl.has(file.blockmapUrl) && file.blockmapUrl === `${file.url}.blockmap.json`, "Missing blockmap object.");
  }
  for (const mod of manifest.mods) linked(mod.url, mod);
  return { plan, manifest };
}

async function signPrepared(directory, privateKeyPem, publicKey = PUBLIC_KEY_B64) {
  const { manifest } = await validatePrepared(directory);
  const key = crypto.createPrivateKey(privateKeyPem);
  assert(key.asymmetricKeyType === "ed25519", "The updater requires its existing Ed25519 key.");
  assert(crypto.createPublicKey(key).export({ type: "spki", format: "der" }).toString("base64") === publicKey,
    "Signing key does not match the public key trusted by installed River clients.");
  const signed = signManifest(manifest, privateKeyPem);
  assert(verifyManifest(signed, publicKey), "Generated update signature failed verification.");
  writeJson(path.join(directory, "manifest.json"), signed);
  return signed;
}

if (require.main === module) {
  (async () => {
    const { values } = parseArgs({ options: {
      artifact: { type: "string" }, "linux-artifact": { type: "string" }, output: { type: "string" }, commit: { type: "string" }, notes: { type: "string" },
      "minimum-version": { type: "string" }, channel: { type: "string", default: "stable" },
      "published-at": { type: "string" }, required: { type: "boolean", default: false }, sign: { type: "string" }
    } });
    if (values.sign) {
      assert(process.env.RIVER_UPDATE_SIGNING_KEY, "Set RIVER_UPDATE_SIGNING_KEY to the existing private-key file.");
      await signPrepared(path.resolve(values.sign), fs.readFileSync(process.env.RIVER_UPDATE_SIGNING_KEY, "utf8"));
      console.log("Update manifest signed and verified against the existing public key.");
    } else {
      assert(values.artifact && values["linux-artifact"] && values.output && values.notes, "Required: --artifact DIR --linux-artifact DIR --output NEW_DIR --commit SHA --notes JSON --minimum-version VERSION --published-at ISO_UTC");
      const plan = await prepareSignedUpdate({ artifactDirectory: path.resolve(values.artifact), outputDirectory: path.resolve(values.output),
        linuxArtifactDirectory: path.resolve(values["linux-artifact"]),
        expectedCommit: values.commit, notes: json(values.notes), minimumVersion: values["minimum-version"],
        channel: values.channel, publishedAt: values["published-at"], required: values.required });
      console.log(`Prepared ${plan.version} (${plan.channel}) from ${plan.sourceCommit}: ${plan.objects.length} objects. No upload performed.`);
    }
  })().catch((error) => { console.error(error.message); process.exitCode = 1; });
}

module.exports = { prepareSignedUpdate, validatePrepared, signPrepared, compareVersions, releaseKeyFor, objectUrl, hash, validateNotes };

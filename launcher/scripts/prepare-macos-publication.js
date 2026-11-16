"use strict";

const fs = require("node:fs");
const path = require("node:path");
const crypto = require("node:crypto");
const { parseArgs } = require("node:util");
const { assert, assertCommit, regularFile, json, digestFile } = require("./release-artifact");
const { releaseKeyFor, objectUrl, hash, validateNotes } = require("./prepare-signed-update");
const { signManifest, verifyManifest, PUBLIC_KEY_B64, canonicalize } = require("../src/update-signature");
const config = require("../update-publishing.json");
const architectures = ["arm64", "x64"];
const write = (file, value) => fs.writeFileSync(file, JSON.stringify(value));
const dmgName = (version, arch) => `River-Client-${version}-macOS-${arch}.dmg`;

async function verifyMacArtifact(directory, expectedCommit, arch) {
  assertCommit(expectedCommit);
  assert(architectures.includes(arch), "Unsupported Mac architecture.");
  const info = json(regularFile(directory, "build-result.json"));
  assert(info.sourceCommit === expectedCommit && info.sourceDirty === false, "Mac artifact source mismatch or dirty source.");
  assert(/^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/.test(info.version), "Invalid Mac artifact version.");
  const name = dmgName(info.version, arch);
  const names = [name, "build-result.json", `build-result-${arch}.json`, `notarization-${arch}.json`, "SHA256SUMS"];
  assert(fs.readdirSync(directory).sort().join("\n") === names.sort().join("\n"), "Unexpected Mac artifact contents.");
  for (const file of names) regularFile(directory, file);
  const perArch = json(regularFile(directory, `build-result-${arch}.json`));
  assert(perArch.sourceCommit === expectedCommit && perArch.sourceDirty === false && perArch.version === info.version
    && canonicalize(perArch.artifacts) === canonicalize(info.artifacts), "Inconsistent Mac build evidence.");
  assert(info.artifacts?.length === 1, "Expected exactly one DMG per architecture.");
  const artifact = info.artifacts[0];
  assert(artifact.arch === arch && artifact.file === name && artifact.signed === true && artifact.notarized === true,
    "Mac artifact is not signed and notarized for the requested architecture.");
  const notary = json(regularFile(directory, `notarization-${arch}.json`));
  assert(notary.status === "Accepted" && /^[a-f0-9-]{36}$/i.test(notary.id || "") && artifact.notarizationId === notary.id,
    "Missing accepted Apple notarization receipt.");
  const actual = await digestFile(regularFile(directory, name));
  assert(actual.size > 0 && actual.size === artifact.size && actual.sha256 === artifact.sha256, "Mac DMG checksum mismatch.");
  assert(fs.readFileSync(path.join(directory, "SHA256SUMS"), "utf8").trim() === `${actual.sha256}  ${name}`,
    "Mac checksum inventory differs from the DMG.");
  return { version: info.version, sourceCommit: expectedCommit, ...artifact };
}

async function prepareMacPublication({ artifactDirectory, outputDirectory, expectedCommit, notes, publishedAt }) {
  assert(!fs.existsSync(outputDirectory), "Publication output already exists; retain it for retries.");
  const artifacts = await Promise.all(architectures.map((arch) => verifyMacArtifact(path.join(artifactDirectory, arch), expectedCommit, arch)));
  const version = artifacts[0].version;
  assert(artifacts.every((a) => a.version === version), "Mac architectures have different versions.");
  const changelog = validateNotes(notes, version);
  assert(typeof publishedAt === "string" && Number.isFinite(Date.parse(publishedAt)) && new Date(publishedAt).toISOString() === publishedAt,
    "An explicit canonical UTC publication timestamp is required.");
  const releaseKey = `${releaseKeyFor(version, expectedCommit, "stable")}/macos`;
  fs.mkdirSync(outputDirectory, { recursive: true });
  try {
    const objects = [], macUrls = {}, files = {};
    for (const artifact of artifacts) {
      fs.copyFileSync(regularFile(path.join(artifactDirectory, artifact.arch), artifact.file), path.join(outputDirectory, artifact.file));
      const object = { key: `${releaseKey}/${artifact.file}`, file: artifact.file, size: artifact.size, sha256: artifact.sha256,
        contentType: "application/x-apple-diskimage" };
      objects.push(object);
      macUrls[artifact.arch] = objectUrl(object.key);
      files[artifact.arch] = { name: artifact.file, size: artifact.size, sha256: artifact.sha256 };
    }
    fs.writeFileSync(path.join(outputDirectory, "SHA256SUMS"), artifacts.map((a) => `${a.sha256}  ${a.file}`).join("\n") + "\n");
    const sums = await digestFile(path.join(outputDirectory, "SHA256SUMS"));
    objects.push({ key: `${releaseKey}/SHA256SUMS`, file: "SHA256SUMS", ...sums, contentType: "text/plain; charset=utf-8" });
    const manifest = { name: "River Client", platform: "darwin", channel: "stable", version, sourceCommit: expectedCommit,
      publishedAt, required: false, minimumSystemVersion: "12.0", pageUrl: config.pageUrl, macUrls, files: { macos: files },
      checksums: { url: objectUrl(`${releaseKey}/SHA256SUMS`), ...sums }, changelog, notes: changelog.summary };
    const plan = { schemaVersion: 1, platform: "darwin", version, sourceCommit: expectedCommit, releaseKey,
      manifestSha256: hash(JSON.stringify(manifest)), objects };
    write(path.join(outputDirectory, "manifest.unsigned.json"), manifest);
    write(path.join(outputDirectory, "publication-plan.json"), plan);
    await validateMacPublication(outputDirectory);
    return plan;
  } catch (error) { fs.rmSync(outputDirectory, { recursive: true, force: true }); throw error; }
}

async function validateMacPublication(directory) {
  const plan = json(regularFile(directory, "publication-plan.json"));
  const manifest = json(regularFile(directory, "manifest.unsigned.json"));
  assert(plan.schemaVersion === 1 && plan.platform === "darwin"
    && plan.releaseKey === `${releaseKeyFor(plan.version, plan.sourceCommit, "stable")}/macos`, "Invalid Mac publication plan.");
  assert(manifest.platform === "darwin" && manifest.channel === "stable" && manifest.version === plan.version
    && manifest.sourceCommit === plan.sourceCommit && manifest.required === false && !manifest.minimumVersion && !manifest.signature,
    "Mac manifest differs from the release plan or changes update policy.");
  assert(hash(JSON.stringify(manifest)) === plan.manifestSha256, "Prepared Mac manifest changed.");
  const expected = architectures.map((arch) => dmgName(plan.version, arch)).concat("SHA256SUMS");
  assert(Array.isArray(plan.objects) && plan.objects.length === 3, "Expected both Mac architectures and checksums.");
  for (const name of expected) {
    const matches = plan.objects.filter((o) => o.file === name && o.key === `${plan.releaseKey}/${name}`);
    assert(matches.length === 1, "Unexpected or duplicate Mac publication key.");
    const object = matches[0], actual = await digestFile(regularFile(directory, name));
    assert(actual.size === object.size && actual.sha256 === object.sha256, `Prepared Mac file changed: ${name}`);
    const arch = architectures.find((a) => name === dmgName(plan.version, a));
    const metadata = arch ? manifest.files?.macos?.[arch] : manifest.checksums;
    const url = arch ? manifest.macUrls?.[arch] : manifest.checksums?.url;
    assert(url === objectUrl(object.key) && metadata?.sha256 === actual.sha256 && metadata?.size === actual.size
      && (!arch || metadata.name === name), "Unverified Mac manifest reference.");
  }
  const sums = architectures.map((arch) => `${manifest.files.macos[arch].sha256}  ${dmgName(plan.version, arch)}`).join("\n") + "\n";
  assert(fs.readFileSync(regularFile(directory, "SHA256SUMS"), "utf8") === sums, "Mac checksum inventory changed.");
  return { plan, manifest };
}

async function signMacPublication(directory, privateKeyPem, publicKey = PUBLIC_KEY_B64) {
  const { manifest } = await validateMacPublication(directory);
  const key = crypto.createPrivateKey(privateKeyPem);
  assert(key.asymmetricKeyType === "ed25519" && crypto.createPublicKey(key).export({ type: "spki", format: "der" }).toString("base64") === publicKey,
    "Signing key does not match the existing River update key.");
  const signed = signManifest(manifest, privateKeyPem);
  assert(verifyManifest(signed, publicKey), "Mac manifest signature verification failed.");
  write(path.join(directory, "manifest.json"), signed);
  return signed;
}

if (require.main === module) {
  (async () => {
    const { values } = parseArgs({ options: { artifact: { type: "string" }, output: { type: "string" }, commit: { type: "string" },
      notes: { type: "string" }, "published-at": { type: "string" }, sign: { type: "string" } } });
    if (values.sign) {
      assert(process.env.RIVER_UPDATE_SIGNING_KEY, "Set RIVER_UPDATE_SIGNING_KEY to the existing private-key file.");
      await signMacPublication(path.resolve(values.sign), fs.readFileSync(process.env.RIVER_UPDATE_SIGNING_KEY, "utf8"));
    } else {
      assert(values.artifact && values.output && values.notes, "Required: --artifact DIR --output NEW_DIR --commit SHA --notes JSON --published-at ISO_UTC");
      const plan = await prepareMacPublication({ artifactDirectory: path.resolve(values.artifact), outputDirectory: path.resolve(values.output),
        expectedCommit: values.commit, notes: json(values.notes), publishedAt: values["published-at"] });
      console.log(`Prepared macOS ${plan.version} from ${plan.sourceCommit}. No upload performed.`);
    }
  })().catch((error) => { console.error(error.message); process.exitCode = 1; });
}

module.exports = { architectures, dmgName, verifyMacArtifact, prepareMacPublication, validateMacPublication, signMacPublication };

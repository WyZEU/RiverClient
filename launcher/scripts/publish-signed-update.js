"use strict";

const fs = require("node:fs");
const path = require("node:path");
const { parseArgs } = require("node:util");
const { assert, regularFile, json } = require("./release-artifact");
const { validatePrepared, compareVersions, hash } = require("./prepare-signed-update");
const { verifyManifest, PUBLIC_KEY_B64, canonicalize } = require("../src/update-signature");
const config = require("../update-publishing.json");

function verifiedSnapshot(snapshot, publicKey, label) {
  assert(snapshot && verifyManifest(snapshot.value, publicKey), `Missing or invalid signed ${label} manifest.`);
  assert(snapshot.etag, `Missing ${label} ETag.`);
  return snapshot;
}
async function fetchBytes(url, fetchRemote) {
  const response = await fetchRemote(url, { redirect: "error", signal: AbortSignal.timeout(30000), cache: "no-store" });
  assert(response.ok, `Public release endpoint returned HTTP ${response.status}: ${url}`);
  // These requests are metadata only; never buffer unbounded response bodies.
  const chunks = [];
  let size = 0;
  for await (const chunk of response.body) {
    size += chunk.length;
    assert(size <= 16 * 1024 ** 2, "Public release metadata is too large.");
    chunks.push(Buffer.from(chunk));
  }
  return Buffer.concat(chunks);
}

async function publishPrepared({ directory, store, expectedPreviousVersion, publicKey = PUBLIC_KEY_B64, fetchRemote = fetch }) {
  const { plan, manifest: unsigned } = await validatePrepared(directory);
  const manifest = json(regularFile(directory, "manifest.json"));
  assert(verifyManifest(manifest, publicKey), "Prepared update manifest has an invalid signature.");
  const { signature, ...payload } = manifest;
  assert(canonicalize(payload) === canonicalize(unsigned), "Signed manifest differs from the reviewed publication plan.");
  const bytes = Buffer.from(JSON.stringify(manifest));
  const channelKey = plan.channel === "stable" ? "latest.json" : "beta.json";
  const receipt = { version: plan.version, sourceCommit: plan.sourceCommit, channel: plan.channel,
    channelCommitted: false, assetsVerified: false, historyMarked: false };
  try {
    const capabilities = JSON.parse(await fetchBytes(`${config.publicBaseUrl}/release-capabilities.json`, fetchRemote));
    assert(capabilities.publicationProtocol === 1, "Deploy the immutable-publication Worker changes before publishing.");
    assert(capabilities.linuxPublicationProtocol === 1, "Deploy the Linux rolling-download Worker changes before publishing.");
    const stable = verifiedSnapshot(await store.getJson("latest.json"), publicKey, "stable");
    const served = JSON.parse(await fetchBytes(`${config.publicBaseUrl}/latest.json`, fetchRemote));
    assert(verifyManifest(served, publicKey) && canonicalize(served) === canonicalize(stable.value),
      "The public Worker does not serve the unchanged signed R2 manifest. Deploy its fix first.");
    const current = plan.channel === "stable" ? stable : await store.getJson("beta.json");
    if (current) verifiedSnapshot(current, publicKey, plan.channel);
    const alreadyPublished = !!current && canonicalize(current.value) === canonicalize(manifest);
    receipt.channelCommitted = alreadyPublished;
    if (!alreadyPublished) {
      assert((current?.value.version || "none") === expectedPreviousVersion, "Channel version changed; review the current release before publishing.");
      assert(compareVersions(plan.version, stable.value.version) > 0, "Publication must be newer than the stable release.");
      if (current) assert(compareVersions(plan.version, current.value.version) > 0, "Refusing a downgrade or replacement of an existing release version.");
    }
    // All bytes were checked locally above; create-only writes also prevent an
    // existing immutable release from being silently replaced by a new signing run.
    for (const object of plan.objects) {
      if (!await store.head(object.key)) await store.putFile(object.key, regularFile(directory, object.file), object);
      await store.verify(object.key, object);
    }
    const archiveKey = `${plan.releaseKey}/release-manifest.json`;
    const archived = await store.getJson(archiveKey);
    if (archived) assert(canonicalize(archived.value) === canonicalize(manifest), "Conflicting immutable release manifest.");
    else await store.putJson(archiveKey, bytes);
    await store.verify(archiveKey, { size: bytes.length, sha256: hash(bytes) });
    const publicFiles = await fetchBytes(manifest.fileManifestUrl, fetchRemote);
    assert(hash(publicFiles) === manifest.files.fileManifest.sha256, "Public file-manifest readback differs from the signed release.");
    receipt.assetsVerified = true;

    // A single compare-and-swap changes the channel. Downloads and the website's
    // rolling installer route use URLs selected by that same signed manifest.
    if (!alreadyPublished) {
      try { await store.putJson(channelKey, bytes, current?.etag || null); }
      catch (error) {
        // A lost response does not prove the conditional write failed. Read back
        // before reporting failure, so an operator does not blindly publish again.
        const recovered = await store.getJson(channelKey).catch(() => null);
        if (!recovered || canonicalize(recovered.value) !== canonicalize(manifest)) {
          receipt.channelCommitUncertain = !recovered;
          throw error;
        }
      }
    }
    receipt.channelCommitted = true;
    const promoted = verifiedSnapshot(await store.getJson(channelKey), publicKey, plan.channel);
    assert(canonicalize(promoted.value) === canonicalize(manifest), "Channel readback changed after promotion.");
    if (plan.channel === "stable") {
      const markerKey = `releases/${plan.version}/published.json`;
      const marker = { version: plan.version, sourceCommit: plan.sourceCommit, releaseKey: plan.releaseKey, manifestSha256: hash(bytes) };
      const previous = await store.getJson(markerKey);
      if (previous) assert(canonicalize(previous.value) === canonicalize(marker), "Conflicting stable-release history marker.");
      else await store.putJson(markerKey, Buffer.from(JSON.stringify(marker)));
      receipt.historyMarked = true;
      const visible = JSON.parse(await fetchBytes(`${config.publicBaseUrl}/latest.json`, fetchRemote));
      assert(verifyManifest(visible, publicKey) && canonicalize(visible) === canonicalize(manifest), "Published manifest is not served unchanged by the Worker.");
    }
    receipt.manifestUrl = `${config.publicBaseUrl}/${channelKey}`;
    receipt.installerUrl = manifest.installerUrl;
    receipt.linuxUrl = manifest.linuxUrl;
    return receipt;
  } catch (error) {
    receipt.error = error.message;
    if (receipt.channelCommitted) error.message = `Channel promotion committed; verify live state and retry this SAME prepared release to finish verification/history. ${error.message}`;
    else if (receipt.channelCommitUncertain) error.message = `Channel promotion result is uncertain; verify live state before retrying this SAME prepared release. ${error.message}`;
    throw error;
  } finally {
    fs.writeFileSync(path.join(directory, "publication-result.json"), JSON.stringify(receipt, null, 2) + "\n");
  }
}

if (require.main === module) {
  (async () => {
    const { values } = parseArgs({ options: {
      prepared: { type: "string" }, "expect-current-version": { type: "string" },
      publish: { type: "boolean", default: false }
    } });
    assert(values.prepared, "Required: --prepared DIR; add --publish --expect-current-version VERSION only for a live promotion.");
    const directory = path.resolve(values.prepared);
    if (!values.publish) {
      const { plan } = await validatePrepared(directory);
      console.log(`Verified local publication plan ${plan.version} (${plan.objects.length} objects). No credentials loaded or remote writes performed.`);
      return;
    }
    assert(values["expect-current-version"], "Specify the reviewed current channel version (or none for an empty beta channel).");
    const { createR2Store } = require("./r2-release-store");
    const receipt = await publishPrepared({ directory, store: createR2Store(), expectedPreviousVersion: values["expect-current-version"] });
    console.log(JSON.stringify(receipt, null, 2));
  })().catch((error) => { console.error(error.message); process.exitCode = 1; });
}

module.exports = { publishPrepared, fetchBytes };

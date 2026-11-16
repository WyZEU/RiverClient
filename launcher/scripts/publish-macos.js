"use strict";

const fs = require("node:fs");
const path = require("node:path");
const { parseArgs } = require("node:util");
const { assert, regularFile, json } = require("./release-artifact");
const { validateMacPublication } = require("./prepare-macos-publication");
const { compareVersions, hash } = require("./prepare-signed-update");
const { fetchBytes } = require("./publish-signed-update");
const { verifyManifest, PUBLIC_KEY_B64, canonicalize } = require("../src/update-signature");
const config = require("../update-publishing.json");

async function publishMac({ directory, store, expectedPreviousVersion, publicKey = PUBLIC_KEY_B64, fetchRemote = fetch }) {
  const { plan, manifest: unsigned } = await validateMacPublication(directory);
  const manifest = json(regularFile(directory, "manifest.json"));
  const { signature, ...payload } = manifest;
  assert(verifyManifest(manifest, publicKey) && canonicalize(payload) === canonicalize(unsigned), "Invalid or changed signed Mac manifest.");
  const bytes = Buffer.from(JSON.stringify(manifest));
  const receipt = { version: plan.version, sourceCommit: plan.sourceCommit, publicationCommitted: false, assetsVerified: false };
  const manifestUrl = `${config.publicBaseUrl}/macos.json`;
  try {
    const capabilities = JSON.parse(await fetchBytes(`${config.publicBaseUrl}/release-capabilities.json`, fetchRemote));
    assert(capabilities.macosPublicationProtocol === 1, "Deploy the reviewed Mac download routes before publishing.");
    const current = await store.getJson("macos.json");
    if (current) {
      assert(current.etag && current.value.platform === "darwin" && verifyManifest(current.value, publicKey), "Invalid current signed Mac release.");
      const served = JSON.parse(await fetchBytes(manifestUrl, fetchRemote));
      assert(canonicalize(served) === canonicalize(current.value), "Worker does not serve the unchanged Mac manifest.");
    } else {
      const empty = await fetchRemote(manifestUrl, { redirect: "error", signal: AbortSignal.timeout(30000), cache: "no-store" });
      await empty.body?.cancel();
      assert(empty.status === 404, "Expected an unpublished Mac channel (HTTP 404).");
    }
    const alreadyPublished = !!current && canonicalize(current.value) === canonicalize(manifest);
    receipt.publicationCommitted = alreadyPublished;
    if (!alreadyPublished) {
      assert((current?.value.version || "none") === expectedPreviousVersion, "Mac release changed; review its current version first.");
      if (current) assert(compareVersions(plan.version, current.value.version) > 0, "Refusing a Mac downgrade or replacement of an existing version.");
    }
    for (const object of plan.objects) {
      if (!await store.head(object.key)) await store.putFile(object.key, regularFile(directory, object.file), object);
      await store.verify(object.key, object);
    }
    const archiveKey = `${plan.releaseKey}/release-manifest.json`;
    const archived = await store.getJson(archiveKey);
    if (archived) assert(canonicalize(archived.value) === canonicalize(manifest), "Conflicting immutable Mac manifest.");
    else await store.putJson(archiveKey, bytes);
    await store.verify(archiveKey, { size: bytes.length, sha256: hash(bytes) });
    assert(hash(await fetchBytes(manifest.checksums.url, fetchRemote)) === manifest.checksums.sha256, "Public Mac checksum readback mismatch.");
    for (const url of Object.values(manifest.macUrls)) {
      const response = await fetchRemote(url, { method: "HEAD", redirect: "error", signal: AbortSignal.timeout(30000), cache: "no-store" });
      await response.body?.cancel();
      assert(response.ok, "A staged Mac download is not publicly reachable.");
    }
    receipt.assetsVerified = true;
    if (!alreadyPublished) {
      try { await store.putJson("macos.json", bytes, current?.etag || null); }
      catch (error) {
        const recovered = await store.getJson("macos.json").catch(() => null);
        if (!recovered || canonicalize(recovered.value) !== canonicalize(manifest)) {
          receipt.commitUncertain = !recovered;
          throw error;
        }
      }
    }
    receipt.publicationCommitted = true;
    const committed = await store.getJson("macos.json");
    assert(committed && canonicalize(committed.value) === canonicalize(manifest), "Mac channel changed after promotion.");
    const visible = JSON.parse(await fetchBytes(manifestUrl, fetchRemote));
    assert(verifyManifest(visible, publicKey) && canonicalize(visible) === canonicalize(manifest), "Published Mac manifest readback mismatch.");
    for (const arch of ["arm64", "x64"]) {
      const response = await fetchRemote(`${config.publicBaseUrl}/downloads/River-Client-macOS-${arch}.dmg`, {
        method: "HEAD", redirect: "manual", signal: AbortSignal.timeout(30000), cache: "no-store"
      });
      await response.body?.cancel();
      assert(response.status === 302 && response.headers.get("location") === manifest.macUrls[arch], "Mac rolling download does not select the published release.");
    }
    return Object.assign(receipt, { manifestUrl, macUrls: manifest.macUrls });
  } catch (error) {
    receipt.error = error.message;
    if (receipt.publicationCommitted || receipt.commitUncertain) error.message = `Mac promotion ${receipt.publicationCommitted ? "committed" : "result is uncertain"}; check live state and retry the SAME build and inputs. ${error.message}`;
    throw error;
  } finally {
    fs.writeFileSync(path.join(directory, "publication-result.json"), JSON.stringify(receipt, null, 2) + "\n");
  }
}

if (require.main === module) {
  (async () => {
    const { values } = parseArgs({ options: { prepared: { type: "string" }, publish: { type: "boolean", default: false },
      "expect-current-version": { type: "string" } } });
    assert(values.prepared, "Required: --prepared DIR; live publication also needs --publish --expect-current-version VERSION.");
    const directory = path.resolve(values.prepared);
    if (!values.publish) {
      await validateMacPublication(directory);
      console.log("Mac publication verified locally. No credentials loaded or remote writes performed.");
      return;
    }
    assert(values["expect-current-version"], "Specify the reviewed Mac version, or none for the first publication.");
    const { createR2Store } = require("./r2-release-store");
    console.log(JSON.stringify(await publishMac({ directory, store: createR2Store(), expectedPreviousVersion: values["expect-current-version"] }), null, 2));
  })().catch((error) => { console.error(error.message); process.exitCode = 1; });
}

module.exports = { publishMac };

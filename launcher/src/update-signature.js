// Signed update manifests. The manifest says where every download is and what its SHA-256 is, so
// whoever can change the manifest can change what the launcher installs. The hash alone cannot
// stop that when the manifest and the files sit in the same place. So the manifest is signed with
// an Ed25519 key that lives only on the publisher's PC, and the launcher refuses a manifest that
// does not verify against the public key below.
const crypto = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");

const PUBLIC_KEY_B64 = fs.readFileSync(path.join(__dirname, "config", "update-public-key.txt"), "utf8").trim();

/** JSON with every object's keys sorted, so the signed bytes never depend on key order. */
function canonicalize(value) {
  if (Array.isArray(value)) return `[${value.map(canonicalize).join(",")}]`;
  if (value && typeof value === "object") {
    return `{${Object.keys(value).sort().filter((k) => value[k] !== undefined).map((k) => `${JSON.stringify(k)}:${canonicalize(value[k])}`).join(",")}}`;
  }
  return JSON.stringify(value);
}

function signedBytes(manifest) {
  const { signature, ...rest } = manifest || {};
  return Buffer.from(canonicalize(rest), "utf8");
}

/** Adds `signature` (base64) to a copy of the manifest. [privateKeyPem] is the publisher's key. */
function signManifest(manifest, privateKeyPem) {
  const signature = crypto.sign(null, signedBytes(manifest), crypto.createPrivateKey(privateKeyPem)).toString("base64");
  return { ...manifest, signature };
}

/** True only when the manifest carries a signature that verifies. [publicKeyB64] is for tests. */
function verifyManifest(manifest, publicKeyB64 = PUBLIC_KEY_B64) {
  try {
    if (!manifest || typeof manifest.signature !== "string" || !manifest.signature) return false;
    const key = crypto.createPublicKey({ key: Buffer.from(publicKeyB64, "base64"), format: "der", type: "spki" });
    return crypto.verify(null, signedBytes(manifest), key, Buffer.from(manifest.signature, "base64"));
  } catch {
    return false;
  }
}

module.exports = { canonicalize, signManifest, verifyManifest, PUBLIC_KEY_B64 };

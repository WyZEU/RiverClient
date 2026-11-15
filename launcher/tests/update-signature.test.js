const test = require("node:test");
const assert = require("node:assert/strict");
const crypto = require("node:crypto");
const { signManifest, verifyManifest, canonicalize } = require("../src/update-signature.js");

const pair = crypto.generateKeyPairSync("ed25519");
const pem = pair.privateKey.export({ type: "pkcs8", format: "pem" });
const pub = pair.publicKey.export({ type: "spki", format: "der" }).toString("base64");
const manifest = { version: "0.3.0", mods: [{ name: "clientcore.jar", sha256: "aa", minecraft: "1.21.11" }], notes: "x" };

test("a signed manifest verifies, whatever the key order", () => {
  const signed = signManifest(manifest, pem);
  assert.equal(verifyManifest(signed, pub), true);
  const shuffled = { signature: signed.signature, notes: signed.notes, mods: [{ minecraft: "1.21.11", sha256: "aa", name: "clientcore.jar" }], version: signed.version };
  assert.equal(verifyManifest(shuffled, pub), true);
});

test("changing anything breaks it: a hash, a url, a version", () => {
  const signed = signManifest(manifest, pem);
  assert.equal(verifyManifest({ ...signed, version: "0.3.1" }, pub), false);
  assert.equal(verifyManifest({ ...signed, mods: [{ ...signed.mods[0], sha256: "bb" }] }, pub), false);
  assert.equal(verifyManifest({ ...signed, extra: "https://evil" }, pub), false);
});

test("unsigned, garbage and another key's signature are all refused", () => {
  assert.equal(verifyManifest(manifest, pub), false);
  assert.equal(verifyManifest({ ...manifest, signature: "not base64!!" }, pub), false);
  assert.equal(verifyManifest(null, pub), false);
  const other = crypto.generateKeyPairSync("ed25519");
  const forged = signManifest(manifest, other.privateKey.export({ type: "pkcs8", format: "pem" }));
  assert.equal(verifyManifest(forged, pub), false);
});

test("canonical form sorts keys and skips undefined", () => {
  assert.equal(canonicalize({ b: 1, a: [2, { d: 1, c: undefined }] }), '{"a":[2,{"d":1}],"b":1}');
});

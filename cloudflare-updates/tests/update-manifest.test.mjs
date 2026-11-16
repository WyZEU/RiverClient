import test from "node:test";
import assert from "node:assert/strict";
import { generateKeyPairSync } from "node:crypto";
import worker from "../src/worker.js";
import { resetUpdatePolicyCache } from "../src/update-policy.js";
import signatures from "../../launcher/src/update-signature.js";

const { signManifest, verifyManifest } = signatures;
const pair = generateKeyPairSync("ed25519");
const privateKey = pair.privateKey.export({ type: "pkcs8", format: "pem" });
const publicKey = pair.publicKey.export({ type: "spki", format: "der" }).toString("base64");
const releases = ["0.3.0", "0.2.9", "0.2.8", "0.2.7"];

function environment(manifest, policy) {
  resetUpdatePolicyCache();
  return {
    UPDATES: {
      async get(key) {
        const value = key === "latest.json" ? manifest : key === "config/update-policy.json" ? policy : null;
        return value ? { json: async () => structuredClone(value) } : null;
      },
      async list() {
        return { delimitedPrefixes: releases.map((version) => `releases/${version}/`), truncated: false };
      }
    }
  };
}

async function get(route, env) {
  const response = await worker.fetch(new Request(`https://updates.riverclient.xyz${route}`), env, { waitUntil() {} });
  assert.equal(response.status, 200);
  return response.json();
}

for (const route of ["/", "/latest.json"]) {
  for (const policy of [{ forcedMinimum: "0.2.9", maxBehind: 0 }, { forcedMinimum: "", maxBehind: 3 }]) {
    test(`${route} preserves the signed manifest with policy ${JSON.stringify(policy)}`, async () => {
      const signed = signManifest({ version: "0.3.0", minimumVersion: "0.1.7", files: { package: { sha256: "ab" } } }, privateKey);
      const served = await get(route, environment(signed, policy));
      assert.deepEqual(served, signed);
      assert.equal(verifyManifest(served, publicKey), true);
      // Policy fields remain covered by the signature, just like download hashes.
      assert.equal(verifyManifest({ ...served, minimumVersion: "0.2.9" }, publicKey), false);
    });
  }
}

test("a signed manifest without minimumVersion is served without adding signed fields", async () => {
  const signed = signManifest({ version: "0.3.0", minVersion: "0.1.7" }, privateKey);
  const served = await get("/latest.json", environment(signed, { forcedMinimum: "0.2.9", maxBehind: 3 }));
  assert.deepEqual(served, signed);
  assert.equal(verifyManifest(served, publicKey), true);
});

test("the built-in fallback retains its publisher signature", async () => {
  const served = await get("/latest.json", environment(null, { forcedMinimum: "0.3.0", maxBehind: 3 }));
  assert.equal(verifyManifest(served), true);
});

test("standalone mod policy still raises its minimum without changing the launcher manifest", async () => {
  const signed = signManifest({ version: "0.3.0", minimumVersion: "0.1.7" }, privateKey);
  const env = environment(signed, { forcedMinimum: "0.2.9", maxBehind: 3 });
  const status = await get("/version-status.json", env);
  assert.equal(status.latest, "0.3.0");
  assert.equal(status.minimumVersion, "0.2.9");
  assert.equal(status.maxBehind, 3);
  assert.deepEqual(status.releases, releases);
  const served = await get("/latest.json", env);
  assert.deepEqual(served, signed);
  assert.equal(verifyManifest(served, publicKey), true);
});

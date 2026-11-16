import test from "node:test";
import assert from "node:assert/strict";
import { generateKeyPairSync } from "node:crypto";
import worker, { publishedInstallerTarget, publishedLinuxTarget } from "../src/worker.js";
import { resetUpdatePolicyCache, releaseVersions } from "../src/update-policy.js";
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

test("publication capability and rolling installer use the committed immutable release", async () => {
  const target = "https://updates.riverclient.xyz/downloads/releases/0.3.1-abcdef123456/River-Client-Setup.exe";
  const env = environment({ version: "0.3.1", installerUrl: target }, {});
  assert.deepEqual(await get("/release-capabilities.json", env), { publicationProtocol: 1, macosPublicationProtocol: 1, linuxPublicationProtocol: 1 });
  const response = await worker.fetch(new Request("https://updates.riverclient.xyz/downloads/River-Client-Setup.exe"), env, { waitUntil() {} });
  assert.equal(response.status, 302);
  assert.equal(response.headers.get("location"), target);
  assert.equal(response.headers.get("cache-control"), "no-store");
});

test("legacy rolling installer remains readable and redirects cannot escape public downloads", async () => {
  const base = "https://updates.riverclient.xyz";
  for (const target of [`${base}/downloads/River-Client-Setup.exe`, "https://elsewhere.example/releases/River-Client-Setup.exe",
    `${base}/downloads/config/River-Client-Setup.exe`, `${base}/downloads/releases/0.3.1/River-Client-Setup.exe?secret=1`,
    `${base}/downloads/releases/0.3.1/other.exe`]) assert.equal(publishedInstallerTarget({ installerUrl: target }), null);
  const env = environment({ version: "0.3.0", installerUrl: `${base}/downloads/River-Client-Setup.exe` }, {});
  const previous = env.UPDATES.get;
  env.UPDATES.get = async (key) => key === "River-Client-Setup.exe" ? { body: "legacy installer", httpEtag: '"legacy"' } : previous(key);
  const response = await worker.fetch(new Request(`${base}/downloads/River-Client-Setup.exe`), env, { waitUntil() {} });
  assert.equal(response.status, 200);
  assert.equal(await response.text(), "legacy installer");
});

test("partial uploads and beta namespaces do not advance standalone-mod release policy", async () => {
  resetUpdatePolicyCache();
  const prefixes = ["releases/0.3.0/", "releases/0.3.1-abcdef123456/", "releases/0.3.2-beta-abcdef123456/"];
  const env = { UPDATES: { list: async () => ({ delimitedPrefixes: prefixes, truncated: false }) } };
  assert.deepEqual(await releaseVersions(env), ["0.3.0"]);
  prefixes.push("releases/0.3.1/"); // Final marker, written only after latest.json commits.
  resetUpdatePolicyCache();
  assert.deepEqual(await releaseVersions(env), ["0.3.1", "0.3.0"]);
});

test("Linux root download follows the stable manifest and preserves the legacy fallback", async () => {
  const base = "https://updates.riverclient.xyz";
  const target = `${base}/downloads/releases/0.3.1-abcdef123456/River-Client-Linux-0.3.1.tar.gz`;
  const env = environment({ version: "0.3.1", linuxUrl: target }, {});
  const request = new Request(`${base}/downloads/River-Client-Linux.tar.gz`);
  const response = await worker.fetch(request, env, { waitUntil() {} });
  assert.equal(response.status, 302);
  assert.equal(response.headers.get("location"), target);
  assert.equal(response.headers.get("cache-control"), "no-store");
  const legacy = environment({ version: "0.3.0", linuxUrl: `${base}/downloads/River-Client-Linux.tar.gz` }, {});
  const previous = legacy.UPDATES.get;
  legacy.UPDATES.get = async (key) => key === "River-Client-Linux.tar.gz" ? { body: "legacy Linux", httpEtag: '"legacy"' } : previous(key);
  const fallback = await worker.fetch(request, legacy, { waitUntil() {} });
  assert.equal(fallback.status, 200);
  assert.equal(await fallback.text(), "legacy Linux");
  for (const linuxUrl of [`${base}/downloads/River-Client-Linux.tar.gz`, target.replace(base, "https://elsewhere.example"),
    `${target}?secret=1`, target.replace("Linux-0.3.1", "Linux-0.3.0"), `${base}/downloads/config/River-Client-Linux-0.3.1.tar.gz`]) {
    assert.equal(publishedLinuxTarget({ version: "0.3.1", linuxUrl }), null);
  }
});

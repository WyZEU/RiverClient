import test from "node:test";
import assert from "node:assert/strict";
import { generateKeyPairSync } from "node:crypto";
import worker from "../src/worker.js";
import { publishedMacTarget } from "../src/macos-downloads.js";
import signatures from "../../launcher/src/update-signature.js";

const pair = generateKeyPairSync("ed25519");
const key = pair.privateKey.export({ type: "pkcs8", format: "pem" });
const publicKey = pair.publicKey.export({ type: "spki", format: "der" }).toString("base64");
const base = "https://updates.riverclient.xyz";
const manifest = signatures.signManifest({ version: "0.3.1", platform: "darwin", channel: "stable", sourceCommit: "c".repeat(40),
  macUrls: Object.fromEntries(["arm64", "x64"].map((arch) => [arch, `${base}/downloads/releases/0.3.1-cccccccccccc/macos/River-Client-0.3.1-macOS-${arch}.dmg`])) }, key);
const env = (value) => ({ UPDATES: { get: async (key) => { assert.equal(key, "macos.json"); return value ? { json: async () => value } : null; } } });
const get = (route, value = manifest, method = "GET") => worker.fetch(new Request(base + route, { method }), env(value), { waitUntil() {} });

test("unpublished Mac routes stay unavailable without a Windows fallback", async () => {
  for (const route of ["/macos.json", "/downloads/River-Client-macOS-arm64.dmg", "/downloads/River-Client-macOS-x64.dmg"]) {
    assert.equal((await get(route, null)).status, 404);
  }
});
test("Mac JSON stays signed and both rolling downloads select the same immutable release", async () => {
  const response = await get("/macos.json");
  assert.equal(response.headers.get("cache-control"), "no-store");
  assert.equal(response.headers.get("access-control-allow-origin"), "https://riverclient.xyz");
  const body = await response.json();
  assert.deepEqual(body, manifest);
  assert.equal(signatures.verifyManifest(body, publicKey), true);
  for (const arch of ["arm64", "x64"]) for (const method of ["GET", "HEAD"]) {
    const redirect = await get(`/downloads/River-Client-macOS-${arch}.dmg`, manifest, method);
    assert.equal(redirect.status, 302);
    assert.equal(redirect.headers.get("location"), manifest.macUrls[arch]);
  }
  assert.equal(await (await get("/macos.json", manifest, "HEAD")).text(), "");
});
test("partial releases, external URLs, traversal and wrong architecture cannot become Mac links", async () => {
  for (const url of ["https://other.example/mac.dmg", `${base}/downloads/config/key.dmg`, manifest.macUrls.x64,
    manifest.macUrls.arm64 + "?secret=1", manifest.macUrls.arm64.replace("macos/", "macos/../")]) {
    const invalid = { ...manifest, macUrls: { ...manifest.macUrls, arm64: url } };
    assert.equal(publishedMacTarget(invalid, "arm64"), null);
    assert.equal((await get("/macos.json", invalid)).status, 503);
    assert.equal((await get("/downloads/River-Client-macOS-x64.dmg", invalid)).status, 503);
  }
  assert.equal(publishedMacTarget(manifest, "linux"), null);
  assert.equal((await get("/macos.json", { ...manifest, macUrls: { arm64: manifest.macUrls.arm64 } })).status, 503);
  assert.equal((await get("/macos.json", manifest, "POST")).status, 405);
});

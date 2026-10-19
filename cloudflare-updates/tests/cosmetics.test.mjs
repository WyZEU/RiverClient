import test from "node:test";
import assert from "node:assert/strict";
import { Cosmetics } from "../src/cosmetics.js";
import { scrubCrashReport } from "../src/crash.js";
import worker, { isPublicDownloadKey } from "../src/worker.js";
import { Metrics } from "../src/metrics.js";

function cosmeticsWithMemoryStorage() {
  const values = new Map();
  const storage = {
    get: async (key) => values.get(key),
    put: async (key, value) => values.set(key, value),
    transaction: async (fn) => fn(storage)
  };
  return { cosmetics: new Cosmetics({ storage }), values };
}

test("redemption limits guesses without persisting a visitor address", async () => {
  const { cosmetics, values } = cosmeticsWithMemoryStorage();
  const redeem = (visitor) => cosmetics.fetch(new Request("https://cosmetics/redeem", {
    method: "POST",
    headers: { "X-River-Visitor": visitor },
    body: JSON.stringify({ code: "WRONGCODE", uuid: "0123456789abcdef0123456789abcdef" })
  }));

  for (let n = 0; n < 20; n++) assert.equal((await redeem("203.0.113.7")).status, 404);
  const limited = await redeem("203.0.113.7");
  assert.equal(limited.status, 429);
  assert.ok(Number(limited.headers.get("Retry-After")) > 0);
  assert.equal((await redeem("203.0.113.8")).status, 404);
  assert.ok([...values.keys()].every((key) => !key.includes("203.0.113.7")));
});

test("crash reports remove common credentials and local account paths", () => {
  const scrubbed = scrubCrashReport("Bearer secretToken1234 C:\\Users\\alice\\game refresh_token=secretToken1234");
  assert.ok(!scrubbed.includes("secretToken1234"));
  assert.ok(!scrubbed.includes("alice"));
});

test("public downloads cannot read crash reports from the shared bucket", async () => {
  const lookedUp = [];
  const env = { UPDATES: { get: async (key) => {
    lookedUp.push(key);
    return { body: "data", httpEtag: '"etag"', size: 4, httpMetadata: {} };
  } } };
  for (const path of [
    "crash-reports/20260919123456-12345678.json",
    "latest.json",
    "releases/0.1.9.3/../crash-reports/20260919123456-12345678.json",
    "%63rash-reports%2F20260919123456-12345678.json"
  ]) {
    assert.equal(isPublicDownloadKey(decodeURIComponent(path)), false);
    const res = await worker.fetch(new Request(`https://updates.riverclient.xyz/downloads/${path}`), env, {});
    assert.equal(res.status, 404);
  }
  assert.deepEqual(lookedUp, []);
  assert.equal(isPublicDownloadKey("River-Client-Setup.exe"), true);
  assert.equal(isPublicDownloadKey("River-Client-Linux.tar.gz"), true);
  assert.equal(isPublicDownloadKey("River-Client-Linux.tar.gz.bak"), false);
  assert.equal(isPublicDownloadKey("releases/0.1.9.3/app/resources/app.asar.blockmap.json"), true);
  const publicRes = await worker.fetch(new Request("https://updates.riverclient.xyz/downloads/releases/0.1.9.3/file-manifest.json"), env, {});
  assert.equal(publicRes.status, 200);
  assert.deepEqual(lookedUp, ["releases/0.1.9.3/file-manifest.json"]);
});

test("crash uploads are limited before the bucket write", async () => {
  const metrics = new Metrics({}, {});
  const written = [];
  const env = {
    METRICS_DO: { idFromName: (name) => name, get: () => ({ fetch: (req, init) => metrics.fetch(new Request(req, init)) }) },
    UPDATES: { put: async (key) => written.push(key) }
  };
  const upload = (ip) => worker.fetch(new Request("https://updates.riverclient.xyz/crash-report", {
    method: "POST",
    headers: { "User-Agent": "RiverClientLauncher/0.1", "CF-Connecting-IP": ip },
    body: JSON.stringify({ log: "test crash" })
  }), env, {});
  for (let i = 0; i < 10; i++) assert.equal((await upload("203.0.113.11")).status, 200);
  const limited = await upload("203.0.113.11");
  assert.equal(limited.status, 429);
  assert.ok(Number(limited.headers.get("Retry-After")) > 0);
  assert.equal(written.length, 10);
  assert.equal((await upload("203.0.113.12")).status, 200);
  assert.ok([...metrics.crashUploadHits.keys()].every((key) => !key.includes("203.0.113.11")));
});

test("referral stats use the same bad-key throttle as other admin routes", async () => {
  let failures = 0;
  let referralReads = 0;
  const env = {
    REFERRAL_STATS_KEY: "local-test-key",
    METRICS_DO: { idFromName: (name) => name, get: () => ({ fetch: async () => Response.json({ lockedOut: ++failures >= 8 }) }) },
    REFERRALS_DO: { idFromName: (name) => name, get: () => ({ fetch: async () => {
      referralReads++;
      return Response.json({ ok: true });
    } }) }
  };
  for (let i = 0; i < 7; i++) {
    const res = await worker.fetch(new Request("https://updates.riverclient.xyz/referrals.json", {
      headers: { Authorization: "Bearer wrong-key" }
    }), env, {});
    assert.equal(res.status, 401);
  }
  const limited = await worker.fetch(new Request("https://updates.riverclient.xyz/referrals.json", {
    headers: { Authorization: "Bearer wrong-key" }
  }), env, {});
  assert.equal(limited.status, 429);
  assert.equal(referralReads, 0);
});

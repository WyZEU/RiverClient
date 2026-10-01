import test from "node:test";
import assert from "node:assert/strict";
import { createHash } from "node:crypto";

// Workers' FixedLengthStream is a TransformStream that knows its length up front.
globalThis.FixedLengthStream ??= class extends TransformStream { constructor() { super(); } };

const { default: worker } = await import("../src/worker.js");

class MemoryBucket {
  objects = new Map();
  put(key, bytes) { this.objects.set(key, bytes); }
  async head(key) {
    const bytes = this.objects.get(key);
    return bytes ? { key, size: bytes.length } : null;
  }
  async get(key, options) {
    const bytes = this.objects.get(key);
    return bytes && !options?.range ? this.#object(key, bytes) : null;
  }
  #object(key, bytes) {
    return {
      key,
      size: bytes.length,
      httpEtag: `"${key}"`,
      httpMetadata: {},
      body: new Blob([bytes]).stream()
    };
  }
}

function ctx() {
  const pending = [];
  return { pending, waitUntil: (promise) => pending.push(promise), passThroughOnException() {} };
}

const KEY = "releases/0.1.9.3.2/River-Client-App-0.1.9.3.2.zip";
const url = `https://updates.riverclient.xyz/downloads/${KEY}`;

test("a zip that only exists as parts is served whole, in order", async () => {
  const bucket = new MemoryBucket();
  const whole = new Uint8Array(3 * 1024 * 1024 + 17).map((_, i) => (i * 31) % 251);
  bucket.put(`${KEY}.001`, whole.slice(0, 1024 * 1024));
  bucket.put(`${KEY}.002`, whole.slice(1024 * 1024, 2 * 1024 * 1024));
  bucket.put(`${KEY}.003`, whole.slice(2 * 1024 * 1024));

  const context = ctx();
  const response = await worker.fetch(new Request(url), { UPDATES: bucket }, context);
  assert.equal(response.status, 200);
  assert.equal(response.headers.get("Content-Length"), String(whole.length));
  const body = new Uint8Array(await response.arrayBuffer());
  await Promise.all(context.pending);
  assert.equal(
    createHash("sha256").update(body).digest("hex"),
    createHash("sha256").update(whole).digest("hex")
  );
});

test("a zip that exists whole is served as itself", async () => {
  const bucket = new MemoryBucket();
  bucket.put(KEY, new Uint8Array([1, 2, 3]));
  bucket.put(`${KEY}.001`, new Uint8Array([9, 9, 9, 9]));
  const response = await worker.fetch(new Request(url), { UPDATES: bucket }, ctx());
  assert.deepEqual([...new Uint8Array(await response.arrayBuffer())], [1, 2, 3]);
});

test("missing files are still a 404, and only zips are ever joined", async () => {
  const bucket = new MemoryBucket();
  bucket.put("releases/0.1.9.3.2/app/resources/app.asar.001", new Uint8Array([1]));
  for (const key of [KEY, "releases/0.1.9.3.2/app/resources/app.asar"]) {
    const response = await worker.fetch(new Request(`https://updates.riverclient.xyz/downloads/${key}`), { UPDATES: bucket }, ctx());
    assert.equal(response.status, 404);
  }
});

import test from "node:test";
import assert from "node:assert/strict";

globalThis.FixedLengthStream ??= class extends TransformStream { constructor() { super(); } };
const { default: worker } = await import("../src/worker.js");

const PNG = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3, 4]);
const WYZ = "11111111111141118111111111111111";

class MemoryBucket {
  objects = new Map();
  async put(key, bytes, options) {
    this.objects.set(key, { bytes: new Uint8Array(bytes), ...options });
  }
  async head(key) {
    const o = this.objects.get(key);
    return o ? { key, customMetadata: o.customMetadata, httpMetadata: o.httpMetadata } : null;
  }
  async get(key) {
    const o = this.objects.get(key);
    if (!o) return null;
    return { key, body: new Blob([o.bytes]).stream(), httpEtag: `"${key}"`, httpMetadata: o.httpMetadata, customMetadata: o.customMetadata };
  }
}

/** A Social DO that knows one token, and a Metrics DO that allows [allow] uploads. */
function env(bucket, { allow = 99 } = {}) {
  let uploads = 0;
  return {
    UPDATES: bucket,
    SOCIAL_DO: {
      idFromName: () => "global",
      get: () => ({
        fetch: async (req) => Response.json(req.headers.get("Authorization") === "Bearer good"
          ? { ok: true, uuid: WYZ, name: "WyZ_EU" }
          : { ok: false })
      })
    },
    METRICS_DO: {
      idFromName: () => "global",
      get: () => ({ fetch: async () => Response.json({ retryAfter: ++uploads > allow ? 60 : 0 }) })
    }
  };
}

const ctx = { waitUntil() {}, passThroughOnException() {} };

function upload(body, token = "good") {
  return new Request("https://updates.riverclient.xyz/screenshots", {
    method: "POST",
    headers: { Authorization: `Bearer ${token}`, "Content-Type": "image/png" },
    body
  });
}

test("a signed-in upload is stored and its page and image are served", async () => {
  const bucket = new MemoryBucket();
  const response = await worker.fetch(upload(PNG), env(bucket), ctx);
  const result = await response.json();
  assert.equal(result.ok, true);
  assert.match(result.url, /^https:\/\/updates\.riverclient\.xyz\/s\/[A-Za-z0-9]{10}$/);

  const stored = bucket.objects.get(`screenshots/${result.id}.png`);
  assert.equal(stored.customMetadata.uuid, WYZ);
  assert.equal(stored.customMetadata.name, "WyZ_EU");

  const page = await worker.fetch(new Request(result.url), env(bucket), ctx);
  const html = await page.text();
  assert.equal(page.headers.get("Content-Type"), "text/html; charset=utf-8");
  assert.ok(html.includes(`og:image" content="${result.url}.png"`));
  assert.ok(html.includes("Screenshot by WyZ_EU"));

  const image = await worker.fetch(new Request(`${result.url}.png`), env(bucket), ctx);
  assert.equal(image.headers.get("Content-Type"), "image/png");
  assert.deepEqual(new Uint8Array(await image.arrayBuffer()), PNG);
});

test("no session, not an image, or too many uploads is refused and nothing is stored", async () => {
  const bucket = new MemoryBucket();
  assert.equal((await worker.fetch(upload(PNG, "bad"), env(bucket), ctx)).status, 401);
  assert.equal((await worker.fetch(upload(new TextEncoder().encode("<html>not an image")), env(bucket), ctx)).status, 415);
  const limited = env(bucket, { allow: 0 });
  assert.equal((await worker.fetch(upload(PNG), limited, ctx)).status, 429);
  assert.equal(bucket.objects.size, 0);
});

test("unknown or malformed ids are a 404, and names are escaped on the page", async () => {
  const bucket = new MemoryBucket();
  for (const path of ["/s/nope", "/s/AAAAAAAAAA", "/s/..%2Fcrash-reports", "/s/AAAAAAAAAA.exe"]) {
    assert.equal((await worker.fetch(new Request(`https://updates.riverclient.xyz${path}`), env(bucket), ctx)).status, 404);
  }
  await bucket.put("screenshots/BBBBBBBBBB.png", PNG, { customMetadata: { name: "<script>x</script>", at: "0" } });
  const html = await (await worker.fetch(new Request("https://updates.riverclient.xyz/s/BBBBBBBBBB"), env(bucket), ctx)).text();
  assert.ok(!html.includes("<script>x"));
  assert.ok(html.includes("&lt;script&gt;x"));
});

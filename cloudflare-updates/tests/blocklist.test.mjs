import test from "node:test";
import assert from "node:assert/strict";
import { BLOCKED_MESSAGE, blockedUuids, isBlocked, resetBlocklistCache } from "../src/blocklist.js";
import { SocialStore } from "../src/social.js";

const HACKED = "28003cee-19ce-47b1-b2c8-de68eba9fff7";
const REAL = "0c8205fb-037b-448e-a431-9c2c06301b0a";

class MemoryStorage {
  values = new Map();
  async get(key) { return this.values.get(key); }
  async put(key, value) { this.values.set(key, structuredClone(value)); }
  async delete(key) { this.values.delete(key); }
}

/** A bucket holding one blocklist file, counting how often it was read. */
function bucket(payload) {
  const bucket = {
    reads: 0,
    async get(key) {
      bucket.reads++;
      if (key !== "config/blocklist.json" || payload === null) return null;
      return { json: async () => payload };
    }
  };
  return bucket;
}

test("the blocklist matches UUIDs however they are written, and is read once a minute", async () => {
  resetBlocklistCache();
  const UPDATES = bucket({ blocked: [{ name: "Galaxysdc", uuid: HACKED.toUpperCase() }, { name: "no uuid" }] });
  const env = { UPDATES };
  assert.equal(await isBlocked(env, HACKED.replace(/-/g, "")), true);
  assert.equal(await isBlocked(env, HACKED), true);
  assert.equal(await isBlocked(env, REAL), false);
  assert.equal(await isBlocked(env, ""), false);
  assert.equal(UPDATES.reads, 1);
});

test("no bucket, no file, or a bucket that throws blocks nobody", async () => {
  resetBlocklistCache();
  assert.equal((await blockedUuids({})).size, 0);
  resetBlocklistCache();
  assert.equal(await isBlocked({ UPDATES: bucket(null) }, HACKED), false);
  resetBlocklistCache();
  const broken = { UPDATES: { get: async () => { throw new Error("R2 is down"); } } };
  assert.equal(await isBlocked(broken, HACKED), false);
});

test("a blocked account gets no session at sign-in, and an unblocked one still does", async () => {
  const storage = new MemoryStorage();
  const blocked = new Set([HACKED]);
  let signingInAs = HACKED;
  const store = new SocialStore(storage, {
    verifyImpl: async () => ({ ok: true, uuid: signingInAs }),
    isBlocked: async (uuid) => blocked.has(uuid)
  });

  const { nonce } = await store.beginAuth();
  const refused = await store.completeAuth("Galaxysdc", { nonce });
  assert.equal(refused.ok, false);
  assert.equal(refused.blocked, true);
  assert.equal(refused.message, BLOCKED_MESSAGE);
  assert.equal([...storage.values.keys()].some((key) => key.includes(HACKED)), false, "nothing was stored for them");

  signingInAs = REAL;
  const next = await store.beginAuth();
  const accepted = await store.completeAuth("Galaxysdc_Larp", { nonce: next.nonce });
  assert.equal(accepted.ok, true);
  assert.equal((await store.sessionFor(accepted.token)).uuid, REAL);
});

test("blocking someone ends the session they already hold", async () => {
  const storage = new MemoryStorage();
  const blocked = new Set();
  const store = new SocialStore(storage, {
    verifyImpl: async () => ({ ok: true, uuid: HACKED }),
    isBlocked: async (uuid) => blocked.has(uuid)
  });
  const { nonce } = await store.beginAuth();
  const signedIn = await store.completeAuth("Galaxysdc", { nonce });
  assert.equal(signedIn.ok, true);
  assert.equal((await store.sessionFor(signedIn.token)).uuid, HACKED);

  blocked.add(HACKED);
  assert.equal(await store.sessionFor(signedIn.token), null);
  blocked.delete(HACKED);
  assert.equal(await store.sessionFor(signedIn.token), null, "the session is gone, not merely hidden");
});

test("messaging a friend who was blacklisted says so instead of failing silently", async () => {
  const storage = new MemoryStorage();
  const blocked = new Set([HACKED]);
  const store = new SocialStore(storage, { isBlocked: async (uuid) => blocked.has(uuid) });
  await storage.put(`u:${HACKED}`, { uuid: HACKED, name: "Galaxysdc" });
  await storage.put(`f:${REAL}`, [HACKED]);
  const session = { uuid: REAL, name: "Galaxysdc_Larp" };

  const refused = await store.sendMessage(session, HACKED, "hey, you there?");
  assert.equal(refused.ok, false);
  assert.match(refused.message, /Galaxysdc has been blacklisted from River Client/);
  assert.match(refused.message, /Ask the owner why/);
  assert.equal([...storage.values.keys()].some((key) => key.startsWith("dm:") || key.startsWith("in:")), false, "nothing was delivered");

  blocked.clear();
  assert.equal((await store.sendMessage(session, HACKED, "hey, you there?")).ok, true);
});

test("someone who is not a friend still just hears that only friends can be messaged", async () => {
  const storage = new MemoryStorage();
  const store = new SocialStore(storage, { isBlocked: async () => true });
  const result = await store.sendMessage({ uuid: REAL, name: "x" }, HACKED, "hi");
  assert.equal(result.message, "You can only message friends.");
});

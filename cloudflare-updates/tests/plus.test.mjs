import test from "node:test";
import assert from "node:assert/strict";
import { Cosmetics, DAY_MS, PLUS_COSMETICS, plusActive } from "../src/cosmetics.js";

const DASHED = "01234567-89ab-cdef-0123-456789abcdef";
const PLAIN = "0123456789abcdef0123456789abcdef";

function store() {
  const values = new Map();
  const storage = {
    get: async (key) => values.get(key),
    put: async (key, value) => values.set(key, value),
    delete: async (key) => values.delete(key),
    list: async ({ prefix }) => new Map([...values].filter(([k]) => k.startsWith(prefix))),
    transaction: async (fn) => fn(storage)
  };
  return { cosmetics: new Cosmetics({ storage }), values };
}

const call = async (cosmetics, path, body) => (await cosmetics.fetch(new Request(`https://cosmetics${path}`,
  body ? { method: "POST", body: JSON.stringify(body) } : {}))).json();

test("a grant turns Plus on, and days stack on top of what is left", async () => {
  const { cosmetics } = store();
  assert.equal((await call(cosmetics, `/plus/status?uuid=${PLAIN}`)).plus, false);
  const first = await call(cosmetics, "/plus/grant", { uuid: DASHED, days: 30 });
  assert.equal(first.ok, true);
  const second = await call(cosmetics, "/plus/grant", { uuid: PLAIN, days: 30 });
  assert.ok(second.until - first.until >= 30 * DAY_MS - 1000);
  const status = await call(cosmetics, `/plus/status?uuid=${DASHED}`);
  assert.equal(status.plus, true);
});

test("expired Plus is off and its capes are no longer wearable", async () => {
  const { cosmetics, values } = store();
  values.set(`plus:${PLAIN}`, { until: Date.now() - 1000, since: 0 });
  assert.equal((await call(cosmetics, `/plus/status?uuid=${PLAIN}`)).plus, false);
  const owned = (await call(cosmetics, `/owned?uuid=${PLAIN}`)).owned;
  assert.ok(![...PLUS_COSMETICS].some((c) => owned.includes(c)));
});

test("Plus capes are wearable while Plus is on, and revoking takes them away", async () => {
  const { cosmetics } = store();
  await call(cosmetics, "/plus/grant", { uuid: PLAIN, days: 30 });
  const cape = [...PLUS_COSMETICS][0];
  assert.deepEqual((await call(cosmetics, "/entitled", { cosmetic: cape, uuids: [PLAIN] })).allowed, [PLAIN]);
  await call(cosmetics, "/plus/revoke", { uuid: DASHED });
  assert.deepEqual((await call(cosmetics, "/entitled", { cosmetic: cape, uuids: [PLAIN] })).allowed, []);
});

test("the batch check only reports players with active Plus", async () => {
  const { cosmetics } = store();
  await call(cosmetics, "/plus/grant", { uuid: PLAIN, days: 1 });
  const other = "fedcba9876543210fedcba9876543210";
  assert.deepEqual((await call(cosmetics, "/plus/batch", { uuids: [DASHED, other] })).plus, [PLAIN]);
});

test("grants need a uuid and a sane number of days", async () => {
  const { cosmetics } = store();
  for (const body of [{ uuid: PLAIN, days: 0 }, { uuid: PLAIN, days: 99999 }, { uuid: "", days: 30 }]) {
    assert.equal((await call(cosmetics, "/plus/grant", body)).ok, false);
  }
  assert.equal(plusActive(null), false);
});

test("granting Plus needs its own key, not the stats dashboard's", async () => {
  const { default: worker } = await import("../src/worker.js");
  const { cosmetics } = store();
  const env = {
    REFERRAL_STATS_KEY: "stats-key",
    PLUS_ADMIN_KEY: "plus-key",
    COSMETICS_DO: { idFromName: (name) => name, get: () => ({ fetch: (url, init) => cosmetics.fetch(new Request(url, init)) }) }
  };
  const grant = (key) => worker.fetch(new Request("https://updates.riverclient.xyz/admin/plus/grant", {
    method: "POST",
    headers: { Authorization: `Bearer ${key}`, "Content-Type": "application/json" },
    body: JSON.stringify({ uuid: PLAIN, days: 30 })
  }), env, {});
  assert.equal((await grant("stats-key")).status, 401);
  assert.equal((await call(cosmetics, `/plus/status?uuid=${PLAIN}`)).plus, false);
  assert.equal((await grant("plus-key")).status, 200);
  assert.equal((await call(cosmetics, `/plus/status?uuid=${PLAIN}`)).plus, true);

  delete env.PLUS_ADMIN_KEY;
  assert.equal((await grant("")).status, 401);
});

test("the public status says yes or no and never when Plus ends", async () => {
  const { default: worker } = await import("../src/worker.js");
  const { cosmetics } = store();
  await call(cosmetics, "/plus/grant", { uuid: PLAIN, days: 30 });
  const env = { COSMETICS_DO: { idFromName: (name) => name, get: () => ({ fetch: (url, init) => cosmetics.fetch(new Request(url, init)) }) } };
  const res = await worker.fetch(new Request(`https://updates.riverclient.xyz/plus/status?uuid=${DASHED}`), env, {});
  assert.deepEqual(await res.json(), { ok: true, plus: true });
});

test("friends with Plus are marked in the social roster, matched across uuid spellings", async () => {
  const { SocialStore } = await import("../src/social.js");
  const { plusOf } = await import("../src/cosmetics.js");
  const { cosmetics } = store();
  await call(cosmetics, "/plus/grant", { uuid: PLAIN, days: 30 });
  const env = { COSMETICS_DO: { idFromName: (name) => name, get: () => ({ fetch: (url, init) => cosmetics.fetch(new Request(url, init)) }) } };
  const values = new Map();
  const storage = {
    get: async (key) => values.get(key),
    put: async (key, value) => values.set(key, value),
    delete: async (key) => values.delete(key),
    transaction: async (fn) => fn(storage)
  };
  const social = new SocialStore(storage, { plusOf: (uuids) => plusOf(env, uuids) });
  const me = "fedcba98-7654-3210-fedc-ba9876543210";
  const other = "11111111-2222-3333-4444-555555555555";
  values.set(`f:${me}`, [DASHED, other]);
  values.set(`u:${DASHED}`, { uuid: DASHED, name: "Plusser", lastSeen: Date.now() });
  values.set(`u:${other}`, { uuid: other, name: "Normal", lastSeen: Date.now() });
  const roster = await social.friendList({ uuid: me, name: "Me" });
  const byName = Object.fromEntries(roster.friends.map((f) => [f.name, f.plus]));
  assert.deepEqual(byName, { Plusser: true, Normal: false });
});

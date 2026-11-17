import test from "node:test";
import assert from "node:assert/strict";
import { BOOSTER_GRACE_DAYS, Cosmetics, DAY_MS, gatedCosmetics } from "../src/cosmetics.js";

const A = "0123456789abcdef0123456789abcdef";
const B = "fedcba9876543210fedcba9876543210";

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

const owns = async (cosmetics, uuid) => (await call(cosmetics, `/owned?uuid=${uuid}`)).owned.includes("booster");

test("a booster code, redeemed in game, turns the cape on for that Minecraft account", async () => {
  const { cosmetics } = store();
  assert.equal(await owns(cosmetics, A), false);
  const made = await call(cosmetics, "/booster/code", { discordId: "111" });
  assert.equal(made.ok, true);
  const redeemed = await call(cosmetics, "/redeem", { code: made.code, uuid: A });
  assert.deepEqual([redeemed.ok, redeemed.cosmetic], [true, "booster"]);
  assert.equal(await owns(cosmetics, A), true);
  assert.equal(await owns(cosmetics, B), false);
});

test("a code works once", async () => {
  const { cosmetics } = store();
  const { code } = await call(cosmetics, "/booster/code", { discordId: "111" });
  assert.equal((await call(cosmetics, "/redeem", { code, uuid: A })).ok, true);
  assert.equal((await call(cosmetics, "/redeem", { code, uuid: B })).ok, false);
  assert.equal(await owns(cosmetics, B), false);
});

test("an old code is refused", async () => {
  const { cosmetics, values } = store();
  const { code } = await call(cosmetics, "/booster/code", { discordId: "111" });
  values.set(`bcode:${code}`, { discordId: "111", expiresAt: Date.now() - 1000 });
  const result = await call(cosmetics, "/redeem", { code, uuid: A });
  assert.equal(result.ok, false);
  assert.equal(await owns(cosmetics, A), false);
});

test("the sync keeps who still boosts and takes the cape from who stopped", async () => {
  const { cosmetics } = store();
  for (const [discordId, uuid] of [["111", A], ["222", B]]) {
    const { code } = await call(cosmetics, "/booster/code", { discordId });
    await call(cosmetics, "/redeem", { code, uuid });
  }
  const result = await call(cosmetics, "/booster/sync", { discordIds: ["111"] });
  assert.deepEqual([result.kept, result.removed], [1, 1]);
  assert.equal(await owns(cosmetics, A), true);
  assert.equal(await owns(cosmetics, B), false);
});

test("a sync with no list is refused instead of taking every cape away", async () => {
  const { cosmetics } = store();
  const { code } = await call(cosmetics, "/booster/code", { discordId: "111" });
  await call(cosmetics, "/redeem", { code, uuid: A });
  assert.equal((await call(cosmetics, "/booster/sync", {})).ok, false);
  assert.equal(await owns(cosmetics, A), true);
});

test("each sync pushes the end date out, and with no sync the cape runs out on its own", async () => {
  const { cosmetics, values } = store();
  const { code } = await call(cosmetics, "/booster/code", { discordId: "111" });
  await call(cosmetics, "/redeem", { code, uuid: A });
  values.set(`booster:${A}`, { ...values.get(`booster:${A}`), until: Date.now() + 1000 });
  await call(cosmetics, "/booster/sync", { discordIds: ["111"] });
  assert.ok(values.get(`booster:${A}`).until > Date.now() + (BOOSTER_GRACE_DAYS - 0.1) * DAY_MS);
  values.set(`booster:${A}`, { ...values.get(`booster:${A}`), until: Date.now() - 1000 });
  assert.equal(await owns(cosmetics, A), false);
});

test("linking the same Discord account to another Minecraft account moves the cape", async () => {
  const { cosmetics } = store();
  for (const uuid of [A, B]) {
    const { code } = await call(cosmetics, "/booster/code", { discordId: "111" });
    await call(cosmetics, "/redeem", { code, uuid });
  }
  assert.equal(await owns(cosmetics, A), false);
  assert.equal(await owns(cosmetics, B), true);
});

test("the booster cape is gated, so others only see it on people who earned it", async () => {
  assert.ok(gatedCosmetics().has("booster"));
  const { cosmetics } = store();
  assert.deepEqual((await call(cosmetics, "/entitled", { cosmetic: "booster", uuids: [A] })).allowed, []);
  const { code } = await call(cosmetics, "/booster/code", { discordId: "111" });
  await call(cosmetics, "/redeem", { code, uuid: A });
  assert.deepEqual((await call(cosmetics, "/entitled", { cosmetic: "booster", uuids: [A, B] })).allowed, [A]);
});

test("the booster routes need their own key", async () => {
  const { default: worker } = await import("../src/worker.js");
  const { cosmetics } = store();
  const env = {
    REFERRAL_STATS_KEY: "stats-key",
    BOOSTER_ADMIN_KEY: "booster-key",
    COSMETICS_DO: { idFromName: (name) => name, get: () => ({ fetch: (url, init) => cosmetics.fetch(new Request(url, init)) }) }
  };
  const post = (path, key, body) => worker.fetch(new Request(`https://updates.riverclient.xyz${path}`, {
    method: "POST",
    headers: { Authorization: `Bearer ${key}`, "Content-Type": "application/json" },
    body: JSON.stringify(body)
  }), env, {});
  assert.equal((await post("/admin/booster/code", "stats-key", { discordId: "111" })).status, 401);
  assert.equal((await post("/admin/booster/sync", "stats-key", { discordIds: [] })).status, 401);
  assert.equal((await post("/admin/booster/code", "booster-key", { discordId: "111" })).status, 200);
  delete env.BOOSTER_ADMIN_KEY;
  assert.equal((await post("/admin/booster/code", "", { discordId: "111" })).status, 401);
});

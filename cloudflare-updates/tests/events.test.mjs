import test from "node:test";
import assert from "node:assert/strict";
import { HALLOWEEN_EVENT, MAX_HEARTBEAT_GAP_MS, advanceEventPlay } from "../src/events.js";
import { Cosmetics, gatedCosmetics } from "../src/cosmetics.js";
import { SocialStore } from "../src/social.js";

class MemoryStorage {
  values = new Map();
  async get(key) { return this.values.get(key); }
  async put(key, value) { this.values.set(key, structuredClone(value)); }
  async delete(key) { this.values.delete(key); }
}

const START = Date.UTC(2026, 9, 20, 18, 0, 0);
const PLAYER = { uuid: "0c8205fb-037b-448e-a431-9c2c06301b0a", name: "Galaxysdc_Larp" };

test("play time counts heartbeat gaps, capped, and only while a world is open", () => {
  let { record } = advanceEventPlay(undefined, START, true);
  assert.equal(record.ms, 0);
  ({ record } = advanceEventPlay(record, START + 10_000, true));
  assert.equal(record.ms, 10_000);
  // The game was closed for an hour: only the capped gap counts.
  ({ record } = advanceEventPlay(record, START + 3_610_000, true));
  assert.equal(record.ms, 10_000 + MAX_HEARTBEAT_GAP_MS);
  // Heartbeats from the title screen count nothing and break the chain.
  ({ record } = advanceEventPlay(record, START + 3_620_000, false));
  assert.equal(record.last, 0);
  ({ record } = advanceEventPlay(record, START + 3_630_000, true));
  assert.equal(record.ms, 10_000 + MAX_HEARTBEAT_GAP_MS);
  // Nothing counts once the event is over.
  const late = advanceEventPlay({ ms: HALLOWEEN_EVENT.goalMs - 1, last: HALLOWEEN_EVENT.endsAt - 5_000 }, HALLOWEEN_EVENT.endsAt + 5_000, true);
  assert.equal(late.completed, false);
});

test("three hours of heartbeats grant the Halloween cape once, and it stays", async () => {
  let clock = START;
  const grants = [];
  const store = new SocialStore(new MemoryStorage(), {
    now: () => clock,
    grantCosmetic: async (uuid, cosmetic) => { grants.push([uuid, cosmetic]); return true; }
  });
  let result;
  for (let beat = 0; beat <= HALLOWEEN_EVENT.goalMs / 10_000; beat++) {
    result = await store.heartbeat(PLAYER, { playing: true });
    clock += 10_000;
  }
  assert.equal(result.event.done, true);
  assert.deepEqual(grants, [[PLAYER.uuid, "halloween"]]);
  await store.heartbeat(PLAYER, { playing: true });
  assert.equal(grants.length, 1);
});

test("a failed grant is retried on the next heartbeat", async () => {
  let clock = START;
  let works = false;
  const store = new SocialStore(new MemoryStorage(), { now: () => clock, grantCosmetic: async () => works });
  await store.storage.put(`u:${PLAYER.uuid}`, { events: { [HALLOWEEN_EVENT.id]: { ms: HALLOWEEN_EVENT.goalMs - 5_000, last: clock } } });
  clock += 10_000;
  assert.equal((await store.heartbeat(PLAYER, { playing: true })).event.done, false);
  works = true;
  clock += 10_000;
  assert.equal((await store.heartbeat(PLAYER, { playing: true })).event.done, true);
});

test("the Halloween cape is only gated once the event is over, and a grant unlocks it", async () => {
  assert.equal(gatedCosmetics(START).has("halloween"), false);
  assert.equal(gatedCosmetics(HALLOWEEN_EVENT.endsAt).has("halloween"), true);
  assert.equal(gatedCosmetics(HALLOWEEN_EVENT.endsAt).has("axie"), true);

  const values = new Map();
  const cosmetics = new Cosmetics({ storage: { get: async (k) => values.get(k), put: async (k, v) => values.set(k, v) } });
  const grant = () => cosmetics.fetch(new Request("https://cosmetics/grant", { method: "POST", body: JSON.stringify({ uuid: PLAYER.uuid, cosmetic: "halloween" }) }));
  assert.equal((await (await grant()).json()).ok, true);
  await grant();
  assert.deepEqual(await cosmetics.ownedBy(PLAYER.uuid), ["halloween"]);
});

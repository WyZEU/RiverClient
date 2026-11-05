import test from "node:test";
import assert from "node:assert/strict";
import { containsProfanity, Social, SocialStore } from "../src/social.js";

const WYZ = "11111111-1111-4111-8111-111111111111";
const FRIEND = "22222222-2222-4222-8222-222222222222";

class MemoryStorage {
  values = new Map();
  async get(key) { return this.values.get(key); }
  async put(key, value) { this.values.set(key, structuredClone(value)); }
}

test("DM filter rejects whole-word profanity without storing the message", async () => {
  const storage = new MemoryStorage();
  const store = new SocialStore(storage);
  const session = { uuid: WYZ, name: "WyZ" };
  await storage.put(`f:${WYZ}`, [FRIEND]);

  for (const safe of ["class", "assist", "Scunthorpe", "hello friend"]) {
    assert.equal(containsProfanity(safe), false);
    assert.equal((await store.sendMessage(session, FRIEND, safe)).ok, true);
  }
  const before = (await store.history(session, FRIEND)).messages.length;
  for (const blocked of ["fuck", "SHIT", "ｆｕｃｋ", "f\u200buck"]) {
    assert.equal(containsProfanity(blocked), true);
    const result = await store.sendMessage(session, FRIEND, blocked);
    assert.equal(result.ok, false);
  }
  assert.equal((await store.history(session, FRIEND)).messages.length, before);
});

test("verified accounts have separate burst limits for each social action", async () => {
  const social = new Social({ storage: new MemoryStorage() }, {});
  let now = 100_000;
  social.now = () => now;
  social.store.sessionFor = async (token) => token === "another-session"
    ? { uuid: FRIEND, name: "Friend" }
    : { uuid: WYZ, name: "WyZ" };
  let sent = 0;
  let requested = 0;
  let reported = 0;
  social.store.sendMessage = async () => { sent++; return { ok: true, sent: {} }; };
  social.store.sendFriendRequest = async () => { requested++; return { ok: true }; };
  social.store.report = async () => { reported++; return { ok: true }; };
  social.push = () => {};

  const call = (route, token = "first-session") => social.fetch(new Request(`https://social/social${route}`, {
    method: "POST",
    headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
    body: JSON.stringify({ uuid: FRIEND, text: "hello", name: "Friend", reason: "spam" })
  }));

  for (let i = 0; i < 8; i++) assert.equal((await call("/dm/send")).status, 200);
  const dmLimited = await call("/dm/send", "second-session");
  assert.equal(dmLimited.status, 429);
  assert.ok(Number(dmLimited.headers.get("Retry-After")) > 0);
  assert.equal(sent, 8);
  assert.equal((await call("/dm/send", "another-session")).status, 200);

  for (let i = 0; i < 5; i++) assert.equal((await call("/friends/request")).status, 200);
  assert.equal((await call("/friends/request")).status, 429);
  assert.equal(requested, 5);

  for (let i = 0; i < 3; i++) assert.equal((await call("/report")).status, 200);
  assert.equal((await call("/report")).status, 429);
  assert.equal(reported, 3);

  now += 11_000;
  assert.equal((await call("/dm/send")).status, 200);
  now += 60_000;
  assert.equal((await call("/friends/request")).status, 200);
});

test("slower sustained spam still hits the longer account limits", () => {
  const social = new Social({ storage: new MemoryStorage() }, {});
  let now = 1_000_000;
  social.now = () => now;
  for (let group = 0; group < 8; group++) {
    for (let i = 0; i < 8; i++) {
      if (group === 7 && i === 4) {
        assert.ok(social.actionRetryAfter(WYZ, "/dm/send") > 0);
        return;
      }
      assert.equal(social.actionRetryAfter(WYZ, "/dm/send"), 0);
    }
    now += 11_000;
  }
});

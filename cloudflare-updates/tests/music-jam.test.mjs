import test from "node:test";
import assert from "node:assert/strict";
import { Social } from "../src/social.js";

const host = "11111111-1111-4111-8111-111111111111";
const guest = "22222222-2222-4222-8222-222222222222";
const other = "33333333-3333-4333-8333-333333333333";
const song = { title: "Shared song", artist: "Artist", album: "Album", uri: "spotify:track:4uLU6hMCjMI75M1A2tKUQC", positionMs: 1200, durationMs: 200_000, playing: true };
const lyrics = [{ timeMs: 1000, text: "One line", words: [{ startMs: 1000, durationMs: 400, text: "One" }, { startMs: 1400, durationMs: 600, text: "line" }] }];

async function fixture() {
  const values = new Map();
  const storage = { async get(key) { return structuredClone(values.get(key)); }, async put(key, value) { values.set(key, structuredClone(value)); } };
  const social = new Social({ storage }, {});
  let now = 100_000;
  const notices = [];
  social.now = () => now;
  social.push = (uuid, payload) => notices.push({ uuid, payload: structuredClone(payload) });
  social.store.sessionFor = async (token) => [host, guest, other].includes(token) ? { uuid: token, name: token === host ? "Host" : "Guest" } : null;
  await storage.put(`f:${host}`, [guest, other]);
  await storage.put(`f:${guest}`, [host]);
  await storage.put(`f:${other}`, [host]);
  const request = (route, uuid, body = {}) => social.fetch(new Request(`https://social/social/jam/${route}`, {
    method: "POST", headers: { Authorization: `Bearer ${uuid}`, "Content-Type": "application/json" }, body: JSON.stringify(body)
  }));
  const call = async (...args) => (await request(...args)).json();
  const create = await call("create", host, { track: song });
  const identity = { id: create.room.id, generation: create.room.generation };
  return { social, storage, values, notices, request, call, identity, advance(ms) { now += ms; } };
}

test("two invited clients receive the same song, clock and lyrics without copying lyrics on heartbeats", async () => {
  const f = await fixture();
  assert.equal((await f.call("invite", host, { ...f.identity, uuid: guest })).ok, true);
  const state = await f.call("state", guest);
  assert.equal(state.room, null);
  assert.equal(state.invites.length, 1);
  assert.equal((await f.call("join", guest, f.identity)).room.members.length, 2);
  const publish = await f.call("publish", host, { ...f.identity, sequence: 1, track: song, lyrics });
  assert.equal(publish.ok, true);
  const packet = f.notices.filter((n) => n.uuid === guest && n.payload.type === "jam").at(-1).payload;
  assert.deepEqual(packet.room.track, song);
  assert.deepEqual(packet.room.lyrics, lyrics);
  assert.equal(packet.room.updatedAt, packet.serverNow);
  f.advance(10_000);
  assert.equal((await f.call("publish", host, { ...f.identity, sequence: 2, track: { ...song, positionMs: 11_200 } })).ok, true);
  const heartbeat = f.notices.filter((n) => n.uuid === guest && n.payload.type === "jam").at(-1).payload;
  assert.equal(heartbeat.room.lyrics, undefined);
  assert.deepEqual((await f.call("state", guest)).room.lyrics, lyrics);
  await f.call("publish", host, { ...f.identity, sequence: 3, track: { ...song, title: "Next song", uri: "", positionMs: 0 } });
  assert.deepEqual(f.notices.filter((n) => n.uuid === guest && n.payload.type === "jam").at(-1).payload.room.lyrics, []);
});

test("authentication, invitation, generation and host ownership are required", async () => {
  const f = await fixture();
  assert.equal((await f.request("state", "unknown")).status, 401);
  assert.equal((await f.call("join", guest, f.identity)).ok, false);
  await f.call("invite", host, { ...f.identity, uuid: guest });
  assert.equal((await f.call("join", guest, { ...f.identity, generation: "old" })).ok, false);
  await f.call("join", guest, f.identity);
  assert.equal((await f.call("publish", guest, { ...f.identity, sequence: 1, track: song })).ok, false);
  assert.equal((await f.call("invite", guest, { ...f.identity, uuid: other })).ok, false);
  await f.call("publish", host, { ...f.identity, sequence: 2, track: { ...song, playing: false, positionMs: 900 } });
  assert.equal((await f.call("publish", host, { ...f.identity, sequence: 1, track: song })).ok, false);
  assert.equal((await f.call("state", guest)).room.track.playing, false);
});

test("parallel joins and a host seek keep all members and the newest clock", async () => {
  const f = await fixture();
  await f.call("invite", host, { ...f.identity, uuid: guest });
  await f.call("invite", host, { ...f.identity, uuid: other });
  const results = await Promise.all([
    f.call("join", guest, f.identity), f.call("join", other, f.identity),
    f.call("publish", host, { ...f.identity, sequence: 4, track: { ...song, positionMs: 8000 } })
  ]);
  assert.ok(results.every((result) => result.ok));
  const state = await f.call("state", guest);
  assert.equal(state.room.members.length, 3);
  assert.equal(state.room.sequence, 4);
  assert.equal(state.room.track.positionMs, 8000);
});

test("leaving and blocking stop sharing and an expired host cannot resume an old room", async () => {
  const f = await fixture();
  await f.call("invite", host, { ...f.identity, uuid: guest });
  await f.call("join", guest, f.identity);
  await f.storage.put(`bl:${guest}`, [host]);
  await f.call("publish", host, { ...f.identity, sequence: 1, track: song });
  assert.equal((await f.call("state", guest)).room, null);
  assert.ok(f.notices.some((n) => n.uuid === guest && n.payload.type === "jam-ended"));
  f.advance(91_000);
  assert.equal((await f.call("state", host)).room, null);
  assert.equal((await f.call("publish", host, { ...f.identity, sequence: 2, track: song })).ok, false);
});

test("host departure ends the session for every joined client", async () => {
  const f = await fixture();
  await f.call("invite", host, { ...f.identity, uuid: guest });
  await f.call("join", guest, f.identity);
  await f.call("leave", host);
  assert.equal((await f.call("state", guest)).room, null);
  assert.ok(f.notices.some((n) => n.uuid === guest && n.payload.type === "jam-ended"));
});

test("untrusted links, oversized payloads, bad timing and flooding are rejected", async () => {
  const f = await fixture();
  for (const track of [{ ...song, uri: "file:///tmp/anything" }, { ...song, positionMs: -1 }]) {
    assert.equal((await f.call("publish", host, { ...f.identity, sequence: 1, track })).ok, false);
  }
  assert.equal((await f.call("publish", host, { ...f.identity, sequence: 1, track: song, lyrics: [{ timeMs: -1, text: "bad" }] })).ok, false);
  assert.equal((await f.request("publish", host, { padding: "x".repeat(100_000) })).status, 413);
  for (let i = 0; i < 6; i++) await f.request("state", host);
  const limited = await f.request("state", host);
  assert.equal(limited.status, 429);
  assert.ok(Number(limited.headers.get("Retry-After")) > 0);
  f.advance(61_000);
  assert.equal((await f.request("state", host)).status, 200);
});

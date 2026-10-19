/**
 * Logic tests for the social backend, driven against an in-memory store and a stubbed
 * Mojang so they run in plain Node with no worker, no network and no live accounts.
 *
 * The security properties are the point here: identity must come from Mojang and nowhere
 * else, blocking must actually stop contact, and you must not be able to message someone
 * who is not your friend.
 */

import { SocialStore } from "./src/social.js";

class MemoryStorage {
  constructor() { this.map = new Map(); }
  async get(key) { return this.map.get(key); }
  async put(key, value) { this.map.set(key, structuredClone(value)); }
  async delete(key) { this.map.delete(key); }
}

let now = 1_700_000_000_000;
const clock = () => now;

/**
 * Stands in for certificate verification. Only accounts registered here 'hold' a valid
 * Mojang-signed certificate; everyone else is an impostor. Real RSA is exercised
 * separately in test-certificate.js against genuine Mojang material.
 */
class FakeCertificates {
  constructor() { this.accounts = new Map(); this.calls = 0; }
  register(name, uuid) { this.accounts.set(name.toLowerCase(), uuid); }
  verify = async (certificate) => {
    this.calls += 1;
    const uuid = this.accounts.get(String(certificate.claimName || '').toLowerCase());
    if (!uuid) return { ok: false, message: 'Mojang did not sign that certificate.' };
    if (!certificate.nonce) return { ok: false, message: 'Missing challenge.' };
    return { ok: true, uuid };
  };
}

const results = [];
const check = (name, condition, detail = "") => {
  results.push({ name, pass: Boolean(condition) });
  console.log(`${condition ? "PASS" : "FAIL"}  ${name}${detail ? ` - ${detail}` : ""}`);
};

async function signIn(store, certs, name, uuid) {
  certs.register(name, uuid);
  const { nonce } = await store.beginAuth();
  return store.completeAuth(name, { nonce, claimName: name });
}

async function main() {
  const certs = new FakeCertificates();
  const store = new SocialStore(new MemoryStorage(), { now: clock, verifyImpl: certs.verify });

  const WYZ = "11111111-1111-4111-8111-111111111111";
  const FRIEND = "22222222-2222-4222-8222-222222222222";
  const STRANGER = "33333333-3333-4333-8333-333333333333";

  // ---- authentication
  const wyz = await signIn(store, certs, "WyZ_EU", WYZ);
  check("a verified account gets a session", wyz.ok && wyz.uuid === WYZ, wyz.message || wyz.uuid);

  const impostor = await store.beginAuth().then((p) => store.completeAuth("NotARealAccount", { nonce: p.nonce, claimName: "NotARealAccount" }));
  check("an account Mojang does not confirm is refused", !impostor.ok, impostor.message);

  // Replay protection: the same nonce must not authenticate twice.
  const pending = await store.beginAuth();
  await store.completeAuth("WyZ_EU", { nonce: pending.nonce, claimName: "WyZ_EU" });
  const replay = await store.completeAuth("WyZ_EU", { nonce: pending.nonce, claimName: "WyZ_EU" });
  check("a handshake cannot be replayed", !replay.ok, replay.message);

  const forged = await store.sessionFor("not-a-real-token");
  check("a made-up session token is rejected", forged === null);

  const friend = await signIn(store, certs, "BestFriend", FRIEND);
  const stranger = await signIn(store, certs, "SomeStranger", STRANGER);
  const wyzSession = { uuid: wyz.uuid, name: wyz.name };
  const friendSession = { uuid: friend.uuid, name: friend.name };
  const strangerSession = { uuid: stranger.uuid, name: stranger.name };

  // ---- friend requests
  const request = await store.sendFriendRequest(wyzSession, "BestFriend");
  check("a friend request can be sent by username", request.ok, request.message);

  const beforeAccept = await store.friendList(friendSession);
  check("the request shows up for the recipient", beforeAccept.requests.length === 1, JSON.stringify(beforeAccept.requests));

  const accept = await store.acceptFriendRequest(friendSession, WYZ);
  check("accepting creates the friendship", accept.ok, accept.message);

  const wyzFriends = await store.friendList(wyzSession);
  check("friendship is mutual", wyzFriends.friends.some((f) => f.uuid === FRIEND), JSON.stringify(wyzFriends.friends));

  const self = await store.sendFriendRequest(wyzSession, "WyZ_EU");
  check("you cannot friend yourself", !self.ok, self.message);

  // A pending request in the other direction should settle, not create a second request.
  await store.sendFriendRequest(strangerSession, "WyZ_EU");
  const crossed = await store.sendFriendRequest(wyzSession, "SomeStranger");
  check("mutual requests resolve into a friendship", crossed.ok && !crossed.pending, crossed.message);
  await store.removeFriend(wyzSession, STRANGER);

  // ---- inviting someone who has never opened River
  {
    const NEWCOMER = "55555555-5555-4555-8555-555555555555";
    // No sign-in for them yet, so nothing maps their name - the sender's client resolved
    // the UUID from Mojang and passes it in.
    const invite = await store.sendFriendRequest(wyzSession, "NeverUsedRiver", NEWCOMER);
    check("you can invite a player who has never used River", invite.ok, invite.message);

    const byNameOnly = await store.sendFriendRequest(wyzSession, "AlsoUnknown");
    check("without a resolved UUID an unknown name is still refused", !byNameOnly.ok, byNameOnly.message);

    // They install River and sign in for the first time.
    const newcomer = await signIn(store, certs, "NeverUsedRiver", NEWCOMER);
    const waiting = await store.friendList({ uuid: newcomer.uuid, name: newcomer.name });
    check(
      "the request is waiting the first time they sign in",
      waiting.requests.some((r) => r.from === WYZ),
      JSON.stringify(waiting.requests)
    );

    const accepted = await store.acceptFriendRequest({ uuid: newcomer.uuid, name: newcomer.name }, WYZ);
    check("they can accept it after installing", accepted.ok, accepted.message);
    await store.removeFriend(wyzSession, NEWCOMER);
  }

  // ---- declining requests
  await store.sendFriendRequest(strangerSession, "WyZ_EU");
  const declined = await store.declineFriendRequest(wyzSession, STRANGER);
  check("a pending request can be declined", declined.ok, declined.message);

  const afterDecline = await store.friendList(wyzSession);
  check("declining clears it from the pending list", !afterDecline.requests.some((r) => r.from === STRANGER));
  check("declining does not create a friendship", !afterDecline.friends.some((f) => f.uuid === STRANGER));

  const declineAgain = await store.declineFriendRequest(wyzSession, STRANGER);
  check("declining a request that is gone is refused", !declineAgain.ok, declineAgain.message);

  // Declining is not blocking: they must still be able to ask again later.
  const reRequest = await store.sendFriendRequest(strangerSession, "WyZ_EU");
  check("a declined player can send a new request", reRequest.ok, reRequest.message);
  await store.declineFriendRequest(wyzSession, STRANGER);

  // ---- messaging
  const dm = await store.sendMessage(wyzSession, FRIEND, "hey, want to play?");
  check("a friend can be messaged", dm.ok, dm.message);

  const notFriend = await store.sendMessage(wyzSession, STRANGER, "hello stranger");
  check("a non-friend cannot be messaged", !notFriend.ok, notFriend.message);

  const empty = await store.sendMessage(wyzSession, FRIEND, "   ");
  check("empty messages are rejected", !empty.ok, empty.message);

  const nasty = await store.sendMessage(wyzSession, FRIEND, "red§ctext\nsecond line");
  check(
    "control characters and colour codes are stripped",
    nasty.ok && !nasty.sent.text.includes("§") && !nasty.sent.text.includes("\n"),
    JSON.stringify(nasty.sent?.text)
  );

  const longText = "x".repeat(900);
  const truncated = await store.sendMessage(wyzSession, FRIEND, longText);
  check("over-long messages are truncated", truncated.ok && truncated.sent.text.length === 512, String(truncated.sent?.text.length));

  // Both sides must read the same conversation regardless of who asks.
  const mine = await store.history(wyzSession, FRIEND);
  const theirs = await store.history(friendSession, WYZ);
  check("both participants see the same thread", mine.messages.length === theirs.messages.length && mine.messages.length === 3, `${mine.messages.length} vs ${theirs.messages.length}`);

  // ---- presence
  const beat = await store.heartbeat(wyzSession, {});
  const friendRow = () => beat.friends.find((f) => f.uuid === FRIEND);
  await store.heartbeat(friendSession, {});
  const afterBoth = await store.friendList(wyzSession);
  check("a friend who just pinged shows as online", afterBoth.friends.find((f) => f.uuid === FRIEND)?.online === true);

  now += 5 * 60 * 1000; // let the heartbeat go stale
  const later = await store.friendList(wyzSession);
  check("a stale friend drops to offline", later.friends.find((f) => f.uuid === FRIEND)?.online === false);
  now -= 5 * 60 * 1000;

  // Server address must not leak unless the player opted in.
  await store.heartbeat(friendSession, { server: "play.example.net", shareServer: false });
  const hidden = await store.friendList(wyzSession);
  check("server address stays private by default", hidden.friends.find((f) => f.uuid === FRIEND)?.server === "");

  await store.heartbeat(friendSession, { server: "play.example.net", shareServer: true });
  const shared = await store.friendList(wyzSession);
  check("server address is shared when opted in", shared.friends.find((f) => f.uuid === FRIEND)?.server === "play.example.net");

  // ---- availability travelling with the heartbeat
  {
    await store.heartbeat(friendSession, { status: "dnd" });
    const seen = await store.friendList(wyzSession);
    check("do-not-disturb reaches friends", seen.friends.find((f) => f.uuid === FRIEND)?.status === "dnd", JSON.stringify(seen.friends.find((f) => f.uuid === FRIEND)));

    await store.heartbeat(friendSession, { status: "invisible" });
    const hidden = await store.friendList(wyzSession);
    const row = hidden.friends.find((f) => f.uuid === FRIEND);
    check("invisible actually reads as offline, not a label", row?.online === false && row?.status === "offline", JSON.stringify(row));

    await store.heartbeat(friendSession, { status: "online" });
  }

  // ---- message notices (what drives the DM notification)
  {
    await store.sendMessage(friendSession, WYZ, "ping!");
    const beat = await store.heartbeat(wyzSession, {});
    check("a new message shows up in the next heartbeat", beat.inbox?.some((n) => n.from === FRIEND), JSON.stringify(beat.inbox));

    const second = await store.heartbeat(wyzSession, {});
    check("it is not announced twice", (second.inbox || []).length === 0, JSON.stringify(second.inbox));

    // The sender must not be told about their own message.
    await store.sendMessage(wyzSession, FRIEND, "pong");
    const senderBeat = await store.heartbeat(wyzSession, {});
    check("your own outgoing message does not notify you", (senderBeat.inbox || []).length === 0, JSON.stringify(senderBeat.inbox));
  }

  // ---- moderation
  const blocked = await store.block(wyzSession, FRIEND);
  check("blocking succeeds", blocked.ok, blocked.message);

  const afterBlock = await store.friendList(wyzSession);
  check("blocking also removes the friendship", !afterBlock.friends.some((f) => f.uuid === FRIEND));

  const blockedDm = await store.sendMessage(friendSession, WYZ, "let me back in");
  check("a blocked player cannot message you", !blockedDm.ok, blockedDm.message);

  const blockedRequest = await store.sendFriendRequest(friendSession, "WyZ_EU");
  check("a blocked player cannot re-add you", !blockedRequest.ok, blockedRequest.message);
  check(
    "the block is not revealed to the blocked player",
    blockedRequest.message === "Could not find a Minecraft account with that name.",
    blockedRequest.message
  );

  const blockedList = await store.blockedList(wyzSession);
  check("blocked players can be listed for unblocking", blockedList.blocked.some((b) => b.uuid === FRIEND), JSON.stringify(blockedList.blocked));

  const report = await store.report(wyzSession, FRIEND, "spamming me");
  check("reporting works and captures context", report.ok, report.message);

  await store.unblock(wyzSession, FRIEND);
  const afterUnblock = await store.sendFriendRequest(wyzSession, "BestFriend");
  check("unblocking restores contact", afterUnblock.ok, afterUnblock.message);

  check("every sign-in went through certificate verification", certs.calls >= 4, );

  const failed = results.filter((r) => !r.pass);
  console.log(failed.length ? `\n${failed.length} FAILED: ${failed.map((f) => f.name).join(", ")}` : `\nAll ${results.length} social checks passed.`);
  process.exit(failed.length ? 1 : 0);
}

main().catch((error) => { console.error("test crashed:", error); process.exit(1); });

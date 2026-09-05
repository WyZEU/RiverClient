/**
 * River social backend: friends, direct messages, presence and moderation.
 *
 * Runs as a Durable Object with persistent storage (declared as a SQLite class in
 * wrangler.toml). This is deliberately separate from the Presence DO, which is in-memory
 * and ephemeral by design - friendships and messages must survive eviction.
 *
 * Identity is NOT taken on trust. Presence today accepts whatever name/uuid a client
 * sends, which is harmless for a cosmetic badge but would let anyone send chat messages
 * as anyone else. Instead every session is proven with the Mojang-signed player
 * certificate that also underpins signed chat:
 *
 *   1. client asks River for a one-time nonce             POST /social/auth/begin
 *   2. client fetches its certificate from Mojang          (works: the player's own IP)
 *   3. client signs the nonce with the certificate key
 *   4. client sends certificate + signature                POST /social/auth/complete
 *   5. River checks Mojang's signature OFFLINE, then the nonce signature
 *
 * The original design called Mojang's hasJoined from here, which cannot work: Mojang
 * answers every Cloudflare Worker request with 403 while the same call from an ordinary
 * IP succeeds. Verifying the certificate needs no outbound Mojang call at all, so the
 * UUID on every message stays authentic without a server of our own.
 */

import { MOJANG_PLAYER_CERTIFICATE_KEYS } from "./mojang-keys.js";

const MAX_FRIENDS = 200;
const MAX_REQUESTS = 100;
const MAX_BLOCKS = 200;
const MAX_MESSAGES_PER_CONVERSATION = 500;
const MAX_MESSAGE_LENGTH = 512;
const SESSION_TTL_MS = 7 * 24 * 60 * 60 * 1000;
/** Sign-in attempts allowed per IP per window, before /auth/* starts returning 429. */
const AUTH_WINDOW_MS = 60 * 1000;
const AUTH_MAX_PER_WINDOW = 12;
const PENDING_AUTH_TTL_MS = 2 * 60 * 1000;
/** A user counts as online this long after their last heartbeat. */
const ONLINE_WINDOW_MS = 60 * 1000;

const KEY = {
  user: (uuid) => `u:${uuid}`,
  nameIndex: (name) => `nm:${String(name).toLowerCase()}`,
  friends: (uuid) => `f:${uuid}`,
  requests: (uuid) => `rq:${uuid}`,
  blocks: (uuid) => `bl:${uuid}`,
  session: (token) => `s:${token}`,
  pendingAuth: (serverId) => `pa:${serverId}`,
  report: (id) => `rp:${id}`,
  /** Unseen message notices, drained by the recipient's next heartbeat. */
  inbox: (uuid) => `in:${uuid}`,
  conversation: (a, b) => `dm:${[a, b].sort().join("|")}`
};

// These responses are authenticated, so they must not be readable by any origin that
// happens to hold a session token. The only callers are the in-game Java client and the
// launcher's main process, neither of which enforces CORS, so scoping this to the site
// costs nothing. Security headers match the Worker's, since the DO answers directly.
const json = (body, status = 200) => Response.json(body, {
  status,
  headers: {
    "Access-Control-Allow-Origin": "https://riverclient.xyz",
    "Cache-Control": "no-store",
    "X-Content-Type-Options": "nosniff",
    "X-Frame-Options": "DENY",
    "Referrer-Policy": "no-referrer",
    "Strict-Transport-Security": "max-age=31536000; includeSubDomains"
  }
});
const fail = (message, status = 400) => json({ ok: false, message }, status);

function normalizeUuid(raw) {
  const hex = String(raw || "").replace(/-/g, "").toLowerCase();
  if (hex.length !== 32) return "";
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

function cleanText(raw) {
  // Strip control characters: they corrupt in-game rendering and can smuggle
  // section-sign colour codes into other players' chat.
  return String(raw || "")
    .replace(/[\u0000-\u001f\u007f\u00a7]/g, "")
    .trim()
    .slice(0, MAX_MESSAGE_LENGTH);
}

const b64ToBytes = (value) => Uint8Array.from(atob(String(value || "")), (c) => c.charCodeAt(0));

/** UUID text to its 16 raw bytes, the form Mojang signs over. */
function uuidToBytes(uuid) {
  const hex = String(uuid || "").replace(/-/g, "");
  if (hex.length !== 32) return null;
  const out = new Uint8Array(16);
  for (let i = 0; i < 16; i += 1) out[i] = parseInt(hex.slice(i * 2, i * 2 + 2), 16);
  return out;
}

/**
 * Verifies a Mojang player certificate and the holder's proof of possession, entirely
 * offline.
 *
 * Two independent things have to hold:
 *   1. Mojang signed (uuid || expiresAt || publicKey), so this key really belongs to that
 *      account. Their signature is SHA1withRSA over exactly that layout.
 *   2. The caller can sign our one-time nonce with the matching private key, so they are
 *      the account holder rather than someone replaying a certificate they saw.
 *
 * Neither step contacts Mojang, which is the point: Cloudflare's egress is 403'd by them.
 */
async function verifyCertificate({ uuid, publicKey, publicKeySignature, expiresAt, nonce, nonceSignature }) {
  const uuidBytes = uuidToBytes(uuid);
  if (!uuidBytes) return { ok: false, message: "Malformed account id." };

  const expiryMs = Date.parse(expiresAt);
  if (!Number.isFinite(expiryMs)) return { ok: false, message: "Malformed certificate expiry." };
  if (expiryMs <= Date.now()) return { ok: false, message: "That Minecraft certificate has expired. Restart the game." };

  let publicKeyDer;
  let mojangSig;
  let nonceSig;
  try {
    publicKeyDer = b64ToBytes(publicKey);
    mojangSig = b64ToBytes(publicKeySignature);
    nonceSig = b64ToBytes(nonceSignature);
  } catch {
    return { ok: false, message: "Malformed certificate." };
  }

  // uuid(16) || expiresAt(8, big-endian millis) || publicKey DER
  const payload = new Uint8Array(16 + 8 + publicKeyDer.length);
  payload.set(uuidBytes, 0);
  new DataView(payload.buffer).setBigInt64(16, BigInt(expiryMs), false);
  payload.set(publicKeyDer, 24);

  let signedByMojang = false;
  for (const keyB64 of MOJANG_PLAYER_CERTIFICATE_KEYS) {
    try {
      const mojangKey = await crypto.subtle.importKey(
        "spki", b64ToBytes(keyB64),
        { name: "RSASSA-PKCS1-v1_5", hash: "SHA-1" }, false, ["verify"]
      );
      if (await crypto.subtle.verify("RSASSA-PKCS1-v1_5", mojangKey, mojangSig, payload)) {
        signedByMojang = true;
        break;
      }
    } catch {
      // Try the next published key rather than failing the whole sign-in.
    }
  }
  if (!signedByMojang) return { ok: false, message: "Mojang did not sign that certificate." };

  try {
    const holderKey = await crypto.subtle.importKey(
      "spki", publicKeyDer,
      { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["verify"]
    );
    const proved = await crypto.subtle.verify(
      "RSASSA-PKCS1-v1_5", holderKey, nonceSig, new TextEncoder().encode(nonce)
    );
    if (!proved) return { ok: false, message: "Could not prove you hold that account." };
  } catch {
    return { ok: false, message: "Could not check your proof of ownership." };
  }

  return { ok: true, uuid: normalizeUuid(uuid) };
}

/**
 * All state access lives here so it can be driven against a plain Map in tests as well as
 * real Durable Object storage.
 */
export class SocialStore {
  constructor(storage, { now = () => Date.now(), fetchImpl, verifyImpl = verifyCertificate } = {}) {
    this.storage = storage;
    this.now = now;
    this.fetchImpl = fetchImpl;
    /** Swappable so tests can drive sign-in without real RSA material. */
    this.verifyImpl = verifyImpl;
  }

  async get(key, fallback) {
    const value = await this.storage.get(key);
    return value === undefined || value === null ? fallback : value;
  }

  put(key, value) { return this.storage.put(key, value); }

  // ------------------------------------------------------------------ auth

  /**
   * Hands out a one-time nonce for the client to sign. Single-use and short-lived so a
   * captured signature cannot be replayed by someone else later.
   */
  async beginAuth() {
    const nonce = crypto.randomUUID().replace(/-/g, "") + crypto.randomUUID().replace(/-/g, "");
    await this.put(KEY.pendingAuth(nonce), { at: this.now() });
    // serverId is kept in the response purely so older clients still parse it.
    return { nonce, serverId: nonce };
  }

  /**
   * Completes sign-in from a Mojang-signed player certificate plus a signature over the
   * nonce from [beginAuth]. The UUID is whatever Mojang signed - never what the caller
   * claims - so it cannot be forged.
   *
   * The display name IS self-asserted (Mojang's certificate binds the UUID only, not the
   * name), so it is treated as a label rather than identity: a name is only indexed for
   * friend lookups while no other account already holds it.
   */
  async completeAuth(name, certificate = {}) {
    const nonce = String(certificate.nonce || "");
    const pending = await this.get(KEY.pendingAuth(nonce), null);
    if (!pending) return { ok: false, message: "That sign-in attempt is unknown or already used." };
    await this.storage.delete(KEY.pendingAuth(nonce));
    if (this.now() - pending.at > PENDING_AUTH_TTL_MS) {
      return { ok: false, message: "That sign-in attempt expired. Try again." };
    }

    const verified = await this.verifyImpl({ ...certificate, nonce });
    if (!verified.ok) return { ok: false, message: verified.message };

    const uuid = verified.uuid;
    const claimedName = String(name || "").slice(0, 32) || uuid.slice(0, 8);

    const token = crypto.randomUUID();
    const expiresAt = this.now() + SESSION_TTL_MS;
    await this.put(KEY.session(token), { uuid, name: claimedName, expiresAt });

    const user = await this.get(KEY.user(uuid), {});
    await this.put(KEY.user(uuid), { ...user, uuid, name: claimedName, lastSeen: this.now() });

    // Only claim the name if it is free or already ours, so nobody can squat someone
    // else's username and receive their friend requests.
    const nameOwner = await this.get(KEY.nameIndex(claimedName), null);
    if (!nameOwner || nameOwner === uuid) await this.put(KEY.nameIndex(claimedName), uuid);

    return { ok: true, token, uuid, name: claimedName, expiresAt };
  }

  async sessionFor(token) {
    if (!token) return null;
    const session = await this.get(KEY.session(token), null);
    if (!session) return null;
    if (session.expiresAt <= this.now()) {
      await this.storage.delete(KEY.session(token));
      return null;
    }
    return session;
  }

  // ------------------------------------------------------------------ friends

  async friendsOf(uuid) { return this.get(KEY.friends(uuid), []); }
  async blocksOf(uuid) { return this.get(KEY.blocks(uuid), []); }

  async isBlockedEitherWay(a, b) {
    const [aBlocks, bBlocks] = await Promise.all([this.blocksOf(a), this.blocksOf(b)]);
    return aBlocks.includes(b) || bBlocks.includes(a);
  }

  /**
   * [targetUuidHint] is the UUID the sender's own client resolved from Mojang.
   *
   * It matters because the worker cannot resolve names itself (Mojang 403s Cloudflare),
   * and because requiring the target to already exist here meant you could only add people
   * who had opened River before you. With a client-resolved UUID the request is simply
   * filed against that account; the moment they first sign in, it is waiting for them.
   *
   * Lying about the UUID only misdirects your own request - the SENDER is always the
   * verified session, so a request can never be forged as coming from someone else.
   */
  async sendFriendRequest(session, targetName, targetUuidHint = "") {
    const targetUuid = normalizeUuid(targetUuidHint) || await this.get(KEY.nameIndex(targetName), null);
    if (!targetUuid) return { ok: false, message: "Could not find a Minecraft account with that name." };
    if (targetUuid === session.uuid) return { ok: false, message: "You cannot add yourself." };

    if (await this.isBlockedEitherWay(session.uuid, targetUuid)) {
      // Deliberately identical to the not-found case: revealing that you are blocked
      // just invites people to work around it.
      return { ok: false, message: "Could not find a Minecraft account with that name." };
    }

    const existing = await this.friendsOf(session.uuid);
    if (existing.includes(targetUuid)) return { ok: false, message: "You are already friends." };
    if (existing.length >= MAX_FRIENDS) return { ok: false, message: "Your friends list is full." };

    // If they already asked you, treat this as accepting instead of creating a loop.
    const mine = await this.get(KEY.requests(session.uuid), []);
    if (mine.some((entry) => entry.from === targetUuid)) {
      return this.acceptFriendRequest(session, targetUuid);
    }

    const theirs = await this.get(KEY.requests(targetUuid), []);
    if (theirs.some((entry) => entry.from === session.uuid)) {
      return { ok: true, message: "Request already sent.", pending: true };
    }
    if (theirs.length >= MAX_REQUESTS) return { ok: false, message: "That player has too many pending requests." };

    theirs.push({ from: session.uuid, name: session.name, at: this.now() });
    await this.put(KEY.requests(targetUuid), theirs);
    return { ok: true, message: "Friend request sent.", pending: true, to: targetUuid };
  }

  async acceptFriendRequest(session, fromUuid) {
    const mine = await this.get(KEY.requests(session.uuid), []);
    const index = mine.findIndex((entry) => entry.from === fromUuid);
    if (index === -1) return { ok: false, message: "No request from that player." };
    mine.splice(index, 1);
    await this.put(KEY.requests(session.uuid), mine);

    if (await this.isBlockedEitherWay(session.uuid, fromUuid)) {
      return { ok: false, message: "That request is no longer available." };
    }

    const [ours, theirs] = await Promise.all([this.friendsOf(session.uuid), this.friendsOf(fromUuid)]);
    if (!ours.includes(fromUuid)) ours.push(fromUuid);
    if (!theirs.includes(session.uuid)) theirs.push(session.uuid);
    await Promise.all([this.put(KEY.friends(session.uuid), ours), this.put(KEY.friends(fromUuid), theirs)]);
    return { ok: true, message: "Friend added." };
  }

  /**
   * Turns a request down without blocking. Silent by design - the sender is not told,
   * so declining cannot be used to bait someone into re-adding you.
   */
  async declineFriendRequest(session, fromUuid) {
    const mine = await this.get(KEY.requests(session.uuid), []);
    const remaining = mine.filter((entry) => entry.from !== fromUuid);
    if (remaining.length === mine.length) return { ok: false, message: "No request from that player." };
    await this.put(KEY.requests(session.uuid), remaining);
    return { ok: true, message: "Request declined." };
  }

  async removeFriend(session, otherUuid) {
    const [ours, theirs] = await Promise.all([this.friendsOf(session.uuid), this.friendsOf(otherUuid)]);
    await Promise.all([
      this.put(KEY.friends(session.uuid), ours.filter((id) => id !== otherUuid)),
      this.put(KEY.friends(otherUuid), theirs.filter((id) => id !== session.uuid))
    ]);
    return { ok: true, message: "Friend removed." };
  }

  /** Friends with live status, plus incoming requests. */
  async friendList(session) {
    const [ids, requests] = await Promise.all([
      this.friendsOf(session.uuid),
      this.get(KEY.requests(session.uuid), [])
    ]);
    const now = this.now();
    const friends = [];
    for (const id of ids) {
      const user = await this.get(KEY.user(id), null);
      if (!user) continue;
      const online = now - (user.lastSeen || 0) < ONLINE_WINDOW_MS;
      friends.push({
        uuid: id,
        name: user.name,
        online,
        status: online ? (user.status || "online") : "offline",
        // Only shared when the friend opted in via their heartbeat.
        server: user.shareServer ? user.server || "" : ""
      });
    }
    friends.sort((a, b) => (b.online - a.online) || a.name.localeCompare(b.name));
    return { ok: true, friends, requests };
  }

  /**
   * Heartbeat. [server] is only stored when the player opted into sharing it - presence
   * deliberately hashes server addresses, so joining a friend is strictly opt-in.
   */
  async heartbeat(session, { server = "", shareServer = false, status = "online" } = {}) {
    const user = await this.get(KEY.user(session.uuid), {});
    // Availability travels with the heartbeat so idle/do-not-disturb reach friends
    // instead of everyone simply reading as "online".
    const availability = ["online", "idle", "dnd", "invisible"].includes(status) ? status : "online";
    await this.put(KEY.user(session.uuid), {
      ...user,
      uuid: session.uuid,
      name: session.name,
      // Invisible must not merely relabel you - it has to actually read as offline, so the
      // heartbeat is not recorded as a sighting at all.
      lastSeen: availability === "invisible" ? 0 : this.now(),
      status: availability,
      shareServer: Boolean(shareServer),
      server: shareServer ? String(server || "").slice(0, 100) : ""
    });

    // Hand over and clear any message notices, so each one is announced once.
    const inbox = await this.get(KEY.inbox(session.uuid), []);
    if (inbox.length) await this.put(KEY.inbox(session.uuid), []);

    const roster = await this.friendList(session);
    return { ...roster, inbox };
  }

  // ------------------------------------------------------------------ moderation

  async block(session, targetUuid) {
    const blocks = await this.blocksOf(session.uuid);
    if (!blocks.includes(targetUuid)) {
      if (blocks.length >= MAX_BLOCKS) return { ok: false, message: "Block list is full." };
      blocks.push(targetUuid);
      await this.put(KEY.blocks(session.uuid), blocks);
    }
    // Blocking implies unfriending, and drops any pending request from them.
    await this.removeFriend(session, targetUuid);
    const requests = await this.get(KEY.requests(session.uuid), []);
    await this.put(KEY.requests(session.uuid), requests.filter((entry) => entry.from !== targetUuid));
    return { ok: true, message: "Player blocked." };
  }

  /**
   * Who you have blocked, with names where we know them. Someone you blocked before they
   * ever signed in has no stored name, so their id is shown instead - still unblockable.
   */
  async blockedList(session) {
    const ids = await this.blocksOf(session.uuid);
    const blocked = [];
    for (const id of ids) {
      const user = await this.get(KEY.user(id), null);
      blocked.push({ uuid: id, name: user?.name || id.slice(0, 8) });
    }
    blocked.sort((a, b) => a.name.localeCompare(b.name));
    return { ok: true, blocked };
  }

  async unblock(session, targetUuid) {
    const blocks = await this.blocksOf(session.uuid);
    await this.put(KEY.blocks(session.uuid), blocks.filter((id) => id !== targetUuid));
    return { ok: true, message: "Player unblocked." };
  }

  async report(session, targetUuid, reason) {
    const id = crypto.randomUUID();
    // Snapshot recent messages so a report can be judged after the fact.
    const messages = await this.get(KEY.conversation(session.uuid, targetUuid), []);
    await this.put(KEY.report(id), {
      id,
      by: session.uuid,
      byName: session.name,
      target: targetUuid,
      reason: cleanText(reason).slice(0, 300),
      at: this.now(),
      recent: messages.slice(-20)
    });
    return { ok: true, message: "Report submitted. Thanks - staff will review it." };
  }

  // ------------------------------------------------------------------ direct messages

  async sendMessage(session, toUuid, text) {
    const body = cleanText(text);
    if (!body) return { ok: false, message: "Message is empty." };

    const friends = await this.friendsOf(session.uuid);
    // Friends-only messaging: without this anyone who learned a UUID could DM anyone.
    if (!friends.includes(toUuid)) return { ok: false, message: "You can only message friends." };
    if (await this.isBlockedEitherWay(session.uuid, toUuid)) {
      return { ok: false, message: "You cannot message that player." };
    }

    const key = KEY.conversation(session.uuid, toUuid);
    const messages = await this.get(key, []);
    const message = {
      id: crypto.randomUUID(),
      from: session.uuid,
      fromName: session.name,
      to: toUuid,
      text: body,
      at: this.now()
    };
    messages.push(message);
    // Ring-buffer the history so one conversation cannot grow without bound.
    await this.put(key, messages.slice(-MAX_MESSAGES_PER_CONVERSATION));

    // Queue a notice for the recipient. Kept separate from history so their client can
    // learn about a message without polling every conversation it has, and drained on
    // their next heartbeat so a notification is shown exactly once.
    const inbox = await this.get(KEY.inbox(toUuid), []);
    inbox.push({ from: session.uuid, fromName: session.name, text: body.slice(0, 120), at: message.at });
    await this.put(KEY.inbox(toUuid), inbox.slice(-20));

    return { ok: true, message: "Sent.", sent: message };
  }

  async history(session, otherUuid, limit = 50) {
    const messages = await this.get(KEY.conversation(session.uuid, otherUuid), []);
    const capped = Math.max(1, Math.min(200, Number(limit) || 50));
    return { ok: true, messages: messages.slice(-capped) };
  }
}

/** Durable Object entry point. Routes /social/* onto {@link SocialStore}. */
export class Social {
  constructor(state, env) {
    this.state = state;
    this.env = env;
    // Sign-in verifies Mojang-signed player certificates offline, so no Mojang call is
    // made from here at all - which is what makes this work on Cloudflare.
    this.store = new SocialStore(state.storage);
    /** uuid -> Set<WebSocket>, for pushing messages to players who are connected. */
    this.sockets = new Map();
    /** ip -> number[] of recent auth attempt timestamps. See rateLimited(). */
    this.authHits = new Map();
  }

  /**
   * Sliding-window limit on the sign-in endpoints.
   *
   * /auth/begin mints a nonce and /auth/complete runs two RSA verifications, so an
   * unthrottled caller can burn Worker CPU and farm nonces for free. Everything
   * routes through the single "global" Durable Object, so an in-memory counter here
   * sees every attempt and needs no storage writes.
   */
  rateLimited(request) {
    const ip = request.headers.get("cf-connecting-ip") || "unknown";
    const now = Date.now();
    const cutoff = now - AUTH_WINDOW_MS;

    const hits = (this.authHits.get(ip) || []).filter((t) => t > cutoff);
    hits.push(now);
    this.authHits.set(ip, hits);

    // Drop idle entries so one DO instance cannot accumulate every IP it ever saw.
    if (this.authHits.size > 5000) {
      for (const [key, times] of this.authHits) {
        if (!times.some((t) => t > cutoff)) this.authHits.delete(key);
      }
    }

    return hits.length > AUTH_MAX_PER_WINDOW;
  }

  push(uuid, payload) {
    const set = this.sockets.get(uuid);
    if (!set) return;
    const text = JSON.stringify(payload);
    for (const socket of set) {
      try { socket.send(text); } catch {}
    }
  }

  async fetch(request) {
    const url = new URL(request.url);
    const route = url.pathname.replace(/^\/social/, "");

    if (route === "/auth/begin" || route === "/auth/complete") {
      if (this.rateLimited(request)) {
        return json(
          { ok: false, message: "Too many sign-in attempts. Wait a minute and try again." },
          429
        );
      }
    }

    if (route === "/auth/begin") return json({ ok: true, ...(await this.store.beginAuth()) });

    let body = {};
    if (request.method === "POST") body = await request.json().catch(() => ({}));

    if (route === "/auth/complete") {
      const result = await this.store.completeAuth(String(body.name || ""), {
        uuid: String(body.uuid || ""),
        publicKey: String(body.publicKey || ""),
        publicKeySignature: String(body.publicKeySignature || ""),
        expiresAt: String(body.expiresAt || ""),
        nonce: String(body.nonce || body.serverId || ""),
        nonceSignature: String(body.nonceSignature || "")
      });
      return json(result, result.ok ? 200 : 401);
    }

    // Everything past this point requires a verified session.
    const token = (request.headers.get("Authorization") || "").replace(/^Bearer\s+/i, "") || String(body.token || "");
    const session = await this.store.sessionFor(token);
    if (!session) return fail("Sign in again.", 401);

    const target = normalizeUuid(body.uuid);

    switch (route) {
      // Lets the worker resolve a session to a verified UUID (used for tester builds).
      case "/whoami":
        return json({ ok: true, uuid: session.uuid, name: session.name });
      case "/friends":
        return json(await this.store.friendList(session));
      case "/friends/request":
        return json(await this.store.sendFriendRequest(session, String(body.name || ""), String(body.uuid || "")));
      case "/friends/accept": {
        const result = await this.store.acceptFriendRequest(session, target);
        if (result.ok) this.push(target, { type: "friend-added", uuid: session.uuid, name: session.name });
        return json(result);
      }
      case "/friends/decline":
        return json(await this.store.declineFriendRequest(session, target));
      case "/friends/remove":
        return json(await this.store.removeFriend(session, target));
      case "/presence":
        return json(await this.store.heartbeat(session, {
          server: String(body.server || ""),
          shareServer: body.shareServer === true,
          status: String(body.status || "online")
        }));
      case "/blocked":
        return json(await this.store.blockedList(session));
      case "/block":
        return json(await this.store.block(session, target));
      case "/unblock":
        return json(await this.store.unblock(session, target));
      case "/report":
        return json(await this.store.report(session, target, String(body.reason || "")));
      case "/dm/send": {
        const result = await this.store.sendMessage(session, target, String(body.text || ""));
        if (result.ok) this.push(target, { type: "dm", message: result.sent });
        return json(result);
      }
      case "/dm/history":
        return json(await this.store.history(session, target, body.limit));
      case "/ws": {
        if (request.headers.get("Upgrade") !== "websocket") return fail("Expected a WebSocket upgrade.", 426);
        const pair = new WebSocketPair();
        const [client, server] = Object.values(pair);
        server.accept();
        if (!this.sockets.has(session.uuid)) this.sockets.set(session.uuid, new Set());
        this.sockets.get(session.uuid).add(server);
        const drop = () => {
          const set = this.sockets.get(session.uuid);
          if (!set) return;
          set.delete(server);
          if (!set.size) this.sockets.delete(session.uuid);
        };
        server.addEventListener("close", drop);
        server.addEventListener("error", drop);
        return new Response(null, { status: 101, webSocket: client });
      }
      default:
        return fail("Unknown social route.", 404);
    }
  }
}

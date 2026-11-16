/**
 * Who is allowed to wear which cosmetic.
 *
 * Most River cosmetics are free to everyone and never come near this. A gated one - a
 * cape made for a creator, given to their viewers - is claimed with a code that creator
 * hands out, and the claim is recorded here against the player's Minecraft UUID.
 *
 * Why a code rather than "whoever used the referral link". A referral link counts a click
 * and then serves the same installer to everybody; two people following it get an
 * identical file, so an install cannot be traced back to the link that produced it.
 * Making it traceable would mean a per-download build or the launcher reporting where it
 * came from. A code the creator gives out reaches the same people without either.
 *
 * What this can and cannot enforce. The texture ships inside every copy of the mod, so
 * somebody editing their own config can put an unowned cape on their own screen and there
 * is no fixing that client-side. What it does control is what everyone else sees: the
 * presence roster is the only route by which one player learns another's cape, and it
 * drops any gated cape claimed by an account that has not redeemed it. So an unentitled
 * cape is visible to exactly one person, which is the part that matters.
 *
 * Storage-backed rather than in-memory: a grant is permanent and has to survive eviction.
 */

import { HALLOWEEN_EVENT } from "./events.js";

/** Cosmetics that require a redemption. Anything not listed is free to everyone. */
export const GATED_COSMETICS = new Set(["axie", "casaeullo", "galaxy"]);

/*
  River Plus: an optional monthly subscription. It only ever adds cosmetics and early
  access, never anything that changes how the game plays. A record is
  `plus:<uuid> = { until, since, note }` and Plus is active while `until` is in the future;
  a payment (or a manual grant) pushes `until` forward. Plus capes are owned by whoever has
  Plus right now, so they come and go with the subscription.
*/
export const PLUS_COSMETICS = new Set(["plus", "gilded", "royal", "prism"]);
export const DAY_MS = 86_400_000;

export function plusActive(record, now = Date.now()) {
  return Boolean(record && Number(record.until) > now);
}

/**
 * The gated set at a given moment. The Halloween cape is free to everyone until the
 * Halloween event is over, and from then on only to the players who earned it.
 */
export function gatedCosmetics(now = Date.now()) {
  const gated = new Set([...GATED_COSMETICS, ...PLUS_COSMETICS]);
  if (now >= HALLOWEEN_EVENT.endsAt) gated.add(HALLOWEEN_EVENT.cosmetic);
  return gated;
}

/**
 * Gives a cosmetic to a player for good, from inside the worker (an event reward). True
 * once it is stored. There is no public route to this: only the worker's own code reaches it.
 */
export async function grantCosmetic(env, uuid, cosmetic) {
  if (!env?.COSMETICS_DO) return false;
  try {
    const stub = env.COSMETICS_DO.get(env.COSMETICS_DO.idFromName("global"));
    const res = await stub.fetch("https://cosmetics/grant", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ uuid, cosmetic })
    });
    return (await res.json())?.ok === true;
  } catch {
    return false;
  }
}

/**
 * Which of these players have River Plus right now, as dashless uuids. Asks in batches of
 * 64 (the batch route's cap). Fails closed: no answer, no badge.
 */
export async function plusOf(env, uuids) {
  const result = new Set();
  if (!env?.COSMETICS_DO || !uuids.length) return result;
  try {
    const stub = env.COSMETICS_DO.get(env.COSMETICS_DO.idFromName("global"));
    for (let i = 0; i < uuids.length; i += 64) {
      const res = await stub.fetch("https://cosmetics/plus/batch", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ uuids: uuids.slice(i, i + 64) })
      });
      for (const uuid of (await res.json())?.plus ?? []) result.add(uuid);
    }
  } catch {
    // Unreachable store: nobody shows as Plus until it answers again.
  }
  return result;
}

/** Codes and cosmetic ids are storage keys and go into JSON, so keep the charset tight. */
export function normalizeCode(raw) {
  return String(raw || "").toUpperCase().replace(/[^A-Z0-9]/g, "").slice(0, 24);
}

export function normalizeCosmetic(raw) {
  return String(raw || "").toLowerCase().replace(/[^a-z0-9_-]/g, "").slice(0, 32);
}

/** A Minecraft UUID, with or without dashes. */
export function normalizeUuid(raw) {
  return String(raw || "").toLowerCase().replace(/[^0-9a-f-]/g, "").slice(0, 48);
}

/** The same UUID without dashes: River Plus records are keyed this way so both spellings match. */
export function compactUuid(raw) {
  return normalizeUuid(raw).replace(/-/g, "");
}

/*
  Server capes: a creator's cape whose art and name live on the server, not in the mod. The
  cosmetic id is "sc-" plus a random token. The token is only ever handed to the one player
  who redeemed the code, and it is kept out of /owned (which anyone can ask for), so nobody
  can learn a server cape exists without the code.
*/
export const SERVER_CAPE_PREFIX = "sc-";
export const SERVER_CAPE_PNG_CHARS = 120_000; // base64 of a ~90 KB PNG, under the 128 KB storage value cap
const SERVER_CAPE_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

export function isServerCape(cosmetic) {
  return String(cosmetic || "").startsWith(SERVER_CAPE_PREFIX);
}

export function newServerCapeToken() {
  const bytes = crypto.getRandomValues(new Uint8Array(12));
  return [...bytes].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function randomCode(length = 12) {
  const bytes = crypto.getRandomValues(new Uint8Array(length));
  return [...bytes].map((b) => SERVER_CAPE_CODE_ALPHABET[b % SERVER_CAPE_CODE_ALPHABET.length]).join("");
}

const REDEEM_WINDOW_MS = 10 * 60 * 1000;
const REDEEM_ATTEMPTS = 20;

export class Cosmetics {
  constructor(state) {
    this.state = state;
  }

  async ownedBy(uuid) {
    return (await this.state.storage.get(`own:${uuid}`)) || [];
  }

  async plusRecord(uuid) {
    return (await this.state.storage.get(`plus:${compactUuid(uuid)}`)) || null;
  }

  /** What a player may wear: what they redeemed or earned, plus the Plus capes while Plus is on. */
  async wearable(uuid, now = Date.now()) {
    const owned = await this.ownedBy(uuid);
    return plusActive(await this.plusRecord(uuid), now) ? [...new Set([...owned, ...PLUS_COSMETICS])] : owned;
  }

  async checkRedeemLimit(visitor) {
    // The Worker supplies the address only to this DO. Persist a salted digest,
    // never the address itself; the salt stays in DO storage across evictions.
    const salt = await this.state.storage.transaction(async (txn) => {
      let value = await txn.get("redeem-rate-salt");
      if (!value) {
        value = crypto.randomUUID();
        await txn.put("redeem-rate-salt", value);
      }
      return value;
    });
    const bytes = new TextEncoder().encode(`${salt}:${visitor || "unknown"}`);
    const digest = await crypto.subtle.digest("SHA-256", bytes);
    const key = `redeem-rate:${[...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("")}`;
    const now = Date.now();
    return this.state.storage.transaction(async (txn) => {
      const previous = await txn.get(key);
      const bucket = previous && now - previous.startedAt < REDEEM_WINDOW_MS
        ? previous
        : { startedAt: now, attempts: 0 };
      if (bucket.attempts >= REDEEM_ATTEMPTS) {
        return Math.max(1, Math.ceil((REDEEM_WINDOW_MS - (now - bucket.startedAt)) / 1000));
      }
      bucket.attempts++;
      await txn.put(key, bucket);
      return 0;
    });
  }

  async fetch(request) {
    const url = new URL(request.url);
    const payload = request.method === "POST" ? await request.json().catch(() => ({})) : {};

    /*
      Redeem a code. Codes are deliberately multi-use: a creator hands one code to a whole
      audience, so a single-use code would mean minting thousands and is not what this is
      for. `uses` is recorded so a partnership can be judged on how many people actually
      claimed the cape, which is a far better signal than the click count.
    */
    if (url.pathname === "/redeem") {
      const code = normalizeCode(payload?.code);
      const uuid = normalizeUuid(payload?.uuid);
      if (!code || !uuid) return Response.json({ ok: false, message: "Enter a code." }, { status: 400 });

      const retryAfter = await this.checkRedeemLimit(request.headers.get("X-River-Visitor"));
      if (retryAfter) return Response.json({ ok: false, message: "Too many code attempts. Try again later." }, {
        status: 429,
        headers: { "Retry-After": String(retryAfter) }
      });

      const record = await this.state.storage.get(`code:${code}`);
      if (!record) return Response.json({ ok: false, message: "That code does not exist." }, { status: 404 });
      if (record.revoked) return Response.json({ ok: false, message: "That code is no longer active." }, { status: 410 });

      if (isServerCape(record.cosmetic)) {
        const token = record.cosmetic.slice(SERVER_CAPE_PREFIX.length);
        const serverOwned = (await this.state.storage.get(`sown:${uuid}`)) || [];
        const already = serverOwned.includes(token);
        if (!already) {
          serverOwned.push(token);
          await this.state.storage.put(`sown:${uuid}`, serverOwned);
        }
        record.uses = (record.uses || 0) + (already ? 0 : 1);
        if (!already) record.lastUsedAt = Date.now();
        await this.state.storage.put(`code:${code}`, record);
        const cape = await this.state.storage.get(`cape:${token}`);
        return Response.json({ ok: true, already, token, name: cape?.name || "" });
      }

      const owned = await this.ownedBy(uuid);
      if (owned.includes(record.cosmetic)) {
        // Not an error: someone re-entering a code they already used should be told they
        // have it, not told they did something wrong.
        return Response.json({ ok: true, already: true, cosmetic: record.cosmetic });
      }

      owned.push(record.cosmetic);
      record.uses = (record.uses || 0) + 1;
      record.lastUsedAt = Date.now();
      await this.state.storage.put(`own:${uuid}`, owned);
      await this.state.storage.put(`code:${code}`, record);
      return Response.json({ ok: true, already: false, cosmetic: record.cosmetic });
    }

    if (url.pathname === "/owned") {
      const uuid = normalizeUuid(url.searchParams.get("uuid"));
      if (!uuid) return Response.json({ ok: false, owned: [] }, { status: 400 });
      return Response.json({ ok: true, owned: await this.wearable(uuid) });
    }

    /** An event reward. Reached only through grantCosmetic: the worker never routes here. */
    if (url.pathname === "/grant") {
      const uuid = normalizeUuid(payload?.uuid);
      const cosmetic = normalizeCosmetic(payload?.cosmetic);
      if (!uuid || !cosmetic) return Response.json({ ok: false }, { status: 400 });
      const owned = await this.ownedBy(uuid);
      if (!owned.includes(cosmetic)) {
        owned.push(cosmetic);
        await this.state.storage.put(`own:${uuid}`, owned);
      }
      return Response.json({ ok: true });
    }

    /** Bulk check used by the presence roster, which sees many players at once. */
    if (url.pathname === "/entitled") {
      const uuids = Array.isArray(payload?.uuids) ? payload.uuids.slice(0, 64).map(normalizeUuid) : [];
      const cosmetic = normalizeCosmetic(payload?.cosmetic);
      const allowed = [];
      for (const uuid of uuids) {
        if (!uuid) continue;
        if ((await this.wearable(uuid)).includes(cosmetic)) allowed.push(uuid);
      }
      return Response.json({ ok: true, allowed });
    }

    /** Whether one player has River Plus right now. Public: it is what their badge shows anyway. */
    if (url.pathname === "/plus/status") {
      const uuid = normalizeUuid(url.searchParams.get("uuid"));
      if (!uuid) return Response.json({ ok: false, plus: false }, { status: 400 });
      const record = await this.plusRecord(uuid);
      const plus = plusActive(record);
      return Response.json({ ok: true, plus, until: plus ? record.until : 0 });
    }

    /** Bulk check for the presence roster: which of these players have Plus. */
    if (url.pathname === "/plus/batch") {
      const uuids = Array.isArray(payload?.uuids) ? payload.uuids.slice(0, 64).map(compactUuid) : [];
      const now = Date.now();
      const plus = [];
      for (const uuid of uuids) {
        if (uuid && plusActive(await this.plusRecord(uuid), now)) plus.push(uuid);
      }
      return Response.json({ ok: true, plus });
    }

    /** A server cape's art and name, by token. The token is the secret: only redeemers have it. */
    if (url.pathname === "/cape") {
      const token = String(url.searchParams.get("token") || "").toLowerCase().replace(/[^a-f0-9]/g, "");
      if (token.length !== 24) return Response.json({ ok: false }, { status: 400 });
      const cape = await this.state.storage.get(`cape:${token}`);
      if (!cape) return Response.json({ ok: false }, { status: 404 });
      return Response.json({ ok: true, name: cape.name, png: cape.png });
    }

    // ---------------------------------------------------------------- admin

    /*
      Creates a server cape: the art, its name and a code that unlocks it. The code is made
      here unless one is given. Admin-only, like every other code.
    */
    if (url.pathname === "/server-cape/create") {
      const name = String(payload?.name || "").trim();
      const png = String(payload?.png || "");
      if (!/^[A-Za-z0-9 -]{1,24}$/.test(name)) return Response.json({ ok: false, message: "Name: 1-24 letters, digits, spaces or dashes." }, { status: 400 });
      if (!png.startsWith("iVBORw0KGgo") || png.length > SERVER_CAPE_PNG_CHARS) {
        return Response.json({ ok: false, message: "Send a PNG, under about 90 KB." }, { status: 400 });
      }
      const token = newServerCapeToken();
      let code = normalizeCode(payload?.code);
      if (code && code.length < 10) return Response.json({ ok: false, message: "A custom code needs 10 or more letters and digits." }, { status: 400 });
      if (!code) code = randomCode();
      if (await this.state.storage.get(`code:${code}`)) return Response.json({ ok: false, message: "That code already exists." }, { status: 409 });
      await this.state.storage.put(`cape:${token}`, { name, png, createdAt: Date.now() });
      await this.state.storage.put(`code:${code}`, {
        code,
        cosmetic: SERVER_CAPE_PREFIX + token,
        note: String(payload?.note || "").slice(0, 120),
        createdAt: Date.now(),
        uses: 0,
        lastUsedAt: null,
        revoked: false
      });
      return Response.json({ ok: true, token, code, name });
    }

    if (url.pathname === "/codes/create") {
      const code = normalizeCode(payload?.code);
      const cosmetic = normalizeCosmetic(payload?.cosmetic);
      if (!code || !cosmetic) return Response.json({ ok: false, message: "Need a code and a cosmetic." }, { status: 400 });
      const existing = await this.state.storage.get(`code:${code}`);
      /*
        `resetUses` zeroes the count without touching who already owns the cosmetic. The
        count is the number of people who claimed a code, which is the honest measure of
        whether a partnership did anything, and testing a code inflates it: a dev client
        signs in under a throwaway profile, so every trial run looks like another person.
        Being able to start the count from zero on the day the code is handed out is worth
        more than preserving a figure that is mostly rehearsal.
      */
      const resetUses = payload?.resetUses === true;
      await this.state.storage.put(`code:${code}`, {
        code,
        cosmetic,
        note: String(payload?.note || "").slice(0, 120),
        createdAt: existing?.createdAt ?? Date.now(),
        uses: resetUses ? 0 : (existing?.uses ?? 0),
        lastUsedAt: resetUses ? null : (existing?.lastUsedAt ?? null),
        revoked: false
      });
      return Response.json({ ok: true, code, cosmetic });
    }

    /*
      Revoking stops the code working from now on. Grants already made are left alone: the
      people who claimed it did nothing wrong, and taking a cosmetic back off them because
      a partnership ended is not the behaviour anyone would want.
    */
    if (url.pathname === "/codes/revoke") {
      const code = normalizeCode(payload?.code);
      const record = await this.state.storage.get(`code:${code}`);
      if (!record) return Response.json({ ok: false }, { status: 404 });
      record.revoked = true;
      await this.state.storage.put(`code:${code}`, record);
      return Response.json({ ok: true, code });
    }

    /*
      Granting Plus adds days on top of whatever is left, so paying early never loses time.
      Payments will call this same path once they exist; until then it is how testers and
      early supporters get Plus.
    */
    if (url.pathname === "/plus/grant") {
      const uuid = compactUuid(payload?.uuid);
      const days = Math.floor(Number(payload?.days));
      if (!uuid || !(days > 0 && days <= 3660)) return Response.json({ ok: false, message: "Need a uuid and 1-3660 days." }, { status: 400 });
      const now = Date.now();
      const current = await this.plusRecord(uuid);
      const from = plusActive(current, now) ? Number(current.until) : now;
      const record = {
        until: from + days * DAY_MS,
        since: plusActive(current, now) ? current.since : now,
        note: String(payload?.note ?? current?.note ?? "").slice(0, 120)
      };
      await this.state.storage.put(`plus:${uuid}`, record);
      return Response.json({ ok: true, uuid, ...record });
    }

    /*
      A Tebex payment event, already checked by the worker. Each event id is handled once, so
      Tebex retrying a call never stacks a second month. A payment with no Minecraft account
      on it is kept for a person to sort out by hand.
    */
    if (url.pathname === "/plus/tebex") {
      const eventId = String(payload?.eventId || "").slice(0, 80);
      if (!eventId) return Response.json({ ok: false }, { status: 400 });
      const seenKey = `tebex:${eventId}`;
      if (await this.state.storage.get(seenKey)) return Response.json({ ok: true, duplicate: true });
      const uuid = compactUuid(payload?.uuid);
      const now = Date.now();
      let outcome = "ignored";
      if (payload?.action === "grant" && uuid) {
        const days = Math.min(Math.max(Math.floor(Number(payload?.days)) || 0, 1), 400);
        // A payment means "Plus for the next month from now", not "a month more": Tebex can send
        // both a payment and a renewal for the same charge, and that must not add two months.
        const current = await this.plusRecord(uuid);
        await this.state.storage.put(`plus:${uuid}`, {
          until: Math.max(plusActive(current, now) ? Number(current.until) : 0, now + days * DAY_MS),
          since: plusActive(current, now) ? current.since : now,
          note: "tebex"
        });
        outcome = "granted";
      } else if (payload?.action === "revoke" && uuid) {
        await this.state.storage.delete(`plus:${uuid}`);
        outcome = "revoked";
      } else if (payload?.action !== "none") {
        outcome = "no-account";
      }
      await this.state.storage.put(seenKey, { at: now, type: String(payload?.type || "").slice(0, 60), uuid, outcome });
      return Response.json({ ok: true, outcome });
    }

    if (url.pathname === "/plus/revoke") {
      const uuid = compactUuid(payload?.uuid);
      if (!uuid) return Response.json({ ok: false }, { status: 400 });
      await this.state.storage.delete(`plus:${uuid}`);
      return Response.json({ ok: true, uuid });
    }

    if (url.pathname === "/plus/list") {
      const entries = await this.state.storage.list({ prefix: "plus:" });
      const now = Date.now();
      const members = [...entries.entries()].map(([key, value]) => ({
        uuid: key.slice("plus:".length), ...value, active: plusActive(value, now)
      })).sort((a, b) => (b.until || 0) - (a.until || 0));
      return Response.json({ ok: true, members });
    }

    if (url.pathname === "/codes") {
      const entries = await this.state.storage.list({ prefix: "code:" });
      const codes = [...entries.values()].sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0));
      return Response.json({ ok: true, codes });
    }

    return Response.json({ ok: false }, { status: 404 });
  }
}

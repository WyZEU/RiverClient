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

/** Cosmetics that require a redemption. Anything not listed is free to everyone. */
export const GATED_COSMETICS = new Set(["axie"]);

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

export class Cosmetics {
  constructor(state) {
    this.state = state;
  }

  async ownedBy(uuid) {
    return (await this.state.storage.get(`own:${uuid}`)) || [];
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

      const record = await this.state.storage.get(`code:${code}`);
      if (!record) return Response.json({ ok: false, message: "That code does not exist." }, { status: 404 });
      if (record.revoked) return Response.json({ ok: false, message: "That code is no longer active." }, { status: 410 });

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
      return Response.json({ ok: true, owned: await this.ownedBy(uuid) });
    }

    /** Bulk check used by the presence roster, which sees many players at once. */
    if (url.pathname === "/entitled") {
      const uuids = Array.isArray(payload?.uuids) ? payload.uuids.slice(0, 64).map(normalizeUuid) : [];
      const cosmetic = normalizeCosmetic(payload?.cosmetic);
      const allowed = [];
      for (const uuid of uuids) {
        if (!uuid) continue;
        if ((await this.ownedBy(uuid)).includes(cosmetic)) allowed.push(uuid);
      }
      return Response.json({ ok: true, allowed });
    }

    // ---------------------------------------------------------------- admin

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

    if (url.pathname === "/codes") {
      const entries = await this.state.storage.list({ prefix: "code:" });
      const codes = [...entries.values()].sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0));
      return Response.json({ ok: true, codes });
    }

    return Response.json({ ok: false }, { status: 404 });
  }
}

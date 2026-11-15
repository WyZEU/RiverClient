/**
 * Per-creator referral counting.
 *
 * A creator gets a link of their own - updates.riverclient.xyz/r/<name> - which counts
 * the click and then sends the browser straight to the installer. The point is to be
 * able to answer "did this partnership do anything", which is otherwise guesswork.
 *
 * What this measures is clicks, not installs, and the two are not the same: someone can
 * click and never run the installer. Counting real installs would mean the launcher
 * reporting back where it came from, which is a much bigger thing to add to people's
 * machines for a number that is only used to rank partnerships. Clicks are honest about
 * what they are and cost the user nothing.
 *
 * Counters live in a Durable Object rather than KV deliberately. KV's free tier allows
 * about a thousand writes a day, which is the same cap that made the presence roster
 * unusable there - a single busy launch day would silently stop recording.
 */

/** Creator names become storage keys, so only a conservative character set is allowed. */
export function referralSlug(raw) {
  return String(raw || "")
    .toLowerCase()
    .replace(/[^a-z0-9_-]/g, "")
    .slice(0, 32);
}

const DAY_KEYS_KEPT = 60;

export class Referrals {
  constructor(state) {
    this.state = state;
  }

  async fetch(request) {
    const url = new URL(request.url);

    // Recorded through the DO so concurrent clicks queue instead of overwriting each
    // other: a read-modify-write against KV would lose counts under any real traffic.
    if (url.pathname === "/hit") {
      const slug = referralSlug(url.searchParams.get("slug"));
      if (!slug) return Response.json({ ok: false }, { status: 400 });

      const key = `ref:${slug}`;
      const now = Date.now();
      const day = new Date(now).toISOString().slice(0, 10);
      const record = (await this.state.storage.get(key)) || { total: 0, firstAt: now, days: {} };

      record.total += 1;
      record.lastAt = now;
      record.days[day] = (record.days[day] || 0) + 1;

      // Keep the history bounded; a creator link left up for years should not grow a
      // record forever when only recent weeks are ever looked at.
      const days = Object.keys(record.days).sort();
      while (days.length > DAY_KEYS_KEPT) delete record.days[days.shift()];

      await this.state.storage.put(key, record);
      return Response.json({ ok: true, slug, total: record.total });
    }

    // Removing a creator who is no longer a partner, so the list stays a list of people
    // River actually works with rather than an archive of everyone who ever had a link.
    if (url.pathname === "/forget") {
      const slug = referralSlug(url.searchParams.get("slug"));
      if (!slug) return Response.json({ ok: false }, { status: 400 });
      await this.state.storage.delete(`ref:${slug}`);
      return Response.json({ ok: true, slug });
    }

    if (url.pathname === "/stats") {
      const entries = await this.state.storage.list({ prefix: "ref:" });
      const creators = [];
      for (const [key, value] of entries) {
        creators.push({
          slug: key.slice("ref:".length),
          total: value.total || 0,
          firstAt: value.firstAt || null,
          lastAt: value.lastAt || null,
          last30: Object.entries(value.days || {})
            .sort(([a], [b]) => (a < b ? 1 : -1))
            .slice(0, 30)
            .reduce((out, [d, n]) => ({ ...out, [d]: n }), {})
        });
      }
      creators.sort((a, b) => b.total - a.total);
      return Response.json({ ok: true, creators });
    }

    return Response.json({ ok: false }, { status: 404 });
  }
}

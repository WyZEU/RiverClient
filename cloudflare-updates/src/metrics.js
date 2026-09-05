/**
 * Usage counting: downloads, update checks, launches and failed launches.
 *
 * This exists because the only number River had was presence, and presence answers a
 * much narrower question than it looks like it does. A presence ping carries a signed-in
 * Microsoft profile id, so it counts people who signed in and got as far as starting the
 * game. Someone who downloads River, opens it and never signs in is invisible to it, and
 * so is anyone whose launch fails. Reading "1 online" as "one user" was wrong in a way
 * that is impossible to notice from the number itself.
 *
 * Everything here is counted server-side from requests the launcher already makes, apart
 * from launches and failures, which the launcher reports. Nothing identifies a person:
 * see the note on identity below.
 *
 * Counters live in a Durable Object rather than KV for the same reason the presence
 * roster does - KV's free tier allows about a thousand writes a day, and a
 * read-modify-write against it loses counts whenever two requests overlap.
 */

const DAYS_KEPT = 90;

/*
  Version numbers are stripped out of a user agent before it is hashed.

  The launcher announces its own version in its user agent, so leaving them in meant
  every release renamed everybody. An install that followed 0.1.8.1 through 0.1.8.7 was
  counted as seven different people, each one credited to its country again, and the
  all time unique figures climbed every time a release shipped rather than when anyone
  new arrived. Browsers do the same thing on a slower clock. What survives still tells a
  launcher from a browser and one platform from another, which is all this needs to do.
*/
function normalizeUserAgent(raw) {
  return String(raw || "").replace(/[0-9]+(?:[._][0-9]+)*/g, "#");
}

/*
  Who counts as one person.

  A launcher sends an install id it generated once and keeps on disk, and when that is
  present it is the whole identity. It survives updates and a changing address, which is
  what the fallback below cannot do.

  A download comes from a browser, which has no such id, so those still fall back to the
  address and the normalised user agent. Both paths are hashed with a per deployment salt
  and truncated, and no raw value is ever stored - not the address, and not the install id
  either.

  What the fallback buys and what it does not: it is enough to stop one person refreshing
  the download page from reading as ten downloads, which is the whole point. It is not a
  reliable count of people. Two people behind one household address with the same browser
  collapse into one, one person on a laptop and a phone counts as two, and an address that
  changes overnight counts as two as well. The salt means the hashes cannot be matched
  against an address list even by someone holding the data.
*/
async function identity(request, salt) {
  const installed = (request.headers.get("X-River-Install") || "").trim().toLowerCase();
  const seed = /^[0-9a-f]{16,64}$/.test(installed)
    ? `${salt}:install:${installed}`
    : `${salt}:${request.headers.get("CF-Connecting-IP") || ""}:${normalizeUserAgent(request.headers.get("User-Agent"))}`;
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(seed));
  return [...new Uint8Array(digest)].slice(0, 12).map((b) => b.toString(16).padStart(2, "0")).join("");
}

/** The launcher announces itself as RiverClientLauncher/<version>, so no ping is needed. */
function launcherVersion(request) {
  const match = /RiverClientLauncher\/([0-9][0-9A-Za-z.+-]{0,23})/.exec(request.headers.get("User-Agent") || "");
  return match ? match[1] : "unknown";
}

function today(now) {
  return new Date(now).toISOString().slice(0, 10);
}

/** Keys are storage paths and go into JSON read by a browser, so keep the charset tight. */
function tag(raw, max = 24) {
  return String(raw || "").replace(/[^A-Za-z0-9._+-]/g, "").slice(0, max) || "unknown";
}

function bump(map, key) {
  if (!key) return;
  map[key] = (map[key] || 0) + 1;
}

const EMPTY_DAY = { installs: 0, installsUnique: 0, checks: 0, checksUnique: 0, launches: 0, failures: 0 };

/** Wrong admin keys allowed from one address before it is shut out, and for how long. */
const GATE_MAX_FAILURES = 8;
const GATE_WINDOW_MS = 15 * 60 * 1000;

export class Metrics {
  constructor(state, env) {
    this.state = state;
    this.env = env;
    /*
      Failed admin key attempts, per address, in memory only.

      Nothing about a failed guess is worth keeping on disk: it matters for a few minutes
      and then it does not. Being in memory also means an attacker cannot fill storage by
      guessing, and eviction only ever forgives attempts rather than blocking anyone.

      It lives on this object because every admin route already reaches it, and one shared
      counter is the point - a limiter per isolate would reset whenever traffic moved.
    */
    this.gateFailures = new Map();
  }

  /**
   * Records or checks a failed admin attempt. Returns whether the caller is shut out.
   *
   * [clear] wipes the record for that address, which the Worker passes after a key that
   * turned out to be correct: whoever just proved they hold the secret should not be
   * carrying a strike from the attempts before they found it.
   */
  gate(ip, failed, clear = false) {
    const now = Date.now();
    const key = String(ip || "unknown");

    if (clear) {
      this.gateFailures.delete(key);
      return false;
    }

    const recent = (this.gateFailures.get(key) || []).filter((t) => t > now - GATE_WINDOW_MS);

    if (failed) recent.push(now);
    if (recent.length) this.gateFailures.set(key, recent);
    else this.gateFailures.delete(key);

    // Drop addresses that have gone quiet, so one object cannot accumulate every address
    // that ever guessed wrong.
    if (this.gateFailures.size > 5000) {
      for (const [addr, times] of this.gateFailures) {
        if (!times.some((t) => t > now - GATE_WINDOW_MS)) this.gateFailures.delete(addr);
      }
    }

    return recent.length >= GATE_MAX_FAILURES;
  }

  async day(date) {
    return (await this.state.storage.get(`day:${date}`)) || {
      ...EMPTY_DAY,
      versions: {},
      countries: {},
      mc: {},
      reasons: {}
    };
  }

  async totals() {
    return (await this.state.storage.get("totals")) || {
      ...EMPTY_DAY,
      firstAt: null,
      versions: {},
      countries: {},
      mc: {},
      reasons: {},
      // Hour of day, UTC, counted across every day rather than per day: the question is
      // what time people play, which needs every Tuesday evening piled together. The
      // dashboard converts these to the reader's own clock.
      launchHours: Array(24).fill(0),
      checkHours: Array(24).fill(0)
    };
  }

  /*
    One marker per identity rather than one per identity per day. It carries the last day
    that identity was seen, which is what makes a repeat visit on the same day free and a
    return visit tomorrow countable, without keeping a set per day.
  */
  async firstSightings(kind, id, date, now) {
    if (!id) return { allTime: false, today: false };
    const key = `seen:${kind}:${id}`;
    const previous = await this.state.storage.get(key);
    const result = { allTime: !previous, today: !previous || previous.day !== date };
    await this.state.storage.put(key, {
      day: date,
      first: previous?.first ?? now,
      last: now,
      n: (previous?.n || 0) + 1
    });
    return result;
  }

  async record(kind, request, extra = {}) {
    const now = Date.now();
    const date = today(now);
    const hour = new Date(now).getUTCHours();
    const day = await this.day(date);
    const totals = await this.totals();
    if (!totals.firstAt) totals.firstAt = now;
    // Records written before these existed have no array to bump.
    if (!Array.isArray(totals.launchHours)) totals.launchHours = Array(24).fill(0);
    if (!Array.isArray(totals.checkHours)) totals.checkHours = Array(24).fill(0);

    // request.cf does not survive a stub call, so the worker forwards the country in
    // the body. The direct read is the fallback for a request that reaches this DO
    // without going through recordMetric.
    const country = tag(extra.country || request.cf?.country, 8);
    const version = tag(extra.launcherVersion || launcherVersion(request));

    const salt = this.env.METRICS_SALT || "river";
    const id = await identity(request, salt);

    /*
      Country counts people, not requests. Counting every request meant a launcher left
      open all day, polling for updates, buried every other country under its own - the
      chart answered "where does the traffic come from" when the question is "where are
      the people". A country is credited once, the first time an identity is ever seen,
      and never again no matter how much that install talks to the server afterwards.
    */
    const firstEverSeen = await this.firstSightings("geo", id, date, now);
    if (firstEverSeen.allTime) {
      bump(day.countries, country);
      bump(totals.countries, country);
    }

    if (kind === "install" || kind === "check") {
      const seen = await this.firstSightings(kind, id, date, now);
      const field = kind === "install" ? "installs" : "checks";
      const uniqueField = kind === "install" ? "installsUnique" : "checksUnique";

      day[field] = (day[field] || 0) + 1;
      totals[field] = (totals[field] || 0) + 1;
      if (seen.today) day[uniqueField] = (day[uniqueField] || 0) + 1;
      // All-time unique is a count of identities ever seen, so it only moves the first
      // time one appears. Deduplicating the daily figure again here would be wrong.
      if (seen.allTime) totals[uniqueField] = (totals[uniqueField] || 0) + 1;

      if (kind === "check") {
        bump(day.versions, version);
        bump(totals.versions, version);
        // A launcher polls while it is open, so this is roughly when River is running,
        // which is a broader signal than when the game is started.
        totals.checkHours[hour] += 1;
      }
    }

    if (kind === "launch" || kind === "failure") {
      const field = kind === "launch" ? "launches" : "failures";
      day[field] = (day[field] || 0) + 1;
      totals[field] = (totals[field] || 0) + 1;

      /*
        Successful launches only. Counting failures here as well made the version chart
        answer a different question than its label claimed: a version that fails to start
        ten times running would have led the list of what people play.
      */
      if (kind === "launch") {
        bump(day.mc, tag(extra.mcVersion, 16));
        bump(totals.mc, tag(extra.mcVersion, 16));
        totals.launchHours[hour] += 1;
      }
      bump(day.versions, version);
      bump(totals.versions, version);

      if (kind === "failure") {
        const reason = tag(extra.reason, 32);
        bump(day.reasons, reason);
        bump(totals.reasons, reason);
      }
    }

    await this.state.storage.put(`day:${date}`, day);
    await this.state.storage.put("totals", totals);
    return { ok: true };
  }

  /*
    Day records are bounded here rather than on every write: pruning is a list plus a
    delete, and paying that on a launch ping to remove a record that will still be there
    tomorrow is wasted work. The dashboard is the only caller and is read rarely.
  */
  async prune() {
    const entries = await this.state.storage.list({ prefix: "day:" });
    const keys = [...entries.keys()].sort();
    while (keys.length > DAYS_KEPT) {
      await this.state.storage.delete(keys.shift());
    }
  }

  async fetch(request) {
    const url = new URL(request.url);
    const payload = request.method === "POST" ? await request.json().catch(() => ({})) : {};

    if (url.pathname === "/gate") {
      return Response.json({
        lockedOut: this.gate(payload?.ip, payload?.failed === true, payload?.clear === true)
      });
    }

    if (url.pathname === "/record") {
      const kind = url.searchParams.get("kind");
      if (!["install", "check", "launch", "failure"].includes(kind)) {
        return Response.json({ ok: false }, { status: 400 });
      }
      return Response.json(await this.record(kind, request, payload));
    }

    /*
      Zeroes everything. Here because verifying the counters against the deployed Worker
      means putting real requests through them, and those hits would otherwise sit in the
      all-time totals forever. Gated behind the dashboard key in the Worker.
    */
    if (url.pathname === "/reset") {
      await this.state.storage.deleteAll();
      return Response.json({ ok: true });
    }

    /*
      Clears the country counts and the markers behind them, without touching downloads,
      launches or anything else. Country used to count requests and now counts people, so
      the figures either side of that change cannot be added together - and nuking every
      counter to fix one of them would throw away the history that is still correct.
    */
    if (url.pathname === "/countries/reset") {
      const totals = await this.totals();
      totals.countries = {};
      await this.state.storage.put("totals", totals);

      const days = await this.state.storage.list({ prefix: "day:" });
      for (const [key, value] of days) {
        await this.state.storage.put(key, { ...value, countries: {} });
      }

      // The geo markers decide who has already been counted, so they go too - otherwise
      // every existing install stays permanently uncounted under the new meaning.
      const markers = await this.state.storage.list({ prefix: "seen:geo:" });
      for (const key of markers.keys()) await this.state.storage.delete(key);

      return Response.json({ ok: true, cleared: markers.size });
    }

    if (url.pathname === "/summary") {
      await this.prune();
      const entries = await this.state.storage.list({ prefix: "day:" });
      const days = [...entries]
        .map(([key, value]) => ({ date: key.slice("day:".length), ...value }))
        .sort((a, b) => (a.date < b.date ? 1 : -1))
        .slice(0, 60);
      return Response.json({ ok: true, totals: await this.totals(), days });
    }

    return Response.json({ ok: false }, { status: 404 });
  }
}

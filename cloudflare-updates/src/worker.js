// CORS is scoped to the website, which is the only browser caller: it reads
// /latest.json, /presence/count and /downloads/. The launcher fetches from the
// Electron main process and the in-game client from Java, and neither enforces
// CORS, so narrowing this cannot break them. It does stop an arbitrary page from
// reading authenticated /social responses with a stolen session token.
const SITE_ORIGIN = "https://riverclient.xyz";

const securityHeaders = {
  // The custom domain is HTTPS-only; tell browsers never to try plaintext.
  "Strict-Transport-Security": "max-age=31536000; includeSubDomains",
  "X-Content-Type-Options": "nosniff",
  "X-Frame-Options": "DENY",
  "Referrer-Policy": "no-referrer",
  // Nothing here is a document, so no script/style/frame source is ever legitimate.
  "Content-Security-Policy": "default-src 'none'; frame-ancestors 'none'"
};

const DASHBOARD_ORIGIN = "https://river-stats.pages.dev";

const headers = {
  "Access-Control-Allow-Origin": SITE_ORIGIN,
  "Access-Control-Allow-Methods": "GET, HEAD, POST, OPTIONS",
  // Authorization is needed so social clients can present their session token.
  "Access-Control-Allow-Headers": "Content-Type, Authorization",
  "Cache-Control": "no-store",
  ...securityHeaders
};

export { Social } from "./social.js";

const discordInviteUrl = "https://discord.gg/neQzwBTvp3";
const publicUpdateBaseUrl = "https://updates.riverclient.xyz";

// A remote River user is considered "present" for this many ms after their last
// ping. KV's minimum expirationTtl is 60s, so entries are GC'd at 60s but filtered
// out of the roster sooner than that here.
const PRESENCE_FRESH_MS = 30_000;

/*
  Offered by the launcher on its presence socket and echoed back, which is what completes
  the handshake and doubles as the gate on the route. Separate from the update push
  protocol because the two sockets are different shapes: that one is an anonymous fanout
  to everybody, this one belongs to one signed-in player.
*/
const PRESENCE_PROTOCOL = "river.presence.v1";

/** Availability a player can broadcast. "invisible" is never stored - see /presence/status. */
const STATUS_VALUES = new Set(["online", "idle", "dnd", "invisible"]);

import { Referrals, referralSlug } from "./referrals.js";
import { Metrics } from "./metrics.js";
import { scrubCrashReport, crashReportId, MAX_CRASH_BYTES } from "./crash.js";
import { Cosmetics, GATED_COSMETICS, normalizeUuid } from "./cosmetics.js";
import { Broadcast, PUSH_PROTOCOL } from "./broadcast.js";

export { Referrals, Metrics, Cosmetics, Broadcast };

/*
  Counting must never be able to fail a request. Everything measured here is a side effect
  of something the user actually wanted - a download, an update check, a launch - and a
  bookkeeping error is not a reason to take that away from them. waitUntil also keeps the
  write off the response path, so nothing waits on the Durable Object.
*/
function recordMetric(env, ctx, kind, request, extra = {}) {
  if (!env.METRICS_DO) return;
  const task = (async () => {
    try {
      const stub = env.METRICS_DO.get(env.METRICS_DO.idFromName("global"));
      await stub.fetch(`https://metrics/record?kind=${kind}`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          // request.cf and the UA do not survive into the stub call, so the few fields
          // the DO reads are forwarded explicitly.
          "User-Agent": request.headers.get("User-Agent") || "",
          "CF-Connecting-IP": request.headers.get("CF-Connecting-IP") || "",
          // A launcher that knows its own install id sends it, and the DO counts by that
          // instead of by address. Absent for downloads, which come from a browser.
          "X-River-Install": request.headers.get("X-River-Install") || ""
        },
        body: JSON.stringify({ ...extra, country: request.cf?.country || "" })
      });
    } catch {}
  })();
  if (ctx?.waitUntil) ctx.waitUntil(task);
}

/** Only the launcher's own update poll is an update check; the website reads this too. */
function isLauncher(request) {
  return /RiverClientLauncher\//.test(request.headers.get("User-Agent") || "");
}

/**
 * Compares two secrets without leaking, through how long the comparison took, how much of
 * one matched.
 *
 * `a === b` on a string stops at the first differing character, so in principle the time
 * it takes reveals the length of the matching prefix, and a secret can be recovered one
 * character at a time rather than guessed whole. Over the public internet that measurement
 * is buried in network noise and this is close to theoretical, but the fix is four lines.
 *
 * Both sides are hashed first so the digests are always the same length, and the whole
 * digest is always walked - the loop cannot exit early even when it already knows.
 */
async function secretsMatch(a, b) {
  if (!a || !b) return false;
  const encoder = new TextEncoder();
  const [left, right] = await Promise.all([
    crypto.subtle.digest("SHA-256", encoder.encode(a)),
    crypto.subtle.digest("SHA-256", encoder.encode(b))
  ]);
  const x = new Uint8Array(left);
  const y = new Uint8Array(right);
  let diff = 0;
  for (let i = 0; i < x.length; i++) diff |= x[i] ^ y[i];
  return diff === 0;
}

/*
  Shared gate for the dashboard's routes. The dashboard is not on riverclient.xyz, so the
  site-wide CORS origin does not cover it; rather than widen that for everything, these
  routes answer to the dashboard's own origin and nothing else, so a page elsewhere cannot
  read them even holding the key.
*/
async function adminGate(request, env) {
  const origin = request.headers.get("Origin") || "";
  const allowed = origin === DASHBOARD_ORIGIN || origin.endsWith(".river-stats.pages.dev");
  const adminHeaders = {
    ...headers,
    "Access-Control-Allow-Origin": allowed ? origin : SITE_ORIGIN,
    "Vary": "Origin"
  };

  const key = (request.headers.get("Authorization") || "").replace(/^Bearer\s+/i, "").trim();
  const expected = env.REFERRAL_STATS_KEY || "";

  /*
    The key is checked before the rate limiter is consulted, and a correct key is never
    turned away.

    Doing it the other way round locks the owner out of their own dashboard: the limit is
    per address, so eight mistyped attempts from the machine that holds the real key shut
    that machine out for a quarter of an hour. Refusing a request that proved it holds the
    secret buys nothing anyway - the limiter exists to make wrong guesses expensive, and a
    right answer is not a guess.

    A correct key also clears the record, so a fumbled password manager followed by a
    correct paste leaves nothing behind.
  */
  const ok = await secretsMatch(key, expected);
  let lockedOut = false;

  if (env.METRICS_DO) {
    try {
      const stub = env.METRICS_DO.get(env.METRICS_DO.idFromName("global"));
      const probe = await stub.fetch("https://metrics/gate", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          ip: request.headers.get("CF-Connecting-IP") || "",
          failed: !ok,
          clear: ok
        })
      });
      lockedOut = !ok && (await probe.json())?.lockedOut === true;
    } catch {
      // A broken counter must not refuse anyone. Failing open here only costs rate
      // limiting; the key check above has already decided whether this caller is allowed.
    }
  }

  return { ok, lockedOut, headers: adminHeaders };
}

const latestManifest = {
  "name": "River Client",
  "version": "0.1.9.2",
  "minimumVersion": "0.1.7",
  "required": false,
  "publishedAt": "2026-09-11T16:16:47.560Z",
  "pageUrl": "https://riverclient.xyz/",
  "installerUrl": "https://updates.riverclient.xyz/downloads/River-Client-Setup.exe",
  "portableUrl": "https://updates.riverclient.xyz/downloads/releases/0.1.9.2/River-Client-Portable-0.1.9.2.exe",
  "packageUrl": "https://updates.riverclient.xyz/downloads/releases/0.1.9.2/River-Client-App-0.1.9.2.zip",
  "fileManifestUrl": "https://updates.riverclient.xyz/downloads/releases/0.1.9.2/file-manifest.json",
  "appFileManifestUrl": "https://updates.riverclient.xyz/downloads/releases/0.1.9.2/file-manifest.json",
  "appFileBaseUrl": "https://updates.riverclient.xyz/downloads/releases/0.1.9.2/app/",
  "fileCount": 91,
  "files": {
    "installer": {
      "name": "River-Client-Setup.exe",
      "size": 241325193,
      "sha256": "d4c87feb568d44351c5283bd75fe6855a227c03f386825682d218f9471eed571"
    },
    "portable": {
      "name": "River-Client-Portable-0.1.9.2.exe",
      "size": 281181635,
      "sha256": "13b845d5bc51df202f17bed1544639be5e8e4d13e2c33eb9159a122ce4e93687"
    },
    "package": {
      "name": "River-Client-App-0.1.9.2.zip",
      "size": 305539074,
      "sha256": "244020e1802302e63d4383e7a26081033444401ee594fbec93852516aad22993"
    },
    "fileManifest": {
      "name": "file-manifest.json",
      "size": 28607,
      "sha256": "153aadcb6bff0f640ae58e5d96c4f62b135f2c1c62d8a5e70e15669a4c094900",
      "count": 91
    }
  },
  "changelog": {
    "version": "0.1.9.2",
    "title": "River Client 0.1.9.2",
    "summary": "An All tab in the wardrobe menu, a new cape, and the launcher doesn't flash blank anymore.",
    "items": [
      "Added an All tab to the module menu, so you don't have to click through every category to find one mod.",
      "Added the 8-Ball cape.",
      "Fixed the launcher and updater windows sitting on a blank screen for a second when they open."
    ]
  },
  "notes": "An All tab in the wardrobe menu, a new cape, and the launcher doesn't flash blank anymore."
};

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    if (request.method === "OPTIONS") {
      // Answered here for every route, so the dashboard's origin has to be recognised at
      // this point - the admin route below never sees a preflight otherwise.
      const preflightOrigin = request.headers.get("Origin") || "";
      const dashboard = preflightOrigin === DASHBOARD_ORIGIN || preflightOrigin.endsWith(".river-stats.pages.dev");
      return new Response(null, {
        headers: dashboard
          ? { ...headers, "Access-Control-Allow-Origin": preflightOrigin, "Vary": "Origin" }
          : headers
      });
    }

    if (url.hostname === "discord.riverclient.xyz") {
      return Response.redirect(discordInviteUrl, 302);
    }

    /*
      Creator referral links. /r/<name> counts the click and sends the browser on to the
      installer, so a creator can put one link in a description and the partnership can
      be judged on something other than impressions. The redirect happens regardless of
      whether counting worked - a bookkeeping failure must never cost someone a download.
    */
    if (url.pathname.startsWith("/r/")) {
      const slug = referralSlug(url.pathname.slice("/r/".length));
      const target = latestManifest.installerUrl;
      if (!slug) return Response.redirect(target, 302);
      try {
        const stub = env.REFERRALS_DO.get(env.REFERRALS_DO.idFromName("referrals"));
        await stub.fetch(`https://referrals/hit?slug=${encodeURIComponent(slug)}`);
      } catch {}
      return Response.redirect(target, 302);
    }

    // Click counts per creator. Gated: it is not private data exactly, but which
    // partnerships are working is nobody else's business.
    if (url.pathname === "/referrals.json") {
      const key = (request.headers.get("Authorization") || "").replace(/^Bearer\s+/i, "").trim();
      const expected = env.REFERRAL_STATS_KEY || "";
      if (!expected || key !== expected) {
        return new Response("Unauthorized.", { status: 401, headers });
      }
      const stub = env.REFERRALS_DO.get(env.REFERRALS_DO.idFromName("referrals"));
      if (request.method === "DELETE") {
        const slug = referralSlug(url.searchParams.get("slug"));
        const gone = await stub.fetch(`https://referrals/forget?slug=${encodeURIComponent(slug)}`);
        return new Response(gone.body, { status: gone.status, headers: { ...headers, "Content-Type": "application/json" } });
      }
      const res = await stub.fetch("https://referrals/stats");
      return new Response(res.body, { status: res.status, headers: { ...headers, "Content-Type": "application/json" } });
    }

    /*
      Everything the private dashboard shows, behind the same key as the referral stats.
      One request rather than four, so the page cannot half-load and show a partial
      picture that looks like a drop in usage.
    */
    if (url.pathname === "/admin/overview.json") {
      /*
        The dashboard is not on riverclient.xyz, so the site-wide CORS origin does not
        cover it. Rather than widen that for everything, this one route answers to the
        dashboard's own origin and nothing else - a page on any other origin still cannot
        read this even if it somehow had the key.
      */
      const { ok: authorised, lockedOut, headers: adminHeaders } = await adminGate(request, env);
      if (request.method === "OPTIONS") return new Response(null, { headers: adminHeaders });
      if (!authorised) return new Response(lockedOut ? "Too many attempts." : "Unauthorized.", { status: lockedOut ? 429 : 401, headers: adminHeaders });

      const presenceStub = env.PRESENCE_DO.get(env.PRESENCE_DO.idFromName("global"));
      const referralStub = env.REFERRALS_DO.get(env.REFERRALS_DO.idFromName("referrals"));
      const [presence, referrals, metrics] = await Promise.all([
        presenceStub.fetch("https://presence/presence/history").then((r) => r.json()).catch(() => null),
        referralStub.fetch("https://referrals/stats").then((r) => r.json()).catch(() => null),
        env.METRICS_DO
          ? env.METRICS_DO.get(env.METRICS_DO.idFromName("global"))
              .fetch("https://metrics/summary").then((r) => r.json()).catch(() => null)
          : null
      ]);

      return Response.json({
        ok: true,
        generatedAt: Date.now(),
        release: {
          version: latestManifest.version,
          publishedAt: latestManifest.publishedAt,
          notes: latestManifest.notes,
          fileCount: latestManifest.fileCount,
          installerSize: latestManifest.files?.installer?.size ?? null
        },
        presence: presence || { online: null, days: [] },
        referrals: referrals?.creators || [],
        metrics: metrics?.ok ? { totals: metrics.totals, days: metrics.days } : null
      }, { headers: adminHeaders });
    }

    /*
      Fired by the publish script once the new manifest is actually live, which is what
      turns a release into a push. Announcing any earlier would tell a launcher to fetch
      a version the manifest does not yet describe.
    */
    if (url.pathname === "/admin/announce" && request.method === "POST") {
      const { ok: authorised, lockedOut, headers: adminHeaders } = await adminGate(request, env);
      if (!authorised) return new Response(lockedOut ? "Too many attempts." : "Unauthorized.", { status: lockedOut ? 429 : 401, headers: adminHeaders });
      if (!env.BROADCAST_DO) return Response.json({ ok: false, message: "No broadcast binding." }, { status: 503, headers: adminHeaders });
      const stub = env.BROADCAST_DO.get(env.BROADCAST_DO.idFromName("global"));
      const result = await stub.fetch(new Request("https://broadcast/announce", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: await request.text()
      }));
      return new Response(result.body, { status: result.status, headers: adminHeaders });
    }

    // How many launchers are holding a push socket open right now, for the dashboard.
    if (url.pathname === "/admin/broadcast.json") {
      const { ok: authorised, lockedOut, headers: adminHeaders } = await adminGate(request, env);
      if (request.method === "OPTIONS") return new Response(null, { headers: adminHeaders });
      if (!authorised) return new Response(lockedOut ? "Too many attempts." : "Unauthorized.", { status: lockedOut ? 429 : 401, headers: adminHeaders });
      if (!env.BROADCAST_DO) return Response.json({ ok: false, connected: 0 }, { headers: adminHeaders });
      const stub = env.BROADCAST_DO.get(env.BROADCAST_DO.idFromName("global"));
      const result = await stub.fetch(new Request("https://broadcast/status"));
      return new Response(result.body, { status: result.status, headers: adminHeaders });
    }

    // Zeroes the usage counters. Same key as the dashboard, and a POST so it cannot
    // happen by opening a URL. /admin/countries-reset clears only the country counts.
    if (
      (url.pathname === "/admin/metrics-reset" || url.pathname === "/admin/countries-reset")
      && request.method === "POST"
    ) {
      const { ok: authorised, lockedOut, headers: adminHeaders } = await adminGate(request, env);
      if (!authorised) return new Response(lockedOut ? "Too many attempts." : "Unauthorized.", { status: lockedOut ? 429 : 401, headers: adminHeaders });
      if (!env.METRICS_DO) return Response.json({ ok: false }, { status: 503, headers: adminHeaders });
      const stub = env.METRICS_DO.get(env.METRICS_DO.idFromName("global"));
      const target = url.pathname.endsWith("countries-reset") ? "/countries/reset" : "/reset";
      const result = await stub.fetch(`https://metrics${target}`, { method: "POST" });
      return new Response(result.body, { headers: adminHeaders });
    }

    /*
      Crash reports. Listed separately from the overview because a list of reports grows
      without bound and the overview is polled - the dashboard asks for these only when
      the crash tab is opened, and for a report's body only when one is expanded.
    */
    if (url.pathname === "/admin/crash-reports.json") {
      const { ok: authorised, lockedOut, headers: adminHeaders } = await adminGate(request, env);
      if (request.method === "OPTIONS") return new Response(null, { headers: adminHeaders });
      if (!authorised) return new Response(lockedOut ? "Too many attempts." : "Unauthorized.", { status: lockedOut ? 429 : 401, headers: adminHeaders });

      const id = url.searchParams.get("id");
      if (id) {
        // Ids are generated server-side, but this one arrived in a query string, so it
        // is checked against that shape rather than trusted into an object key.
        if (!/^[0-9]{14}-[0-9a-f]{8}$/.test(id)) {
          return Response.json({ ok: false }, { status: 400, headers: adminHeaders });
        }
        const object = await env.UPDATES.get(`crash-reports/${id}.json`);
        if (!object) return Response.json({ ok: false }, { status: 404, headers: adminHeaders });
        return new Response(object.body, {
          headers: { ...adminHeaders, "Content-Type": "application/json; charset=utf-8" }
        });
      }

      const listing = await env.UPDATES.list({ prefix: "crash-reports/", limit: 200 });
      const reports = listing.objects
        .map((o) => ({
          id: o.key.slice("crash-reports/".length).replace(/\.json$/, ""),
          size: o.size,
          uploadedAt: o.uploaded
        }))
        .sort((a, b) => (a.id < b.id ? 1 : -1));
      return Response.json({ ok: true, reports }, { headers: adminHeaders });
    }

    if (url.pathname === "/discord" || url.pathname === "/discord/") {
      return Response.redirect("https://discord.riverclient.xyz", 302);
    }

    // Tester-only builds. Gated on a verified River session rather than a shared password,
    // so access is tied to a Mojang-verified account and can be revoked per person.
    if (url.pathname === "/beta.json") {
      return serveTesterManifest(request, env);
    }

    /*
      The launcher holds one of these open instead of polling /latest.json, and is told
      when a release lands rather than asking.

      Deliberately anonymous. Node cannot set request headers on a WebSocket handshake,
      so this socket could not carry the User-Agent and install id the counters read even
      if it wanted to - and it does not need to. The launcher still makes one ordinary
      /latest.json request when it starts, which is counted properly with those headers
      and is a far better stand-in for "somebody opened River" than the hundreds of polls
      a day it used to file. So nothing is recorded here.

      The subprotocol is the only gate. Like the User-Agent check on /crash-report it
      proves nothing, but it keeps the route from being a free socket for anything that
      stumbles across it.
    */
    if (url.pathname === "/ws/updates") {
      if (!env.BROADCAST_DO) return new Response("Push updates unavailable.", { status: 503, headers });
      if (request.headers.get("Upgrade") !== "websocket") {
        return new Response("Expected a WebSocket upgrade.", { status: 426, headers });
      }
      const offered = (request.headers.get("Sec-WebSocket-Protocol") || "").split(",").map((value) => value.trim());
      if (!offered.includes(PUSH_PROTOCOL)) return new Response("Not found.", { status: 404, headers });
      const stub = env.BROADCAST_DO.get(env.BROADCAST_DO.idFromName("global"));
      return stub.fetch(new Request("https://broadcast/connect", request));
    }

    if (url.pathname === "/" || url.pathname === "/latest.json") {
      /*
        Counted only for the launcher. The website reads this route as well, and so does
        anything pointed at the domain - most of the traffic on it is one client polling
        every minute or so, which is not a person checking for an update and should not
        be counted as one. The launcher announces its version in the User-Agent, which is
        also where the version split comes from without asking the launcher for anything.
      */
      if (isLauncher(request)) recordMetric(env, ctx, "check", request);

      const object = await env.UPDATES.get("latest.json");
      if (object) {
        return new Response(object.body, {
          headers: {
            ...headers,
            "Content-Type": "application/json; charset=utf-8",
            "ETag": object.httpEtag
          }
        });
      }

      return Response.json(latestManifest, {
        headers: {
          ...headers,
          "Content-Type": "application/json; charset=utf-8"
        }
      });
    }

    if (url.pathname === "/news.json") {
      const object = await env.UPDATES.get("news.json");
      if (!object) return Response.json([], { headers });
      return new Response(object.body, {
        headers: {
          ...headers,
          "Content-Type": "application/json; charset=utf-8",
          "ETag": object.httpEtag
        }
      });
    }

    if (url.pathname.startsWith("/downloads/")) {
      const key = decodeURIComponent(url.pathname.slice("/downloads/".length));
      if (!key || key.includes("..")) return new Response("Invalid object key.", { status: 400, headers });

      /*
        Only the top-level installer counts as a download. Everything under releases/ is
        fetched by installs that already exist: applying an update pulls the file manifest
        plus every changed file, which is dozens of requests per version. Counting the
        whole /downloads/ prefix would report an update rolling out as a wave of new users.
      */
      if (key === "River-Client-Setup.exe") recordMetric(env, ctx, "install", request);

      /*
        Range requests, so a client can ask for part of a file instead of all of it.

        An update currently re-sends whole files. Two of them are most of the download and
        barely change: the executable differs between releases by a single 64KB block out
        of three and a half thousand, because all that moves is the signature and the
        embedded integrity hash, and app.asar differs by about two percent. Being able to
        fetch just those ranges is what turns a 321MB download of those two files into
        roughly two.

        The header is parsed here rather than passed to R2 as-is because R2 wants an offset
        and a length while HTTP gives a first and last byte, and an open ended "bytes=N-"
        has no length at all.
      */
      const rangeHeader = request.headers.get("Range") || "";
      const match = /^bytes=(\d*)-(\d*)$/.exec(rangeHeader.trim());
      let range;
      if (match && (match[1] || match[2])) {
        if (match[1]) {
          const offset = Number(match[1]);
          range = match[2] ? { offset, length: Number(match[2]) - offset + 1 } : { offset };
        } else {
          // "bytes=-N" means the last N bytes.
          range = { suffix: Number(match[2]) };
        }
      }

      const object = await env.UPDATES.get(key, range ? { range } : undefined);
      if (!object) return new Response("Not found.", { status: 404, headers });
      // The key is caller-controlled, so it cannot go into a quoted header value
      // as-is: a quote or newline in it would break out of the Content-Disposition
      // parameter. Keep only characters that are safe and meaningful in a filename.
      const filename =
        (key.split("/").pop() || "").replace(/[^A-Za-z0-9._-]/g, "") || "River-Client.exe";

      const baseHeaders = {
        ...headers,
        "Content-Type": object.httpMetadata?.contentType || "application/octet-stream",
        "Content-Disposition": `attachment; filename="${filename}"`,
        "ETag": object.httpEtag,
        // Advertised even on a whole-file response, so a client knows it may ask for part.
        "Accept-Ranges": "bytes"
      };

      if (range && object.range) {
        const start = object.range.offset ?? 0;
        const length = object.range.length ?? (object.size - start);
        return new Response(object.body, {
          status: 206,
          headers: {
            ...baseHeaders,
            "Content-Range": `bytes ${start}-${start + length - 1}/${object.size}`,
            "Content-Length": String(length)
          }
        });
      }

      return new Response(object.body, { headers: baseHeaders });
    }

    /*
      Cosmetic entitlements. Redeeming is public because the code itself is the credential:
      a creator hands it to an audience, so anyone holding it is meant to be able to use it.
      What stops it being a free-for-all is that the roster checks ownership before showing
      a gated cape to anybody else.
    */
    if (url.pathname === "/cosmetics/redeem" && request.method === "POST") {
      if (!env.COSMETICS_DO) return Response.json({ ok: false }, { status: 503, headers });
      const body = await request.json().catch(() => ({}));
      const stub = env.COSMETICS_DO.get(env.COSMETICS_DO.idFromName("global"));
      const result = await stub.fetch("https://cosmetics/redeem", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ code: body?.code, uuid: body?.uuid })
      });
      return new Response(result.body, { status: result.status, headers });
    }

    if (url.pathname === "/cosmetics/owned" && request.method === "GET") {
      if (!env.COSMETICS_DO) return Response.json({ ok: true, owned: [] }, { headers });
      const uuid = normalizeUuid(url.searchParams.get("uuid"));
      const stub = env.COSMETICS_DO.get(env.COSMETICS_DO.idFromName("global"));
      const result = await stub.fetch(`https://cosmetics/owned?uuid=${encodeURIComponent(uuid)}`);
      return new Response(result.body, { status: result.status, headers });
    }

    /** Which cosmetics need redeeming at all, so the client can grey the right ones out. */
    if (url.pathname === "/cosmetics/gated") {
      return Response.json({ ok: true, gated: [...GATED_COSMETICS] }, { headers });
    }

    // Minting and revoking codes. Key-gated: a code is the thing being handed out, so
    // anyone able to create one could grant any cosmetic to anybody.
    if (url.pathname.startsWith("/admin/cosmetic-codes")) {
      const { ok: authorised, lockedOut, headers: adminHeaders } = await adminGate(request, env);
      if (request.method === "OPTIONS") return new Response(null, { headers: adminHeaders });
      if (!authorised) return new Response(lockedOut ? "Too many attempts." : "Unauthorized.", { status: lockedOut ? 429 : 401, headers: adminHeaders });
      if (!env.COSMETICS_DO) return Response.json({ ok: false }, { status: 503, headers: adminHeaders });

      const stub = env.COSMETICS_DO.get(env.COSMETICS_DO.idFromName("global"));
      const action = url.pathname.slice("/admin/cosmetic-codes".length);
      const target = action === "/create" ? "/codes/create"
        : action === "/revoke" ? "/codes/revoke"
        : "/codes";
      const init = request.method === "POST"
        ? { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(await request.json().catch(() => ({}))) }
        : {};
      const result = await stub.fetch(`https://cosmetics${target}`, init);
      return new Response(result.body, { status: result.status, headers: adminHeaders });
    }

    /*
      Launch reporting. The launcher posts once when the game starts and once if starting
      it failed, which is the only way to see either: a launch that never reaches the sign
      in screen sends no presence ping, so failures were previously invisible unless
      someone said something. The body carries no account and no machine identifier - the
      Minecraft version, the launcher version and, for a failure, a short reason.
    */
    if (url.pathname === "/metrics/launch" && request.method === "POST") {
      const body = await request.json().catch(() => ({}));
      const kind = body?.event === "failure" ? "failure" : "launch";
      recordMetric(env, ctx, kind, request, {
        mcVersion: body?.mcVersion,
        launcherVersion: body?.launcherVersion,
        reason: body?.reason
      });
      return Response.json({ ok: true }, { headers });
    }

    /*
      Crash reports, uploaded only when someone presses the button after a crash. The log
      is scrubbed here as well as in the launcher: this route is public, and a client is
      not in a position to promise what it sent.

      Restricting it to the launcher's User-Agent is not a security control, since a UA is
      trivially forged. It is here to keep the route from being a general purpose write
      into the bucket for anything that stumbles across it. The size cap is the real limit.
    */
    if (url.pathname === "/crash-report" && request.method === "POST") {
      if (!isLauncher(request)) return new Response("Not found.", { status: 404, headers });

      const declared = Number(request.headers.get("Content-Length") || 0);
      if (declared > MAX_CRASH_BYTES * 2) {
        return Response.json({ ok: false, message: "Report too large." }, { status: 413, headers });
      }

      const body = await request.json().catch(() => null);
      const log = scrubCrashReport(body?.log);
      if (!log.trim()) return Response.json({ ok: false, message: "Empty report." }, { status: 400, headers });

      const id = crashReportId();
      const report = {
        id,
        receivedAt: Date.now(),
        mcVersion: String(body?.mcVersion || "").slice(0, 16),
        launcherVersion: String(body?.launcherVersion || "").slice(0, 24),
        // Free text the user typed about what they were doing, scrubbed the same way.
        note: scrubCrashReport(body?.note).slice(0, 2000),
        country: request.cf?.country || "",
        log
      };

      try {
        await env.UPDATES.put(`crash-reports/${id}.json`, JSON.stringify(report), {
          httpMetadata: { contentType: "application/json" }
        });
      } catch {
        return Response.json({ ok: false, message: "Could not store the report." }, { status: 502, headers });
      }

      return Response.json({ ok: true, id }, { headers });
    }

    // Presence must be handled before the workers.dev redirect: in-game clients on
    // older builds POST to the workers.dev hostname, and POST bodies don't survive
    // a 302 anyway.
    if (
      (url.pathname === "/presence" || url.pathname === "/presence/roster" || url.pathname === "/presence/status")
      && request.method === "POST"
    ) {
      return routePresence(request, env);
    }

    /*
      The launcher holds this open instead of announcing itself every 20 seconds. Being
      online is the socket being open, so closing River is noticed immediately rather
      than after the freshness window lapses.
    */
    if (url.pathname === "/presence/ws") {
      if (!env.PRESENCE_DO) return new Response("Presence unavailable.", { status: 503, headers });
      if (request.headers.get("Upgrade") !== "websocket") {
        return new Response("Expected a WebSocket upgrade.", { status: 426, headers });
      }
      return env.PRESENCE_DO.get(env.PRESENCE_DO.idFromName("global")).fetch(request);
    }

    // Public online count for riverclient.xyz. Separate from the POST routes
    // above because it takes no body and needs no account.
    if (url.pathname === "/presence/count" && request.method === "GET") {
      if (!env.PRESENCE_DO) return Response.json({ ok: true, online: 0 }, { headers });
      return env.PRESENCE_DO.get(env.PRESENCE_DO.idFromName("global")).fetch(request);
    }

    // Friends, DMs and moderation. Separate DO from presence: presence is intentionally
    // in-memory and disposable, whereas friendships and messages must persist.
    if (url.pathname.startsWith("/social/")) {
      if (!env.SOCIAL_DO) {
        return Response.json({ ok: false, message: "Social features are not enabled yet." }, { status: 503, headers });
      }
      return env.SOCIAL_DO.get(env.SOCIAL_DO.idFromName("global")).fetch(request);
    }

    if (url.hostname.endsWith(".workers.dev")) {
      return Response.redirect(`${publicUpdateBaseUrl}${url.pathname}${url.search}`, 302);
    }

    return new Response("Not found.", { status: 404, headers });
  }
};

// Presence (badge/cape roster + friends online status) runs through a single in-memory
// Durable Object instead of KV. Every ping used to be 2 KV writes + 1 list, and the
// free tier caps each at 1,000/day, so presence 500'd after about an hour. The DO holds
// the roster in memory with no per-ping storage writes, so there is no daily cap.
/**
 * Serves the tester build manifest to allow-listed accounts only.
 *
 * The caller presents a River session token, which only exists after a Mojang-signed
 * certificate was verified, so "who is a tester" is a list of real Minecraft UUIDs rather
 * than anyone who learned a URL. Removing a UUID from RIVER_TESTER_UUIDS revokes access
 * immediately - no build or key has to be rotated.
 */
async function serveTesterManifest(request, env) {
  const token = (request.headers.get("Authorization") || "").replace(/^Bearer\s+/i, "").trim();
  if (!token) {
    return Response.json({ ok: false, message: "Tester builds need a signed-in River account." }, { status: 401, headers });
  }
  if (!env.SOCIAL_DO) {
    return Response.json({ ok: false, message: "Tester builds are not available right now." }, { status: 503, headers });
  }

  const stub = env.SOCIAL_DO.get(env.SOCIAL_DO.idFromName("global"));
  const who = await stub.fetch(new Request("https://river.internal/social/whoami", {
    method: "POST",
    headers: { "Content-Type": "application/json", "Authorization": `Bearer ${token}` },
    body: "{}"
  }));
  const identity = await who.json().catch(() => null);
  if (!identity?.ok || !identity.uuid) {
    return Response.json({ ok: false, message: "Sign in to River again." }, { status: 401, headers });
  }

  const allowed = String(env.RIVER_TESTER_UUIDS || "")
    .split(",")
    .map((entry) => entry.trim().replace(/-/g, "").toLowerCase())
    .filter(Boolean);
  if (!allowed.includes(String(identity.uuid).replace(/-/g, "").toLowerCase())) {
    return Response.json(
      { ok: false, message: "This account is not on the River tester list." },
      { status: 403, headers }
    );
  }

  const object = await env.UPDATES.get("beta.json");
  if (!object) {
    return Response.json({ ok: false, message: "No tester build has been published yet." }, { status: 404, headers });
  }
  return new Response(object.body, {
    headers: { ...headers, "Content-Type": "application/json; charset=utf-8", "ETag": object.httpEtag }
  });
}

function routePresence(request, env) {
  if (!env.PRESENCE_DO) return Response.json({ players: [] }, { headers });
  const id = env.PRESENCE_DO.idFromName("global");
  return env.PRESENCE_DO.get(id).fetch(request);
}

export class Presence {
  constructor(state, env) {
    /*
      In-memory, and deliberately still so. The in-game client re-announces every few
      seconds over HTTP, so anything lost to eviction on that path repopulates by itself.

      The launcher no longer works that way. It used to POST /presence/status every 20
      seconds - about 4,300 requests a day each, which became the largest polling loop
      River had left once the update poll was replaced by a push socket. It now holds a
      socket open instead, and being online simply means that socket is open: no
      heartbeat to send, no timeout to guess at, and a disconnect is observed rather than
      inferred 30 seconds late.

      That would have been a regression on its own, because a launcher with nothing to
      re-announce cannot repopulate this map after an eviction. Sockets and the data
      attached to them do survive it, so the roster is rebuilt from them on the way into
      any query - see syncFromSockets below.
    */
    this.players = new Map();
    // Storage is used for exactly one thing: the highest number of players seen at once
    // on a given day. That is a number, not a roster - no uuid, name or server is written.
    this.state = state;
    // Kept so the roster can ask the cosmetics store whether a gated cape is owned.
    this.env = env;

    // Answered by the runtime without waking this object, so an idle launcher holding a
    // socket open costs nothing between status changes.
    try {
      this.state.setWebSocketAutoResponse(new WebSocketRequestResponsePair("ping", "pong"));
    } catch {}
  }

  /**
   * Folds everyone holding an open socket back into the roster.
   *
   * Hibernation drops this object from memory between messages but keeps its sockets and
   * whatever was attached to them, so the sockets are the durable half of the roster and
   * this map is only a cache of them. Called before anything reads the roster.
   */
  syncFromSockets(now) {
    let sockets = [];
    try { sockets = this.state.getWebSockets(); } catch { return; }
    for (const socket of sockets) {
      let attached = null;
      try { attached = socket.deserializeAttachment(); } catch {}
      if (!attached?.uuid) continue;
      /*
        Appearing offline is the entire point of invisible, so a socket carrying it is
        skipped rather than folded back in. Without this the rebuild below would undo the
        removal on the very next read and invisible would do nothing at all.

        Skipped rather than deleted, so somebody signed in twice who is invisible on one
        machine and online on another still shows as online.
      */
      if (attached.status === "invisible") continue;
      const existing = this.players.get(attached.uuid) || {};
      this.players.set(attached.uuid, {
        ...existing,
        uuid: attached.uuid,
        name: attached.name || existing.name || "",
        status: attached.status || existing.status || "online",
        // Marks this entry as owned by a live socket so prune() leaves it alone: it is
        // online because the connection is open, not because a ping arrived recently.
        viaSocket: true,
        ts: now
      });
    }
  }

  /*
    A launcher says who it is once, then only when something changes. "invisible" is the
    point of appearing offline, so it drops the entry while leaving the socket open -
    the same meaning it has on the HTTP path.
  */
  webSocketMessage(ws, message) {
    if (typeof message !== "string") return;
    if (message === "ping") { try { ws.send("pong"); } catch {} return; }

    let payload = null;
    try { payload = JSON.parse(message); } catch { return; }
    if (!payload || typeof payload !== "object") return;

    const now = Date.now();
    let attached = null;
    try { attached = ws.deserializeAttachment(); } catch {}

    if (payload.type === "hello") {
      const uuid = String(payload.uuid ?? "").slice(0, 48);
      if (!uuid) return;
      attached = {
        uuid,
        name: String(payload.name ?? "").slice(0, 32),
        status: STATUS_VALUES.has(payload.status) ? payload.status : "online"
      };
    } else if (payload.type === "status" && attached?.uuid) {
      attached = { ...attached, status: STATUS_VALUES.has(payload.status) ? payload.status : "online" };
    } else {
      return;
    }

    try { ws.serializeAttachment(attached); } catch {}

    if (attached.status === "invisible") {
      this.players.delete(attached.uuid);
      return;
    }
    const existing = this.players.get(attached.uuid) || {};
    this.players.set(attached.uuid, {
      ...existing,
      uuid: attached.uuid,
      name: attached.name,
      status: attached.status,
      viaSocket: true,
      ts: now
    });
    this.recordPeak(now);
  }

  /*
    A closed socket is the only signal needed to know somebody has gone. This is the part
    the 30 second freshness window was standing in for, and it is exact rather than a
    guess that could be half a minute stale.
  */
  webSocketClose(ws) {
    this.dropSocket(ws);
  }

  webSocketError(ws) {
    this.dropSocket(ws);
  }

  dropSocket(ws) {
    let attached = null;
    try { attached = ws.deserializeAttachment(); } catch {}
    try { ws.close(); } catch {}
    if (!attached?.uuid) return;
    /*
      Only forget them if no other socket is still holding that uuid open. Somebody
      running River on two machines closes one of them without going offline.
    */
    let stillHere = false;
    try {
      for (const socket of this.state.getWebSockets()) {
        if (socket === ws) continue;
        let other = null;
        try { other = socket.deserializeAttachment(); } catch {}
        if (other?.uuid === attached.uuid) { stillHere = true; break; }
      }
    } catch {}
    if (!stillHere) this.players.delete(attached.uuid);
  }

  /**
   * Records today's peak concurrent player count.
   *
   * River already receives these announcements to build the live roster, so this counts
   * what is already arriving rather than asking clients for anything new, and keeps only
   * the high-water mark per day. Deliberately not a "unique users" figure: that would
   * mean storing something per player to deduplicate against, and a peak answers "how
   * many people use this" without keeping anything about who they are.
   *
   * Failure must never affect presence itself, so this is fire-and-forget.
   */
  async recordPeak(now) {
    try {
      const stamp = new Date(now);
      const day = stamp.toISOString().slice(0, 10);
      const key = `peak:${day}`;
      const current = this.players.size;
      const stored = (await this.state.storage.get(key)) || 0;
      if (current > stored) await this.state.storage.put(key, current);

      /*
        The same thing again by hour of the day, to answer when people actually play
        rather than how many played. Two numbers per hour, because at River's size the
        peak alone is nearly always 1 and would draw a flat line across the whole day:
        `pings` counts how much activity landed in that hour, which has a shape even when
        only one person is ever online at a time.

        Buckets are UTC. The dashboard converts them to whoever is reading.
      */
      const hourKey = `hour:${String(stamp.getUTCHours()).padStart(2, "0")}`;
      const hour = (await this.state.storage.get(hourKey)) || { peak: 0, pings: 0 };
      hour.pings += 1;
      if (current > hour.peak) hour.peak = current;
      await this.state.storage.put(hourKey, hour);
    } catch {}
  }

  /*
    Only entries that got here by announcing themselves expire. A socket-backed one is
    online for exactly as long as its socket is open, and is removed when it closes.
  */
  prune(now) {
    for (const [uuid, value] of this.players) {
      if (value.viaSocket) continue;
      if (now - (value.ts || 0) > PRESENCE_FRESH_MS) this.players.delete(uuid);
    }
  }

  async fetch(request) {
    const url = new URL(request.url);
    const now = Date.now();
    const empty = () => Response.json({ players: [] }, { headers });

    // Everything below reads the roster, and after an eviction the sockets are the only
    // place the launcher half of it still exists.
    this.syncFromSockets(now);

    /*
      The launcher's presence connection, in place of a POST every 20 seconds.

      It arrives with no identity attached: a WebSocket handshake from Node cannot carry
      headers, and a uuid does not belong in a URL where it would end up in logs. The
      launcher sends a hello frame once the socket is open instead, and until it does the
      connection counts for nothing.
    */
    if (url.pathname === "/presence/ws") {
      if (request.headers.get("Upgrade") !== "websocket") {
        return new Response("Expected a WebSocket upgrade.", { status: 426, headers });
      }
      const offered = (request.headers.get("Sec-WebSocket-Protocol") || "").split(",").map((value) => value.trim());
      if (!offered.includes(PRESENCE_PROTOCOL)) return new Response("Not found.", { status: 404, headers });
      const pair = new WebSocketPair();
      const [client, server] = Object.values(pair);
      this.state.acceptWebSocket(server);
      return new Response(null, {
        status: 101,
        webSocket: client,
        headers: { "Sec-WebSocket-Protocol": PRESENCE_PROTOCOL }
      });
    }

    // Public count for the website. GET only, and it returns a number and
    // nothing else - no names, no uuids, no servers. Anyone can read how many
    // people are on River right now, nobody can read who.
    if (url.pathname === "/presence/count" && request.method === "GET") {
      this.prune(now);
      return Response.json({ ok: true, online: this.players.size }, { headers });
    }

        // Daily peaks, for the private dashboard. Numbers only, no identities.
        if (url.pathname === "/presence/history" && request.method === "GET") {
          this.prune(now);
          const entries = await this.state.storage.list({ prefix: "peak:" });
          const days = [];
          for (const [key, value] of entries) days.push({ day: key.slice("peak:".length), peak: value });
          days.sort((a, b) => (a.day < b.day ? 1 : -1));

          // Always all 24, so the dashboard draws a full day rather than only the hours
          // that have ever seen somebody.
          const hourEntries = await this.state.storage.list({ prefix: "hour:" });
          const hours = Array.from({ length: 24 }, (_, h) => {
            const stored = hourEntries.get(`hour:${String(h).padStart(2, "0")}`) || {};
            return { hour: h, peak: stored.peak || 0, pings: stored.pings || 0 };
          });

          /*
            Split so the socket page can show how the two halves compare: a launcher
            holding a connection open against the in-game client, which still announces
            itself over HTTP and always will - it is Java, and only alive while somebody
            is actually playing.
          */
          let sockets = 0;
          try { sockets = this.state.getWebSockets().length; } catch {}
          let viaSocket = 0;
          for (const value of this.players.values()) if (value.viaSocket) viaSocket += 1;

          return Response.json(
            {
              ok: true,
              online: this.players.size,
              sockets,
              viaSocket,
              viaAnnounce: this.players.size - viaSocket,
              days: days.slice(0, 60),
              hours
            },
            { headers }
          );
        }

    let payload;
    try {
      payload = await request.json();
    } catch {
      return empty();
    }

    // Availability announced by the launcher (in-game presence goes through the badge
    // path below). Kept separate because that path demands a server hash, which the
    // launcher has no business inventing just to say "I'm online".
    if (url.pathname === "/presence/status") {
      const uuid = String(payload?.uuid ?? "").slice(0, 48);
      if (!uuid) return empty();
      const status = STATUS_VALUES.has(payload?.status) ? payload.status : "online";
      // "invisible" is the point of appearing offline, so it is a removal, not a state
      // other clients could read back out of the roster.
      if (status === "invisible") {
        this.players.delete(uuid);
        return Response.json({ ok: true }, { headers });
      }
      const existing = this.players.get(uuid) || {};
      this.players.set(uuid, {
        ...existing,
        uuid,
        name: String(payload?.name ?? "").slice(0, 32),
        status,
        ts: now
      });
      this.prune(now);
          this.recordPeak(now);
      return Response.json({ ok: true }, { headers });
    }

    // Friends roster: name-only in, status-only out (never reveals which server).
    if (url.pathname === "/presence/roster") {
      const names = Array.isArray(payload?.names)
        ? payload.names.slice(0, 64).map((n) => String(n).toLowerCase().slice(0, 32)).filter(Boolean)
        : [];
      this.prune(now);
      if (!names.length) return empty();
      const wanted = new Set(names);
      const seen = new Set();
      const players = [];
      for (const value of this.players.values()) {
        const lname = String(value.name || "").toLowerCase();
        if (wanted.has(lname) && !seen.has(lname)) {
          seen.add(lname);
          players.push({ name: value.name, online: true, status: value.status || "online" });
        }
      }
      return Response.json({ players }, { headers });
    }

    // Badge/cape roster: everyone River-active on the same hashed server.
    const server = String(payload?.server ?? "").replace(/[^a-zA-Z0-9]/g, "").slice(0, 64);
    const uuid = String(payload?.uuid ?? "").slice(0, 48);
    if (!server || !uuid) return empty();

    const name = String(payload?.name ?? "").slice(0, 32);
    const badge = payload?.badge === true;
    const cape = String(payload?.cape ?? "").replace(/[^a-z]/g, "").slice(0, 24);
    const effects = Array.isArray(payload?.effects)
      ? payload.effects.slice(0, 16).map((e) => String(e).slice(0, 24))
      : [];

    this.players.set(uuid, { uuid, name, server, badge, cape, effects, ts: now });
    this.prune(now);

    const players = [];
    for (const value of this.players.values()) {
      if (value.server !== server) continue;
      players.push({ uuid: value.uuid, name: value.name, badge: value.badge === true, cape: value.cape ?? "", effects: value.effects ?? [] });
    }

    /*
      Gated capes are checked here rather than trusted from the client, because this
      response is the only way one player ever learns what another is wearing. A client
      can put an unowned cape on its own screen and nothing can stop that, but claiming
      one here gets it dropped, so it stays visible to exactly that one person.

      Only the gated ids are looked up, so the free capes - which is almost everybody -
      cost nothing extra.
    */
    const gatedClaims = new Map();
    for (const player of players) {
      if (!player.cape || !GATED_COSMETICS.has(player.cape)) continue;
      if (!gatedClaims.has(player.cape)) gatedClaims.set(player.cape, []);
      gatedClaims.get(player.cape).push(player.uuid);
    }

    if (gatedClaims.size && this.env?.COSMETICS_DO) {
      const stub = this.env.COSMETICS_DO.get(this.env.COSMETICS_DO.idFromName("global"));
      for (const [cosmetic, uuids] of gatedClaims) {
        let allowed = [];
        try {
          const res = await stub.fetch("https://cosmetics/entitled", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ cosmetic, uuids })
          });
          allowed = (await res.json())?.allowed ?? [];
        } catch {
          // If the entitlement store cannot be reached, drop the gated cape rather than
          // letting it through. Failing closed on a cosmetic costs somebody a cape for a
          // few seconds; failing open makes the gate meaningless the moment it wobbles.
          allowed = [];
        }
        const ok = new Set(allowed);
        for (const player of players) {
          if (player.cape === cosmetic && !ok.has(player.uuid)) player.cape = "";
        }
      }
    }

    return Response.json({ players }, { headers });
  }
}

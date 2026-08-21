const headers = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, HEAD, POST, OPTIONS",
  // Authorization is needed so social clients can present their session token.
  "Access-Control-Allow-Headers": "Content-Type, Authorization",
  "Cache-Control": "no-store"
};

export { Social } from "./social.js";

const discordInviteUrl = "https://discord.gg/neQzwBTvp3";
const publicUpdateBaseUrl = "https://updates.riverclient.xyz";

// A remote River user is considered "present" for this many ms after their last
// ping. KV's minimum expirationTtl is 60s, so entries are GC'd at 60s but filtered
// out of the roster sooner than that here.
const PRESENCE_FRESH_MS = 30_000;

/** Availability a player can broadcast. "invisible" is never stored - see /presence/status. */
const STATUS_VALUES = new Set(["online", "idle", "dnd", "invisible"]);

const latestManifest = {
  "name": "River Client",
  "version": "0.1.6.3",
  "minimumVersion": "0.1.6.3",
  "required": false,
  "publishedAt": "2026-08-20T19:13:03.923Z",
  "pageUrl": "https://riverclient.xyz/",
  "installerUrl": "https://updates.riverclient.xyz/downloads/River-Client-Setup.exe",
  "portableUrl": "https://updates.riverclient.xyz/downloads/releases/0.1.6.3/River-Client-Portable-0.1.6.3.exe",
  "packageUrl": "https://updates.riverclient.xyz/downloads/releases/0.1.6.3/River-Client-App-0.1.6.3.zip",
  "fileManifestUrl": "https://updates.riverclient.xyz/downloads/releases/0.1.6.3/file-manifest.json",
  "appFileManifestUrl": "https://updates.riverclient.xyz/downloads/releases/0.1.6.3/file-manifest.json",
  "appFileBaseUrl": "https://updates.riverclient.xyz/downloads/releases/0.1.6.3/app/",
  "fileCount": 81,
  "files": {
    "installer": {
      "name": "River-Client-Setup.exe",
      "size": 131261057,
      "sha256": "4013e7bfa2261134eca039f42c894c60a9a5b5f024cd1f233079491486283b64"
    },
    "portable": {
      "name": "River-Client-Portable-0.1.6.3.exe",
      "size": 176503345,
      "sha256": "71768fb1c79f18dceb7ba958045ae4c9d1088b8c4d833b0b614edaf1b5dadaf1"
    },
    "package": {
      "name": "River-Client-App-0.1.6.3.zip",
      "size": 195179276,
      "sha256": "f36a8e299693659b9b68cada08de5d5eb424064b9cfa409523882140728b54d8"
    },
    "fileManifest": {
      "name": "file-manifest.json",
      "size": 24389,
      "sha256": "8e66a74f966796c4016f86bb07587f5002dbef876738d7dbbd65f69e0d910be0",
      "count": 81
    }
  },
  "changelog": {
    "version": "0.1.6.3",
    "title": "River Client 0.1.6.3",
    "summary": "Fixes launch prep being much slower than it should be for existing installs, and cleans up the standalone updater window's look.",
    "items": [
      "Fixed 'Preparing assets' being needlessly slow: the launcher was re-hashing every single asset file on every launch, even ones already downloaded and unchanged. It now trusts files that already exist at their expected location, which is what actually made launches feel slow for anyone with an existing install.",
      "The standalone updater window now looks like the rest of the app instead of rendering mostly unstyled."
    ]
  },
  "notes": "Fixes launch prep being much slower than it should be for existing installs, and cleans up the standalone updater window's look."
};

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === "OPTIONS") return new Response(null, { headers });

    if (url.hostname === "discord.riverclient.xyz") {
      return Response.redirect(discordInviteUrl, 302);
    }

    if (url.pathname === "/discord" || url.pathname === "/discord/") {
      return Response.redirect("https://discord.riverclient.xyz", 302);
    }

    // Tester-only builds. Gated on a verified River session rather than a shared password,
    // so access is tied to a Mojang-verified account and can be revoked per person.
    if (url.pathname === "/beta.json") {
      return serveTesterManifest(request, env);
    }

    if (url.pathname === "/" || url.pathname === "/latest.json") {
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
      const object = await env.UPDATES.get(key);
      if (!object) return new Response("Not found.", { status: 404, headers });
      const filename = key.split("/").pop() || "River-Client.exe";
      return new Response(object.body, {
        headers: {
          ...headers,
          "Content-Type": object.httpMetadata?.contentType || "application/octet-stream",
          "Content-Disposition": `attachment; filename="${filename}"`,
          "ETag": object.httpEtag
        }
      });
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
    // In-memory only. If the DO is evicted while idle the roster is lost, but clients
    // re-announce every few seconds so it repopulates - fine for ephemeral presence.
    this.players = new Map();
  }

  prune(now) {
    for (const [uuid, value] of this.players) {
      if (now - (value.ts || 0) > PRESENCE_FRESH_MS) this.players.delete(uuid);
    }
  }

  async fetch(request) {
    const url = new URL(request.url);
    const now = Date.now();
    const empty = () => Response.json({ players: [] }, { headers });

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
    return Response.json({ players }, { headers });
  }
}

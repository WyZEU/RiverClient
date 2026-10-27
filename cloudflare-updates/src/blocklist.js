/*
  Accounts River refuses to serve: sign-in, the friends service, tester builds and the
  in-game roster all treat them as if they had never signed up.

  The list is config/blocklist.json in the updates bucket, edited one person at a time with
  `npm run blocklist` in launcher/. Nothing has to be redeployed or republished: a change
  reaches every Durable Object within CACHE_MS. The config/ prefix is never served
  publicly (isPublicDownloadKey only allows releases/).

  Entries are Mojang UUIDs, never names, because a name can be changed or taken over and
  the UUID is the only thing a Mojang-signed certificate actually proves.
*/
const KEY = "config/blocklist.json";
const CACHE_MS = 60_000;
const RETRY_MS = 10_000;

export const BLOCKED_MESSAGE = "This account can't use River Client.";

let cache = { at: -Infinity, uuids: new Set() };

const normalize = (value) => String(value || "").trim().replace(/-/g, "").toLowerCase();

/**
 * Everyone currently blocked, as dashless lowercase UUIDs. When the bucket cannot be read
 * the last known list is kept and the read is retried shortly: an R2 hiccup must neither
 * unblock somebody who was blocked a minute ago nor lock out everyone else.
 */
export async function blockedUuids(env, now = Date.now()) {
  if (!env?.UPDATES) return new Set();
  if (now - cache.at < CACHE_MS) return cache.uuids;
  try {
    const object = await env.UPDATES.get(KEY);
    const payload = object ? await object.json() : null;
    const list = Array.isArray(payload?.blocked) ? payload.blocked : [];
    cache = { at: now, uuids: new Set(list.map((entry) => normalize(entry?.uuid)).filter(Boolean)) };
  } catch {
    cache = { ...cache, at: now - CACHE_MS + RETRY_MS };
  }
  return cache.uuids;
}

export async function isBlocked(env, uuid) {
  const wanted = normalize(uuid);
  return Boolean(wanted) && (await blockedUuids(env)).has(wanted);
}

/** Tests only: forget what was read so the next call goes to the bucket again. */
export function resetBlocklistCache() {
  cache = { at: -Infinity, uuids: new Set() };
}

/*
  How far behind a River install may fall before it has to update.

  Two rules, and the stricter one wins:
  - Nobody may be more than `maxBehind` releases behind. With 3, the latest and the two
    before it still run; a fourth-newest install is made to update.
  - A forced minimum set from the dashboard: "everyone below this version updates now",
    for a release that fixes something that cannot wait.

  Both end up as the manifest's minimumVersion, which every launcher since 0.1.7 already
  treats as "you cannot play until you update". The standalone mod reads the same answer
  from /version-status.json.

  The policy is config/update-policy.json in the updates bucket (never served publicly),
  so a change reaches everyone on their next check without a redeploy or a publish. The
  release list is the releases/ folders of the same bucket, so it can never disagree with
  what can actually be downloaded.
*/
const POLICY_KEY = "config/update-policy.json";
const CACHE_MS = 60_000;

export const DEFAULT_POLICY = { forcedMinimum: "", maxBehind: 3 };

let policyCache = { at: -Infinity, policy: DEFAULT_POLICY };
let releaseCache = { at: -Infinity, versions: [] };

export function resetUpdatePolicyCache() {
  policyCache = { at: -Infinity, policy: DEFAULT_POLICY };
  releaseCache = { at: -Infinity, versions: [] };
}

/** 1 when a is newer than b, -1 when older, 0 when the same. "0.2.5.1" > "0.2.5". */
export function compareVersions(a, b) {
  const pa = String(a || "").split(/[.+-]/).map((part) => parseInt(part, 10) || 0);
  const pb = String(b || "").split(/[.+-]/).map((part) => parseInt(part, 10) || 0);
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    const d = (pa[i] || 0) - (pb[i] || 0);
    if (d !== 0) return d > 0 ? 1 : -1;
  }
  return 0;
}

const isVersion = (value) => /^\d+(\.\d+){1,3}$/.test(String(value || ""));

export function normalizePolicy(raw) {
  const forced = String(raw?.forcedMinimum || "").trim();
  const behind = Number(raw?.maxBehind);
  return {
    forcedMinimum: isVersion(forced) ? forced : "",
    maxBehind: Number.isInteger(behind) && behind >= 0 && behind <= 50 ? behind : DEFAULT_POLICY.maxBehind
  };
}

export async function readPolicy(env, now = Date.now()) {
  if (!env?.UPDATES) return DEFAULT_POLICY;
  if (now - policyCache.at < CACHE_MS) return policyCache.policy;
  try {
    const object = await env.UPDATES.get(POLICY_KEY);
    policyCache = { at: now, policy: normalizePolicy(object ? await object.json() : null) };
  } catch {
    // Keep what was last known: a bucket hiccup must not lift a forced update.
    policyCache = { ...policyCache, at: now };
  }
  return policyCache.policy;
}

export async function writePolicy(env, raw) {
  const policy = normalizePolicy(raw);
  await env.UPDATES.put(POLICY_KEY, JSON.stringify({ ...policy, updatedAt: new Date().toISOString() }), {
    httpMetadata: { contentType: "application/json" }
  });
  policyCache = { at: Date.now(), policy };
  return policy;
}

/** Every version with a releases/<version>/ folder, newest first. */
export async function releaseVersions(env, now = Date.now()) {
  if (!env?.UPDATES) return [];
  if (now - releaseCache.at < CACHE_MS) return releaseCache.versions;
  try {
    const found = [];
    let cursor;
    do {
      const page = await env.UPDATES.list({ prefix: "releases/", delimiter: "/", cursor });
      for (const prefix of page.delimitedPrefixes || []) {
        const version = prefix.slice("releases/".length).replace(/\/$/, "");
        if (isVersion(version)) found.push(version);
      }
      cursor = page.truncated ? page.cursor : undefined;
    } while (cursor);
    releaseCache = { at: now, versions: found.sort((a, b) => compareVersions(b, a)) };
  } catch {
    releaseCache = { ...releaseCache, at: now };
  }
  return releaseCache.versions;
}

/**
 * The oldest version still allowed to run, given the latest public release.
 * Releases newer than `latest` (a tester build) do not count towards "behind".
 */
export function effectiveMinimum({ latest, manifestMinimum, policy, releases }) {
  let minimum = isVersion(manifestMinimum) ? manifestMinimum : "";
  const raise = (candidate) => {
    if (!isVersion(candidate) || compareVersions(candidate, latest) > 0) return;
    if (!minimum || compareVersions(candidate, minimum) > 0) minimum = candidate;
  };
  const published = releases.filter((version) => compareVersions(version, latest) <= 0);
  if (policy.maxBehind > 0 && published.length >= policy.maxBehind) raise(published[policy.maxBehind - 1]);
  raise(policy.forcedMinimum);
  return minimum;
}

/** What a client needs to know about its own version, for the standalone mod. */
export function versionStatus({ latest, minimum, releases, maxBehind }) {
  return {
    latest,
    minimumVersion: minimum,
    maxBehind,
    releases: releases.filter((version) => compareVersions(version, latest) <= 0).slice(0, 20),
    url: "https://riverclient.xyz/"
  };
}

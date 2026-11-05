/*
  CurseForge for everyone, without a key in the launcher.

  CurseForge's API needs a key. Shipping River's inside the launcher would hand it to
  anyone who opens the installer, so the launcher asks here instead and the key stays a
  worker secret (CURSEFORGE_API_KEY). Only the read-only lookups the launcher makes are
  passed on, nothing else of CurseForge's API is reachable through this, and answers are
  cached at the edge so popular searches don't spend the key's quota again and again.
*/
const UPSTREAM = "https://api.curseforge.com/v1";
export const CURSEFORGE_PREFIX = "/cf/v1";

const ALLOWED = [
  /^\/mods\/search$/,
  /^\/mods\/\d+$/,
  /^\/mods\/\d+\/files$/,
  /^\/mods\/\d+\/files\/\d+$/
];

/** Search results change often enough to want fresh ones; a project's files less so. */
function cacheSeconds(path) {
  return path === "/mods/search" ? 600 : 1800;
}

export function isAllowedCurseForgePath(path) {
  return ALLOWED.some((pattern) => pattern.test(path));
}

export async function curseForgeProxy(request, env, ctx) {
  const json = (body, status) => Response.json(body, { status, headers: { "Cache-Control": "no-store" } });
  if (request.method !== "GET") return json({ message: "Only lookups go through here." }, 405);

  const url = new URL(request.url);
  const path = url.pathname.slice(CURSEFORGE_PREFIX.length);
  if (!isAllowedCurseForgePath(path)) return json({ message: "Not available." }, 404);
  if (!env.CURSEFORGE_API_KEY) return json({ message: "CurseForge isn't set up on River's server yet." }, 503);

  const upstream = new URL(UPSTREAM + path);
  upstream.search = url.search;

  // Cached by the upstream address, which never contains the key.
  const cache = typeof caches !== "undefined" ? caches.default : null;
  const cacheKey = new Request(upstream.toString(), { method: "GET" });
  if (cache) {
    const hit = await cache.match(cacheKey);
    if (hit) return hit;
  }

  let response;
  try {
    response = await fetch(upstream, { headers: { Accept: "application/json", "x-api-key": env.CURSEFORGE_API_KEY } });
  } catch {
    return json({ message: "CurseForge couldn't be reached." }, 502);
  }
  if (!response.ok) return json({ message: `CurseForge answered ${response.status}.` }, response.status === 404 ? 404 : 502);

  const out = new Response(response.body, {
    status: 200,
    headers: {
      "Content-Type": "application/json",
      "Cache-Control": `public, max-age=${cacheSeconds(path)}`
    }
  });
  if (cache) {
    const copy = out.clone();
    const put = cache.put(cacheKey, copy);
    if (ctx?.waitUntil) ctx.waitUntil(put);
    else await put;
  }
  return out;
}

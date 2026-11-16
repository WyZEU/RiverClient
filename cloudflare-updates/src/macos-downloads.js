const base = "https://updates.riverclient.xyz";

export function publishedMacTarget(manifest, arch) {
  if (!["arm64", "x64"].includes(arch) || manifest?.platform !== "darwin" || manifest.channel !== "stable"
    || !/^[0-9]+\.[0-9]+\.[0-9]+$/.test(manifest.version) || !/^[a-f0-9]{40}$/.test(manifest.sourceCommit)) return null;
  const name = `River-Client-${manifest.version}-macOS-${arch}.dmg`;
  const target = `${base}/downloads/releases/${manifest.version}-${manifest.sourceCommit.slice(0, 12)}/macos/${name}`;
  return manifest.macUrls?.[arch] === target ? target : null;
}

export async function macDownloadResponse(request, env, headers) {
  const pathname = new URL(request.url).pathname;
  const arch = /^\/downloads\/River-Client-macOS-(arm64|x64)\.dmg$/.exec(pathname)?.[1];
  if (pathname !== "/macos.json" && !arch) return null;
  if (!["GET", "HEAD"].includes(request.method)) return new Response("Method not allowed.", { status: 405, headers });
  const object = await env.UPDATES.get("macos.json");
  if (!object) return new Response("Mac release not published.", { status: 404, headers });
  const manifest = await object.json();
  // Never advertise one architecture while its matching release is incomplete.
  if (!["arm64", "x64"].every((a) => publishedMacTarget(manifest, a))) {
    return new Response("Mac release unavailable.", { status: 503, headers });
  }
  if (arch) return new Response(null, { status: 302, headers: { ...headers, Location: publishedMacTarget(manifest, arch) } });
  // Keep every signed field unchanged. Windows policy and fallback are independent.
  return new Response(request.method === "HEAD" ? null : JSON.stringify(manifest), {
    headers: { ...headers, "Content-Type": "application/json; charset=utf-8" }
  });
}

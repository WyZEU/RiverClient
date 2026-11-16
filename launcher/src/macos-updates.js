const { verifyManifest } = require("./update-signature");

// A Windows release must not offer a nonexistent or older Mac download.
async function fetchMacUpdateManifest(headers, { fetchRemote = fetch, verify = verifyManifest, arch = process.arch } = {}) {
  const url = "https://updates.riverclient.xyz/macos.json";
  try {
    const response = await fetchRemote(url, { headers, signal: AbortSignal.timeout(4000), redirect: "error", cache: "no-store" });
    if (!response.ok) return null;
    const payload = await response.json();
    if (!verify(payload) || payload.platform !== "darwin" || payload.channel !== "stable"
      || !["arm64", "x64"].includes(arch) || !payload.macUrls?.[arch]) return null;
    return { url, payload };
  } catch { return null; }
}

module.exports = { fetchMacUpdateManifest };

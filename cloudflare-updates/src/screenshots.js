/**
 * Screenshot sharing: the in-game "Share" button uploads a screenshot here and gets back
 * a link to a page that shows it, with the tags Discord and other apps read to embed it.
 *
 * Only signed-in River accounts can upload - the worker resolves the session to a
 * Mojang-verified UUID first - so this is not an open image host, and every upload
 * carries the UUID and name of whoever sent it.
 */

/** A 4K PNG of Minecraft is 8-15 MB; the client sends anything bigger as a JPEG. */
export const MAX_SCREENSHOT_BYTES = 10 * 1024 * 1024;

const ID_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";

/** 10 characters from a 57-letter alphabet: unguessable enough that links are the key. */
export function screenshotId() {
  const bytes = crypto.getRandomValues(new Uint8Array(10));
  return [...bytes].map((b) => ID_ALPHABET[b % ID_ALPHABET.length]).join("");
}

export function isScreenshotId(value) {
  return /^[A-Za-z0-9]{10}$/.test(String(value || ""));
}

/** What the bytes actually are, whatever the request claimed. Null for anything else. */
export function imageKind(bytes) {
  const b = new Uint8Array(bytes.slice(0, 8));
  if (b.length >= 8 && b[0] === 0x89 && b[1] === 0x50 && b[2] === 0x4e && b[3] === 0x47
    && b[4] === 0x0d && b[5] === 0x0a && b[6] === 0x1a && b[7] === 0x0a) {
    return { ext: "png", type: "image/png" };
  }
  if (b.length >= 3 && b[0] === 0xff && b[1] === 0xd8 && b[2] === 0xff) {
    return { ext: "jpg", type: "image/jpeg" };
  }
  return null;
}

function escapeHtml(value) {
  return String(value || "").replace(/[&<>"']/g, (c) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", "\"": "&quot;", "'": "&#39;"
  })[c]);
}

/** The share page: the picture, who took it, and the tags that make chat apps embed it. */
export function screenshotPage({ id, imageUrl, pageUrl, name, takenAt }) {
  const who = escapeHtml(name || "a River player");
  const when = takenAt ? new Date(takenAt).toISOString().slice(0, 10) : "";
  const title = `Screenshot by ${who}`;
  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${title} - River Client</title>
<meta property="og:type" content="website">
<meta property="og:site_name" content="River Client">
<meta property="og:title" content="${title}">
<meta property="og:url" content="${escapeHtml(pageUrl)}">
<meta property="og:image" content="${escapeHtml(imageUrl)}">
<meta name="twitter:card" content="summary_large_image">
<meta name="twitter:image" content="${escapeHtml(imageUrl)}">
<meta name="theme-color" content="#0d0e11">
<style>
  :root { color-scheme: dark; }
  * { box-sizing: border-box; }
  body { margin: 0; min-height: 100vh; background: #0d0e11; color: #f3f4f6;
    font: 14px/1.5 system-ui, -apple-system, "Segoe UI", sans-serif;
    display: flex; flex-direction: column; }
  header, footer { display: flex; align-items: center; justify-content: space-between;
    gap: 16px; padding: 12px 16px; border-bottom: 1px solid #1f2229; }
  footer { border-bottom: 0; border-top: 1px solid #1f2229; color: #8b92a1; font-size: 13px; }
  a { color: #5aa9ff; text-decoration: none; }
  a:hover { color: #8cc4ff; }
  .brand { font-weight: 600; letter-spacing: 0.01em; color: #f3f4f6; }
  .meta { color: #8b92a1; font-size: 13px; }
  main { flex: 1; display: flex; align-items: center; justify-content: center; padding: 16px; }
  img { display: block; max-width: 100%; max-height: calc(100vh - 140px); border: 1px solid #1f2229; }
</style>
</head>
<body>
<header>
  <a class="brand" href="https://riverclient.xyz/">River Client</a>
  <span class="meta">${title}${when ? ` &middot; ${when}` : ""}</span>
</header>
<main>
  <a href="${escapeHtml(imageUrl)}"><img src="${escapeHtml(imageUrl)}" alt="${title}"></a>
</main>
<footer>
  <span>Shared from Minecraft with River Client.</span>
  <a href="https://riverclient.xyz/">Get River</a>
</footer>
</body>
</html>`;
}

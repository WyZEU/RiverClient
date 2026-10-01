"use strict";

/**
 * CurseForge content search and install.
 *
 * Search already existed but was hardcoded to mods (classId 6) and downloads were
 * never implemented, so anything found there could only be opened in a browser.
 * This adds resource packs and shaders, and resolves a real downloadable file so
 * installs work in-app the same way Modrinth ones do.
 *
 * Everything here needs the user's own CurseForge API key - their terms require
 * each consumer to use their own - so callers must handle the no-key case.
 */

const API = "https://api.curseforge.com/v1";

/** CurseForge groups content by classId under game 432 (Minecraft). */
const CLASS_IDS = {
  mod: 6,
  resourcepack: 12,
  shader: 6552
};

/** CurseForge's numeric loader ids; only the ones River can run matter. */
const LOADER_IDS = {
  fabric: 4,
  quilt: 5,
  forge: 1,
  neoforge: 6
};

function classIdFor(contentType) {
  return CLASS_IDS[contentType] || CLASS_IDS.mod;
}

function headers(apiKey) {
  return { Accept: "application/json", "x-api-key": apiKey };
}

async function search({ query, version, loader, contentType, apiKey, pageSize = 24 }) {
  const url = new URL(`${API}/mods/search`);
  url.searchParams.set("gameId", "432");
  url.searchParams.set("classId", String(classIdFor(contentType)));
  url.searchParams.set("searchFilter", String(query || ""));
  url.searchParams.set("gameVersion", String(version || "1.21.11"));
  url.searchParams.set("pageSize", String(pageSize));
  url.searchParams.set("sortField", "2"); // popularity
  url.searchParams.set("sortOrder", "desc");

  // Loader only applies to mods; a resource pack has no loader and filtering by one
  // returns nothing.
  if (contentType === "mod") {
    const loaderId = LOADER_IDS[String(loader || "fabric").toLowerCase()];
    if (loaderId) url.searchParams.set("modLoaderType", String(loaderId));
  }

  const response = await fetch(url, { headers: headers(apiKey) });
  if (!response.ok) {
    return { ok: false, status: response.status, results: [] };
  }
  const body = await response.json();
  return { ok: true, data: body.data || [] };
}

/**
 * Newest file for this game version (and loader, for mods).
 *
 * CurseForge lets authors mark a project "download disabled"; those files have no
 * downloadUrl and cannot be fetched by third parties at all, so that case is
 * reported rather than silently failing.
 */
async function resolveFile({ modId, version, loader, contentType, apiKey }) {
  const url = new URL(`${API}/mods/${modId}/files`);
  url.searchParams.set("gameVersion", String(version || "1.21.11"));
  url.searchParams.set("pageSize", "50");
  if (contentType === "mod") {
    const loaderId = LOADER_IDS[String(loader || "fabric").toLowerCase()];
    if (loaderId) url.searchParams.set("modLoaderType", String(loaderId));
  }

  const response = await fetch(url, { headers: headers(apiKey) });
  if (!response.ok) return { ok: false, message: `CurseForge file lookup failed with ${response.status}.` };

  const files = (await response.json()).data || [];
  if (!files.length) return { ok: false, message: "No CurseForge file matches this Minecraft version." };

  const sorted = files.sort((a, b) => new Date(b.fileDate).getTime() - new Date(a.fileDate).getTime());
  const usable = sorted.find((f) => f.downloadUrl);
  if (!usable) {
    return {
      ok: false,
      message: "The author has disabled third-party downloads for this project. Open it on CurseForge to install manually."
    };
  }

  return {
    ok: true,
    file: {
      id: usable.id,
      fileName: usable.fileName,
      downloadUrl: usable.downloadUrl,
      displayName: usable.displayName,
      date: usable.fileDate,
      // Required dependencies are relationType 3 in CurseForge's schema.
      dependencies: (usable.dependencies || [])
        .filter((d) => d.relationType === 3)
        .map((d) => d.modId)
    }
  };
}

async function getMod({ modId, apiKey }) {
  const response = await fetch(`${API}/mods/${modId}`, { headers: headers(apiKey) });
  if (!response.ok) return null;
  return (await response.json()).data || null;
}

module.exports = { search, resolveFile, getMod, classIdFor, CLASS_IDS, LOADER_IDS };

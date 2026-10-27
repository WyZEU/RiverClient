// An exported .rvr lists what the instance had, and carries the files that have no project to
// download them from. Importing has to bring the rest back, so this works out what: the
// exported manifest names the Modrinth project and the exact version of each file, the
// archive's own folders hold the others, and anything left is a file nothing can restore.
const SECTIONS = [
  ["mods", "mod"],
  ["resourcepacks", "resourcepack"],
  ["shaders", "shader"]
];

function objectOf(value) {
  return value && typeof value === "object" && !Array.isArray(value) ? value : {};
}

/**
 * Modrinth project ids are eight letters and digits. CurseForge ids are plain numbers, and a
 * manifest entry's projectId is whichever site the file came from, so asking Modrinth for a
 * CurseForge id would only ever fail.
 */
function isModrinthProjectId(value) {
  return typeof value === "string" && /^[A-Za-z0-9]{8}$/.test(value) && !/^\d+$/.test(value);
}

function fromModrinth(entry) {
  if (!entry || typeof entry !== "object") return false;
  if (entry.source && entry.source !== "modrinth") return false;
  return isModrinthProjectId(entry.projectId);
}

/**
 * @param manifest     the archive's riv3r-mods.json
 * @param contentIndex the archive's content-index.json
 * @param hasFile      (contentType, file) => whether the imported instance already has the file
 * @param isCore       file => whether River stages the file itself
 * @returns {{ keep: object, restore: object[], local: object[] }}
 *   keep: manifest entries for files already present, by section;
 *   restore: what to download, one per Modrinth project;
 *   local: files with no Modrinth project to download them from.
 */
function planRvrRestore(manifest, contentIndex, hasFile, isCore = () => false) {
  const keep = { mods: {}, resourcepacks: {}, shaders: {} };
  const restore = [];
  const local = [];
  const handled = new Set();
  const projects = new Set();

  const addRestore = (contentType, file, entry) => {
    const key = `${contentType}:${entry.projectId}`;
    if (projects.has(key)) return;
    projects.add(key);
    restore.push({
      contentType,
      file,
      title: entry.title || file,
      projectId: entry.projectId,
      versionId: entry.versionId || "",
      disabled: Boolean(entry.disabled) || /\.disabled$/i.test(file)
    });
  };

  for (const [section, contentType] of SECTIONS) {
    for (const [file, meta] of Object.entries(objectOf(manifest?.[section]))) {
      if (!meta || typeof meta !== "object") continue;
      handled.add(`${section}:${file}`);
      if (hasFile(contentType, file)) {
        keep[section][file] = meta;
      } else if (fromModrinth(meta)) {
        addRestore(contentType, file, meta);
      } else if (!isCore(file) && meta.source !== "river") {
        local.push({ contentType, file, title: meta.title || file });
      }
    }

    const indexed = Array.isArray(contentIndex?.[section]) ? contentIndex[section] : [];
    for (const entry of indexed) {
      const file = entry?.file;
      if (!file || handled.has(`${section}:${file}`) || hasFile(contentType, file) || isCore(file)) continue;
      handled.add(`${section}:${file}`);
      if (fromModrinth(entry)) addRestore(contentType, file, entry);
      else local.push({ contentType, file, title: entry.title || file });
    }
  }
  return { keep, restore, local };
}

module.exports = { planRvrRestore, isModrinthProjectId };

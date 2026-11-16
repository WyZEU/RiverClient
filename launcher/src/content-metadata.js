// A lookup made for an earlier installation must not be applied to its replacement,
// even if both releases use the same filename. Presentation fields can change freely.
function sameInstalledContent(before, current) {
  return Boolean(before && current) && [
    "source", "projectId", "versionId", "versionNumber", "installedAt", "gameVersion", "loader"
  ].every((key) => (before[key] || "") === (current[key] || "")) &&
    (Boolean(before.projectId) || (before.slug || "") === (current.slug || ""));
}

function mergeMissingMetadata(current, patch) {
  const next = { ...current };
  for (const key of ["projectId", "slug", "title", "author"]) {
    if (!next[key] && patch[key]) next[key] = patch[key];
  }
  if (!next.iconUrl && patch.iconUrl) {
    next.iconUrl = patch.iconUrl;
    next.remoteIconUrl = next.remoteIconUrl || patch.remoteIconUrl;
  }
  next.iconCheckedAt = patch.iconCheckedAt;
  return next;
}

module.exports = { sameInstalledContent, mergeMissingMetadata };

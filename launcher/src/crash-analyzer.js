"use strict";

/**
 * Crash analysis.
 *
 * A crash report plus 200 mods is not actionable for a player - "here is the log"
 * is where most launchers stop. Minecraft crash reports do contain the answer: the
 * "Mixins in Stacktrace" block lists exactly which mods injected into the class
 * that blew up. This turns that into a ranked set of suspects and concrete fixes.
 *
 * Deliberately conservative: it says "likely", never "certain", and always exposes
 * the evidence, because a mixin in the stack is strong correlation, not proof.
 */

/** Packages that are never a culprit worth surfacing to a player. */
const IGNORED_MIXIN_PREFIXES = [
  "net.minecraft",
  "net.fabricmc.fabric",
  "com.llamalad7.mixinextras",
  "org.spongepowered"
];

/** Mixin package root -> mod id, where the package does not match the mod name. */
const PACKAGE_HINTS = [
  ["com.seibel.distanthorizons", "distanthorizons"],
  ["net.caffeinemc.mods.sodium", "sodium"],
  ["me.jellysquid.mods.sodium", "sodium"],
  ["dev.lambdaurora.lambdynlights", "lambdynlights"],
  ["dev.lambdaurora.spruceui", "spruceui"],
  ["io.wispforest.owo", "owo"],
  ["dev.tr7zw.entityculling", "entityculling"],
  ["ca.fxco.moreculling", "moreculling"],
  ["btw.lowercase.skyboxify", "skyboxify"],
  ["com.moulberry.flashback", "flashback"],
  ["com.moulberry.axiom", "axiom"],
  ["me.pepperbell.continuity", "continuity"],
  ["dev.isxander.debugify", "debugify"],
  ["net.blay09.mods.balm", "balm"],
  ["dev.kir.cubeswithoutborders", "cubes-without-borders"],
  ["fabric.me.thosea.badoptimizations", "badoptimizations"],
  ["me.thosea.badoptimizations", "badoptimizations"],
  ["net.natural.motionblur", "naturalmotionblur"],
  ["dev.zelo.renderscale", "renderscale"],
  ["org.redlance.dima_dencep.mods.rrls", "rrls"],
  ["dev.wyz.clientcore", "clientcore"]
];

/** Mods River ships or depends on; blaming these is almost always wrong. */
const RIVER_OWNED = new Set(["clientcore", "fabric-api", "fabricloader"]);

function modIdFromMixinClass(mixinClass) {
  for (const [prefix, id] of PACKAGE_HINTS) {
    if (mixinClass.startsWith(prefix + ".")) return id;
  }
  // Fall back to the mixin config in the trailing parenthesis:
  // "(skyboxify.mixins.json)" -> "skyboxify".
  const config = mixinClass.match(/\(([^)]+)\.mixins\.json\)/);
  if (config) return config[1].replace(/\.(client|common)$/, "");
  return "";
}

/**
 * Mods whose mixins appear in the crashing frames, most-implicated first.
 *
 * Scoring is NOT a count of mixins. Sodium, owo and friends inject into nearly
 * every render class, so counting occurrences simply ranks the most prolific mixin
 * mod every time - which is almost always the wrong answer. Instead:
 *
 *   - a mixin on the class that actually threw counts far more than one further up;
 *   - a mod present across most of the listed classes is LESS informative, not
 *     more, so breadth is penalised - "patches everything" tells us nothing;
 *   - an out-of-date mod is boosted hard: a mod built against an older Minecraft is
 *     the most common cause of a null vanilla field, and it is also the one suspect
 *     with an obvious fix.
 */
function suspectsFromReport(text, outdatedIds = new Set()) {
  const block = text.match(/Mixins in Stacktrace:\r?\n([\s\S]*?)(?:\r?\n\r?\n|-- )/);
  if (!block) return [];

  // Group entries under the class they were applied to; the first is the thrower.
  const classes = [];
  for (const line of block[1].split(/\r?\n/)) {
    if (!line.trim()) continue;
    const indent = line.length - line.trimStart().length;
    if (line.trim().endsWith(":") && indent <= 1) {
      classes.push([]);
      continue;
    }
    if (!classes.length) continue;
    classes[classes.length - 1].push(line.trim());
  }
  if (!classes.length) return [];

  const totalClasses = classes.length;
  const hits = new Map();

  classes.forEach((entries, classIndex) => {
    for (const entry of entries) {
      if (IGNORED_MIXIN_PREFIXES.some((prefix) => entry.startsWith(prefix))) continue;
      const id = modIdFromMixinClass(entry);
      if (!id || RIVER_OWNED.has(id)) continue;
      const record = hits.get(id) || { classes: new Set(), inThrower: false };
      record.classes.add(classIndex);
      if (classIndex === 0) record.inThrower = true;
      hits.set(id, record);
    }
  });

  const scored = [];
  for (const [id, record] of hits) {
    // Only mods that patched the class which actually threw are candidates at all.
    if (!record.inThrower) continue;
    let score = 3;
    const breadth = record.classes.size / totalClasses;
    if (breadth > 0.5) score -= 2;
    else if (breadth > 0.25) score -= 1;
    if (outdatedIds.has(id)) score += 4;
    scored.push({ id, score, outdated: outdatedIds.has(id) });
  }

  return scored.sort((a, b) => {
    if (a.outdated !== b.outdated) return a.outdated ? -1 : 1;
    return b.score - a.score;
  });
}

/** Human-readable summary of what actually went wrong. */
function describeFailure(text) {
  if (/OutOfMemoryError|GC overhead limit/.test(text)) {
    return { kind: "oom", detail: "The game ran out of memory." };
  }
  if (/Mixin apply(ing)? failed|InvalidInjectionException/i.test(text)) {
    return { kind: "mixin", detail: "A mod failed to patch the game, usually a version mismatch." };
  }
  if (/Encountered exception while building chunk meshes/.test(text)) {
    return { kind: "chunk", detail: "A chunk failed to build, usually a broken resource pack or shader." };
  }
  if (/java\.lang\.NullPointerException/.test(text)) {
    return {
      kind: "npe",
      detail: "Something the game expected to exist was missing. This usually means a mod is built for a different Minecraft version."
    };
  }
  const desc = text.match(/Description: ([^\r\n]+)/);
  return { kind: "unknown", detail: desc ? desc[1].trim() : "The game stopped unexpectedly." };
}

/** Cross-cutting problems worth calling out even when no mixin matches. */
function extraFindings(logText) {
  const findings = [];
  if (/getSpriteFinder\(\)" is null/.test(logText)) {
    findings.push({
      severity: "warning",
      title: "A resource pack is breaking chunk rendering",
      detail: "A texture atlas could not be read. This is usually a resource pack missing its pack format metadata, or built for a different Minecraft version."
    });
  }
  if (/OutOfMemoryError|GC overhead limit/.test(logText)) {
    findings.push({
      severity: "error",
      title: "Out of memory",
      detail: "Raise the memory slider in Settings, or lower render distance and shader quality."
    });
  }
  return findings;
}

/**
 * @param {string} crashText Contents of the crash report.
 * @param {string} logText   Contents of latest.log (optional, adds findings).
 * @param {Array}  installed getInstalledMods() output, used to map ids to jars.
 */
function analyzeCrash(crashText, logText, installed) {
  const text = String(crashText || "");
  if (!text.trim()) return null;

  const log = String(logText || "");
  const mods = Array.isArray(installed) ? installed : [];
  const failure = describeFailure(text);

  // Mods with a pending update: from the mod manifest, and from the line the
  // update checker prints on boot.
  const outdated = new Set();
  for (const mod of mods) {
    if (!mod || !mod.update) continue;
    const id = String((mod.metadata && mod.metadata.id) || "").toLowerCase();
    if (id) outdated.add(id);
  }
  for (const match of log.matchAll(/Update available for '([^'@]+)@/g)) {
    outdated.add(match[1].toLowerCase());
  }

  const suspects = suspectsFromReport(text, outdated);

  // Map a suspect id back to the real jar so the UI can act on it.
  const byId = new Map();
  for (const mod of mods) {
    if (!mod) continue;
    const id = String((mod.metadata && mod.metadata.id) || "").toLowerCase();
    if (id) byId.set(id, mod);
    const fromFile = String(mod.file || "").toLowerCase().replace(/\.jar(\.disabled)?$/, "");
    if (fromFile) byId.set(fromFile.replace(/[-_]?\d[\d.+-]*$/, ""), mod);
  }

  const resolved = suspects.slice(0, 8).map((suspect) => {
    const mod = byId.get(suspect.id) || null;
    return {
      id: suspect.id,
      score: suspect.score,
      outdated: Boolean(suspect.outdated),
      file: (mod && mod.file) || "",
      disabled: Boolean(mod && mod.disabled),
      required: Boolean(mod && mod.required),
      updateVersion: (mod && mod.update && mod.update.version) || ""
    };
  });

  const outdatedSuspects = resolved.filter((s) => s.outdated);

  // Honesty about how much the evidence actually narrows things down. With 20 mods
  // patching the crashing class and 20 more out of date, naming one culprit would be
  // invention - so only claim "likely" when the evidence really is that tight.
  let confidence = "unclear";
  if (outdatedSuspects.length === 1) confidence = "likely";
  else if (outdatedSuspects.length > 1) confidence = "narrowed";
  else if (resolved.length === 1) confidence = "likely";

  return {
    failure,
    suspects: resolved,
    outdatedCount: outdatedSuspects.length,
    findings: extraFindings(log),
    confidence
  };
}

module.exports = { analyzeCrash, suspectsFromReport, describeFailure };

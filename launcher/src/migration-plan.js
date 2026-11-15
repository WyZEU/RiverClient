// Everything comes across except mods, which only do when the source is known to be
// Fabric on exactly the same Minecraft version: a mod built for another version or
// loader stops the game from starting. Settings, keybinds, saved hotbars and mod configs
// are safe across versions - Minecraft upgrades options.txt itself, and a config for a
// mod that is not installed is simply never read - and losing them is what makes moving
// to a new launcher feel like starting over.
function migrationPlan(sourceVersion, sourceLoader, targetVersion, sourceLauncher = "") {
  const clientSpecific = /^(feather|lunar client|badlion)$/i.test(sourceLauncher);
  const compatible = !clientSpecific && sourceVersion === targetVersion && sourceLoader === "fabric";
  return {
    folders: compatible
      ? ["mods", "resourcepacks", "shaderpacks", "config", "saves", "screenshots"]
      : ["resourcepacks", "shaderpacks", "config", "saves", "screenshots"],
    files: ["options.txt", "servers.dat", "hotbar.nbt"],
    compatible
  };
}

function migrationVersion(sourceVersion, selectedVersion, supportedVersions) {
  return supportedVersions.includes(sourceVersion) ? sourceVersion : selectedVersion;
}

module.exports = { migrationPlan, migrationVersion };

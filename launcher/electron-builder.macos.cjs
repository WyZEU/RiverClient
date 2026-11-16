const pkg = require("./package.json");

const signed = process.env.RIVER_MAC_SIGNED === "1";
const notarize = process.env.RIVER_MAC_NOTARIZE === "1";
if (signed && !process.env.CSC_NAME) throw new Error("Signed Mac builds require CSC_NAME.");
if (notarize && (!signed || !process.env.APPLE_KEYCHAIN_PROFILE)) {
  throw new Error("Notarized Mac builds require signing and APPLE_KEYCHAIN_PROFILE.");
}
if (!/^\d+\.\d+\.\d+$/.test(pkg.riverVersion)) throw new Error("Mac builds require a three-part riverVersion.");

module.exports = {
  ...pkg.build,
  extends: null,
  directories: { ...pkg.build.directories, output: "dist/macos" },
  extraMetadata: { version: pkg.riverVersion },
  artifactName: "River-Client-${version}-macOS-${arch}.${ext}",
  // Java needs real JARs outside ASAR. Keep just the extraResources copy, and
  // omit the Windows media helper from the Mac application.
  files: [...pkg.build.files, "!app/bundled/**/*", "!app/np-helper.exe", "!app/now-playing.ps1"],
  asarUnpack: [],
  fileAssociations: pkg.build.fileAssociations.map((association) => ({ ...association, icon: "build/icon.icns" })),
  forceCodeSigning: signed,
  mac: {
    target: [{ target: "dmg", arch: ["arm64", "x64"] }],
    category: "public.app-category.games",
    minimumSystemVersion: "12.0",
    icon: "build/icon.icns",
    identity: signed ? process.env.CSC_NAME : "-",
    hardenedRuntime: signed,
    entitlements: "scripts/macos/entitlements.plist",
    entitlementsInherit: "scripts/macos/entitlements.plist",
    // These are sealed bundle resources, not executable code. osx-sign otherwise
    // adds a separate timestamped signature to every binary-format resource.
    signIgnore: ["\\.(?:jar|asar|pak|dat|bin|nib|icns)$"],
    notarize
  },
  dmg: {
    sign: signed,
    title: "River Client",
    contents: [
      { x: 130, y: 220, type: "file" },
      { x: 410, y: 220, type: "link", path: "/Applications" }
    ]
  },
  publish: null
};

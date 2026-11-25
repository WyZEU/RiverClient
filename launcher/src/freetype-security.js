const crypto = require("node:crypto");
const fs = require("node:fs/promises");
const path = require("node:path");
const { fileMatchesSha256 } = require("./river-mod-cache.js");
const { nativeClassifierKey } = require("./runtime-platform.js");

const FREETYPE_BINDING = "org.lwjgl:lwjgl-freetype:";
const UNPATCHED_BINDINGS = new Set(["3.3.3", "3.4.1", "3.4.3"]);
// Mojang's 26.3 security update ships FreeType 2.15.0. Use its native library with
// the original Java binding on profiles that still declare an unpatched build.
const PATCH_VERSION = "3.4.3-mojang-2.15.0";
const NATIVE_PATCHES = {
  "natives-linux": {
    entry: "linux/x64/org/lwjgl/freetype/libfreetype.so",
    archiveSha1: "f20ae220999240d484896d839694b02c2a655608",
    nativeSha256: "a9bbbffc48b294985093d879671589d28fcadeeaa174d9c17c94474919999c38"
  },
  "natives-macos": {
    entry: "macos/x64/org/lwjgl/freetype/libfreetype.dylib",
    archiveSha1: "6a46815640d9ce43533200986a330642a96e9590",
    nativeSha256: "520515cfa9a6b6bb33f250a632733196155d3006ec515ec43a6d90083eafd547"
  },
  "natives-macos-arm64": {
    entry: "macos/arm64/org/lwjgl/freetype/libfreetype.dylib",
    archiveSha1: "95ef1d3420179187f0b5e1ba947329b539e73412",
    nativeSha256: "62e0a3d0d107f4f718f8767e3241802e7820a0bdfcc58ad21f04ae7689321e7a"
  },
  "natives-windows": {
    entry: "windows/x64/org/lwjgl/freetype/freetype.dll",
    archiveSha1: "f85d0f5acaa594f9e93d657f8feff85d6a427b6a",
    nativeSha256: "cf79a6c3b6e8466fa9124ac795a6753512f6a715a768dc7d59b1de22ba19345b"
  },
  "natives-windows-arm64": {
    entry: "windows/arm64/org/lwjgl/freetype/freetype.dll",
    archiveSha1: "5f475c76490bb368f235017985d8d9dc6b5b5121",
    nativeSha256: "177938bf8ba2d05813844d963240771cc0a2033c66782a76d90c3a638f9847a8"
  }
};

function needsFreeTypeSecurityUpdate(libraries) {
  return libraries.some(({ name }) => typeof name === "string" && name.startsWith(FREETYPE_BINDING)
    && UNPATCHED_BINDINGS.has(name.slice(FREETYPE_BINDING.length).split(":")[0]));
}

async function prepareFreeTypeSecurityArgument({ runtimeRoot, platform = process.platform, javaArch, download, readZipEntry }) {
  const classifier = nativeClassifierKey(platform, javaArch);
  const native = NATIVE_PATCHES[classifier];
  if (!["win32", "darwin", "linux"].includes(platform) || !["x64", "arm64"].includes(javaArch) || !native) {
    throw new Error("The FreeType security update requires a supported 64-bit Java runtime.");
  }

  const directory = path.resolve(runtimeRoot, "security", "freetype", native.archiveSha1);
  const library = path.join(directory, path.posix.basename(native.entry));
  const argument = `-Dorg.lwjgl.freetype.libname=${library}`;
  if (await fileMatchesSha256(library, native.nativeSha256)) return argument;

  await fs.mkdir(directory, { recursive: true });
  const staging = await fs.mkdtemp(path.join(directory, ".install-"));
  try {
    const archiveName = `lwjgl-freetype-${PATCH_VERSION}-${classifier}.jar`;
    const archive = path.join(staging, archiveName);
    await download(`https://libraries.minecraft.net/org/lwjgl/lwjgl-freetype/${PATCH_VERSION}/${archiveName}`, archive, native.archiveSha1);
    const contents = readZipEntry(archive, native.entry);
    if (!contents || crypto.createHash("sha256").update(contents).digest("hex") !== native.nativeSha256) {
      throw new Error("The FreeType security library failed its checksum.");
    }
    const stagedLibrary = path.join(staging, path.basename(library));
    await fs.writeFile(stagedLibrary, contents);
    try {
      await fs.rename(stagedLibrary, library);
    } catch (error) {
      // Another launch may have installed and loaded the verified library on Windows.
      if (!(await fileMatchesSha256(library, native.nativeSha256))) throw error;
    }
  } finally {
    await fs.rm(staging, { recursive: true, force: true, maxRetries: 3 });
  }
  return argument;
}

module.exports = { needsFreeTypeSecurityUpdate, prepareFreeTypeSecurityArgument };

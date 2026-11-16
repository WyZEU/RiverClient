const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const {
  nativeClassifierKey, matchesOsRule, isMacNativeLibraryAllowed,
  macJavaCandidates, javaArchitectureMatches, manualUpdateTarget
} = require("../src/runtime-platform.js");

// Execute the actual launcher's library/argument selection functions together.
// This catches missing wiring as well as errors in the platform helpers.
function runtime(platform, arch) {
  const source = fs.readFileSync(path.join(__dirname, "../src/main.js"), "utf8");
  const functionSource = (name) => {
    const start = source.indexOf(`function ${name}(`);
    assert.notEqual(start, -1);
    const end = source.indexOf("\n}", start) + 2;
    return source.slice(start, end);
  };
  const context = vm.createContext({
    isMacNativeLibraryAllowed: (library) => isMacNativeLibraryAllowed(library, platform, arch),
    matchesOsRule: (rule) => matchesOsRule(rule, platform, arch, "25.0.0")
  });
  vm.runInContext(["isLibraryAllowed", "ruleMatches", "argumentValues", "mojangJvmFlags"].map(functionSource).join("\n"), context);
  return context;
}

test("Mac launch classpaths contain only matching native JARs and retain Java libraries", () => {
  const libraries = [
    { name: "org.lwjgl:lwjgl-glfw:3.3.3" },
    { name: "org.lwjgl:lwjgl-glfw:3.3.3:natives-macos", rules: [{ action: "allow", os: { name: "osx" } }] },
    { name: "org.lwjgl:lwjgl-glfw:3.3.3:natives-macos-arm64", rules: [{ action: "allow", os: { name: "osx" } }] },
    { name: "org.lwjgl:lwjgl-glfw:3.3.3:natives-windows", rules: [{ action: "allow", os: { name: "windows" } }] }
  ];
  for (const arch of ["arm64", "x64"]) {
    const selected = libraries.filter(runtime("darwin", arch).isLibraryAllowed).map((l) => l.name);
    assert.deepEqual(selected, ["org.lwjgl:lwjgl-glfw:3.3.3", `org.lwjgl:lwjgl-glfw:3.3.3:${nativeClassifierKey("darwin", arch)}`]);
  }
  assert.deepEqual(libraries.filter(runtime("win32", "x64").isLibraryAllowed).map((l) => l.name), [libraries[0].name, libraries[3].name]);
});

test("Mac JVM gets the first-thread flag, without Mojang's x86-only stack override", () => {
  const metadata = { arguments: { jvm: [
    { rules: [{ action: "allow", os: { name: "osx" } }], value: ["-XstartOnFirstThread"] },
    { rules: [{ action: "allow", os: { arch: "x86" } }], value: "-Xss1M" },
    "-XX:StackShadowPages=32", "-cp", "${classpath}"
  ] } };
  assert.deepEqual(Array.from(runtime("darwin", "arm64").mojangJvmFlags(metadata)), ["-XstartOnFirstThread", "-XX:StackShadowPages=32"]);
  assert.deepEqual(Array.from(runtime("win32", "x64").mojangJvmFlags(metadata)), ["-XX:StackShadowPages=32"]);
  assert.equal(matchesOsRule({ arch: "aarch64" }, "darwin", "arm64"), true);
  assert.equal(matchesOsRule({ name: "osx", version: "^24\\." }, "darwin", "arm64", "25.0.0"), false);
});

test("Java detection accepts native Temurin and rejects the other Mac architecture", () => {
  assert.equal(javaArchitectureMatches("    os.arch = aarch64\n", "arm64"), true);
  assert.equal(javaArchitectureMatches("    os.arch = x86_64\n", "x64"), true);
  assert.equal(javaArchitectureMatches("    os.arch = x86_64\n", "arm64"), false);
  assert.equal(javaArchitectureMatches("Unable to locate a Java Runtime.", "arm64"), false);
  assert.deepEqual(macJavaCandidates("/Users/test"), ["/Library/Java/JavaVirtualMachines", path.join("/Users/test", "Library/Java/JavaVirtualMachines")]);
});

test("Mac updates never open a Windows installer or a Linux archive", () => {
  const fallback = "https://riverclient.xyz/";
  const update = { installerUrl: "https://example.com/setup.exe", linuxUrl: "https://example.com/linux.tar.gz" };
  assert.equal(manualUpdateTarget(update, fallback, "darwin"), fallback);
  assert.equal(manualUpdateTarget({ ...update, macUrl: "https://example.com/mac.dmg" }, fallback, "darwin"), "https://example.com/mac.dmg");
  assert.equal(manualUpdateTarget(update, fallback, "linux"), update.linuxUrl);
  assert.equal(manualUpdateTarget(update, fallback, "win32"), update.installerUrl);
});

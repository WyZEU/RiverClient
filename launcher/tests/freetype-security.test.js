const assert = require("node:assert/strict");
const fs = require("node:fs/promises");
const os = require("node:os");
const path = require("node:path");
const test = require("node:test");
const { needsFreeTypeSecurityUpdate, prepareFreeTypeSecurityArgument } = require("../src/freetype-security.js");

test("FreeType backport covers the old bindings and leaves Mojang's fixed profile in place", () => {
  for (const version of ["3.3.3", "3.4.1", "3.4.3"]) {
    assert.equal(needsFreeTypeSecurityUpdate([{ name: `org.lwjgl:lwjgl-freetype:${version}` }]), true);
  }
  assert.equal(needsFreeTypeSecurityUpdate([{ name: "org.lwjgl:lwjgl-freetype:3.4.3:natives-windows" }]), true);
  assert.equal(needsFreeTypeSecurityUpdate([{ name: "org.lwjgl:lwjgl-freetype:3.4.3-mojang-2.15.0" }]), false);
  assert.equal(needsFreeTypeSecurityUpdate([{ name: "org.lwjgl:lwjgl-stb:3.3.3" }]), false);
});

test("the patch selects the JVM architecture and rejects a mismatched native library", async (t) => {
  const runtimeRoot = await fs.mkdtemp(path.join(os.tmpdir(), "river-font-test-"));
  t.after(() => fs.rm(runtimeRoot, { recursive: true, force: true }));
  let downloaded = false;
  const archiveSha1 = "5f475c76490bb368f235017985d8d9dc6b5b5121";
  const cache = path.join(runtimeRoot, "security", "freetype", archiveSha1);

  await assert.rejects(prepareFreeTypeSecurityArgument({
    runtimeRoot,
    platform: "win32",
    javaArch: "arm64",
    async download(url, target, sha1) {
      downloaded = true;
      assert.equal(new URL(url).origin, "https://libraries.minecraft.net");
      assert.match(url, /lwjgl-freetype-3\.4\.3-mojang-2\.15\.0-natives-windows-arm64\.jar$/);
      assert.equal(sha1, archiveSha1);
      await fs.writeFile(target, "archive fixture");
    },
    readZipEntry(_archive, entry) {
      assert.equal(entry, "windows/arm64/org/lwjgl/freetype/freetype.dll");
      return Buffer.from("unexpected library");
    }
  }), /failed its checksum/);

  assert.equal(downloaded, true);
  assert.deepEqual(await fs.readdir(cache), []);
});

test("a failed download cannot leave an installed library", async (t) => {
  const runtimeRoot = await fs.mkdtemp(path.join(os.tmpdir(), "river-font-test-"));
  t.after(() => fs.rm(runtimeRoot, { recursive: true, force: true }));
  await assert.rejects(prepareFreeTypeSecurityArgument({
    runtimeRoot,
    platform: "win32",
    javaArch: "x64",
    async download(_url, target) {
      await fs.writeFile(target, "partial download");
      throw new Error("offline");
    },
    readZipEntry() {
      assert.fail("A failed download cannot be extracted");
    }
  }), /offline/);
  const cacheRoot = path.join(runtimeRoot, "security", "freetype");
  const [directory] = await fs.readdir(cacheRoot);
  assert.deepEqual(await fs.readdir(path.join(cacheRoot, directory)), []);
});

test("unavailable 32-bit FreeType patches stop launch preparation", async () => {
  await assert.rejects(prepareFreeTypeSecurityArgument({ platform: "win32", javaArch: "ia32" }), /64-bit Java runtime/);
});

import test from "node:test";
import assert from "node:assert/strict";
import { curseForgeProxy, isAllowedCurseForgePath } from "../src/curseforge-proxy.js";

test("only the launcher's read-only lookups are passed on", () => {
  assert.equal(isAllowedCurseForgePath("/mods/search"), true);
  assert.equal(isAllowedCurseForgePath("/mods/238222"), true);
  assert.equal(isAllowedCurseForgePath("/mods/238222/files"), true);
  assert.equal(isAllowedCurseForgePath("/mods/238222/files/4567"), true);
  assert.equal(isAllowedCurseForgePath("/mods/238222/files/4567/download-url"), false);
  assert.equal(isAllowedCurseForgePath("/games"), false);
  assert.equal(isAllowedCurseForgePath("/mods/../games"), false);
});

test("the key is added upstream and never comes back", async () => {
  const seen = [];
  const realFetch = globalThis.fetch;
  globalThis.fetch = async (url, init) => {
    seen.push({ url: String(url), key: init.headers["x-api-key"] });
    return new Response(JSON.stringify({ data: [{ id: 1 }] }), { status: 200 });
  };
  try {
    const res = await curseForgeProxy(new Request("https://updates.riverclient.xyz/cf/v1/mods/search?gameId=432&searchFilter=sodium"), { CURSEFORGE_API_KEY: "secret-key" });
    assert.equal(res.status, 200);
    assert.deepEqual((await res.json()).data, [{ id: 1 }]);
    assert.equal(seen[0].url, "https://api.curseforge.com/v1/mods/search?gameId=432&searchFilter=sodium");
    assert.equal(seen[0].key, "secret-key");
    assert.ok(![...res.headers.values()].some((v) => v.includes("secret-key")));
  } finally {
    globalThis.fetch = realFetch;
  }
});

test("no key configured, no write methods, no other paths", async () => {
  assert.equal((await curseForgeProxy(new Request("https://x/cf/v1/mods/search"), {})).status, 503);
  assert.equal((await curseForgeProxy(new Request("https://x/cf/v1/mods/search", { method: "POST" }), { CURSEFORGE_API_KEY: "k" })).status, 405);
  assert.equal((await curseForgeProxy(new Request("https://x/cf/v1/games"), { CURSEFORGE_API_KEY: "k" })).status, 404);
});

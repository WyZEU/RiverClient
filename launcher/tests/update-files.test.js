const test = require("node:test");
const assert = require("node:assert/strict");
const crypto = require("node:crypto");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { buildDifferentialUpdatePlan, downloadFileViaBlockmap } = require("../src/update-files.js");
const { buildBlockmap } = require("../scripts/blockmap.js");

const sha256 = (bytes) => crypto.createHash("sha256").update(bytes).digest("hex");
const baseUrl = "https://updates.example/releases/0.3.0/app/";

function temporaryDirectory(t) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "river-update-test-"));
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  return directory;
}

function entry(name, bytes, extra = {}) {
  return { path: name, size: bytes.length, sha256: sha256(bytes), url: new URL(name, baseUrl).href, ...extra };
}

test("the download plan keeps blockmaps and selects missing, resized and hash-changed files", async (t) => {
  const installDir = temporaryDirectory(t);
  fs.mkdirSync(path.join(installDir, "resources"));
  fs.writeFileSync(path.join(installDir, "same.dll"), "unchanged");
  fs.writeFileSync(path.join(installDir, "resized.dll"), "short");
  fs.writeFileSync(path.join(installDir, "resources", "app.asar"), "old bytes");
  const blockmapUrl = `${baseUrl}resources/app.asar.blockmap.json`;
  const changed = entry("resources\\app.asar", "new bytes", { blockmapUrl: ` ${blockmapUrl} ` });
  changed.sha256 = changed.sha256.toUpperCase();
  const plan = await buildDifferentialUpdatePlan({
    version: "0.3.0",
    files: [entry("same.dll", "unchanged"), entry("missing.dll", "new"), entry("resized.dll", "longer bytes"), changed]
  }, installDir);
  assert.deepEqual(plan.downloads.map((file) => file.path), ["missing.dll", "resized.dll", "resources/app.asar"]);
  assert.equal(plan.downloads[2].blockmapUrl, blockmapUrl);
  assert.equal(plan.files[3].blockmapUrl, blockmapUrl);
  assert.equal(plan.downloads[2].sha256, sha256("new bytes"));
  assert.equal(plan.totalBytes, 3 + 12 + 9);
});

async function patchFixture(t) {
  const root = temporaryDirectory(t);
  const installDir = path.join(root, "installed");
  fs.mkdirSync(installDir);
  const localPath = path.join(installDir, "app.asar");
  const publishedPath = path.join(root, "published.asar");
  const target = path.join(root, "staged.asar");
  // Distinct deterministic chunks, with only a small edit in the new release.
  const before = Buffer.alloc(2 * 1024 * 1024);
  let seed = 123456;
  for (let i = 0; i < before.length; i++) {
    seed ^= seed << 13; seed ^= seed >>> 17; seed ^= seed << 5;
    before[i] = seed & 255;
  }
  const after = Buffer.from(before);
  after.fill(0x5a, 600000, 604096);
  fs.writeFileSync(localPath, before);
  fs.writeFileSync(publishedPath, after);
  const blockmapUrl = `${baseUrl}app.asar.blockmap.json`;
  const manifest = { version: "0.3.0", files: [entry("app.asar", after, { blockmapUrl })] };
  const plan = await buildDifferentialUpdatePlan(manifest, installDir);
  assert.equal(plan.downloads.length, 1);
  return { file: plan.downloads[0], localPath, target, after, blockmapUrl, map: buildBlockmap(publishedPath) };
}

function downloads(fixture, { corrupt = false, rangeStatus = 206, map = fixture.map } = {}) {
  const requests = [];
  let downloadedBytes = 0;
  return {
    requests,
    get downloadedBytes() { return downloadedBytes; },
    async fetch(url, options) {
      requests.push(url);
      assert.equal(options.headers["User-Agent"], "RiverClient/test");
      if (url === fixture.blockmapUrl) return Response.json(map);
      assert.equal(url, fixture.file.url);
      const range = /^bytes=(\d+)-(\d+)$/.exec(options.headers.Range);
      assert.ok(range, "changed bytes are fetched using HTTP Range");
      const bytes = Buffer.from(fixture.after.subarray(Number(range[1]), Number(range[2]) + 1));
      downloadedBytes += bytes.length;
      if (corrupt) bytes[0] ^= 0xff;
      return new Response(bytes, { status: rangeStatus });
    }
  };
}

test("planned ASAR update reuses local chunks and reconstructs the published SHA-256", async (t) => {
  const fixture = await patchFixture(t);
  const network = downloads(fixture);
  const progress = [];
  const patched = await downloadFileViaBlockmap(fixture.file, fixture.localPath, fixture.target, (value) => progress.push(value), {
    fetch: network.fetch, userAgent: "RiverClient/test"
  });
  assert.equal(patched, true);
  assert.equal(network.requests[0], fixture.blockmapUrl);
  assert.ok(network.downloadedBytes > 0 && network.downloadedBytes < fixture.after.length / 4);
  assert.equal(progress.at(-1).downloaded, network.downloadedBytes);
  assert.deepEqual(fs.readFileSync(fixture.target), fixture.after);
  assert.equal(sha256(fs.readFileSync(fixture.target)), fixture.file.sha256);
});

test("corrupt range bytes fail the whole-file hash and request the full-download fallback", async (t) => {
  const fixture = await patchFixture(t);
  const network = downloads(fixture, { corrupt: true });
  const patched = await downloadFileViaBlockmap(fixture.file, fixture.localPath, fixture.target, null, {
    fetch: network.fetch, userAgent: "RiverClient/test"
  });
  assert.equal(patched, false);
  assert.ok(network.downloadedBytes > 0);
  assert.equal(fs.existsSync(fixture.target), false);
});

test("a blockmap cannot override the trusted whole-file hash", async (t) => {
  const fixture = await patchFixture(t);
  const network = downloads(fixture);
  const patched = await downloadFileViaBlockmap({ ...fixture.file, sha256: "0".repeat(64) }, fixture.localPath, fixture.target, null, {
    fetch: network.fetch, userAgent: "RiverClient/test"
  });
  assert.equal(patched, false);
  assert.ok(network.downloadedBytes > 0);
  assert.equal(fs.existsSync(fixture.target), false);
});

test("a server ignoring Range requests falls back to a full-file download", async (t) => {
  const fixture = await patchFixture(t);
  const network = downloads(fixture, { rangeStatus: 200 });
  assert.equal(await downloadFileViaBlockmap(fixture.file, fixture.localPath, fixture.target, null, {
    fetch: network.fetch, userAgent: "RiverClient/test"
  }), false);
  assert.ok(network.downloadedBytes > 0);
});

test("files without a blockmap keep the ordinary download path", async (t) => {
  const fixture = await patchFixture(t);
  let requests = 0;
  assert.equal(await downloadFileViaBlockmap({ ...fixture.file, blockmapUrl: "" }, fixture.localPath, fixture.target, null, {
    fetch: async () => { requests++; throw new Error("must not fetch"); }
  }), false);
  assert.equal(requests, 0);
});

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const crypto = require("node:crypto");
const { execFileSync } = require("node:child_process");
const { zip } = require("./fixtures/signed-release");
const { verifyMacArtifact, prepareMacPublication, validateMacPublication, signMacPublication } = require("../scripts/prepare-macos-publication");
const { publishMac } = require("../scripts/publish-macos");
const { validateMacBuildSource, downloadSignedBuild } = require("../scripts/download-signed-build");
const { signManifest, verifyManifest } = require("../src/update-signature");
const { fetchMacUpdateManifest } = require("../src/macos-updates");
const { hash } = require("../scripts/prepare-signed-update");
const config = require("../update-publishing.json");

function fixture(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "river-mac-publication-"));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const artifactDirectory = path.join(root, "artifacts"), outputDirectory = path.join(root, "prepared");
  const commit = "c".repeat(40), version = "0.3.1";
  const pair = crypto.generateKeyPairSync("ed25519");
  const privateKey = pair.privateKey.export({ type: "pkcs8", format: "pem" });
  const publicKey = pair.publicKey.export({ type: "spki", format: "der" }).toString("base64");
  for (const arch of ["arm64", "x64"]) {
    const dir = path.join(artifactDirectory, arch);
    fs.mkdirSync(dir, { recursive: true });
    const bytes = Buffer.from(`synthetic ${arch} DMG`), file = `River-Client-${version}-macOS-${arch}.dmg`;
    const info = { sourceCommit: commit, sourceDirty: false, version, artifacts: [{ file, arch, signed: true, notarized: true,
      size: bytes.length, sha256: hash(bytes), notarizationId: "11111111-2222-4333-8444-555555555555" }] };
    fs.writeFileSync(path.join(dir, file), bytes);
    for (const name of ["build-result.json", `build-result-${arch}.json`]) fs.writeFileSync(path.join(dir, name), JSON.stringify(info));
    fs.writeFileSync(path.join(dir, `notarization-${arch}.json`), JSON.stringify({ status: "Accepted", id: info.artifacts[0].notarizationId }));
    fs.writeFileSync(path.join(dir, "SHA256SUMS"), `${hash(bytes)}  ${file}\n`);
  }
  const options = { artifactDirectory, outputDirectory, expectedCommit: commit, publishedAt: "2026-11-16T12:00:00.000Z",
    notes: { version, approved: true, title: "Mac release", summary: "Mac downloads", items: ["Apple Silicon and Intel downloads."] } };
  return { root, artifactDirectory, outputDirectory, commit, version, privateKey, publicKey, options };
}

function remoteStore(f) {
  const objects = new Map([["latest.json", Buffer.from('{"version":"0.3.0","unchanged":true}')], ["beta.json", Buffer.from("unchanged beta")]]);
  const operations = [];
  const store = {
    async head(key) { return objects.has(key) ? { ETag: hash(objects.get(key)) } : null; },
    async getJson(key) { const bytes = objects.get(key); return bytes ? { value: JSON.parse(bytes), etag: hash(bytes) } : null; },
    async verify(key, expected) { assert.equal(objects.get(key)?.length, expected.size); assert.equal(hash(objects.get(key)), expected.sha256); },
    async putFile(key, file) { assert.equal(objects.has(key), false); operations.push(key); objects.set(key, fs.readFileSync(file)); },
    async putJson(key, bytes, etag) {
      assert.equal(objects.has(key) ? hash(objects.get(key)) : null, etag || null, "PreconditionFailed");
      operations.push(key); objects.set(key, Buffer.from(bytes));
    }
  };
  const fetchRemote = async (url) => {
    if (url.endsWith("/release-capabilities.json")) return Response.json({ publicationProtocol: 1, macosPublicationProtocol: 1 });
    const arch = /\/River-Client-macOS-(arm64|x64)\.dmg$/.exec(url)?.[1];
    if (arch) return new Response(null, { status: 302, headers: { location: JSON.parse(objects.get("macos.json")).macUrls[arch] } });
    const key = url.endsWith("/macos.json") ? "macos.json" : decodeURIComponent(new URL(url).pathname.slice("/downloads/".length));
    return objects.has(key) ? new Response(objects.get(key)) : new Response("missing", { status: 404 });
  };
  const publish = (overrides = {}) => publishMac({ directory: f.outputDirectory, store, fetchRemote, publicKey: f.publicKey,
    expectedPreviousVersion: "none", ...overrides });
  return { objects, operations, store, fetchRemote, publish };
}

async function ready(t) {
  const f = fixture(t);
  await prepareMacPublication(f.options);
  f.manifest = await signMacPublication(f.outputDirectory, f.privateKey, f.publicKey);
  return { ...f, ...remoteStore(f) };
}

test("both original Mac DMGs and checksums form an independently signed, optional release", async (t) => {
  const f = await ready(t);
  const { plan, manifest } = await validateMacPublication(f.outputDirectory);
  assert.equal(plan.objects.length, 3);
  assert.equal(manifest.required, false);
  assert.equal(manifest.minimumVersion, undefined);
  assert.equal(verifyManifest(f.manifest, f.publicKey), true);
  for (const arch of ["arm64", "x64"]) {
    const name = manifest.files.macos[arch].name;
    assert.deepEqual(fs.readFileSync(path.join(f.artifactDirectory, arch, name)), fs.readFileSync(path.join(f.outputDirectory, name)));
  }
  await assert.rejects(signMacPublication(f.outputDirectory, f.privateKey), /existing River update key/);
  const stdout = execFileSync(process.execPath, [path.resolve(__dirname, "../scripts/publish-macos.js"), "--prepared", f.outputDirectory], { encoding: "utf8", env: { PATH: process.env.PATH } });
  assert.match(stdout, /No credentials loaded or remote writes/);
});

test("wrong commits, unsigned, dirty, mismatched, unnotarized and changed Mac builds are rejected", async (t) => {
  for (const patch of [{ sourceCommit: "d".repeat(40) }, { sourceDirty: true }, { artifacts: [] }]) {
    const f = fixture(t), file = path.join(f.artifactDirectory, "arm64/build-result.json");
    fs.writeFileSync(file, JSON.stringify({ ...JSON.parse(fs.readFileSync(file)), ...patch }));
    await assert.rejects(verifyMacArtifact(path.dirname(file), f.commit, "arm64"));
  }
  for (const patch of [{ signed: false }, { notarized: false }, { arch: "x64" }, { notarizationId: "other" }]) {
    const f = fixture(t), dir = path.join(f.artifactDirectory, "arm64");
    for (const name of ["build-result.json", "build-result-arm64.json"]) {
      const file = path.join(dir, name), info = JSON.parse(fs.readFileSync(file));
      Object.assign(info.artifacts[0], patch); fs.writeFileSync(file, JSON.stringify(info));
    }
    await assert.rejects(verifyMacArtifact(dir, f.commit, "arm64"));
  }
  const f = fixture(t);
  fs.appendFileSync(path.join(f.artifactDirectory, "x64", `River-Client-${f.version}-macOS-x64.dmg`), "changed");
  await assert.rejects(prepareMacPublication(f.options), /checksum/);
  assert.equal(fs.existsSync(f.outputDirectory), false);
});

test("unapproved release notes and modified prepared DMGs cannot publish", async (t) => {
  const f = fixture(t);
  await assert.rejects(prepareMacPublication({ ...f.options, notes: { ...f.options.notes, approved: false } }), /approved/);
  await prepareMacPublication(f.options);
  await signMacPublication(f.outputDirectory, f.privateKey, f.publicKey);
  const remote = remoteStore(f);
  fs.appendFileSync(path.join(f.outputDirectory, `River-Client-${f.version}-macOS-arm64.dmg`), "changed");
  await assert.rejects(remote.publish(), /changed/);
  assert.deepEqual(remote.operations, []);
});

test("Mac promotion is atomic, retryable and leaves Windows, beta, Linux and mod history alone", async (t) => {
  const f = await ready(t), before = new Map(f.objects);
  const receipt = await f.publish();
  assert.equal(receipt.publicationCommitted, true);
  assert.equal(receipt.assetsVerified, true);
  assert.equal(f.operations.at(-1), "macos.json");
  for (const [key, value] of before) assert.deepEqual(f.objects.get(key), value);
  assert.equal(f.objects.has("releases/0.3.1/published.json"), false);
  const count = f.operations.length;
  await f.publish();
  assert.equal(f.operations.length, count);
});

test("missing Worker, stale reviewed versions, upload failure and immutable conflicts cannot promote", async (t) => {
  for (const mode of ["worker", "version", "upload", "conflict", "public-readback"]) {
    const f = await ready(t);
    const overrides = {};
    if (mode === "worker") overrides.fetchRemote = async () => Response.json({ publicationProtocol: 1 });
    if (mode === "version") overrides.expectedPreviousVersion = "0.3.0";
    if (mode === "upload") f.store.putFile = async () => { throw new Error("upload failed"); };
    if (mode === "conflict") f.objects.set(`releases/${f.version}-${f.commit.slice(0, 12)}/macos/River-Client-${f.version}-macOS-arm64.dmg`, Buffer.from("conflict"));
    if (mode === "public-readback") overrides.fetchRemote = async (url, opts) => url.endsWith("/SHA256SUMS") ? new Response("changed") : f.fetchRemote(url, opts);
    await assert.rejects(f.publish(overrides));
    assert.equal(f.objects.has("macos.json"), false);
  }
});

test("concurrent and newer Mac releases are never overwritten", async (t) => {
  const f = await ready(t);
  const newer = signManifest({ ...f.manifest, version: "0.3.2" }, f.privateKey);
  const put = f.store.putJson;
  f.store.putJson = async (key, ...args) => {
    if (key === "macos.json") f.objects.set(key, Buffer.from(JSON.stringify(newer)));
    return put(key, ...args);
  };
  await assert.rejects(f.publish(), /PreconditionFailed/);
  assert.deepEqual((await f.store.getJson("macos.json")).value, newer);
  await assert.rejects(f.publish({ expectedPreviousVersion: "0.3.2" }), /downgrade/);
});

test("lost promotion responses and post-commit readback failures retain a usable retry receipt", async (t) => {
  const f = await ready(t), put = f.store.putJson;
  f.store.putJson = async (key, ...args) => { await put(key, ...args); if (key === "macos.json") throw new Error("lost response"); };
  await assert.rejects(f.publish({ fetchRemote: async (url, opts) => url.endsWith("/macos.json") && f.objects.has("macos.json")
    ? new Response("offline", { status: 503 }) : f.fetchRemote(url, opts) }), /promotion committed/);
  assert.equal(JSON.parse(fs.readFileSync(path.join(f.outputDirectory, "publication-result.json"))).publicationCommitted, true);
  assert.equal((await f.publish()).publicationCommitted, true);
});

function source(f) {
  return { run: { repository: { full_name: config.repository }, head_repository: { full_name: config.repository },
    path: config.macBuildWorkflow, head_branch: "master", event: "push", status: "completed", conclusion: "success", head_sha: f.commit },
    comparison: { status: "ahead" }, artifacts: ["arm64", "x64"].map((arch, i) => ({ id: i + 1, name: `river-macos-${arch}-${f.commit}`,
      size_in_bytes: 2048, digest: `sha256:${"a".repeat(64)}`, expired: false, created_at: `2026-11-16T12:0${i}:00.000Z` })) };
}

test("Mac source validation requires both artifacts in one successful master signing run", (t) => {
  const f = fixture(t), s = source(f);
  assert.equal(validateMacBuildSource(s.run, s.artifacts, f.commit, s.comparison).length, 2);
  for (const patch of [{ event: "pull_request" }, { path: config.buildWorkflow }, { conclusion: "failure" }, { head_branch: "feature" },
    { head_repository: { full_name: "fork/RiverClient" } }, { head_sha: "d".repeat(40) }]) {
    assert.throws(() => validateMacBuildSource({ ...s.run, ...patch }, s.artifacts, f.commit, s.comparison));
  }
  for (const artifacts of [s.artifacts.slice(0, 1), [...s.artifacts, s.artifacts[0]], s.artifacts.map((a) => ({ ...a, expired: true })),
    s.artifacts.map((a) => ({ ...a, digest: null }))]) assert.throws(() => validateMacBuildSource(s.run, artifacts, f.commit, s.comparison));
  assert.throws(() => validateMacBuildSource(s.run, s.artifacts, f.commit, { status: "diverged" }));
});

test("Mac downloader validates both archive digests, exact inventories and authenticated source version", async (t) => {
  const f = fixture(t), s = source(f), archives = [];
  for (const [i, arch] of ["arm64", "x64"].entries()) {
    const dir = path.join(f.artifactDirectory, arch);
    const bytes = zip(fs.readdirSync(dir).map((name) => ({ name, bytes: fs.readFileSync(path.join(dir, name)) })));
    s.artifacts[i].digest = `sha256:${hash(bytes)}`;
    archives.push(bytes);
  }
  const fetchRemote = async (url, options) => {
    if (url.startsWith("https://artifact.example/")) { assert.equal(options.headers, undefined); return new Response(archives[Number(url.split("/").at(-1)) - 1]); }
    assert.equal(options.headers.Authorization, "Bearer test-token");
    if (url.endsWith("/zip")) return new Response(null, { status: 302, headers: { location: `https://artifact.example/${url.split("/").at(-2)}` } });
    if (url.includes("/contents/")) return Response.json({ encoding: "base64", content: Buffer.from(JSON.stringify({ riverVersion: f.version })).toString("base64") });
    if (url.includes("/compare/")) return Response.json(s.comparison);
    if (url.includes("/artifacts?")) return Response.json({ total_count: 2, artifacts: s.artifacts });
    return Response.json(s.run);
  };
  const options = { platform: "macos", runId: "123", expectedCommit: f.commit, outputDirectory: path.join(f.root, "downloaded"),
    recordFile: path.join(f.root, "source.json"), token: "test-token", fetchRemote };
  const record = await downloadSignedBuild(options);
  assert.equal(record.publishedAt, s.artifacts[1].created_at);
  for (const arch of ["arm64", "x64"]) await verifyMacArtifact(path.join(options.outputDirectory, arch), f.commit, arch);
  s.artifacts[1].digest = `sha256:${"0".repeat(64)}`;
  const bad = { ...options, outputDirectory: path.join(f.root, "bad"), recordFile: path.join(f.root, "bad.json") };
  await assert.rejects(downloadSignedBuild(bad), /digest mismatch/);
  assert.equal(fs.existsSync(bad.outputDirectory), false);
});

test("Mac update checks use only the signed Mac channel and fail closed before publication", async (t) => {
  const f = await ready(t), verify = (value) => verifyManifest(value, f.publicKey);
  for (const arch of ["arm64", "x64"]) {
    const result = await fetchMacUpdateManifest({}, { arch, verify, fetchRemote: async (url) => {
      assert.equal(url, `${config.publicBaseUrl}/macos.json`); return Response.json(f.manifest);
    } });
    assert.equal(result.payload.macUrls[arch], f.manifest.macUrls[arch]);
  }
  for (const value of [{ ...f.manifest, version: "0.4.0" }, signManifest({ version: "0.4.0", platform: "win32" }, f.privateKey)]) {
    assert.equal(await fetchMacUpdateManifest({}, { verify, arch: "arm64", fetchRemote: async () => Response.json(value) }), null);
  }
  let requests = 0;
  assert.equal(await fetchMacUpdateManifest({}, { fetchRemote: async () => { requests++; return new Response(null, { status: 404 }); } }), null);
  assert.equal(requests, 1);
});

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { execFileSync } = require("node:child_process");
const { fixture, zip, hash } = require("./fixtures/signed-release");
const { prepareSignedUpdate, validatePrepared, signPrepared, compareVersions, releaseKeyFor } = require("../scripts/prepare-signed-update");
const { verifyArtifact, extractZip, ARTIFACT_FILES } = require("../scripts/release-artifact");
const { publishPrepared } = require("../scripts/publish-signed-update");
const { signManifest, verifyManifest } = require("../src/update-signature");
const { validateBuildSource, downloadSignedBuild } = require("../scripts/download-signed-build");
const { validateEnvironment } = require("../scripts/configure-update-publishing");
const { buildDifferentialUpdatePlan, downloadFileViaBlockmap } = require("../src/update-files");
const config = require("../update-publishing.json");

function objectStore(f) {
  const objects = new Map();
  const operations = [];
  const initial = signManifest({ version: "0.3.0", minimumVersion: "0.1.7", installerUrl: `${config.publicBaseUrl}/downloads/River-Client-Setup.exe` }, f.privateKey);
  objects.set("latest.json", Buffer.from(JSON.stringify(initial)));
  const store = {
    async head(key) { return objects.has(key) ? { ETag: `"${hash(objects.get(key))}"` } : null; },
    async getJson(key) { const bytes = objects.get(key); return bytes ? { value: JSON.parse(bytes), bytes, etag: `"${hash(bytes)}"` } : null; },
    async putFile(key, file) { assert.equal(objects.has(key), false); operations.push(key); objects.set(key, fs.readFileSync(file)); },
    async verify(key, expected) { const bytes = objects.get(key); assert.ok(bytes); assert.equal(bytes.length, expected.size); assert.equal(hash(bytes), expected.sha256); },
    async putJson(key, bytes, etag) {
      const current = await store.head(key);
      if (etag ? current?.ETag !== etag : current) throw new Error("PreconditionFailed");
      operations.push(key); objects.set(key, Buffer.from(bytes));
    }
  };
  async function fetchRemote(url) {
    if (url.endsWith("/release-capabilities.json")) return Response.json({ publicationProtocol: 1, linuxPublicationProtocol: 1 });
    const key = url.endsWith("/latest.json") ? "latest.json" : decodeURIComponent(new URL(url).pathname.slice("/downloads/".length));
    return objects.has(key) ? new Response(objects.get(key)) : new Response("missing", { status: 404 });
  }
  return { store, objects, operations, fetchRemote, initial };
}
async function ready(t, options) {
  const f = fixture(t, options);
  await prepareSignedUpdate(f.options);
  await signPrepared(f.outputDirectory, f.privateKey, f.publicKey);
  const remote = objectStore(f);
  const publish = (overrides = {}) => publishPrepared({ directory: f.outputDirectory, publicKey: f.publicKey,
    expectedPreviousVersion: "0.3.0", store: remote.store, fetchRemote: remote.fetchRemote, ...overrides });
  return { ...f, ...remote, publish };
}

test("preparation preserves original signed bytes and produces a client-compatible file manifest and blockmaps", async (t) => {
  const f = fixture(t, { large: true });
  const before = fs.readFileSync(path.join(f.artifactDirectory, "SHA256SUMS.txt"), "utf8");
  const plan = await prepareSignedUpdate(f.options);
  assert.equal(fs.readFileSync(path.join(f.artifactDirectory, "SHA256SUMS.txt"), "utf8"), before);
  const { manifest } = await validatePrepared(f.outputDirectory);
  const fileManifest = JSON.parse(fs.readFileSync(path.join(f.outputDirectory, "metadata/file-manifest.json")));
  assert.equal(hash(JSON.stringify(fileManifest)), manifest.files.fileManifest.sha256);
  assert.equal(manifest.files.package.sha256, hash(fs.readFileSync(path.join(f.artifactDirectory, "River-Client-App.zip"))));
  assert.equal(manifest.files.linux.sha256, hash(fs.readFileSync(path.join(f.linuxArtifactDirectory, "River-Client-Linux.tar.gz"))));
  assert.match(manifest.linuxUrl, /\/releases\/0\.3\.1-cccccccccccc\/River-Client-Linux-0\.3\.1\.tar\.gz$/);
  assert.deepEqual(manifest.mods.map((mod) => mod.minecraft), ["26.3", "1.21.11"]);
  for (const mod of manifest.mods) assert.equal(mod.sha256, f.info.runtime.files.find((file) => file.name === mod.name).sha256);
  assert.ok(manifest.installerUrl.includes("/releases/0.3.1-cccccccccccc/"));
  assert.ok(fileManifest.files.find((file) => file.path === "resources/app.asar").blockmapUrl);
  const installed = path.join(f.root, "installed");
  fs.mkdirSync(path.join(installed, "resources"), { recursive: true });
  const previous = Buffer.alloc(8 * 1024 ** 2, 0x41); previous.fill(0x42, 0, 65536);
  fs.writeFileSync(path.join(installed, "resources/app.asar"), previous);
  const downloadPlan = await buildDifferentialUpdatePlan(fileManifest, installed);
  const file = downloadPlan.downloads.find((item) => item.path === "resources/app.asar");
  const map = plan.objects.find((item) => `${config.publicBaseUrl}/downloads/${item.key}` === file.blockmapUrl);
  const target = path.join(f.root, "updated.asar");
  const downloaded = await downloadFileViaBlockmap(file, path.join(installed, file.path), target, null, {
    fetch: async (url, options) => {
      if (url === file.blockmapUrl) return Response.json(JSON.parse(fs.readFileSync(path.join(f.outputDirectory, map.file))));
      const [, start, end] = /bytes=(\d+)-(\d+)/.exec(options.headers.Range);
      return new Response(Buffer.alloc(Number(end) - Number(start) + 1, 0x41), { status: 206 });
    }
  });
  assert.equal(downloaded, true);
  assert.equal(hash(fs.readFileSync(target)), file.sha256);
});

test("artifact checks reject changed installers, wrong source/version and missing Windows validation", async (t) => {
  const f = fixture(t);
  await assert.rejects(verifyArtifact(f.artifactDirectory, "d".repeat(40)), /source commit/);
  f.info.checks.pop(); f.write("WINDOWS-BUILD-INFO.json", JSON.stringify(f.info)); f.checksums();
  await assert.rejects(verifyArtifact(f.artifactDirectory, f.options.expectedCommit), /Windows check/);
  f.write("River-Client-Setup.exe", "tampered");
  await assert.rejects(verifyArtifact(f.artifactDirectory, f.options.expectedCommit), /checksum mismatch/);
});

test("only the default Minecraft version may ship inside the app", async (t) => {
  const f = fixture(t);
  // A build that put every version back into the installer must not publish.
  f.info.runtime.bundledMinecraft = "26.3"; f.write("WINDOWS-BUILD-INFO.json", JSON.stringify(f.info)); f.checksums();
  await assert.rejects(verifyArtifact(f.artifactDirectory, f.options.expectedCommit), /differs from staged build|not be inside the app/);
  f.info.runtime.bundledMinecraft = "1.21.11"; f.write("WINDOWS-BUILD-INFO.json", JSON.stringify(f.info));
  f.write("River-Client-Mods.zip", zip([{ name: "clientcore-0.3.1+26.3.jar", bytes: "tampered" }])); f.checksums();
  await assert.rejects(prepareSignedUpdate(f.options), /ZIP/);
  assert.equal(fs.existsSync(f.outputDirectory), false);
});

test("preparation refuses unapproved notes, unsafe minimums and an existing output directory", async (t) => {
  const f = fixture(t);
  await assert.rejects(prepareSignedUpdate({ ...f.options, notes: { ...f.options.notes, approved: false } }), /approved/);
  await assert.rejects(prepareSignedUpdate({ ...f.options, minimumVersion: "0.4.0" }), /Minimum/);
  await prepareSignedUpdate(f.options);
  await assert.rejects(prepareSignedUpdate(f.options), /already exists/);
});

test("Linux is mandatory and must match the reviewed source, version, targets and archive bytes", async (t) => {
  for (const change of ["missing", "commit", "version", "targets", "bytes"]) {
    const f = fixture(t);
    if (change === "missing") f.options.linuxArtifactDirectory = undefined;
    if (change === "commit") f.linuxInfo.sourceCommit = "d".repeat(40);
    if (change === "version") f.linuxInfo.version = "0.3.0";
    if (change === "targets") f.linuxInfo.runtime.minecraftVersions = ["1.21.11"];
    if (change === "bytes") fs.appendFileSync(path.join(f.linuxArtifactDirectory, "River-Client-Linux.tar.gz"), "corruption");
    fs.writeFileSync(path.join(f.linuxArtifactDirectory, "LINUX-BUILD-INFO.json"), JSON.stringify(f.linuxInfo));
    await assert.rejects(prepareSignedUpdate(f.options), /Linux/);
    assert.equal(fs.existsSync(f.outputDirectory), false);
  }
});

test("new releases use three parts while historical four-part versions remain comparable", () => {
  assert.equal(compareVersions("0.3.1", "0.2.8.1"), 1);
  assert.equal(compareVersions("0.2.8.1", "0.2.8"), 1);
  assert.equal(compareVersions("0.3.0.0", "0.3.0"), 0);
  assert.throws(() => releaseKeyFor("0.3.1.1", "c".repeat(40), "stable"), /MAJOR.MINOR.PATCH/);
});

test("ZIP extraction rejects traversal, links, duplicate case-insensitive names and changed bytes", async (t) => {
  const f = fixture(t);
  for (const [index, entries] of [
    [{ name: "../outside", bytes: "bad" }],
    [{ name: "link", bytes: "/etc/passwd", mode: 0xa1ff }],
    [{ name: "one", bytes: "x" }, { name: "ONE", bytes: "y" }],
    [{ name: "file", bytes: "different" }]
  ].entries()) {
    const file = path.join(f.root, `bad-${index}.zip`), destination = path.join(f.root, `bad-${index}`);
    fs.writeFileSync(file, zip(entries));
    const expected = index === 3 ? new Map([["file", { size: 9, sha256: hash("original!") }]]) : new Set(entries.map((entry) => entry.name));
    await assert.rejects(extractZip(file, destination, expected));
    assert.equal(fs.existsSync(destination), false);
  }
  assert.equal(fs.existsSync(path.join(f.root, "outside")), false);
});

test("only the installed updater key can sign, and changed prepared bytes fail before network writes", async (t) => {
  const f = fixture(t);
  await prepareSignedUpdate(f.options);
  await assert.rejects(signPrepared(f.outputDirectory, f.privateKey), /does not match/);
  const signed = await signPrepared(f.outputDirectory, f.privateKey, f.publicKey);
  assert.equal(verifyManifest(signed, f.publicKey), true);
  fs.appendFileSync(path.join(f.outputDirectory, "app/RiverClient.exe"), "changed");
  const remote = objectStore(f);
  await assert.rejects(publishPrepared({ directory: f.outputDirectory, publicKey: f.publicKey, ...remote, expectedPreviousVersion: "0.3.0" }), /Prepared file changed/);
  assert.deepEqual(remote.operations, []);
});

test("default publisher CLI validates locally without credentials or any upload", async (t) => {
  const f = fixture(t);
  await prepareSignedUpdate(f.options);
  const stdout = execFileSync(process.execPath, [path.resolve(__dirname, "../scripts/publish-signed-update.js"), "--prepared", f.outputDirectory],
    { encoding: "utf8", env: { PATH: process.env.PATH } });
  assert.match(stdout, /No credentials loaded or remote writes/);
});

test("successful publication verifies every object, promotes once, writes history last and is retryable", async (t) => {
  const f = await ready(t);
  const receipt = await f.publish();
  assert.equal(receipt.channelCommitted, true);
  assert.equal(receipt.assetsVerified, true);
  assert.deepEqual(f.operations.slice(-2), ["latest.json", "releases/0.3.1/published.json"]);
  assert.equal(f.operations.includes("River-Client-Setup.exe"), false);
  assert.equal(f.operations.includes("River-Client-Linux.tar.gz"), false, "the rolling URL follows the committed manifest atomically");
  const published = (await f.store.getJson("latest.json")).value;
  const linuxKey = decodeURIComponent(new URL(published.linuxUrl).pathname.slice("/downloads/".length));
  assert.equal(hash(f.objects.get(linuxKey)), published.files.linux.sha256);
  assert.equal(receipt.linuxUrl, published.linuxUrl);
  const length = f.operations.length;
  await f.publish();
  assert.equal(f.operations.length, length);
  assert.equal((await f.store.getJson("latest.json")).value.version, "0.3.1");
});

test("interrupted and corrupt uploads cannot change the channel or standalone-mod history", async (t) => {
  for (const mode of ["interrupted", "corrupt"]) {
    const f = await ready(t);
    const put = f.store.putFile;
    f.store.putFile = async (...args) => {
      if (mode === "interrupted") throw new Error("network failure");
      await put(...args); f.objects.set(args[0], Buffer.from("corrupt"));
    };
    await assert.rejects(f.publish());
    assert.deepEqual((await f.store.getJson("latest.json")).value, f.initial);
    assert.equal(f.objects.has("releases/0.3.1/published.json"), false);
    const receipt = JSON.parse(fs.readFileSync(path.join(f.outputDirectory, "publication-result.json")));
    assert.equal(receipt.channelCommitted, false);
  }
});

test("Worker capabilities, unchanged signatures and reviewed channel version are required before uploads", async (t) => {
  const f = await ready(t);
  await assert.rejects(f.publish({ fetchRemote: async (url) => url.endsWith("/release-capabilities.json")
    ? Response.json({ publicationProtocol: 1 }) : f.fetchRemote(url) }), /Linux rolling-download/);
  await assert.rejects(f.publish({ fetchRemote: async () => new Response("missing", { status: 404 }) }), /404/);
  await assert.rejects(f.publish({ expectedPreviousVersion: "0.2.9" }), /Channel version changed/);
  await assert.rejects(f.publish({ fetchRemote: async (url) => url.endsWith("/latest.json")
    ? Response.json({ ...f.initial, minimumVersion: "0.2.9" }) : f.fetchRemote(url) }), /unchanged signed/);
  assert.deepEqual(f.operations, []);
});

test("immutable conflicts and concurrent channel promotion never overwrite another release", async (t) => {
  const f = await ready(t);
  const plan = JSON.parse(fs.readFileSync(path.join(f.outputDirectory, "publication-plan.json")));
  f.objects.set(plan.objects[0].key, Buffer.from("existing different bytes"));
  await assert.rejects(f.publish());
  assert.deepEqual(f.operations, []);
  f.objects.delete(plan.objects[0].key);
  const other = signManifest({ ...f.initial, version: "0.3.2" }, f.privateKey);
  const put = f.store.putJson;
  f.store.putJson = async (key, ...args) => {
    if (key === "latest.json") f.objects.set(key, Buffer.from(JSON.stringify(other)));
    return put(key, ...args);
  };
  await assert.rejects(f.publish(), /PreconditionFailed/);
  assert.deepEqual((await f.store.getJson("latest.json")).value, other);
});

test("lost commit responses are recovered and post-commit history errors are explicitly retryable", async (t) => {
  const f = await ready(t);
  const put = f.store.putJson;
  let historyFailure = true;
  f.store.putJson = async (key, ...args) => {
    if (key.endsWith("published.json") && historyFailure) { historyFailure = false; throw new Error("history timeout"); }
    await put(key, ...args);
    if (key === "latest.json") throw new Error("lost response");
  };
  await assert.rejects(f.publish(), /Channel promotion committed/);
  assert.equal((await f.store.getJson("latest.json")).value.version, "0.3.1");
  const recovered = await f.publish();
  assert.equal(recovered.historyMarked, true);
});

test("beta publication leaves stable manifest, rolling downloads and stable history untouched", async (t) => {
  const f = fixture(t);
  await prepareSignedUpdate({ ...f.options, channel: "beta" });
  await signPrepared(f.outputDirectory, f.privateKey, f.publicKey);
  const remote = objectStore(f);
  await publishPrepared({ directory: f.outputDirectory, publicKey: f.publicKey, ...remote, expectedPreviousVersion: "none" });
  assert.deepEqual((await remote.store.getJson("latest.json")).value, remote.initial);
  assert.equal((await remote.store.getJson("beta.json")).value.version, "0.3.1");
  assert.equal(remote.operations.includes("latest.json"), false);
  assert.equal(remote.objects.has("releases/0.3.1/published.json"), false);
});

function sourceFixture(commit) {
  return { run: { repository: { full_name: config.repository }, head_repository: { full_name: config.repository },
    path: config.buildWorkflow, event: "push", head_branch: "master", status: "completed", conclusion: "success", head_sha: commit },
    artifacts: [{ id: 123, name: `river-windows-${commit}`, expired: false, size_in_bytes: 1024, digest: `sha256:${"a".repeat(64)}`, created_at: "2026-11-16T11:00:00Z" }],
    comparison: { status: "ahead" } };
}

test("Linux download provenance requires its own artifact from the successful release run", () => {
  const commit = "c".repeat(40), source = sourceFixture(commit);
  assert.throws(() => validateBuildSource(source.run, source.artifacts, commit, source.comparison, "linux"));
  const linux = { ...source.artifacts[0], name: `river-linux-${commit}`, id: 124 };
  assert.equal(validateBuildSource(source.run, [...source.artifacts, linux], commit, source.comparison, "linux").id, 124);
  for (const patch of [{ conclusion: "failure" }, { head_sha: "d".repeat(40) }, { path: ".github/workflows/ci.yml" }]) {
    assert.throws(() => validateBuildSource({ ...source.run, ...patch }, [linux], commit, source.comparison, "linux"));
  }
});

test("build provenance excludes failed runs, PRs, forks, wrong workflow/commit and missing artifact digests", () => {
  const commit = "c".repeat(40), source = sourceFixture(commit);
  assert.equal(validateBuildSource(source.run, source.artifacts, commit, source.comparison).id, 123);
  assert.equal(validateBuildSource({ ...source.run, event: "workflow_dispatch" }, source.artifacts, commit, source.comparison).id, 123);
  for (const patch of [{ conclusion: "failure" }, { event: "pull_request" }, { event: "pull_request_target" },
    { event: "workflow_run" }, { head_branch: "feature" },
    { head_sha: "d".repeat(40) }, { path: ".github/workflows/ci.yml" }, { head_repository: { full_name: "someone/RiverClient" } }]) {
    assert.throws(() => validateBuildSource({ ...source.run, ...patch }, source.artifacts, commit, source.comparison));
  }
  assert.throws(() => validateBuildSource(source.run, [{ ...source.artifacts[0], digest: null }], commit, source.comparison));
  assert.throws(() => validateBuildSource(source.run, source.artifacts, commit, { status: "diverged" }));
});

for (const platform of ["windows", "linux"]) test(`${platform} Actions archive digest is checked and the API token is not sent to download storage`, async (t) => {
  const f = fixture(t), source = sourceFixture(f.options.expectedCommit);
  source.artifacts[0].name = `river-${platform}-${f.options.expectedCommit}`;
  const names = platform === "linux" ? require("../scripts/linux-release-artifact").LINUX_ARTIFACT_FILES : ARTIFACT_FILES;
  const directory = platform === "linux" ? f.linuxArtifactDirectory : f.artifactDirectory;
  const archive = zip(names.map((name) => ({ name, bytes: fs.readFileSync(path.join(directory, name)) })));
  source.artifacts[0].digest = `sha256:${hash(archive)}`;
  const fetchRemote = async (url, options) => {
    if (url === "https://artifact.example/download") { assert.equal(options.headers, undefined); return new Response(archive); }
    assert.equal(options.headers.Authorization, "Bearer test-token");
    if (url.endsWith("/zip")) return new Response(null, { status: 302, headers: { location: "https://artifact.example/download" } });
    if (url.includes("/compare/")) return Response.json(source.comparison);
    if (url.includes("/artifacts?")) return Response.json({ total_count: 1, artifacts: source.artifacts });
    return Response.json(source.run);
  };
  const record = await downloadSignedBuild({ platform, runId: "123", expectedCommit: f.options.expectedCommit,
    outputDirectory: path.join(f.root, "downloaded"), recordFile: path.join(f.root, "source.json"), token: "test-token", fetchRemote });
  assert.equal(record.artifactDigest, source.artifacts[0].digest);
  if (platform === "windows") await verifyArtifact(path.join(f.root, "downloaded"), f.options.expectedCommit);
  else await require("../scripts/linux-release-artifact").verifyLinuxArtifact(path.join(f.root, "downloaded"), f.options.expectedCommit, f.info.version, f.info.runtime.minecraftVersions);
  source.artifacts[0].digest = `sha256:${"0".repeat(64)}`;
  await assert.rejects(downloadSignedBuild({ platform, runId: "123", expectedCommit: f.options.expectedCommit,
    outputDirectory: path.join(f.root, "bad-download"), recordFile: path.join(f.root, "bad-source.json"), token: "test-token", fetchRemote }), /digest mismatch/);
  assert.equal(fs.existsSync(path.join(f.root, "bad-download")), false);
});

test("production secrets are scoped to exactly master, excluding tag lookalikes and wildcard branches", () => {
  const settings = { deployment_branch_policy: { custom_branch_policies: true, protected_branches: false } };
  validateEnvironment(settings, { branch_policies: [{ name: "master", type: "branch" }] });
  assert.throws(() => validateEnvironment(settings, { branch_policies: [{ name: "master", type: "tag" }] }));
  assert.throws(() => validateEnvironment(settings, { branch_policies: [{ name: "*", type: "branch" }] }));
});

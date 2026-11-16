"use strict";

const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { Readable, Transform } = require("node:stream");
const { pipeline } = require("node:stream/promises");
const { execFileSync } = require("node:child_process");
const { parseArgs } = require("node:util");
const { assert, assertCommit, ARTIFACT_FILES, MAX_BYTES, digestFile, extractZip } = require("./release-artifact");
const config = require("../update-publishing.json");

function validateRun(run, expectedCommit, comparison, workflow) {
  assertCommit(expectedCommit);
  assert(run.repository?.full_name === config.repository && run.head_repository?.full_name === config.repository,
    "The signed build must originate in the River repository.");
  assert(run.path === workflow && ["push", "workflow_dispatch"].includes(run.event) && run.head_branch === config.releaseBranch,
    "Expected the master-only release workflow started by push or manual dispatch.");
  assert(run.status === "completed" && run.conclusion === "success" && run.head_sha === expectedCommit,
    "The expected source commit has no successful signed build in this run.");
  assert(["identical", "ahead"].includes(comparison.status), "The build commit is not an ancestor of current master.");
}

function selectArtifact(artifacts, name) {
  const matching = artifacts.filter((item) => item.name === name && !item.expired);
  assert(matching.length === 1 && /^sha256:[a-f0-9]{64}$/.test(matching[0].digest || ""), "Expected one unexpired immutable Actions artifact with a SHA-256 digest.");
  return matching[0];
}

function validateBuildSource(run, artifacts, expectedCommit, comparison, platform = "windows") {
  assert(["windows", "linux"].includes(platform), "Unsupported release artifact platform.");
  validateRun(run, expectedCommit, comparison, config.buildWorkflow);
  return selectArtifact(artifacts, `river-${platform}-${expectedCommit}`);
}

function validateMacBuildSource(run, artifacts, expectedCommit, comparison) {
  validateRun(run, expectedCommit, comparison, config.macBuildWorkflow);
  return ["arm64", "x64"].map((arch) => ({ arch, ...selectArtifact(artifacts, `river-macos-${arch}-${expectedCommit}`) }));
}

async function downloadSignedBuild({ runId, expectedCommit, outputDirectory, recordFile, token, platform = "windows", fetchRemote = fetch }) {
  assert(["windows", "linux", "macos"].includes(platform), "Unknown release platform.");
  assert(/^[1-9]\d*$/.test(String(runId)), "A numeric build run ID is required.");
  assertCommit(expectedCommit);
  assert(!fs.existsSync(outputDirectory) && !fs.existsSync(recordFile), "Download output or source record already exists.");
  const base = `https://api.github.com/repos/${config.repository}`;
  const headers = { Accept: "application/vnd.github+json", "X-GitHub-Api-Version": "2022-11-28", "User-Agent": "River-release-publisher",
    Authorization: `Bearer ${token}` };
  assert(token, "GitHub read access is required to download the signed build.");
  async function api(suffix) {
    const response = await fetchRemote(base + suffix, { headers, redirect: "error", signal: AbortSignal.timeout(30000) });
    assert(response.ok, `GitHub source validation failed (HTTP ${response.status}).`);
    return response.json();
  }
  const [run, listed, comparison] = await Promise.all([
    api(`/actions/runs/${runId}`), api(`/actions/runs/${runId}/artifacts?per_page=100`),
    api(`/compare/${expectedCommit}...${config.releaseBranch}`)
  ]);
  assert(listed.total_count <= 100, "Unexpectedly large build artifact list.");
  let artifacts, version;
  if (platform === "macos") {
    artifacts = validateMacBuildSource(run, listed.artifacts, expectedCommit, comparison);
    const source = await api(`/contents/launcher/package.json?ref=${expectedCommit}`);
    assert(source.encoding === "base64", "Missing source package metadata.");
    version = JSON.parse(Buffer.from(source.content, "base64").toString("utf8")).riverVersion;
    assert(/^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/.test(version), "Invalid Mac source version.");
  } else artifacts = [validateBuildSource(run, listed.artifacts, expectedCommit, comparison, platform)];
  const records = [];
  try {
    for (const artifact of artifacts) {
      assert(Number.isSafeInteger(artifact.size_in_bytes) && artifact.size_in_bytes > 0 && artifact.size_in_bytes <= MAX_BYTES,
        "Actions artifact exceeds the supported archive size.");
      const scratch = fs.mkdtempSync(path.join(os.tmpdir(), "river-actions-artifact-"));
      try {
        const redirect = await fetchRemote(`${base}/actions/artifacts/${artifact.id}/zip`, {
          headers, redirect: "manual", signal: AbortSignal.timeout(30000)
        });
        assert(redirect.status === 302, `GitHub artifact download returned HTTP ${redirect.status}.`);
        const location = new URL(redirect.headers.get("location"));
        assert(location.protocol === "https:" && !location.username && !location.password, "Invalid GitHub artifact download URL.");
        // The signed download URL is issued by GitHub; never forward its API token.
        const response = await fetchRemote(location.href, { redirect: "error", signal: AbortSignal.timeout(600000) });
        assert(response.ok && response.body, "Unable to download the signed Actions artifact.");
        const archive = path.join(scratch, "artifact.zip");
        let downloaded = 0;
        const meter = new Transform({ transform(chunk, _encoding, callback) {
          downloaded += chunk.length;
          if (downloaded > MAX_BYTES) return callback(new Error("Actions archive download is too large."));
          callback(null, chunk);
        } });
        await pipeline(Readable.fromWeb(response.body), meter, fs.createWriteStream(archive, { flags: "wx" }));
        const actual = await digestFile(archive);
        assert(`sha256:${actual.sha256}` === artifact.digest, "GitHub artifact archive digest mismatch.");
        const names = platform === "macos" ? [
          `River-Client-${version}-macOS-${artifact.arch}.dmg`, "build-result.json",
          `build-result-${artifact.arch}.json`, `notarization-${artifact.arch}.json`, "SHA256SUMS"
        ] : platform === "linux" ? require("./linux-release-artifact").LINUX_ARTIFACT_FILES : ARTIFACT_FILES;
        await extractZip(archive, platform === "macos" ? path.join(outputDirectory, artifact.arch) : outputDirectory, new Set(names));
        records.push({ runId: Number(runId), artifactId: artifact.id, sourceCommit: expectedCommit,
          ...(artifact.arch ? { arch: artifact.arch } : {}),
          artifactDigest: artifact.digest, publishedAt: new Date(artifact.created_at).toISOString(),
          url: `https://github.com/${config.repository}/actions/runs/${runId}` });
      } finally { fs.rmSync(scratch, { recursive: true, force: true }); }
    }
    const record = platform === "macos" ? { runId: Number(runId), sourceCommit: expectedCommit, version,
      artifacts: records, publishedAt: records.map((r) => r.publishedAt).sort().at(-1) } : records[0];
    fs.writeFileSync(recordFile, JSON.stringify(record, null, 2) + "\n", { flag: "wx" });
    return record;
  } catch (error) { fs.rmSync(outputDirectory, { recursive: true, force: true }); throw error; }
}

if (require.main === module) {
  (async () => {
    const { values } = parseArgs({ options: { "run-id": { type: "string" }, commit: { type: "string" },
      output: { type: "string" }, record: { type: "string" }, platform: { type: "string", default: "windows" } } });
    assert(values.output && values.record, "Required: --run-id ID --commit SHA --output NEW_DIR --record NEW_JSON");
    const token = process.env.GH_TOKEN || process.env.GITHUB_TOKEN || execFileSync("gh", ["auth", "token"], { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] }).trim();
    const result = await downloadSignedBuild({ runId: values["run-id"], expectedCommit: values.commit,
      outputDirectory: path.resolve(values.output), recordFile: path.resolve(values.record), token, platform: values.platform });
    console.log(JSON.stringify(result, null, 2));
  })().catch((error) => { console.error(error.message); process.exitCode = 1; });
}

module.exports = { validateBuildSource, validateMacBuildSource, downloadSignedBuild };

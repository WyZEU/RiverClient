const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { execFileSync } = require("node:child_process");
const { finished } = require("node:stream/promises");
const { stageLinuxRelease, verifyLinuxArtifact } = require("../scripts/linux-release-artifact");
const { hash } = require("./fixtures/signed-release");

test("Linux staging verifies the actual archive, executable modes, packaged version and runtime", { skip: process.platform === "win32" }, async (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "river-linux-release-test-"));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const write = (name, bytes, mode) => {
    const file = path.join(root, name);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, bytes, { mode });
  };
  const sourceCommit = "c".repeat(40), version = "0.3.1";
  write("launcher/package.json", JSON.stringify({ riverVersion: version }));
  write("settings.gradle.kts", 'versions("26.3")');
  write("river-bootstrap/build.gradle.kts", 'version = "0.1.9.0"');
  const files = ["clientcore-0.3.1+26.3.jar", "river-bootstrap-0.1.9.0.jar"].map((name) => ({ name, size: 4, sha256: hash("PK\x03\x04") }));
  const runtime = { schemaVersion: 1, version, sourceCommit, minecraftVersions: ["26.3"], files };
  const base = "payload/River-Client-Linux";
  for (const { name } of files) write(`${base}/resources/bundled/${name}`, "PK\x03\x04");
  write(`${base}/resources/bundled/river-build-info.json`, JSON.stringify(runtime));
  const elf = Buffer.alloc(64);
  Buffer.from([0x7f, 0x45, 0x4c, 0x46, 2, 1]).copy(elf);
  elf.writeUInt16LE(62, 18);
  write(`${base}/river-client-bin`, elf, 0o755);
  write(`${base}/river-client`, "#!/bin/sh\n", 0o755);
  write(`${base}/chrome_crashpad_handler`, elf, 0o755);
  write("asar/package.json", JSON.stringify({ version }));
  write("asar/app/config/build-version.json", JSON.stringify({ version, sourceCommit }));
  const asar = await import("@electron/asar");
  const pack = async () => {
    const output = await asar.createPackage(path.join(root, "asar"), path.join(root, base, "resources/app.asar"));
    // This ASAR version returns its write stream before the final bytes are flushed.
    await finished(output);
    fs.mkdirSync(path.join(root, "launcher/dist"), { recursive: true });
    execFileSync("tar", ["-czf", path.join(root, "launcher/dist/River-Client-Linux.tar.gz"), "-C", path.join(root, "payload"), "River-Client-Linux"]);
  };
  await pack();
  const info = await stageLinuxRelease(root, sourceCommit);
  assert.equal(info.version, version);
  await verifyLinuxArtifact(path.join(root, "launcher/dist/linux-release"), sourceCommit, version, ["26.3"]);
  fs.rmSync(path.join(root, "launcher/dist/linux-release"), { recursive: true });
  write("asar/app/config/build-version.json", JSON.stringify({ version: "0.3.0", sourceCommit }));
  await pack();
  await assert.rejects(stageLinuxRelease(root, sourceCommit), /launcher version/);
  write("asar/app/config/build-version.json", JSON.stringify({ version, sourceCommit }));
  fs.chmodSync(path.join(root, base, "river-client-bin"), 0o644);
  await pack();
  await assert.rejects(stageLinuxRelease(root, sourceCommit), /executable mode/);
});

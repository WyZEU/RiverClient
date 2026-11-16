const crypto = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");

async function verifyContentFile(file, downloaded, io) {
  const algorithm = ["sha512", "sha1"].find((name) => file.hashes?.[name]);
  const expected = String(file.hashes?.[algorithm] || "").toLowerCase();
  const length = algorithm === "sha512" ? 128 : 40;
  if (!algorithm || !new RegExp(`^[a-f0-9]{${length}}$`).test(expected)) {
    throw new Error("The content download has no valid Modrinth checksum.");
  }
  if (Number.isSafeInteger(file.size) && file.size >= 0 && io.statSync(downloaded).size !== file.size) {
    throw new Error("The content download did not match its expected size.");
  }
  const hash = crypto.createHash(algorithm);
  for await (const chunk of io.createReadStream(downloaded)) hash.update(chunk);
  if (hash.digest("hex") !== expected) {
    throw new Error("The content download did not match its Modrinth checksum.");
  }
}

/**
 * Stage and verify a Modrinth file before touching installed content. Metadata lookups
 * finish before the synchronous file/manifest swap, so unrelated manifest changes made
 * while downloading are retained. Recoverable filesystem errors restore the old files.
 */
async function installContentFile({ file, target, previous, manifestPath, download, createManifest }, io = fs) {
  const directory = path.dirname(target);
  io.mkdirSync(directory, { recursive: true });
  const staging = io.mkdtempSync(path.join(directory, ".river-install-"));
  const downloaded = path.join(staging, "content");
  const stagedManifest = path.join(staging, "manifest.json");
  const backups = [];
  const installed = [];
  let preserveStaging = false;
  try {
    await download(file.url, downloaded);
    await verifyContentFile(file, downloaded, io);
    // Read the latest manifest and commit in the same turn. Awaiting this callback
    // would let another completed download publish a change between the read/write.
    const manifest = createManifest();
    io.writeFileSync(stagedManifest, JSON.stringify(manifest, null, 2));

    // No awaits until the swap is complete: an IPC handler cannot observe or change a
    // half-installed manifest. The staging directory is on the content's filesystem.
    for (const original of [previous, target, manifestPath]) {
      if (!original || !io.existsSync(original)) continue;
      const backup = path.join(staging, `backup-${backups.length}`);
      io.renameSync(original, backup);
      backups.push({ original, backup });
    }
    io.renameSync(downloaded, target);
    installed.push(target);
    io.renameSync(stagedManifest, manifestPath);
    installed.push(manifestPath);
  } catch (error) {
    const recoveryErrors = [];
    for (const destination of installed.reverse()) {
      try { io.rmSync(destination, { force: true }); } catch (failure) { recoveryErrors.push(failure); }
    }
    for (const { original, backup } of backups.reverse()) {
      try { io.renameSync(backup, original); } catch (failure) { recoveryErrors.push(failure); }
    }
    if (recoveryErrors.length) {
      preserveStaging = true;
      throw new Error(`Content installation failed and some files could not be restored. Backups are in ${staging}.`, {
        cause: new AggregateError([error, ...recoveryErrors])
      });
    }
    throw error;
  } finally {
    // Never erase the only remaining copy if Windows also refused a rollback rename.
    if (!preserveStaging) {
      try { io.rmSync(staging, { recursive: true, force: true }); } catch {}
    }
  }
}

module.exports = { installContentFile };

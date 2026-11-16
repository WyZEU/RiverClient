const fs = require("node:fs");
const path = require("node:path");
const crypto = require("node:crypto");

const FORMAT = "river-safe-storage";
const UNAVAILABLE = "Secure system storage is unavailable. Unlock your keychain and try again; saved accounts have not been deleted.";
const PENDING = "Account encryption is pending. Existing sign-ins still work, but their files remain unencrypted until secure system storage is available.";
const UNREADABLE = "Saved accounts could not be read. Their files have been preserved; restore access before changing accounts.";
const WRITE_FAILED = "Could not safely save the account. The previous sign-in file has been preserved.";

// Electron 43 supports the synchronous safeStorage API. Keep the existing synchronous
// main-process auth callers; cache decrypted contents so status polling avoids Keychain.
function createCredentialStore({ safeStorage, platform = process.platform, fileSystem = fs, now = Date.now }) {
  const cache = new Map();
  const states = new Map();
  const retryAt = new Map();
  const clone = (value) => JSON.parse(JSON.stringify(value));
  const failure = (message, code) => Object.assign(new Error(message), { code });

  function available() {
    try {
      if (!safeStorage.isEncryptionAvailable()) return false;
      return platform !== "linux" || ["gnome_libsecret", "kwallet", "kwallet5", "kwallet6"].includes(safeStorage.getSelectedStorageBackend());
    } catch { return false; }
  }

  function encrypt(plaintext) {
    if (!available()) throw failure(UNAVAILABLE, "CREDENTIAL_STORAGE_UNAVAILABLE");
    try {
      const encrypted = safeStorage.encryptString(plaintext);
      if (!Buffer.isBuffer(encrypted) || !encrypted.length || safeStorage.decryptString(encrypted) !== plaintext) throw new Error("Verification failed");
      return JSON.stringify({ format: FORMAT, version: 1, ciphertext: encrypted.toString("base64") });
    } catch { throw failure(UNAVAILABLE, "CREDENTIAL_STORAGE_UNAVAILABLE"); }
  }

  function decrypt(envelope) {
    if (envelope.version !== 1 || typeof envelope.ciphertext !== "string" || !envelope.ciphertext) throw failure(UNREADABLE, "CREDENTIAL_STORAGE_UNREADABLE");
    if (!available()) throw failure(UNAVAILABLE, "CREDENTIAL_STORAGE_UNAVAILABLE");
    try { return safeStorage.decryptString(Buffer.from(envelope.ciphertext, "base64")); }
    catch { throw failure(UNAVAILABLE, "CREDENTIAL_STORAGE_UNAVAILABLE"); }
  }

  function replace(file, serialized, plaintext, encrypted) {
    const temp = `${file}.${crypto.randomUUID()}.tmp`;
    let fd;
    try {
      fileSystem.mkdirSync(path.dirname(file), { recursive: true });
      fd = fileSystem.openSync(temp, "wx", 0o600);
      fileSystem.writeFileSync(fd, serialized, "utf8");
      fileSystem.fsyncSync(fd);
      fileSystem.closeSync(fd);
      fd = undefined;
      const written = fileSystem.readFileSync(temp, "utf8");
      if (written !== serialized || (encrypted && decrypt(JSON.parse(written)) !== plaintext)) throw new Error("Verification failed");
      // The original remains in place through encryption, fsync and read-back verification.
      // Only ciphertext is ever staged during a migration; no plaintext backup is created.
      fileSystem.renameSync(temp, file);
    } catch { throw failure(WRITE_FAILED, "CREDENTIAL_STORAGE_WRITE_FAILED"); }
    finally {
      if (fd !== undefined) { try { fileSystem.closeSync(fd); } catch {} }
      try { fileSystem.rmSync(temp, { force: true }); } catch {}
    }
  }

  function remember(file, raw, value, state, message = "") {
    cache.set(file, { raw, value: clone(value) });
    states.set(file, { state, message });
  }

  function read(file, empty, validate = () => true) {
    let raw;
    try { raw = fileSystem.readFileSync(file, "utf8"); }
    catch (error) {
      if (error.code === "ENOENT") {
        cache.delete(file);
        states.set(file, { state: "empty", message: "" });
        return clone(empty);
      }
      states.set(file, { state: "unavailable", message: UNREADABLE });
      throw failure(UNREADABLE, "CREDENTIAL_STORAGE_UNREADABLE");
    }
    const cached = cache.get(file);
    const state = states.get(file)?.state;
    if (cached?.raw === raw && (state === "encrypted" || (state === "legacy" && now() < (retryAt.get(file) || 0)))) return clone(cached.value);
    let value, envelope;
    try {
      envelope = JSON.parse(raw);
      value = envelope?.format === FORMAT ? JSON.parse(decrypt(envelope)) : envelope;
      if (!validate(value)) throw new Error("Invalid credentials");
    } catch (error) {
      const message = error.code === "CREDENTIAL_STORAGE_UNAVAILABLE" ? UNAVAILABLE : UNREADABLE;
      states.set(file, { state: "unavailable", message });
      throw failure(message, error.code || "CREDENTIAL_STORAGE_UNREADABLE");
    }
    if (envelope?.format === FORMAT) {
      remember(file, raw, value, "encrypted");
    } else {
      try {
        const plaintext = JSON.stringify(value);
        const encrypted = encrypt(plaintext);
        replace(file, encrypted, plaintext, true);
        remember(file, encrypted, value, "encrypted");
      } catch {
        // A pre-existing plaintext login must remain usable when OS storage is missing.
        // Retry later. Never apply this compatibility fallback to an encrypted file.
        remember(file, raw, value, "legacy", PENDING);
        retryAt.set(file, now() + 30_000);
      }
    }
    return clone(value);
  }

  function assertWritable(file, empty, validate) {
    read(file, empty, validate); // Reject unreadable/locked files instead of treating them as an empty account.
    try { encrypt("river-storage-check"); }
    catch (error) {
      if (states.get(file)?.state !== "legacy") throw error;
    }
  }

  function write(file, value, empty, validate = () => true) {
    read(file, empty, validate);
    if (!validate(value)) throw failure(UNREADABLE, "CREDENTIAL_STORAGE_UNREADABLE");
    const plaintext = JSON.stringify(value);
    let serialized;
    try { serialized = encrypt(plaintext); }
    catch (error) {
      if (states.get(file)?.state !== "legacy") throw error;
      // Preserve refresh-token rotation for users whose *existing* legacy storage cannot
      // migrate yet. New files and previously encrypted files never fall back to plaintext.
      replace(file, plaintext, plaintext, false);
      remember(file, plaintext, value, "legacy", PENDING);
      retryAt.set(file, now() + 30_000);
      return clone(value);
    }
    replace(file, serialized, plaintext, true);
    remember(file, serialized, value, "encrypted");
    retryAt.delete(file);
    return clone(value);
  }

  function remove(file) {
    fileSystem.rmSync(file, { force: true });
    cache.delete(file);
    states.delete(file);
    retryAt.delete(file);
  }

  function status() {
    const all = [...states.values()];
    return all.find((entry) => entry.state === "unavailable") || all.find((entry) => entry.state === "legacy") || { state: "encrypted", message: "" };
  }

  return { read, write, remove, assertWritable, status };
}

function publicAuth(auth = {}) {
  const profile = auth.profile;
  return {
    signedIn: Boolean(auth.signedIn),
    expiresAt: Number(auth.expiresAt || 0),
    sessionExpiresAt: Number(auth.sessionExpiresAt || 0),
    profileId: String(auth.profileId || ""),
    profile: profile ? {
      id: String(profile.id || ""), name: String(profile.name || ""),
      skinUrl: String(profile.skinUrl || ""), capeUrl: String(profile.capeUrl || ""),
      capes: Array.isArray(profile.capes) ? profile.capes.filter((cape) => cape && typeof cape === "object").map((cape) => ({
        id: String(cape.id || ""), alias: String(cape.alias || ""), url: String(cape.url || ""), active: Boolean(cape.active)
      })) : []
    } : null,
    ...(auth.refreshError ? { refreshError: String(auth.refreshError) } : {}),
    ...(auth.storageUnavailable ? { storageUnavailable: true } : {})
  };
}

function publicSavedProfile(profile) {
  return { id: String(profile.id || ""), name: String(profile.name || ""), savedAt: String(profile.savedAt || ""), auth: publicAuth(profile.auth) };
}

module.exports = { createCredentialStore, publicAuth, publicSavedProfile };

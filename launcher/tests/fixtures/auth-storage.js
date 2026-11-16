const crypto = require("node:crypto");

// A controllable OS-provider boundary; the store still performs real authenticated
// encryption and real filesystem operations. No user credentials or network are used.
function encryptionProvider() {
  const key = crypto.randomBytes(32);
  return {
    available: true,
    backend: "gnome_libsecret",
    isEncryptionAvailable() { return this.available; },
    getSelectedStorageBackend() { return this.backend; },
    encryptString(value) {
      if (!this.available) throw new Error("Locked test keychain");
      const iv = crypto.randomBytes(12);
      const cipher = crypto.createCipheriv("aes-256-gcm", key, iv);
      const data = Buffer.concat([cipher.update(value, "utf8"), cipher.final()]);
      return Buffer.concat([iv, cipher.getAuthTag(), data]);
    },
    decryptString(value) {
      if (!this.available) throw new Error("Locked test keychain");
      const decipher = crypto.createDecipheriv("aes-256-gcm", key, value.subarray(0, 12));
      decipher.setAuthTag(value.subarray(12, 28));
      return Buffer.concat([decipher.update(value.subarray(28)), decipher.final()]).toString("utf8");
    }
  };
}

function account(id = "alice") {
  return {
    signedIn: true,
    microsoftRefreshToken: `test-ms-refresh-${id}`,
    minecraftAccessToken: `test-minecraft-access-${id}`,
    expiresAt: Date.now() + 60 * 60 * 1000,
    sessionExpiresAt: Date.now() + 30 * 24 * 60 * 60 * 1000,
    profileId: id,
    profile: { id, name: id, skinUrl: `https://example.invalid/${id}.png`, capeUrl: "", capes: [] }
  };
}

module.exports = { encryptionProvider, account };

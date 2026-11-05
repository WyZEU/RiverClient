/**
 * Exercises the REAL certificate verification against genuine Mojang material.
 *
 * test-social.js stubs verification so the friend/message logic can be tested offline;
 * this file is the counterpart that proves the crypto itself, using an actual
 * Mojang-signed certificate for the signed-in account. Run it after touching
 * verifyCertificate or the embedded Mojang keys.
 *
 * Needs the launcher to be signed in (reads its stored token). Skips cleanly otherwise,
 * so it never fails the suite just because nobody is logged in.
 */

import fs from "node:fs";
import crypto from "node:crypto";
import { SocialStore } from "./src/social.js";

const AUTH = "C:/Users/vojir.WYZ/AppData/Roaming/river-client-launcher/minecraft-auth.json";

class MemoryStorage {
  constructor() { this.map = new Map(); }
  async get(key) { return this.map.get(key); }
  async put(key, value) { this.map.set(key, structuredClone(value)); }
  async delete(key) { this.map.delete(key); }
}

const results = [];
const check = (name, condition, detail = "") => {
  results.push({ name, pass: Boolean(condition) });
  console.log(`${condition ? "PASS" : "FAIL"}  ${name}${detail ? ` - ${detail}` : ""}`);
};

/** Mojang labels these PKCS#1 but the bodies are really PKCS#8 / SPKI. */
const stripPem = (pem) => Buffer.from(String(pem).replace(/-----[^-]+-----/g, "").replace(/\s+/g, ""), "base64");

async function main() {
  let auth;
  try {
    auth = JSON.parse(fs.readFileSync(AUTH, "utf8"));
  } catch {
    console.log("SKIP  launcher auth file not found - sign in and re-run.");
    return;
  }
  if (!auth?.minecraftAccessToken || !auth?.profile?.id) {
    console.log("SKIP  launcher is not signed in.");
    return;
  }

  const response = await fetch("https://api.minecraftservices.com/player/certificates", {
    method: "POST",
    headers: { Authorization: `Bearer ${auth.minecraftAccessToken}` }
  });
  if (!response.ok) {
    console.log(`SKIP  Mojang certificate fetch returned ${response.status} (token likely expired).`);
    return;
  }
  const certificate = await response.json();

  const publicKeyDer = stripPem(certificate.keyPair.publicKey);
  const privateKey = crypto.createPrivateKey({ key: stripPem(certificate.keyPair.privateKey), format: "der", type: "pkcs8" });

  const store = new SocialStore(new MemoryStorage());
  const { nonce } = await store.beginAuth();

  const sign = (value) => crypto.sign("sha256", Buffer.from(value, "utf8"), privateKey).toString("base64");

  const payload = {
    nonce,
    uuid: auth.profile.id,
    publicKey: publicKeyDer.toString("base64"),
    publicKeySignature: certificate.publicKeySignatureV2,
    expiresAt: certificate.expiresAt,
    nonceSignature: sign(nonce)
  };

  // ---- the happy path, all real crypto
  const ok = await store.completeAuth(auth.profile.name, payload);
  check("a genuine Mojang certificate signs you in", ok.ok, ok.message || ok.uuid);
  check("the UUID comes from Mojang's signature", ok.uuid?.replace(/-/g, "") === auth.profile.id.replace(/-/g, ""), ok.uuid);

  // ---- claiming someone else's UUID must fail: Mojang's signature won't cover it
  const other = await store.beginAuth();
  const forged = await store.completeAuth("Impostor", {
    ...payload,
    nonce: other.nonce,
    nonceSignature: sign(other.nonce),
    uuid: "99999999-9999-4999-8999-999999999999"
  });
  check("claiming another player's UUID is rejected", !forged.ok, forged.message);

  // ---- holding the certificate without the private key must fail
  const third = await store.beginAuth();
  const noProof = await store.completeAuth(auth.profile.name, {
    ...payload,
    nonce: third.nonce,
    nonceSignature: Buffer.from("not a real signature").toString("base64")
  });
  check("a certificate without proof of possession is rejected", !noProof.ok, noProof.message);

  // ---- signing the wrong challenge must fail (replay of an old signature)
  const fourth = await store.beginAuth();
  const wrongNonce = await store.completeAuth(auth.profile.name, {
    ...payload,
    nonce: fourth.nonce,
    nonceSignature: sign("some other challenge")
  });
  check("a signature over a different challenge is rejected", !wrongNonce.ok, wrongNonce.message);

  // ---- a tampered expiry breaks Mojang's signature
  const fifth = await store.beginAuth();
  const tampered = await store.completeAuth(auth.profile.name, {
    ...payload,
    nonce: fifth.nonce,
    nonceSignature: sign(fifth.nonce),
    expiresAt: new Date(Date.parse(certificate.expiresAt) + 60_000).toISOString()
  });
  check("tampering with the certificate expiry is rejected", !tampered.ok, tampered.message);

  const failed = results.filter((r) => !r.pass);
  console.log(failed.length ? `\n${failed.length} FAILED: ${failed.map((f) => f.name).join(", ")}` : `\nAll ${results.length} certificate checks passed.`);
  process.exit(failed.length ? 1 : 0);
}

main().catch((error) => { console.error("certificate test crashed:", error); process.exit(1); });

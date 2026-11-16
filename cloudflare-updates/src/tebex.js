/**
 * River Plus payments through Tebex. Tebex calls POST /tebex/webhook for every payment
 * event; this checks the call really came from Tebex and turns it into a Plus change.
 *
 * Signature: Tebex sends X-Signature = hex HMAC-SHA256 (key: the endpoint's secret) of the
 * hex SHA-256 of the raw body. Anything that does not match is ignored.
 */
import { compactUuid } from "./cosmetics.js";

/** Days one paid month gives. A little over a month, so a renewal a day late never drops Plus. */
export const PLUS_MONTH_DAYS = 32;

const enc = new TextEncoder();
const hex = (buf) => [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, "0")).join("");

export async function tebexSignature(rawBody, secret) {
  const bodyHash = hex(await crypto.subtle.digest("SHA-256", enc.encode(rawBody)));
  const key = await crypto.subtle.importKey("raw", enc.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  return hex(await crypto.subtle.sign("HMAC", key, enc.encode(bodyHash)));
}

export async function tebexSignatureValid(rawBody, signature, secret) {
  if (!secret || !signature) return false;
  const expected = await tebexSignature(rawBody, secret);
  const given = String(signature).trim().toLowerCase();
  if (given.length !== expected.length) return false;
  let diff = 0;
  for (let i = 0; i < expected.length; i++) diff |= expected.charCodeAt(i) ^ given.charCodeAt(i);
  return diff === 0;
}

/** The Minecraft account a payment is for: the buyer's linked username, or the one on a product. */
export function tebexUuid(subject) {
  const candidates = [
    // River puts the player's account on the order itself (see tebexCheckout).
    subject?.custom?.uuid,
    subject?.recurring_payment?.initial_payment?.custom?.uuid,
    subject?.initial_payment?.custom?.uuid,
    subject?.last_payment?.custom?.uuid,
    subject?.recurring_payment?.last_payment?.custom?.uuid,
    subject?.customer?.username?.id,
    subject?.recurring_payment?.customer?.username?.id,
    ...(Array.isArray(subject?.products) ? subject.products.map((p) => p?.username?.id) : []),
    subject?.initial_payment?.customer?.username?.id,
    subject?.last_payment?.customer?.username?.id
  ];
  for (const c of candidates) {
    const uuid = compactUuid(c);
    if (uuid) return uuid;
  }
  return "";
}

/**
 * How many days a payment is for, from the package name ("River Plus (3 Months)" is 3 months).
 * One month is 31 days plus one spare day, so a renewal a day late never drops Plus.
 */
export function tebexDays(subject) {
  // Renewal webhooks put their payments directly on the subject. Prefer the most
  // recent payment, so a changed subscription uses its current package duration.
  const products = [
    subject?.products,
    subject?.last_payment?.products,
    subject?.initial_payment?.products,
    subject?.recurring_payment?.last_payment?.products,
    subject?.recurring_payment?.initial_payment?.products
  ].find((items) => Array.isArray(items) && items.length > 0) || [];
  let days = 0;
  for (const product of products) {
    const months = Number(/(\d+)\s*month/i.exec(String(product?.name || ""))?.[1]) || 1;
    const quantity = Math.max(1, Math.floor(Number(product?.quantity) || 1));
    days += (months * 31 + 1) * quantity;
  }
  return days || PLUS_MONTH_DAYS;
}

/**
 * What a Tebex event means for River Plus.
 *   grant  - a month or more was paid (first payment or a renewal)
 *   revoke - the money went back (refund, chargeback) or the subscription ended
 *   none   - nothing to do (a cancellation request keeps Plus until the paid month ends)
 */
export function tebexAction(event) {
  const type = String(event?.type || "");
  const subject = event?.subject || {};
  const uuid = tebexUuid(subject);
  switch (type) {
    case "payment.completed":
    case "recurring-payment.renewed":
      return { action: "grant", uuid, days: tebexDays(subject) };
    case "payment.refunded":
    case "payment.dispute.opened":
    case "payment.dispute.lost":
    case "recurring-payment.ended":
      return { action: "revoke", uuid };
    default:
      return { action: "none", uuid };
  }
}

/** The River Plus packages on the Tebex store, by how many months they are for. */
export const PLUS_PACKAGES = { 1: "7815389", 3: "7815393" };

/**
 * A Tebex checkout link for River Plus, with the player's Minecraft account on the order so
 * the payment webhook knows whose Plus it is. Uses the store's public token only.
 */
export async function tebexCheckout(publicToken, uuid, months, fetchImpl = fetch) {
  const packageId = PLUS_PACKAGES[months];
  if (!publicToken || !packageId || !uuid) return { ok: false, message: "Unavailable." };
  const base = `https://headless.tebex.io/api`;
  const basket = await fetchImpl(`${base}/accounts/${publicToken}/baskets`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      complete_url: "https://riverclient.xyz/plus?done=1",
      cancel_url: "https://riverclient.xyz/plus",
      complete_auto_redirect: true,
      custom: { uuid }
    })
  }).then((r) => r.json()).catch(() => null);
  const ident = basket?.data?.ident;
  if (!ident) return { ok: false, message: "Tebex did not start an order." };
  const filled = await fetchImpl(`${base}/baskets/${ident}/packages`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ package_id: packageId, quantity: 1, type: "subscription" })
  }).then((r) => r.json()).catch(() => null);
  const url = filled?.data?.links?.checkout;
  return url ? { ok: true, url } : { ok: false, message: "Tebex did not add River Plus to the order." };
}

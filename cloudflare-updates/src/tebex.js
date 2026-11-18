/**
 * River Plus payments through Tebex. Tebex calls POST /tebex/webhook for every payment
 * event; this checks the call really came from Tebex and turns it into a Plus change.
 *
 * Signature: Tebex sends X-Signature = hex HMAC-SHA256 (key: the endpoint's secret) of the
 * hex SHA-256 of the raw body. Anything that does not match is ignored.
 */
import { compactUuid } from "./cosmetics.js";

/** Days one paid month gives. A little over a month, so a late renewal never drops Plus. */
export const PLUS_MONTH_DAYS = 32;
export const PLUS_PACKAGES = { 1: "7815389", 3: "7815393" };
const PLUS_MONTHS = new Map(Object.entries(PLUS_PACKAGES).map(([months, id]) => [id, Number(months)]));

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

function plusProducts(subject) {
  // Renewal webhooks put their payments directly on the subject. Prefer the most
  // recent payment, so a changed subscription uses its current package duration.
  const products = [
    subject?.products,
    subject?.last_payment?.products,
    subject?.initial_payment?.products,
    subject?.recurring_payment?.last_payment?.products,
    subject?.recurring_payment?.initial_payment?.products
  ].find((items) => Array.isArray(items) && items.length > 0) || [];
  return products.filter((product) => PLUS_MONTHS.has(String(product?.id)));
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
  const products = plusProducts(subject);
  if (!products.length) return { action: "none", uuid };
  switch (type) {
    case "payment.completed":
    case "recurring-payment.renewed": {
      const days = products.reduce((total, product) => {
        const quantity = Math.max(1, Math.floor(Number(product.quantity) || 1));
        return total + (PLUS_MONTHS.get(String(product.id)) * 31 + 1) * quantity;
      }, 0);
      return { action: "grant", uuid, days };
    }
    case "payment.refunded":
    case "payment.dispute.opened":
    case "payment.dispute.lost":
    case "recurring-payment.ended":
      return { action: "revoke", uuid };
    default:
      return { action: "none", uuid };
  }
}

export async function createTebexBasket(publicToken, packageId, type, options, fetchImpl = fetch) {
  const base = `https://headless.tebex.io/api`;
  async function post(path, body) {
    try {
      const response = await fetchImpl(base + path, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        signal: AbortSignal.timeout(8000),
        body: JSON.stringify(body)
      });
      return response.ok ? await response.json() : null;
    } catch {
      return null;
    }
  }
  const basket = await post(`/accounts/${publicToken}/baskets`, options);
  const ident = basket?.data?.ident;
  if (typeof ident !== "string" || !/^[\w-]+$/.test(ident)) return { basket: null, started: false };
  const filled = await post(`/baskets/${ident}/packages`, { package_id: packageId, quantity: 1, type });
  return { basket: filled?.data, started: true };
}

/** A River Plus subscription for the Minecraft account confirmed at checkout. */
export async function tebexCheckout(publicToken, uuid, months, fetchImpl = fetch) {
  const packageId = PLUS_PACKAGES[months];
  if (!publicToken || !packageId || !uuid) return { ok: false, message: "Unavailable." };
  const { basket, started } = await createTebexBasket(publicToken, packageId, "subscription", {
    complete_url: "https://riverclient.xyz/plus?done=1",
    cancel_url: "https://riverclient.xyz/plus",
    complete_auto_redirect: true,
    custom: { uuid }
  }, fetchImpl);
  if (!started) return { ok: false, message: "Tebex did not start an order." };
  const url = basket?.links?.checkout;
  return url ? { ok: true, url } : { ok: false, message: "Tebex did not add River Plus to the order." };
}

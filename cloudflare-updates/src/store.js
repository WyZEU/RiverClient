import { createTebexBasket, getTebexBasket, tebexSignature, tebexSignatureValid } from "./tebex.js";

const STORE_ORIGIN = "https://store.riverclient.xyz";
const SITE_ORIGIN = "https://riverclient.xyz";
const PRICE_CENTS = 599;
const CHECKOUT_LEASE_MS = 60_000;
const MINECRAFT_NAME = /^[A-Za-z0-9_]{3,16}$/;
const PLAYER_ID = /^[a-f0-9]{32}$/;
const ORDER_ID = /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/;
const TRANSACTION_ID = /^tbx-[A-Za-z0-9-]{4,90}$/;

export const WING_PACKAGES = {
  "ember-dragon": { packageId: 7819950, cosmetic: "wingemberdragon" },
  "verdant-bloom": { packageId: 7819951, cosmetic: "wingverdantbloom" },
  "voidshard": { packageId: 7819952, cosmetic: "wingvoidshard" },
  "vampire-lord": { packageId: 7819953, cosmetic: "wingvampirelord" }
};

const packages = new Map(Object.values(WING_PACKAGES).map((entry) => [entry.packageId, entry]));
export const PLUS_WING = "wingriverregalia";
export const WING_COSMETICS = new Set([...packages.values()].map(({ cosmetic }) => cosmetic).concat(PLUS_WING));

const orderKey = (id) => `store:order:${id}`;
const transactionKey = (id) => `store:transaction:${id}`;
const ownershipKey = (uuid) => `store:owned:${uuid}`;
const reservationKey = ({ mode, uuid, cosmetic }) => `store:checkout:${mode}:${uuid}:${cosmetic}`;
const quote = (id, uuid, packageId, mode) => `river-store-v1\n${id}\n${uuid}\n${packageId}\n${mode}\n${PRICE_CENTS}`;
export const isOrderId = (id) => typeof id === "string" && ORDER_ID.test(id);

function hasExpectedPrice(currency, amount) {
  return currency === "EUR" && (typeof amount === "number" || typeof amount === "string") &&
    Number(amount) === PRICE_CENTS / 100;
}

export function storeCorsOrigin(request, env) {
  const origin = request.headers.get("Origin");
  if (origin === STORE_ORIGIN || origin === SITE_ORIGIN) return origin;
  return env.RIVER_STORE_SANDBOX === "1" && origin?.startsWith("https://") &&
    origin === env.RIVER_STORE_PREVIEW_ORIGIN ? origin : "";
}

function httpError(status, message) {
  return Response.json({ ok: false, message }, { status });
}

function checkoutLink(raw) {
  try {
    const url = new URL(raw);
    if (url.origin === "https://pay.tebex.io" && !url.username && !url.password &&
        /^\/[\w-]+\/?$/.test(url.pathname)) return url.href;
  } catch {}
  return "";
}

function basketCheckoutLink(basket) {
  return hasExpectedPrice(basket?.currency, basket?.total_price) ? checkoutLink(basket.links?.checkout) : "";
}

function checkoutResponse(playerName, url) {
  return Response.json({ ok: true, playerName, currency: "EUR", totalPriceCents: PRICE_CENTS, url });
}

async function updateReservation(cosmetics, action, checkout) {
  const response = await cosmetics.fetch(`https://cosmetics/store/checkout/${action}`, {
    method: "POST",
    body: JSON.stringify(checkout)
  });
  return response.ok ? response.json() : null;
}

export async function startWingCheckout(request, env, cosmetics, fetchImpl = fetch) {
  const origin = storeCorsOrigin(request, env);
  if (!origin) return httpError(403, "Checkout is only available from River Store.");
  if (env.RIVER_STORE_CHECKOUT_ENABLED !== "1" || !env.TEBEX_PUBLIC_TOKEN || !env.TEBEX_WEBHOOK_SECRET) {
    return httpError(503, "Checkout is currently unavailable.");
  }

  const body = await request.json().catch(() => null);
  const slug = body?.product;
  const product = typeof slug === "string" && Object.hasOwn(WING_PACKAGES, slug) ? WING_PACKAGES[slug] : null;
  if (!product || body.packageId !== product.packageId) return httpError(400, "Unknown product.");
  if (typeof body.playerName !== "string" || !MINECRAFT_NAME.test(body.playerName)) {
    return httpError(400, "Enter a Minecraft Java username.");
  }
  if (body.checkoutKey !== undefined && !isOrderId(body.checkoutKey)) {
    return httpError(400, "Invalid checkout request.");
  }

  const limit = await cosmetics.fetch("https://cosmetics/store/checkout-limit", {
    method: "POST",
    headers: { "X-River-Visitor": request.headers.get("CF-Connecting-IP") || "unknown" }
  });
  if (!limit.ok) return limit;

  let profile;
  try {
    const response = await fetchImpl(`https://api.mojang.com/users/profiles/minecraft/${body.playerName}`, {
      signal: AbortSignal.timeout(5000)
    });
    if (response.status === 404 || response.status === 204) return httpError(404, "Minecraft Java profile not found.");
    if (!response.ok) return httpError(502, "Minecraft profiles are temporarily unavailable.");
    profile = await response.json();
  } catch {
    return httpError(502, "Minecraft profiles are temporarily unavailable.");
  }
  if (!PLAYER_ID.test(profile?.id ?? "") || typeof profile?.name !== "string" ||
      profile.name.toLowerCase() !== body.playerName.toLowerCase()) {
    return httpError(404, "Minecraft Java profile not found.");
  }
  const { id: uuid, name: playerName } = profile;
  const id = crypto.randomUUID();
  const mode = env.RIVER_STORE_SANDBOX === "1" ? "test" : "live";
  const checkout = { id, uuid, mode, cosmetic: product.cosmetic, origin, checkoutKey: body.checkoutKey ?? "" };
  let reservation = await updateReservation(cosmetics, "reserve", checkout);
  if (reservation?.outcome === "active") {
    const existing = await getTebexBasket(env.TEBEX_PUBLIC_TOKEN, reservation.basketIdent, fetchImpl);
    if (!existing.expired) {
      if (!existing.basket) return httpError(502, "The Tebex checkout is unavailable.");
      if (!reservation.canResume) return httpError(409, "A checkout for these wings is open in another tab.");
      if (existing.basket.complete === true || existing.basket.links?.payment) {
        return httpError(409, "Payment is being processed. Check the original order.");
      }
      const url = basketCheckoutLink(existing.basket);
      return url && existing.basket.complete === false
        ? checkoutResponse(playerName, url)
        : httpError(502, "The Tebex price or checkout is unavailable.");
    }
    reservation = await updateReservation(cosmetics, "reserve", { ...checkout, expiredOrderId: reservation.id });
  }
  if (!reservation) return httpError(503, "Checkout is currently unavailable.");
  if (reservation.outcome === "owned") return httpError(409, "This Minecraft account already owns these wings.");
  if (reservation.outcome === "rejected") return httpError(409, "A previous payment was rejected. Contact support with the Tebex receipt.");
  if (reservation.outcome !== "reserved") return httpError(409, "A checkout is in progress. Try again in a moment.");

  const productUrl = `${mode === "test" ? origin : STORE_ORIGIN}/store/${slug}/`;
  const { basket, ident } = await createTebexBasket(env.TEBEX_PUBLIC_TOKEN, product.packageId, "single", {
    complete_url: `${productUrl}?order=${id}`,
    cancel_url: productUrl,
    complete_auto_redirect: true,
    custom: {
      river_order: id,
      river_player: uuid,
      river_package: String(product.packageId),
      river_mode: mode,
      river_signature: await tebexSignature(quote(id, uuid, product.packageId, mode), env.TEBEX_WEBHOOK_SECRET)
    }
  }, fetchImpl);
  const url = basketCheckoutLink(basket);
  const saved = await updateReservation(cosmetics, "finish", { ...checkout, basketIdent: url ? ident : null });
  if (!url) return httpError(502, "The Tebex price or checkout is unavailable.");
  if (!saved?.ok) return httpError(503, "Checkout is currently unavailable.");
  return checkoutResponse(playerName, url);
}

export function reserveWingCheckout(storage, checkout) {
  const key = reservationKey(checkout);
  const now = Date.now();
  return storage.transaction(async (txn) => {
    const owned = await txn.get(ownershipKey(checkout.uuid));
    if (Object.hasOwn(owned ?? {}, checkout.cosmetic)) return { outcome: "owned" };

    const current = await txn.get(key);
    if (current?.basketIdent) {
      // A published checkout stays reserved until Tebex reports it gone or the payment is refunded.
      const order = await txn.get(orderKey(current.id));
      if (order?.status === "rejected") return { outcome: "rejected" };
      if (order?.status !== "refunded" && current.id !== checkout.expiredOrderId) {
        return {
          outcome: "active", id: current.id, basketIdent: current.basketIdent,
          canResume: !!checkout.checkoutKey && current.checkoutKey === checkout.checkoutKey && current.origin === checkout.origin
        };
      }
    } else if (current?.leaseUntil > now) {
      return { outcome: "creating" };
    }

    await txn.put(key, {
      id: checkout.id, checkoutKey: checkout.checkoutKey, origin: checkout.origin,
      leaseUntil: now + CHECKOUT_LEASE_MS
    });
    return { outcome: "reserved" };
  });
}

export function finishWingCheckout(storage, checkout) {
  const key = reservationKey(checkout);
  return storage.transaction(async (txn) => {
    const current = await txn.get(key);
    if (current?.id !== checkout.id || current.basketIdent) return false;
    if (!checkout.basketIdent) {
      await txn.delete(key);
      return true;
    }

    const owned = await txn.get(ownershipKey(checkout.uuid));
    if (Object.hasOwn(owned ?? {}, checkout.cosmetic)) {
      await txn.delete(key);
      return false;
    }
    await txn.put(key, {
      id: current.id, checkoutKey: current.checkoutKey, origin: current.origin,
      basketIdent: checkout.basketIdent
    });
    return true;
  });
}

export async function wingPayment(event, env) {
  const subject = event?.subject;
  const transaction = subject?.transaction_id;
  if (typeof transaction !== "string" || !TRANSACTION_ID.test(transaction)) return null;

  if (event.type === "payment.refunded" || event.type === "payment.dispute.lost") {
    return { action: "refund", transaction };
  }
  if (event.type !== "payment.completed") return null;

  const { river_order: id, river_player: uuid, river_package: packageValue,
    river_mode: mode, river_signature: signature } = subject?.custom ?? {};
  const packageId = Number(packageValue);
  const product = packages.get(packageId);
  const items = subject.products;
  if (!product || !isOrderId(id) || typeof uuid !== "string" || !PLAYER_ID.test(uuid) ||
      (mode !== "live" && mode !== "test") || !Array.isArray(items) || items.length !== 1 ||
      Number(items[0]?.id) !== packageId || Number(items[0]?.quantity) !== 1) return null;

  const gateway = subject.payment_method?.name;
  if (typeof gateway !== "string" || !gateway.trim()) return null;
  const testPayment = subject.test === true || /^test payments?$/i.test(gateway.trim());
  if (testPayment !== (mode === "test") || (testPayment && env.RIVER_STORE_SANDBOX !== "1")) return null;
  if (!(await tebexSignatureValid(quote(id, uuid, packageId, mode), signature, env.TEBEX_WEBHOOK_SECRET))) return null;

  const price = subject.price_paid ?? subject.price;
  const action = hasExpectedPrice(price?.currency, price?.amount) ? "grant" : "reject";
  return { action, transaction, id, uuid, cosmetic: product.cosmetic };
}

export async function ownedWings(storage, uuid) {
  if (!PLAYER_ID.test(uuid)) return [];
  return Object.keys(await storage.get(ownershipKey(uuid)) ?? {});
}

export async function wingOrderStatus(storage, id) {
  if (!isOrderId(id)) return null;
  return (await storage.get(orderKey(id)))?.status ?? "pending";
}

/** Each charge has exactly one order. A refund removes only the grant from that charge. */
export function recordWingPayment(storage, payment) {
  const { transaction, id, uuid, cosmetic } = payment;
  return storage.transaction(async (txn) => {
    const key = transactionKey(transaction);
    const prior = await txn.get(key);

    if (payment.action === "refund") {
      if (prior?.refunded) return "duplicate";
      if (prior?.id) {
        const order = await txn.get(orderKey(prior.id));
        if (order.status === "delivered") {
          const ownership = await txn.get(ownershipKey(order.uuid)) ?? {};
          const remaining = (ownership[order.cosmetic] ?? []).filter((value) => value !== transaction);
          if (remaining.length) ownership[order.cosmetic] = remaining;
          else delete ownership[order.cosmetic];
          await txn.put(ownershipKey(order.uuid), ownership);
        }
        await txn.put(orderKey(prior.id), { ...order, status: "refunded" });
      }
      await txn.put(key, { ...prior, refunded: true });
      return "refunded";
    }

    if (prior?.id || await txn.get(orderKey(id))) return "duplicate";
    let status = payment.action === "grant" ? "delivered" : "rejected";
    if (prior?.refunded) status = "refunded";
    if (status === "delivered") {
      const ownership = await txn.get(ownershipKey(uuid)) ?? {};
      ownership[cosmetic] = [...(ownership[cosmetic] ?? []), transaction];
      await txn.put(ownershipKey(uuid), ownership);
    }
    await txn.put(key, { ...prior, id });
    await txn.put(orderKey(id), { transaction, uuid, cosmetic, status });
    return status;
  });
}

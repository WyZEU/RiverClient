import assert from "node:assert/strict";
import test from "node:test";
import worker, { Cosmetics, Presence } from "../src/worker.js";
import { WING_PACKAGES } from "../src/store.js";
import { tebexSignature } from "../src/tebex.js";

const UUID = "0123456789abcdef0123456789abcdef";
const DASHED = "01234567-89ab-cdef-0123-456789abcdef";
const ORIGIN = "https://store.riverclient.xyz";
const SECRET = "test-webhook-secret";
const CHECKOUT = "https://pay.tebex.io/test-basket";

function setup(t) {
  const values = new Map();
  const storage = {
    get: async (key) => structuredClone(values.get(key)),
    put: async (key, value) => values.set(key, structuredClone(value)),
    delete: async (key) => values.delete(key),
    transaction: async (operation) => operation(storage)
  };
  const cosmetics = new Cosmetics({ storage });
  const env = {
    COSMETICS_DO: {
      idFromName: (name) => name,
      get: () => ({ fetch: (url, init) => cosmetics.fetch(new Request(url, init)) })
    },
    RIVER_STORE_CHECKOUT_ENABLED: "1",
    RIVER_STORE_SANDBOX: "1",
    TEBEX_PUBLIC_TOKEN: "test",
    TEBEX_WEBHOOK_SECRET: SECRET
  };
  const request = (path, init) => worker.fetch(new Request(`https://updates.riverclient.xyz${path}`, init), env, {});
  let basket = null;
  let basketPrice = { currency: "EUR", amount: 5.99 };
  t.mock.method(globalThis, "fetch", async (url, init) => {
    if (String(url) === "https://api.mojang.com/users/profiles/minecraft/RiverPlayer") {
      return Response.json({ id: UUID, name: "RiverPlayer" });
    }
    if (String(url) === "https://headless.tebex.io/api/accounts/test/baskets") {
      basket = JSON.parse(init.body);
      return Response.json({ data: { ident: "test-basket" } });
    }
    assert.equal(String(url), "https://headless.tebex.io/api/baskets/test-basket/packages");
    assert.deepEqual(JSON.parse(init.body), { package_id: Number(basket.custom.river_package), quantity: 1, type: "single" });
    return Response.json({ data: { currency: basketPrice.currency, total_price: basketPrice.amount, links: { checkout: CHECKOUT } } });
  });
  const checkout = (product, init = {}) => request("/store/checkout", {
    method: "POST",
    headers: { Origin: ORIGIN, "CF-Connecting-IP": "192.0.2.1" },
    body: JSON.stringify({ product, packageId: WING_PACKAGES[product]?.packageId, playerName: "RiverPlayer" }),
    ...init
  });
  const webhook = async (type, subject, sign = SECRET) => {
    const body = JSON.stringify({ id: crypto.randomUUID(), type, subject });
    return request("/tebex/webhook", { method: "POST", body, headers: { "X-Signature": await tebexSignature(body, sign) } });
  };
  const owned = async (uuid = DASHED) => (await (await request(`/cosmetics/owned?uuid=${uuid}`)).json()).owned;
  const order = async (id) => (await (await request(`/store/order?id=${id}`, { headers: { Origin: ORIGIN } })).json()).status;
  const payment = (transaction) => ({
    transaction_id: transaction,
    custom: basket.custom,
    price_paid: { currency: "EUR", amount: 5.99 },
    payment_method: { name: "Test Payments" },
    products: [{ id: Number(basket.custom.river_package), quantity: 1 }]
  });
  return { env, cosmetics, request, checkout, webhook, owned, order, payment, basket: () => basket, setBasketPrice: (value) => { basketPrice = value; } };
}

test("checkout, signed payment, and refund deliver each specific wing without granting Plus", async (t) => {
  const flow = setup(t);
  for (const [slug, item] of Object.entries(WING_PACKAGES)) {
    const response = await flow.checkout(slug);
    assert.equal(response.headers.get("Access-Control-Allow-Origin"), ORIGIN);
    assert.deepEqual(await response.json(), { ok: true, playerName: "RiverPlayer", currency: "EUR", totalPriceCents: 599, url: CHECKOUT });
    const order = flow.basket().custom.river_order;
    assert.ok(flow.basket().complete_url.endsWith(`/store/${slug}/?order=${order}`));
    assert.equal(await flow.order(order), "pending");
    const payment = flow.payment(`tbx-${item.packageId}`);
    assert.equal((await (await flow.webhook("payment.completed", payment)).json()).outcome, "delivered");
    assert.equal(await flow.order(order), "delivered");
    assert.deepEqual(await flow.owned(), [item.cosmetic]);
    assert.deepEqual(await flow.owned(UUID), [item.cosmetic]);
    assert.equal((await (await flow.webhook("payment.completed", payment)).json()).outcome, "duplicate");
    assert.equal((await flow.checkout(slug)).status, 409);
    assert.equal((await (await flow.request(`/plus/status?uuid=${UUID}`)).json()).plus, false);
    assert.equal((await (await flow.webhook("payment.refunded", { transaction_id: payment.transaction_id })).json()).outcome, "refunded");
    assert.equal(await flow.order(order), "refunded");
    assert.deepEqual(await flow.owned(), []);
  }
});

test("bad signatures, order data, prices, and unauthorized origins cannot grant or start a checkout", async (t) => {
  const flow = setup(t);
  assert.equal((await flow.checkout("voidshard", { headers: { Origin: "https://other.example" } })).status, 403);
  assert.equal((await flow.checkout("voidshard", { body: '{"product":"voidshard","packageId":7815389,"playerName":"RiverPlayer"}' })).status, 400);
  for (const price of [
    { currency: "USD", amount: 5.99 },
    { currency: "EUR", amount: 6.38 },
    { currency: "EUR", amount: 5.991 }
  ]) {
    flow.setBasketPrice(price);
    const response = await flow.checkout("voidshard");
    assert.equal(response.status, 502);
    assert.equal((await response.json()).url, undefined);
  }
  flow.setBasketPrice({ currency: "EUR", amount: 5.99 });
  await flow.checkout("voidshard");
  const payment = flow.payment("tbx-validation");
  assert.equal((await flow.webhook("payment.completed", payment, "wrong-secret")).status, 401);
  for (const wrong of [
    { ...payment, custom: { ...payment.custom, river_player: "a".repeat(32) } },
    { ...payment, products: [{ id: 7819952, quantity: 2 }] },
    { ...payment, custom: { uuid: UUID } }
  ]) assert.equal((await (await flow.webhook("payment.completed", wrong)).json()).outcome, "ignored");
  flow.env.RIVER_STORE_SANDBOX = "0";
  assert.equal((await (await flow.webhook("payment.completed", payment)).json()).outcome, "ignored");
  assert.deepEqual(await flow.owned(), []);
  flow.env.RIVER_STORE_CHECKOUT_ENABLED = "0";
  assert.equal((await flow.checkout("voidshard")).status, 503);
});

test("a signed wing payment with the wrong currency or amount rejects the order without granting it", async (t) => {
  const flow = setup(t);
  const mismatches = [
    { currency: "USD", amount: 5.99 },
    { currency: "EUR", amount: 5.98 },
    { currency: "EUR", amount: 5.991 }
  ];
  for (const [index, pricePaid] of mismatches.entries()) {
    assert.equal((await flow.checkout("voidshard")).status, 200);
    const order = flow.basket().custom.river_order;
    const payment = flow.payment(`tbx-price-check-${index}`);
    const event = { ...payment, price: payment.price_paid, price_paid: pricePaid };

    const result = await flow.webhook("payment.completed", event);
    assert.equal(result.status, 200);
    assert.deepEqual(await result.json(), { ok: true, outcome: "rejected" });
    assert.equal(await flow.order(order), "rejected");
    assert.deepEqual(await flow.owned(), []);

    const retry = await flow.webhook("payment.completed", payment);
    assert.equal((await retry.json()).outcome, "duplicate");
    assert.equal(await flow.order(order), "rejected");
    assert.deepEqual(await flow.owned(), []);

    await flow.webhook("payment.refunded", { transaction_id: payment.transaction_id });
    assert.equal(await flow.order(order), "refunded");
  }
});

test("an isolated checkout can return to its configured preview only in sandbox mode", async (t) => {
  const flow = setup(t);
  const preview = "https://river-store-preview.pages.dev";
  const headers = { Origin: preview };
  assert.equal((await flow.checkout("voidshard", { headers })).status, 403);
  flow.env.RIVER_STORE_PREVIEW_ORIGIN = preview;
  const response = await flow.checkout("voidshard", { headers });
  assert.equal(response.status, 200);
  assert.equal(response.headers.get("Access-Control-Allow-Origin"), preview);
  const { complete_url, cancel_url } = flow.basket();
  assert.equal(new URL(complete_url).origin, preview);
  assert.equal(cancel_url, `${preview}/store/voidshard/`);
  flow.env.RIVER_STORE_SANDBOX = "0";
  assert.equal((await flow.checkout("voidshard", { headers })).status, 403);
});

test("an earlier refund blocks a delayed delivery and reversing one charge keeps another valid grant", async (t) => {
  const flow = setup(t);
  await flow.checkout("ember-dragon");
  const first = flow.payment("tbx-first-order");
  await flow.checkout("ember-dragon");
  const second = flow.payment("tbx-second-order");
  await flow.webhook("payment.completed", first);
  await flow.webhook("payment.completed", second);
  await flow.webhook("payment.dispute.lost", { transaction_id: first.transaction_id });
  assert.deepEqual(await flow.owned(), ["wingemberdragon"]);
  assert.equal(await flow.order(first.custom.river_order), "refunded");
  assert.equal(await flow.order(second.custom.river_order), "delivered");
  await flow.webhook("payment.refunded", { transaction_id: second.transaction_id });
  assert.deepEqual(await flow.owned(), []);
  await flow.checkout("ember-dragon");
  const late = flow.payment("tbx-late-order");
  await flow.webhook("payment.refunded", { transaction_id: late.transaction_id });
  await flow.webhook("payment.completed", late);
  assert.equal(await flow.order(late.custom.river_order), "refunded");
  assert.deepEqual(await flow.owned(), []);
});

test("presence only shows wings backed by a purchase or active Plus", async (t) => {
  const flow = setup(t);
  const presence = new Presence({}, flow.env);
  const announce = async (wing) => {
    const response = await presence.fetch(new Request("https://presence/presence", {
      method: "POST", body: JSON.stringify({ uuid: DASHED, server: "serverhash", wing })
    }));
    return (await response.json()).players[0]?.wing;
  };
  assert.equal(await announce("wingvoidshard"), undefined);
  await flow.checkout("voidshard");
  const paid = flow.payment("tbx-roster-paid");
  await flow.webhook("payment.completed", paid);
  await announce("");
  assert.equal(await announce("wingvoidshard"), "wingvoidshard");
  await flow.webhook("payment.refunded", { transaction_id: paid.transaction_id });
  presence.players.get(DASHED).wingCheckedAt -= 61_000;
  assert.equal(await announce("wingvoidshard"), undefined);

  assert.equal(await announce("wingriverregalia"), undefined);
  await flow.cosmetics.fetch(new Request("https://cosmetics/plus/grant", {
    method: "POST", body: JSON.stringify({ uuid: UUID, days: 1 })
  }));
  assert.ok((await flow.owned()).includes("wingriverregalia"));
  const freshPresence = new Presence({}, flow.env);
  const response = await freshPresence.fetch(new Request("https://presence/presence", {
    method: "POST", body: JSON.stringify({ uuid: DASHED, server: "serverhash", wing: "wingriverregalia" })
  }));
  assert.equal((await response.json()).players[0].wing, "wingriverregalia");
});

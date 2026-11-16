import test from "node:test";
import assert from "node:assert/strict";
import { Cosmetics, DAY_MS } from "../src/cosmetics.js";
import { tebexAction, tebexSignature, tebexSignatureValid, PLUS_MONTH_DAYS } from "../src/tebex.js";

const UUID = "0123456789abcdef0123456789abcdef";
const DASHED = "01234567-89ab-cdef-0123-456789abcdef";

function store() {
  const values = new Map();
  const storage = {
    get: async (k) => values.get(k),
    put: async (k, v) => values.set(k, v),
    delete: async (k) => values.delete(k),
    list: async ({ prefix }) => new Map([...values].filter(([k]) => k.startsWith(prefix))),
    transaction: async (fn) => fn(storage)
  };
  return { cosmetics: new Cosmetics({ storage }), values };
}
const post = async (c, path, body) =>
  (await c.fetch(new Request(`https://cosmetics${path}`, { method: "POST", body: JSON.stringify(body) }))).json();

test("signature: right secret passes, wrong secret and edited body fail", async () => {
  const body = JSON.stringify({ id: "abc", type: "validation.webhook" });
  const sig = await tebexSignature(body, "s3cret");
  assert.equal(await tebexSignatureValid(body, sig, "s3cret"), true);
  assert.equal(await tebexSignatureValid(body, sig, "other"), false);
  assert.equal(await tebexSignatureValid(body + " ", sig, "s3cret"), false);
  assert.equal(await tebexSignatureValid(body, "", "s3cret"), false);
});

test("a payment grants a month to the buyer's Minecraft account", () => {
  const a = tebexAction({ type: "payment.completed", subject: { customer: { username: { id: DASHED, username: "Steve" } } } });
  assert.deepEqual(a, { action: "grant", uuid: UUID, days: PLUS_MONTH_DAYS });
  assert.equal(tebexAction({ type: "payment.refunded", subject: { customer: { username: { id: UUID } } } }).action, "revoke");
  assert.equal(tebexAction({ type: "payment.dispute.opened", subject: { products: [{ username: { id: UUID } }] } }).uuid, UUID);
  assert.equal(tebexAction({ type: "recurring-payment.cancellation.requested", subject: {} }).action, "none");
});

test("each event counts once, a second event for the same charge adds nothing, refunds remove Plus", async () => {
  const { cosmetics, values } = store();
  const first = await post(cosmetics, "/plus/tebex", { eventId: "e1", action: "grant", uuid: UUID, days: 32 });
  assert.equal(first.outcome, "granted");
  const again = await post(cosmetics, "/plus/tebex", { eventId: "e1", action: "grant", uuid: UUID, days: 32 });
  assert.equal(again.duplicate, true);
  const until1 = values.get(`plus:${UUID}`).until;
  await post(cosmetics, "/plus/tebex", { eventId: "e2", action: "grant", uuid: UUID, days: 32 });
  assert.ok(values.get(`plus:${UUID}`).until - until1 < 1000);
  const gone = await post(cosmetics, "/plus/tebex", { eventId: "e3", action: "revoke", uuid: UUID });
  assert.equal(gone.outcome, "revoked");
  assert.equal(values.get(`plus:${UUID}`), undefined);
  const noAccount = await post(cosmetics, "/plus/tebex", { eventId: "e4", action: "grant", uuid: "" });
  assert.equal(noAccount.outcome, "no-account");
});

test("the package name sets how long Plus lasts", () => {
  const one = tebexAction({ type: "payment.completed", subject: { products: [{ name: "River Plus (1 Month)", username: { id: UUID } }] } });
  const three = tebexAction({ type: "payment.completed", subject: { products: [{ name: "River Plus (3 Months)", username: { id: UUID } }] } });
  assert.equal(one.days, 32);
  assert.equal(three.days, 94);
});

test("the account River put on the order is the one that gets Plus", () => {
  const a = tebexAction({ type: "payment.completed", subject: { custom: { uuid: DASHED }, products: [{ name: "River Plus (1 Month)" }] } });
  assert.equal(a.uuid, UUID);
});

test("three-month renewals retain the full period from Tebex payment subjects", async () => {
  const payment = { custom: { uuid: DASHED }, products: [{ name: "River Plus (3 Months)", quantity: 1 }] };
  for (const subject of [
    { initial_payment: payment },
    { last_payment: payment },
    { products: [], initial_payment: payment },
    { recurring_payment: { initial_payment: payment } },
    { recurring_payment: { last_payment: payment } }
  ]) {
    const action = tebexAction({ type: "recurring-payment.renewed", subject });
    assert.deepEqual(action, { action: "grant", uuid: UUID, days: 94 });
  }
  const { cosmetics, values } = store();
  const action = tebexAction({ type: "recurring-payment.renewed", subject: { initial_payment: payment, last_payment: payment } });
  assert.deepEqual(action, { action: "grant", uuid: UUID, days: 94 });
  const before = Date.now();
  await post(cosmetics, "/plus/tebex", { eventId: "renewal-3-months", ...action });
  assert.ok(values.get(`plus:${UUID}`).until >= before + 94 * DAY_MS);
  assert.ok(values.get(`plus:${UUID}`).until <= Date.now() + 94 * DAY_MS);
});

test("renewals use the latest package after a subscription changes", () => {
  for (const [initialMonths, currentMonths, expectedDays] of [[1, 3, 94], [3, 1, 32]]) {
    const subject = {
      initial_payment: { custom: { uuid: UUID }, products: [{ name: `River Plus (${initialMonths} Months)` }] },
      last_payment: { custom: { uuid: UUID }, products: [{ name: `River Plus (${currentMonths} Months)` }] }
    };
    assert.equal(tebexAction({ type: "recurring-payment.renewed", subject }).days, expectedDays);
  }
});

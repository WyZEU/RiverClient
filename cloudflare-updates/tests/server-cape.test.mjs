import test from "node:test";
import assert from "node:assert/strict";
import { Cosmetics, SERVER_CAPE_PREFIX, SERVER_CAPE_PNG_CHARS } from "../src/cosmetics.js";

// A PNG's first bytes, base64: the prefix every real PNG starts with.
const PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==";
const UUID = "01234567-89ab-cdef-0123-456789abcdef";
const OTHER = "fedcba98-7654-3210-fedc-ba9876543210";

function store() {
  const values = new Map();
  const storage = {
    get: async (key) => values.get(key),
    put: async (key, value) => values.set(key, value),
    delete: async (key) => values.delete(key),
    list: async ({ prefix }) => new Map([...values].filter(([k]) => k.startsWith(prefix))),
    transaction: async (fn) => fn(storage)
  };
  return { cosmetics: new Cosmetics({ storage }), values };
}

const call = async (cosmetics, path, body) => {
  const res = await cosmetics.fetch(new Request(`https://cosmetics${path}`,
    body ? { method: "POST", body: JSON.stringify(body) } : {}));
  return { status: res.status, body: await res.json() };
};

test("a server cape is created with a code, and the code gives the token to the one who redeems it", async () => {
  const { cosmetics, values } = store();
  const made = await call(cosmetics, "/server-cape/create", { name: "Creator", png: PNG, code: "CREATORCAPE1" });
  assert.equal(made.body.ok, true);
  assert.equal(made.body.code, "CREATORCAPE1");
  const token = made.body.token;

  const first = await call(cosmetics, "/redeem", { code: "CREATORCAPE1", uuid: UUID });
  assert.equal(first.body.ok, true);
  assert.equal(first.body.already, false);
  assert.equal(first.body.token, token);
  assert.equal(first.body.name, "Creator");

  // Re-entering the code is not an error, and gives the same token again.
  const again = await call(cosmetics, "/redeem", { code: "CREATORCAPE1", uuid: UUID });
  assert.equal(again.body.already, true);
  assert.equal(again.body.token, token);
  assert.equal(values.get(`code:CREATORCAPE1`).uses, 1);
});

test("nothing public lists a server cape: /owned never shows it", async () => {
  const { cosmetics } = store();
  const made = await call(cosmetics, "/server-cape/create", { name: "Hidden", png: PNG, code: "HIDDENCAPE01" });
  await call(cosmetics, "/redeem", { code: "HIDDENCAPE01", uuid: UUID });
  const owned = await call(cosmetics, `/owned?uuid=${UUID}`);
  assert.ok(!JSON.stringify(owned.body).includes(made.body.token));
  assert.ok(!JSON.stringify(owned.body).includes("sc-"));
  const other = await call(cosmetics, `/owned?uuid=${OTHER}`);
  assert.deepEqual(other.body.owned, []);
});

test("the art is served by token, and only the token gets it", async () => {
  const { cosmetics } = store();
  const made = await call(cosmetics, "/server-cape/create", { name: "Art", png: PNG, code: "ARTCAPE00001" });
  const art = await call(cosmetics, `/cape?token=${made.body.token}`);
  assert.equal(art.body.name, "Art");
  assert.equal(art.body.png, PNG);
  const wrong = await call(cosmetics, `/cape?token=${"0".repeat(24)}`);
  assert.equal(wrong.status, 404);
  const junk = await call(cosmetics, `/cape?token=../../etc`);
  assert.equal(junk.status, 400);
});

test("bad input is refused: no PNG, a PNG that is too big, a short code, a name with symbols, a repeat code", async () => {
  const { cosmetics } = store();
  assert.equal((await call(cosmetics, "/server-cape/create", { name: "x", png: "aGVsbG8=" })).status, 400);
  assert.equal((await call(cosmetics, "/server-cape/create", { name: "x", png: PNG + "A".repeat(SERVER_CAPE_PNG_CHARS) })).status, 400);
  assert.equal((await call(cosmetics, "/server-cape/create", { name: "x", png: PNG, code: "SHORT" })).status, 400);
  assert.equal((await call(cosmetics, "/server-cape/create", { name: "<b>", png: PNG })).status, 400);
  assert.equal((await call(cosmetics, "/server-cape/create", { name: "Ok", png: PNG, code: "TAKENCODE123" })).status, 200);
  assert.equal((await call(cosmetics, "/server-cape/create", { name: "Ok", png: PNG, code: "TAKENCODE123" })).status, 409);
});

test("a built-in cape code still works the same way", async () => {
  const { cosmetics } = store();
  await call(cosmetics, "/codes/create", { code: "PLAINCODE123", cosmetic: "eclipse" });
  const redeemed = await call(cosmetics, "/redeem", { code: "PLAINCODE123", uuid: UUID });
  assert.equal(redeemed.body.cosmetic, "eclipse");
  assert.equal(redeemed.body.token, undefined);
  assert.ok(SERVER_CAPE_PREFIX === "sc-");
});

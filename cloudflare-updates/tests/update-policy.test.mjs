import test from "node:test";
import assert from "node:assert/strict";
import {
  compareVersions, effectiveMinimum, normalizePolicy, readPolicy, releaseVersions, resetUpdatePolicyCache, writePolicy
} from "../src/update-policy.js";

const RELEASES = ["0.2.7", "0.2.6", "0.2.5.1", "0.2.5", "0.2.4", "0.2.3"];

test("versions compare part by part, with a fourth part counting", () => {
  assert.equal(compareVersions("0.2.5.1", "0.2.5"), 1);
  assert.equal(compareVersions("0.2.10", "0.2.9"), 1);
  assert.equal(compareVersions("0.2.7+1.21.11", "0.2.7"), 1);
  assert.equal(compareVersions("0.2.7", "0.2.7"), 0);
  assert.equal(compareVersions("0.1.9", "0.2"), -1);
});

test("three behind has to update: the latest and the two before it still run", () => {
  const minimum = effectiveMinimum({ latest: "0.2.7", manifestMinimum: "0.1.7", policy: { forcedMinimum: "", maxBehind: 3 }, releases: RELEASES });
  assert.equal(minimum, "0.2.5.1");
  assert.equal(compareVersions("0.2.5", minimum) < 0, true); // three behind: forced
  assert.equal(compareVersions("0.2.5.1", minimum) >= 0, true); // two behind: still fine
});

test("a forced minimum wins when it is stricter, and is ignored when it is newer than the latest release", () => {
  const forced = effectiveMinimum({ latest: "0.2.7", manifestMinimum: "0.1.7", policy: { forcedMinimum: "0.2.7", maxBehind: 3 }, releases: RELEASES });
  assert.equal(forced, "0.2.7");
  const tooNew = effectiveMinimum({ latest: "0.2.7", manifestMinimum: "0.1.7", policy: { forcedMinimum: "0.3.0", maxBehind: 3 }, releases: RELEASES });
  assert.equal(tooNew, "0.2.5.1");
});

test("tester builds newer than the public release do not count towards behind", () => {
  const minimum = effectiveMinimum({ latest: "0.2.7", manifestMinimum: "", policy: { forcedMinimum: "", maxBehind: 3 }, releases: ["0.2.9", "0.2.8", ...RELEASES] });
  assert.equal(minimum, "0.2.5.1");
});

test("maxBehind 0 turns the rule off and keeps the published minimum", () => {
  const minimum = effectiveMinimum({ latest: "0.2.7", manifestMinimum: "0.1.7", policy: { forcedMinimum: "", maxBehind: 0 }, releases: RELEASES });
  assert.equal(minimum, "0.1.7");
});

test("bad policy values fall back to the defaults", () => {
  assert.deepEqual(normalizePolicy({ forcedMinimum: "latest", maxBehind: -2 }), { forcedMinimum: "", maxBehind: 3 });
  assert.deepEqual(normalizePolicy({ forcedMinimum: " 0.2.6 ", maxBehind: 5 }), { forcedMinimum: "0.2.6", maxBehind: 5 });
});

test("the policy and the release list are read from the bucket", async () => {
  resetUpdatePolicyCache();
  const store = new Map();
  const env = {
    UPDATES: {
      async get(key) { return store.has(key) ? { json: async () => JSON.parse(store.get(key)) } : null; },
      async put(key, value) { store.set(key, value); },
      async list({ prefix, delimiter }) {
        assert.equal(prefix, "releases/");
        assert.equal(delimiter, "/");
        return { delimitedPrefixes: ["releases/0.2.6/", "releases/0.2.7/", "releases/junk/", "releases/0.2.5.1/"], truncated: false };
      }
    }
  };
  assert.deepEqual(await readPolicy(env), { forcedMinimum: "", maxBehind: 3 });
  await writePolicy(env, { forcedMinimum: "0.2.7", maxBehind: 2 });
  assert.deepEqual(await readPolicy(env), { forcedMinimum: "0.2.7", maxBehind: 2 });
  assert.deepEqual(await releaseVersions(env), ["0.2.7", "0.2.6", "0.2.5.1"]);
});

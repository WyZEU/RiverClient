"use strict";

const fs = require("node:fs");
const crypto = require("node:crypto");
const { execFileSync } = require("node:child_process");
const { parseArgs } = require("node:util");
const { assert } = require("./release-artifact");
const { PUBLIC_KEY_B64, verifyManifest } = require("../src/update-signature");
const config = require("../update-publishing.json");
const environment = "river-update-production";
const names = ["RIVER_UPDATE_SIGNING_KEY_PEM", "RIVER_R2_ACCESS_KEY_ID", "RIVER_R2_SECRET_ACCESS_KEY"];
function gh(args, input) {
  return execFileSync("gh", args, { input, encoding: "utf8", stdio: ["pipe", "pipe", "pipe"] });
}
function api(endpoint, body) {
  return JSON.parse(gh(["api", endpoint, ...(body ? ["--method", "PUT", "--input", "-"] : [])], body && JSON.stringify(body)));
}
function validateEnvironment(settings, policies) {
  assert(settings.deployment_branch_policy?.custom_branch_policies === true && !settings.deployment_branch_policy.protected_branches,
    "Publication environment must use custom deployment branch rules.");
  assert(policies.branch_policies?.length === 1 && policies.branch_policies[0].name === "master" && policies.branch_policies[0].type === "branch",
    "Publication environment must allow only the master branch, with no tag rules.");
}
async function main() {
  const { values } = parseArgs({ options: { apply: { type: "boolean", default: false }, "key-file": { type: "string" }, "policy-only": { type: "boolean", default: false } } });
  const base = `repos/${config.repository}/environments/${environment}`;
  if (values.apply) {
    const repo = api(`repos/${config.repository}`);
    assert(repo.permissions?.admin, "Repository admin access is needed to configure the protected publication environment.");
    assert(values["key-file"], "Provide --key-file with the EXISTING updater Ed25519 private key.");
    const key = fs.readFileSync(values["key-file"], "utf8");
    const privateKey = crypto.createPrivateKey(key);
    assert(privateKey.asymmetricKeyType === "ed25519" && crypto.createPublicKey(privateKey).export({ format: "der", type: "spki" }).toString("base64") === PUBLIC_KEY_B64,
      "This private key does not match installed clients. Do not rotate the update trust key.");
    assert(process.env.RIVER_R2_ACCESS_KEY_ID && process.env.RIVER_R2_SECRET_ACCESS_KEY, "Supply bucket-scoped R2 Object Read & Write credentials in the two RIVER_R2 environment variables.");
    const { createR2Store } = require("./r2-release-store");
    const current = await createR2Store().getJson("latest.json");
    assert(current && verifyManifest(current.value), "R2 credentials cannot read the existing correctly signed stable manifest.");
    const environments = api(`repos/${config.repository}/environments?per_page=100`).environments;
    const existing = environments.find((item) => item.name === environment);
    if (!existing || (!existing.deployment_branch_policy && !existing.protection_rules?.length)) {
      api(base, { deployment_branch_policy: { protected_branches: false, custom_branch_policies: true } });
      gh(["api", `${base}/deployment-branch-policies`, "--method", "POST", "--input", "-"], JSON.stringify({ name: "master", type: "branch" }));
    }
    validateEnvironment(api(base), api(`${base}/deployment-branch-policies?per_page=100`));
    for (const [name, value] of Object.entries({ RIVER_UPDATE_SIGNING_KEY_PEM: key,
      RIVER_R2_ACCESS_KEY_ID: process.env.RIVER_R2_ACCESS_KEY_ID, RIVER_R2_SECRET_ACCESS_KEY: process.env.RIVER_R2_SECRET_ACCESS_KEY })) {
      gh(["secret", "set", name, "--repo", config.repository, "--env", environment], value);
    }
  }
  validateEnvironment(api(base), api(`${base}/deployment-branch-policies?per_page=100`));
  if (values["policy-only"]) {
    console.log("Verified master-only publication environment.");
    return;
  }
  const secrets = api(`${base}/secrets?per_page=100`).secrets.map((entry) => entry.name);
  assert(names.every((name) => secrets.includes(name)), `Configure environment secrets: ${names.filter((name) => !secrets.includes(name)).join(", ")}`);
  console.log(`Verified master-only ${environment} environment and secret names. Secret values are not returned by GitHub.`);
}

if (require.main === module) main().catch((error) => { console.error(error.message); process.exitCode = 1; });
module.exports = { validateEnvironment };

const path = require("node:path");
const pkg = require("./package.json");
const signing = require("./windows-signing.json");

// Dedicated release configuration. Keep ordinary local builds independent of Azure.
// A hook is needed because electron-builder 26's built-in Azure provider requires
// a client secret/certificate and rejects the passwordless Azure CLI OIDC session.
if (!/^\d+\.\d+\.\d+$/.test(pkg.riverVersion)) {
  throw new Error("Signed Windows releases require a three-part riverVersion.");
}

module.exports = {
  ...pkg.build,
  extends: null,
  forceCodeSigning: true,
  buildVersion: `${pkg.riverVersion}.0`,
  extraMetadata: { version: pkg.riverVersion },
  win: {
    ...pkg.build.win,
    signtoolOptions: {
      publisherName: signing.publisher,
      signingHashAlgorithms: ["sha256"],
      sign: path.join(__dirname, "scripts", "sign-windows.cjs")
    }
  },
  // Nothing is uploaded to GitHub Releases or the production updater by this build.
  publish: null
};

const pkg = require("./package.json");

if (!/^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/.test(pkg.riverVersion)) {
  throw new Error("Linux releases require canonical MAJOR.MINOR.PATCH.");
}

module.exports = {
  ...pkg.build,
  extends: null,
  files: [...pkg.build.files, "!app/bundled/**"],
  extraMetadata: { version: pkg.riverVersion },
  publish: null
};

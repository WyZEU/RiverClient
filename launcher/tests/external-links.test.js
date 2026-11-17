const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

// The production helper, run as written: links from outside may only open as web pages or e-mail.
const source = fs.readFileSync(path.join(__dirname, "../src/main.js"), "utf8");
const start = source.indexOf("function safeExternalUrl(");
const context = vm.createContext({ URL });
vm.runInContext(source.slice(start, source.indexOf("\n}", start) + 2), context);
const safe = (value) => context.safeExternalUrl(value);

test("web and e-mail links open", () => {
  assert.equal(safe("https://modrinth.com/mod/sodium"), "https://modrinth.com/mod/sodium");
  assert.equal(safe("http://example.com/"), "http://example.com/");
  assert.equal(safe("mailto:support@riverclient.xyz"), "mailto:support@riverclient.xyz");
});

test("addresses that could run something are refused", () => {
  for (const value of ["file:///C:/Windows/System32/calc.exe", "ms-msdt:/id PCWDiagnostic", "search-ms:query=x",
    "javascript:alert(1)", String.raw`C:\Windows\notepad.exe`, String.raw`\\server\share\x.exe`, "", null, "not a url"]) {
    assert.equal(safe(value), null, String(value));
  }
});

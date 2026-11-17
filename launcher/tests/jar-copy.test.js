const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const vm = require("node:vm");

// The production helpers, run as written: River's jar in an instance is only copied when it is
// really out of date, and never left half-written.
const source = fs.readFileSync(path.join(__dirname, "../src/main.js"), "utf8");
function extract(name) {
  const start = source.indexOf(`function ${name}(`);
  assert.ok(start >= 0, name);
  return source.slice(start, source.indexOf("\n}", start) + 2);
}
const logs = [];
const context = vm.createContext({ fs, path, emit: (_channel, line) => logs.push(line) });
vm.runInContext(["const keptJarNotes = new Set();", extract("jarCopyStale"), extract("replaceJarFile")].join("\n"), context);

test("a jar copied a moment ago is not copied again", () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "river-jar-"));
  let again = 0;
  for (let i = 0; i < 30; i++) {
    const src = path.join(dir, "src.jar");
    const dst = path.join(dir, "dst.jar");
    fs.writeFileSync(src, "x".repeat(2000 + i));
    const stat = fs.statSync(src);
    context.replaceJarFile(src, dst, stat);
    if (context.jarCopyStale(fs.statSync(src), fs.statSync(dst))) again++;
  }
  assert.equal(again, 0);
  fs.rmSync(dir, { recursive: true, force: true });
});

test("a newer or different jar is copied", () => {
  const now = Date.now();
  assert.equal(context.jarCopyStale({ size: 10, mtimeMs: now }, null), true);
  assert.equal(context.jarCopyStale({ size: 10, mtimeMs: now }, { size: 11, mtimeMs: now }), true);
  assert.equal(context.jarCopyStale({ size: 10, mtimeMs: now + 5000 }, { size: 10, mtimeMs: now }), true);
  assert.equal(context.jarCopyStale({ size: 10, mtimeMs: now + 0.7 }, { size: 10, mtimeMs: now }), false);
});

test("replacing goes through a temp file and leaves no leftovers", () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "river-jar-"));
  const src = path.join(dir, "new.jar");
  const dst = path.join(dir, "clientcore.jar");
  fs.writeFileSync(dst, "old");
  fs.writeFileSync(src, "new contents");
  assert.equal(context.replaceJarFile(src, dst, fs.statSync(src)), true);
  assert.equal(fs.readFileSync(dst, "utf8"), "new contents");
  assert.deepEqual(fs.readdirSync(dir).sort(), ["clientcore.jar", "new.jar"]);
  fs.rmSync(dir, { recursive: true, force: true });
});

test("a jar that cannot be replaced is kept as it was", () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "river-jar-"));
  const dst = path.join(dir, "clientcore.jar");
  fs.writeFileSync(dst, "in use");
  // A source that does not exist stands in for any failure on the way.
  assert.equal(context.replaceJarFile(path.join(dir, "missing.jar"), dst, { atime: new Date(), mtime: new Date() }), false);
  assert.equal(fs.readFileSync(dst, "utf8"), "in use");
  assert.deepEqual(fs.readdirSync(dir), ["clientcore.jar"]);
  fs.rmSync(dir, { recursive: true, force: true });
});

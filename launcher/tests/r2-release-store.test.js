const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { Readable } = require("node:stream");
const { createR2Store } = require("../scripts/r2-release-store");

function temporaryFile(t, bytes) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "river-r2-test-"));
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  const file = path.join(directory, "app.bin");
  fs.writeFileSync(file, bytes);
  return file;
}

test("R2 upload uses create-only multipart completion and streams the exact original bytes", async (t) => {
  const bytes = Buffer.from("abcdefghijklmno"), file = temporaryFile(t, bytes), calls = [], parts = [];
  const client = { async send(command) {
    calls.push({ name: command.constructor.name, input: command.input });
    if (command.constructor.name === "CreateMultipartUploadCommand") return { UploadId: "upload-1" };
    if (command.constructor.name === "UploadPartCommand") {
      const chunks = [];
      for await (const chunk of command.input.Body) chunks.push(chunk);
      parts.push(Buffer.concat(chunks));
      return { ETag: `"part-${parts.length}"` };
    }
    return {};
  } };
  await createR2Store({ client, partBytes: 5 }).putFile("releases/0.3.1-test/app.bin", file, { size: bytes.length, sha256: "test", contentType: "application/octet-stream" });
  assert.deepEqual(Buffer.concat(parts), bytes);
  assert.equal(calls.at(-1).name, "CompleteMultipartUploadCommand");
  assert.equal(calls.at(-1).input.IfNoneMatch, "*");
  assert.equal(calls.at(-1).input.MultipartUpload.Parts.length, 3);
});

test("R2 aborts incomplete multipart uploads and never completes a failed transfer", async (t) => {
  const file = temporaryFile(t, Buffer.alloc(20)), names = [];
  const client = { async send(command) {
    names.push(command.constructor.name);
    if (command.constructor.name === "CreateMultipartUploadCommand") return { UploadId: "failed-upload" };
    if (command.constructor.name === "UploadPartCommand") { command.input.Body.destroy(); throw new Error("network failed"); }
    return {};
  } };
  await assert.rejects(createR2Store({ client, partBytes: 5 }).putFile("releases/0.3.1-test/app.bin", file,
    { size: 20, sha256: "test", contentType: "application/octet-stream" }), /network failed/);
  assert.equal(names.at(-1), "AbortMultipartUploadCommand");
  assert.equal(names.includes("CompleteMultipartUploadCommand"), false);
});

test("small R2 files and channel manifests use conditional writes; auth errors are not treated as absence", async (t) => {
  const file = temporaryFile(t, Buffer.from("abc")), calls = [];
  const client = { async send(command) {
    calls.push(command);
    if (command.input.Body?.pipe) for await (const _chunk of command.input.Body) { /* consume the stream */ }
    if (command.constructor.name === "HeadObjectCommand") throw Object.assign(new Error("denied"), { $metadata: { httpStatusCode: 403 } });
    return {};
  } };
  const store = createR2Store({ client });
  await store.putFile("releases/0.3.1-test/file", file, { size: 3, sha256: "test", contentType: "application/octet-stream" });
  assert.equal(calls.at(-1).input.IfNoneMatch, "*");
  await store.putJson("latest.json", Buffer.from("{}"), '"old-etag"');
  assert.equal(calls.at(-1).input.IfMatch, '"old-etag"');
  assert.equal(calls.at(-1).input.IfNoneMatch, undefined);
  await assert.rejects(store.head("private"), /denied/);
});

test("R2 readback hashes actual stored bytes, independently of their metadata", async () => {
  const client = { async send() { return { Body: Readable.from([Buffer.from("corrupt")]) }; } };
  await assert.rejects(createR2Store({ client }).verify("releases/0.3.1-test/file", { size: 7, sha256: "0".repeat(64) }), /readback mismatch/);
});

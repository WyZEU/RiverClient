"use strict";

const fs = require("node:fs");
const { finished } = require("node:stream/promises");
const { S3Client, GetObjectCommand, HeadObjectCommand, PutObjectCommand,
  CreateMultipartUploadCommand, UploadPartCommand, CompleteMultipartUploadCommand, AbortMultipartUploadCommand } = require("@aws-sdk/client-s3");
const { assert, digestStream } = require("./release-artifact");
const config = require("../update-publishing.json");

const missing = (error) => error?.$metadata?.httpStatusCode === 404;
function createR2Store({ client, partBytes = 16 * 1024 ** 2 } = {}) {
  if (!client) {
    assert(process.env.RIVER_R2_ACCESS_KEY_ID && process.env.RIVER_R2_SECRET_ACCESS_KEY, "R2 publishing credentials are missing.");
    client = new S3Client({ region: "auto", endpoint: `https://${config.accountId}.r2.cloudflarestorage.com`,
      credentials: { accessKeyId: process.env.RIVER_R2_ACCESS_KEY_ID, secretAccessKey: process.env.RIVER_R2_SECRET_ACCESS_KEY },
      requestChecksumCalculation: "WHEN_REQUIRED", responseChecksumValidation: "WHEN_REQUIRED" });
  }
  const send = (Command, fields) => client.send(new Command({ Bucket: config.bucket, ...fields }));
  async function sendFile(Command, fields, file, range) {
    const body = fs.createReadStream(file, range);
    const closed = finished(body, { cleanup: true }).catch(() => {});
    try { return await send(Command, { ...fields, Body: body }); }
    finally { body.destroy(); await closed; }
  }
  return {
    async head(key) {
      try { return await send(HeadObjectCommand, { Key: key }); }
      catch (error) { if (missing(error)) return null; throw error; }
    },
    async getJson(key) {
      let result;
      try { result = await send(GetObjectCommand, { Key: key }); }
      catch (error) { if (missing(error)) return null; throw error; }
      const chunks = [];
      let size = 0;
      for await (const chunk of result.Body) {
        size += chunk.length;
        assert(size <= 16 * 1024 ** 2, "Remote release metadata is too large.");
        chunks.push(chunk);
      }
      const bytes = Buffer.concat(chunks);
      assert(result.ETag, "Missing R2 object ETag.");
      return { value: JSON.parse(bytes.toString("utf8")), bytes, etag: result.ETag };
    },
    async verify(key, expected) {
      const object = await send(GetObjectCommand, { Key: key });
      const actual = await digestStream(object.Body, expected.size);
      assert(actual.size === expected.size && actual.sha256 === expected.sha256, `R2 readback mismatch: ${key}`);
    },
    async putFile(key, file, object) {
      const fields = { Key: key, ContentType: object.contentType, CacheControl: "public, max-age=31536000, immutable",
        Metadata: { sha256: object.sha256 } };
      if (object.size <= partBytes) {
        await sendFile(PutObjectCommand, { ...fields, ContentLength: object.size, IfNoneMatch: "*" }, file);
        return;
      }
      const created = await send(CreateMultipartUploadCommand, fields);
      assert(created.UploadId, "R2 did not create a multipart upload.");
      try {
        const parts = [];
        for (let start = 0, number = 1; start < object.size; start += partBytes, number++) {
          const length = Math.min(partBytes, object.size - start);
          const part = await sendFile(UploadPartCommand, { Key: key, UploadId: created.UploadId, PartNumber: number,
            ContentLength: length }, file, { start, end: start + length - 1 });
          assert(part.ETag, "R2 multipart response omitted its ETag.");
          parts.push({ PartNumber: number, ETag: part.ETag });
        }
        await send(CompleteMultipartUploadCommand, { Key: key, UploadId: created.UploadId,
          MultipartUpload: { Parts: parts }, IfNoneMatch: "*" });
      } catch (error) {
        await send(AbortMultipartUploadCommand, { Key: key, UploadId: created.UploadId }).catch(() => {});
        throw error;
      }
    },
    async putJson(key, bytes, previousEtag = null) {
      await send(PutObjectCommand, { Key: key, Body: bytes, ContentType: "application/json; charset=utf-8",
        CacheControl: "no-store", ...(previousEtag ? { IfMatch: previousEtag } : { IfNoneMatch: "*" }) });
    }
  };
}

module.exports = { createR2Store };

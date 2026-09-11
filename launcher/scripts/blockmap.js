"use strict";

/**
 * Content-defined chunking, so an update can fetch the parts of a file that changed
 * instead of the whole thing.
 *
 * River ships about 515MB of files per release. File level diffing already skips
 * everything that did not change, which covers the Electron DLLs, but two files change on
 * every single build and account for 321MB of that: RiverClient.exe, because the ASAR
 * integrity hash and the signature are patched into it, and resources/app.asar, because
 * it holds our own JavaScript. Almost none of either file is actually different.
 *
 * Fixed size blocks would solve the exe and fail the asar. The exe keeps its layout - the
 * same Electron binary with a few bytes rewritten in place - but an asar is a header
 * followed by concatenated files, so changing one of them moves everything after it and
 * every later block would come out different despite holding identical bytes. Boundaries
 * are therefore chosen by content rather than position: a rolling hash over a small window
 * decides where a chunk ends, so inserting bytes changes the chunks around the insertion
 * and leaves the rest alone.
 */

const crypto = require("node:crypto");
const fs = require("node:fs");

/*
  Gear table for the rolling hash. Deterministic, because both ends must chunk the same
  bytes into the same pieces - it is generated from a fixed seed rather than randomly.
*/
const GEAR = (() => {
  const table = new Uint32Array(256);
  let seed = 0x1f2e3d4c;
  for (let i = 0; i < 256; i++) {
    // xorshift32, purely to spread the bits deterministically.
    seed ^= seed << 13; seed >>>= 0;
    seed ^= seed >>> 17;
    seed ^= seed << 5; seed >>>= 0;
    table[i] = seed;
  }
  return table;
})();

/*
  Measured on two consecutive real builds, fetching only the chunks that changed:

    1MB average    8.71MB of data,  20KB of blockmap
    256KB average  3.01MB of data,  75KB of blockmap
    64KB average   1.41MB of data, 295KB of blockmap

  The blockmap is downloaded every time, so the figure that matters is the sum. 64KB
  wins it at about 1.7MB against 321MB today, and ten ranges is still a handful of
  requests rather than thousands.
*/
const MIN_CHUNK = 16 * 1024;
const AVG_CHUNK = 64 * 1024;
const MAX_CHUNK = 256 * 1024;
// One bit per power of two, so a chunk ends on average every AVG_CHUNK bytes.
const MASK = (1 << Math.round(Math.log2(AVG_CHUNK))) - 1;

/*
  Half a SHA-256 per chunk. 128 bits is far past the point where an accidental collision
  is worth worrying about across a few thousand chunks, and the blockmap is downloaded on
  every update so its size is part of the cost. The rebuilt file is verified against the
  full hash from the file manifest regardless, so a collision would be caught there and
  fall back to a plain download rather than install wrong bytes.
*/
const HASH_CHARS = 32;

/** Only files big enough for the bookkeeping to pay for itself get a blockmap. */
const BLOCKMAP_MIN_BYTES = 8 * 1024 * 1024;

/**
 * Splits a buffer into content-defined chunks.
 * Returns [{ offset, size, sha256 }] covering the whole buffer in order.
 */
function chunkBuffer(buffer) {
  const chunks = [];
  let start = 0;
  while (start < buffer.length) {
    let hash = 0;
    let end = Math.min(start + MAX_CHUNK, buffer.length);
    // Below the minimum a boundary is not even looked for, which stops a pathological
    // run of bytes from producing thousands of tiny chunks.
    for (let i = start + MIN_CHUNK; i < end; i++) {
      hash = (((hash << 1) >>> 0) + GEAR[buffer[i]]) >>> 0;
      if ((hash & MASK) === 0) { end = i + 1; break; }
    }
    const slice = buffer.subarray(start, end);
    // Offsets are not stored: they are just the running total of the sizes.
    chunks.push({
      size: slice.length,
      hash: crypto.createHash("sha256").update(slice).digest("hex").slice(0, HASH_CHARS)
    });
    start = end;
  }
  return chunks;
}

function buildBlockmap(filePath) {
  const buffer = fs.readFileSync(filePath);
  return {
    version: 1,
    algorithm: "gear-cdc-1",
    size: buffer.length,
    sha256: crypto.createHash("sha256").update(buffer).digest("hex"),
    chunks: chunkBuffer(buffer)
  };
}

module.exports = { chunkBuffer, buildBlockmap, BLOCKMAP_MIN_BYTES, HASH_CHARS };

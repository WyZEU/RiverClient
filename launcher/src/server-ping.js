"use strict";

/*
  Direct Minecraft server-list ping, the same status request the game's own
  multiplayer screen sends. The launcher used to ask api.mcsrvstat.us instead, and on
  2026-09-20 that API reported hypixel.net offline ("DNS lookup failed") for everyone,
  cached for five minutes at a time - so every server in River's list read Offline while
  all of them were up. A server's own answer cannot be wrong about whether it is up.
*/

const dns = require("node:dns/promises");
const net = require("node:net");

const DEFAULT_PORT = 25565;
// Any modern protocol number works for a status request; servers answer regardless.
const PROTOCOL_VERSION = 767;

function encodeVarInt(value) {
  const bytes = [];
  let remaining = value >>> 0;
  do {
    let byte = remaining & 0x7f;
    remaining >>>= 7;
    if (remaining !== 0) byte |= 0x80;
    bytes.push(byte);
  } while (remaining !== 0);
  return Buffer.from(bytes);
}

/** Reads a VarInt at [offset]; returns null until enough bytes have arrived. */
function readVarInt(buffer, offset) {
  let value = 0;
  let shift = 0;
  let position = offset;
  while (position < buffer.length) {
    const byte = buffer[position];
    value |= (byte & 0x7f) << shift;
    position += 1;
    if ((byte & 0x80) === 0) return { value: value >>> 0, next: position };
    shift += 7;
    if (shift > 35) throw new Error("VarInt too long");
  }
  return null;
}

function packet(id, payload) {
  const body = Buffer.concat([encodeVarInt(id), payload]);
  return Buffer.concat([encodeVarInt(body.length), body]);
}

function encodeString(text) {
  const bytes = Buffer.from(text, "utf8");
  return Buffer.concat([encodeVarInt(bytes.length), bytes]);
}

function splitAddress(address) {
  const trimmed = String(address || "").trim();
  const match = /^(.*?)(?::(\d{1,5}))?$/.exec(trimmed);
  const host = (match?.[1] || trimmed).replace(/^\[|\]$/g, "");
  const port = match?.[2] ? Number(match[2]) : null;
  return { host, port };
}

/** SRV first, like the game: an address without a port may point somewhere else entirely. */
async function resolveTarget(address) {
  const { host, port } = splitAddress(address);
  if (!host) throw new Error("empty address");
  if (port) return { host, port };
  try {
    const records = await dns.resolveSrv(`_minecraft._tcp.${host}`);
    if (records.length) {
      const best = records.sort((a, b) => a.priority - b.priority || b.weight - a.weight)[0];
      return { host: best.name, port: best.port || DEFAULT_PORT, srvHost: host };
    }
  } catch {}
  return { host, port: DEFAULT_PORT };
}

/** Flattens a chat component (string, object with text/extra, or array) to plain text. */
function chatToText(component) {
  if (component == null) return "";
  if (typeof component === "string") return component;
  if (Array.isArray(component)) return component.map(chatToText).join("");
  if (typeof component === "object") {
    const own = typeof component.text === "string" ? component.text : "";
    const extra = Array.isArray(component.extra) ? component.extra.map(chatToText).join("") : "";
    return own + extra;
  }
  return String(component);
}

function stripFormatting(text) {
  // Line breaks survive: the first line is the name a default-named server gets.
  return String(text || "")
    .replace(/\u00a7[0-9a-fk-or]/gi, "")
    .split(/\r?\n/)
    .map((line) => line.replace(/[ \t]+/g, " ").trim())
    .filter(Boolean)
    .join("\n");
}

/**
 * Resolves with the parsed status, or rejects. [timeoutMs] covers DNS plus the
 * round trip; the game's own screen gives up in about the same time.
 */
async function pingMinecraftServer(address, timeoutMs = 5000) {
  const target = await resolveTarget(address);
  const handshakeHost = target.srvHost || target.host;

  return new Promise((resolve, reject) => {
    const socket = net.createConnection({ host: target.host, port: target.port });
    let received = Buffer.alloc(0);
    let settled = false;

    const finish = (error, value) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      socket.destroy();
      if (error) reject(error);
      else resolve(value);
    };
    const timer = setTimeout(() => finish(new Error("timed out")), timeoutMs);

    socket.setNoDelay(true);
    socket.on("error", (error) => finish(error));
    socket.on("close", () => finish(new Error("connection closed before a status arrived")));
    socket.on("connect", () => {
      const handshake = packet(0x00, Buffer.concat([
        encodeVarInt(PROTOCOL_VERSION),
        encodeString(handshakeHost),
        Buffer.from([(target.port >> 8) & 0xff, target.port & 0xff]),
        encodeVarInt(1)
      ]));
      socket.write(Buffer.concat([handshake, packet(0x00, Buffer.alloc(0))]));
    });
    socket.on("data", (chunk) => {
      received = Buffer.concat([received, chunk]);
      try {
        const length = readVarInt(received, 0);
        if (!length) return;
        if (received.length < length.next + length.value) return;
        const id = readVarInt(received, length.next);
        if (!id || id.value !== 0x00) return finish(new Error(`unexpected packet ${id?.value}`));
        const jsonLength = readVarInt(received, id.next);
        if (!jsonLength) return;
        const json = received.subarray(jsonLength.next, jsonLength.next + jsonLength.value).toString("utf8");
        const status = JSON.parse(json);
        finish(null, {
          motd: stripFormatting(chatToText(status.description)),
          players: status.players
            ? { online: Number(status.players.online || 0), max: Number(status.players.max || 0) }
            : null,
          version: String(status.version?.name || ""),
          icon: typeof status.favicon === "string" && status.favicon.startsWith("data:image/") ? status.favicon : null
        });
      } catch (error) {
        finish(error);
      }
    });
  });
}

module.exports = { pingMinecraftServer };

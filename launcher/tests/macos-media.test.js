"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const script = fs.readFileSync(path.join(__dirname, "../../src/client/resources/assets/clientcore/media/session-macos.js"), "utf8");
const context = vm.createContext({});
vm.runInContext(script, context);
const sample = (info, playing = true, now = 100000) => context.mediaSnapshot(info, playing, "123:com.apple.Music", now);

test("Mac playback uses the player's timestamp and fractional playback rate", () => {
  const track = sample({Title:"Song", Artist:"Artist", ElapsedTime:10.125, Timestamp:98, Duration:180, PlaybackRate:1.5});
  assert.equal(track.positionMs, 13125);
  assert.equal(track.durationMs, 180000);
  assert.equal(track.artist, "Artist");
  assert.equal(track.rate, 1.5);
});

test("paused playback never advances from an old timestamp", () => {
  assert.equal(sample({ElapsedTime:12.5, Timestamp:1, PlaybackRate:1}, false).positionMs, 12500);
});

test("seek updates replace the previous timeline, including backward seeks", () => {
  assert.equal(sample({ElapsedTime:120, Timestamp:100}).positionMs, 120000);
  assert.equal(sample({ElapsedTime:5, Timestamp:100}).positionMs, 5000);
});

test("missing and invalid timestamps do not extrapolate from the epoch", () => {
  for (const Timestamp of [undefined, null, "bad", NaN, Infinity]) {
    assert.equal(sample({ElapsedTime:4, Timestamp}).positionMs, 4000);
  }
  assert.equal(sample({ElapsedTime:4, Timestamp:200}).positionMs, 4000);
});

test("position stays within a known duration but supports streams without duration", () => {
  assert.equal(sample({ElapsedTime:10, Duration:3}).positionMs, 3000);
  assert.equal(sample({ElapsedTime:-10, Duration:3}).positionMs, 0);
  assert.equal(sample({ElapsedTime:100, Duration:0}).positionMs, 100000);
  assert.equal(sample({ElapsedTime:Infinity, Duration:NaN}).positionMs, 0);
});

test("metadata stays literal and missing metadata clears the track", () => {
  const title = 'Žlutý "déšť"\n日本語';
  assert.equal(sample({Title:title}).title, title);
  assert.equal(sample({}).title, "");
  assert.equal(sample({Title:{toString:"ignored"}}).title, "");
});

test("transport commands target only the displayed player and expire", () => {
  const track = sample({Title:"Song"});
  const command = {action:"playpause", source:track.source, at:100000};
  assert.equal(context.mediaCommand(command, track, 100500), 2);
  assert.equal(context.mediaCommand({...command, action:"next"}, track, 100500), 4);
  assert.equal(context.mediaCommand({...command, action:"prev"}, track, 100500), 5);
  assert.equal(context.mediaCommand(command, {...track, source:"456:com.spotify.client"}, 100500), null);
  assert.equal(context.mediaCommand(command, track, 104000), null);
  assert.equal(context.mediaCommand({...command, at:110000}, track, 100500), null);
  assert.equal(context.mediaCommand({...command, at:NaN}, track, 100500), null);
  assert.equal(context.mediaCommand({...command, at:Infinity}, track, 100500), null);
  assert.equal(context.mediaCommand(command, {...track, title:""}, 100500), null);
});

test("unknown and inherited command names never reach the system", () => {
  const track = sample({Title:"Song"});
  for (const action of ["constructor", "toString", "__proto__", "seek", "next; quit", null]) {
    assert.equal(context.mediaCommand({action, source:track.source, at:100000}, track, 100500), null);
  }
});

test("artwork downloads allow only HTTPS image providers, never local or arbitrary URLs", () => {
  for (const host of ["is1-ssl.mzstatic.com", "i.scdn.co", "image-cdn-ak.spotifycdn.com", "i.ytimg.com"]) {
    const url = `https://${host}/image.png`;
    assert.equal(context.artworkUrl(url), url);
  }
  for (const url of ["file:///etc/passwd", "http://i.scdn.co/a", "https://localhost/a", "https://i.scdn.co.evil.com/a", "https://i.scdn.co@evil.com/a"]) {
    assert.equal(context.artworkUrl(url), "");
  }
});

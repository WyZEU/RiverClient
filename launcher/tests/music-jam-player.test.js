const test = require("node:test");
const assert = require("node:assert/strict");
const vm = require("node:vm");
const fs = require("node:fs");
const path = require("node:path");
const source = fs.readFileSync(path.join(__dirname, "../../src/client/resources/assets/clientcore/media/jam-spotify.js"), "utf8");
const uri = "spotify:track:4uLU6hMCjMI75M1A2tKUQC";

function player() {
  let running = true, track = uri, position = 1, playing = true;
  const calls = [];
  const app = {
    running: () => running,
    launch: () => { running = true; calls.push("launch"); },
    currentTrack: { id: () => track, name: () => "Song", artist: () => "Artist" },
    playerState: () => playing ? "playing" : "paused",
    playTrack: (next) => { track = next; calls.push(next); },
    play: () => { playing = true; calls.push("play"); },
    pause: () => { playing = false; calls.push("pause"); }
  };
  Object.defineProperty(app, "playerPosition", { get: () => () => position, set: (next) => { position = next; calls.push(["seek", next]); } });
  const context = vm.createContext({ Application: () => app });
  vm.runInContext(source, context);
  return { run: (args) => context.run(args), calls, stop: () => { running = false; } };
}
test("Spotify discovery never launches a closed player", () => {
  const p = player(); p.stop();
  assert.equal(p.run(["read"]), "{}");
  assert.deepEqual(p.calls, []);
});
test("an accepted jam selects its track and synchronizes seek and pause without repeated seeks", () => {
  const p = player();
  assert.equal(p.run(["sync", uri, "Song", "Artist", "8000", "false"]), "ok");
  assert.deepEqual(p.calls, [["seek", 8], "pause"]);
  p.calls.length = 0;
  p.run(["sync", uri, "Song", "Artist", "8500", "false"]);
  assert.deepEqual(p.calls, []);
  const next = "spotify:track:0uLU6hMCjMI75M1A2tKUQC";
  assert.equal(p.run(["sync", next, "Song", "Artist", "0", "true"]), "waiting");
  assert.deepEqual(p.calls, [next]);
});
test("no player command accepts an arbitrary URI or mismatched track metadata", () => {
  const p = player();
  assert.throws(() => p.run(["sync", "file:///tmp/run", "Song", "Artist", "0", "true"]));
  assert.equal(p.run(["sync", uri, "Different song", "Artist", "5000", "true"]), "waiting");
  assert.deepEqual(p.calls, []);
});

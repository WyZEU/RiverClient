const TTL = 90_000;
const INVITE_TTL = 120_000;
const MAX_MEMBERS = 8;
const roomKey = (id) => `jam:room:${id}`;
const memberKey = (uuid) => `jam:member:${uuid}`;
const inviteKey = (uuid) => `jam:invites:${uuid}`;
const clean = (value, max) => String(value || "").replace(/[\x00-\x1f\x7f\u00a7]/g, "").slice(0, max);
const integer = (value, max = 86_400_000) => Number.isSafeInteger(value) && value >= 0 && value <= max;

export function jamTrack(value) {
  if (!value || !integer(value.positionMs) || !integer(value.durationMs) || typeof value.playing !== "boolean") throw new Error("Invalid song clock.");
  const title = clean(value.title, 200), artist = clean(value.artist, 200);
  if (!title) throw new Error("No song is playing.");
  const uri = String(value.uri || "");
  if (uri && !/^spotify:track:[A-Za-z0-9]{22}$/.test(uri)) throw new Error("Invalid Spotify track.");
  return { title, artist, album: clean(value.album, 200), uri, durationMs: value.durationMs,
    positionMs: Math.min(value.positionMs, value.durationMs || 86_400_000), playing: value.playing };
}

export function jamLyrics(value) {
  if (!Array.isArray(value) || value.length > 500 || new TextEncoder().encode(JSON.stringify(value)).length > 64_000) throw new Error("Lyrics are too large.");
  let previous = -1;
  return value.map((line) => {
    if (!integer(line.timeMs) || line.timeMs < previous) throw new Error("Invalid lyric timing.");
    previous = line.timeMs;
    const result = { timeMs: line.timeMs, text: clean(line.text, 500) };
    if (line.words != null) {
      if (!Array.isArray(line.words) || line.words.length > 100) throw new Error("Too many lyric words.");
      let start = -1;
      result.words = line.words.map((word) => {
        if (!integer(word.startMs) || word.startMs < start || !integer(word.durationMs, 60_000)) throw new Error("Invalid word timing.");
        start = word.startMs;
        return { startMs: word.startMs, durationMs: word.durationMs, text: clean(word.text, 100) };
      });
    }
    return result;
  });
}

/** A private, explicitly joined listening session. Audio stays in each member's player. */
export class MusicJams {
  constructor(store, push, now = () => Date.now()) { this.store = store; this.push = push; this.now = now; }
  async room(id) {
    const room = id && await this.store.get(roomKey(id), null);
    return room && room.updatedAt + TTL > this.now() ? room : null;
  }
  async friends(a, b) {
    return (await this.store.friendsOf(a)).includes(b) && (await this.store.friendsOf(b)).includes(a) &&
      !await this.store.isBlockedEitherWay(a, b);
  }
  packet(room, includeLyrics = true) {
    return { type: "jam", room: room && (includeLyrics ? room : { ...room, lyrics: undefined }), serverNow: this.now() };
  }
  async broadcast(room, includeLyrics = true) {
    const allowed = [];
    for (const member of room.members) {
      if (member.uuid === room.host || await this.friends(room.host, member.uuid)) allowed.push(member);
      else { await this.store.put(memberKey(member.uuid), null); this.push(member.uuid, { type: "jam-ended", id: room.id, generation: room.generation }); }
    }
    room.members = allowed;
    await this.store.put(roomKey(room.id), room);
    for (const member of allowed) this.push(member.uuid, this.packet(room, includeLyrics));
  }
  async state(uuid) {
    const id = await this.store.get(memberKey(uuid), "");
    let room = await this.room(id);
    if (room && room.host !== uuid && !await this.friends(room.host, uuid)) { await this.leave(uuid); room = null; }
    const invites = (await this.store.get(inviteKey(uuid), [])).filter((invite) => invite.expiresAt > this.now());
    return { ok: true, ...this.packet(room?.members.some((member) => member.uuid === uuid) ? room : null), invites };
  }
  async leave(uuid) {
    const room = await this.room(await this.store.get(memberKey(uuid), ""));
    await this.store.put(memberKey(uuid), null);
    if (!room) return;
    if (room.host === uuid) {
      await this.store.put(roomKey(room.id), null);
      for (const member of room.members) {
        await this.store.put(memberKey(member.uuid), null);
        this.push(member.uuid, { type: "jam-ended", id: room.id, generation: room.generation });
      }
    } else {
      room.members = room.members.filter((member) => member.uuid !== uuid);
      await this.broadcast(room);
      this.push(uuid, { type: "jam-ended", id: room.id, generation: room.generation });
    }
  }
  async handle(route, session, body) {
    const uuid = session.uuid;
    if (route === "/jam/state") return this.state(uuid);
    if (route === "/jam/leave") { await this.leave(uuid); return { ok: true, room: null }; }
    if (route === "/jam/create") {
      const track = jamTrack(body.track);
      await this.leave(uuid);
      const room = { id: uuid, generation: crypto.randomUUID(), host: uuid, hostName: clean(session.name, 40),
        members: [{ uuid, name: clean(session.name, 40) }], track, lyrics: [], sequence: 0, updatedAt: this.now() };
      await this.store.put(memberKey(uuid), uuid);
      await this.broadcast(room);
      return { ok: true, ...this.packet(room) };
    }
    const room = await this.room(String(body.id || ""));
    if (!room || room.generation !== body.generation) return { ok: false, message: "This Music Jam has ended." };
    if (route === "/jam/join") {
      const invites = await this.store.get(inviteKey(uuid), []);
      const invite = invites.find((entry) => entry.id === room.id && entry.generation === room.generation && entry.expiresAt > this.now());
      if (!invite || !await this.friends(room.host, uuid)) return { ok: false, message: "A friend invitation is required." };
      if (room.members.length >= MAX_MEMBERS) return { ok: false, message: "This Music Jam is full." };
      await this.leave(uuid);
      room.members = room.members.filter((member) => member.uuid !== uuid);
      room.members.push({ uuid, name: clean(session.name, 40) });
      await this.store.put(inviteKey(uuid), invites.filter((entry) => entry !== invite));
      await this.store.put(memberKey(uuid), room.id);
      await this.broadcast(room);
      return { ok: true, ...this.packet(room) };
    }
    if (room.host !== uuid) return { ok: false, message: "Only the host can change this Music Jam." };
    if (route === "/jam/invite") {
      const friend = String(body.uuid || "");
      if (friend === uuid || !await this.friends(uuid, friend)) return { ok: false, message: "Choose a River friend." };
      if (room.members.some((member) => member.uuid === friend)) return { ok: false, message: "Already in this Music Jam." };
      const invite = { id: room.id, generation: room.generation, hostName: room.hostName, expiresAt: this.now() + INVITE_TTL };
      const invites = (await this.store.get(inviteKey(friend), [])).filter((entry) => entry.expiresAt > this.now() && entry.id !== room.id).slice(-7);
      invites.push(invite);
      await this.store.put(inviteKey(friend), invites);
      this.push(friend, { type: "jam-invite", invite });
      return { ok: true, message: "Invitation sent." };
    }
    if (route === "/jam/publish") {
      if (!integer(body.sequence, Number.MAX_SAFE_INTEGER) || body.sequence <= room.sequence) return { ok: false, message: "Stale song update." };
      const track = jamTrack(body.track);
      const changed = ["title", "artist", "durationMs", "uri"].some((key) => track[key] !== room.track[key]);
      const lyrics = body.lyrics == null ? (changed ? [] : room.lyrics) : jamLyrics(body.lyrics);
      Object.assign(room, { track, lyrics, sequence: body.sequence, updatedAt: this.now() });
      const includeLyrics = changed || body.lyrics != null;
      await this.broadcast(room, includeLyrics);
      return { ok: true, ...this.packet(room, false) };
    }
    return { ok: false, message: "Unknown Music Jam action." };
  }
}

/*
  Halloween 2026: play ten hours of Minecraft with River before Halloween is over, and the
  Halloween cape and theme stay yours after it. For everybody else they leave on 1 November
  the way they always did.

  Played time is counted from the signed-in heartbeat (see SocialStore.heartbeat), which
  only a client holding the Mojang account can send, and the in-game client only sends it
  while a world is open. So a player can't be credited by anyone but themselves.
*/
export const HALLOWEEN_EVENT = {
  id: "halloween2026",
  label: "Halloween 2026",
  cosmetic: "halloween",
  goalMs: 10 * 60 * 60 * 1000,
  // 31 October is over in every time zone by noon UTC on 1 November.
  endsAt: Date.UTC(2026, 10, 1, 12, 0, 0)
};

/*
  The game sends a heartbeat every ten seconds while you play. A longer gap means it was
  closed or offline, and only this much of the gap is counted, so time
  away never adds up.
*/
export const MAX_HEARTBEAT_GAP_MS = 20_000;

/**
 * The next state of one player's event record after a heartbeat.
 *
 * [record] is what was stored ({ ms, last, done }), [playing] whether a world was open.
 * Returns the record to store and whether this heartbeat reached the goal. The record is
 * only marked done by the caller once the reward has actually been granted, so a failed
 * grant is simply tried again on the next heartbeat.
 */
export function advanceEventPlay(record, now, playing, event = HALLOWEEN_EVENT) {
  const prev = record || {};
  if (prev.done) return { record: prev, completed: false };
  const ms = Number(prev.ms) || 0;
  if (!playing || now >= event.endsAt) return { record: { ms, last: 0 }, completed: false };
  const last = Number(prev.last) || 0;
  const gained = last ? Math.max(0, Math.min(now - last, MAX_HEARTBEAT_GAP_MS)) : 0;
  const total = ms + gained;
  return { record: { ms: total, last: now }, completed: total >= event.goalMs };
}

/** What the client is told about the event: enough to show progress and the deadline. */
export function eventStatus(record, event = HALLOWEEN_EVENT) {
  const done = Boolean(record?.done);
  return {
    id: event.id,
    label: event.label,
    cosmetic: event.cosmetic,
    playedMs: done ? event.goalMs : Math.min(Number(record?.ms) || 0, event.goalMs),
    goalMs: event.goalMs,
    endsAt: event.endsAt,
    done
  };
}

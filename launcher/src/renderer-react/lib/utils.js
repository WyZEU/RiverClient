import { clsx } from "clsx";
import { twMerge } from "tailwind-merge";

export function cn(...inputs) {
  return twMerge(clsx(inputs));
}

/**
 * How long after playing an instance we stop saying when it was last played.
 * Right after a session, "last played 2 minutes ago" is noise the user just lived
 * through - so within this window the label is suppressed entirely.
 */
export const JUST_PLAYED_MS = 5 * 60 * 1000;

/**
 * Human "last played" label, or "" when it should not be shown at all:
 * never played / unknown, or played so recently that saying so adds nothing.
 * An instance that is running right now reports a timestamp of "now", so it
 * falls into the just-played window and stays quiet too.
 */
export function formatLastPlayed(at) {
  const t = Number(at) || 0;
  if (!t) return "";
  const age = Date.now() - t;
  if (age < JUST_PLAYED_MS) return "";
  const minutes = Math.floor(age / 60000);
  const hours = Math.floor(minutes / 60);
  const days = Math.floor(hours / 24);
  if (hours < 1) return `last played ${minutes}m ago`;
  if (days < 1) return `last played ${hours}h ago`;
  if (days === 1) return "last played yesterday";
  if (days < 30) return `last played ${days}d ago`;
  return `last played ${new Date(t).toLocaleDateString()}`;
}

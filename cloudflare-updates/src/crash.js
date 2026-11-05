/**
 * Crash report intake.
 *
 * A log is only ever uploaded because someone pressed a button after their game crashed.
 * Nothing is sent automatically, and a crash with no upload leaves no trace here at all.
 *
 * A Minecraft log is not a safe thing to accept as-is. It carries the Windows account
 * name in every file path, and depending on how the game was started it can carry the
 * session token in the launch arguments. Anyone who reads a report should not be able to
 * take an account from it, so the scrub below runs before anything is written, on the
 * server rather than only in the launcher: reports arrive over a public route and the
 * client is not in a position to promise what it sent.
 */

/** Reports go to R2, so the ceiling is about keeping one upload from being a whole bucket. */
export const MAX_CRASH_BYTES = 512 * 1024;

const REDACTIONS = [
  /*
    Order matters. The argument form goes first because its value is very often a JWT:
    letting the JWT rule run first turns it into a placeholder that the argument rule then
    matches half of, which redacts correctly but leaves a mangled line to read.
  */
  // --accessToken <value> and friends, which is how a token reaches the game's argv.
  [/(--(?:accessToken|access_token|clientToken|session|uuid|xuid)[=\s]+)[^\s"']+/gi, "$1[removed]"],
  // Session and identity tokens. The JWT shape is the one Microsoft and Minecraft both
  // use, and it is worth matching on its own: it survives being copied out of context.
  [/eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}/g, "[token removed]"],
  [/\b(access[_-]?token|refresh[_-]?token|session[_-]?token|id[_-]?token|client[_-]?secret|xbl[_-]?token|api[_-]?key)\b(["'\s:=]+)[A-Za-z0-9._~+/-]{8,}/gi, "$1$2[removed]"],
  [/\bBearer\s+[A-Za-z0-9._~+/-]{8,}=*/gi, "Bearer [removed]"],

  /*
    The Windows account name appears in every path in the log, and it is very often a
    real name. Both slash conventions show up in the same file: Java prints one and the
    launcher prints the other.
  */
  [/([A-Za-z]:\\Users\\)[^\\\r\n"']+/g, "$1[user]"],
  [/(\/Users\/)[^/\r\n"']+/g, "$1[user]"],
  [/(\/home\/)[^/\r\n"']+/g, "$1[user]"],

  [/\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b/g, "[email removed]"]
];

export function scrubCrashReport(raw) {
  let text = String(raw || "");
  if (text.length > MAX_CRASH_BYTES) text = `${text.slice(0, MAX_CRASH_BYTES)}\n[truncated]`;
  for (const [pattern, replacement] of REDACTIONS) text = text.replace(pattern, replacement);
  return text;
}

/** Ids are used as R2 object keys and echoed back to a browser. */
export function crashReportId(now = Date.now()) {
  const stamp = new Date(now).toISOString().replace(/[^0-9]/g, "").slice(0, 14);
  const suffix = [...crypto.getRandomValues(new Uint8Array(4))]
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
  return `${stamp}-${suffix}`;
}

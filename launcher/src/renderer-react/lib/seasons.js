/**
 * Seasonal themes. A season is a launcher theme that is only offered while it is on: the
 * Halloween theme exists from 1 to 31 October, and outside that window the launcher behaves
 * as if it had never been picked. The in-game client keeps the same windows (Seasons.kt) -
 * change both together. The main process applies the same window in main.js.
 *
 * Months are 1-12. A window that crosses New Year (to < from) is supported.
 */
import { createContext, useContext } from "react";

/**
 * The season the launcher is dressed for right now ("halloween" or null), provided by the
 * shell. Read from context rather than from the root element's data-season, which the shell
 * only stamps after a render and so lags one render behind a theme switch.
 */
export const SeasonContext = createContext(null);

export function useSeasonId() {
  return useContext(SeasonContext);
}

export const SEASONS = [
  { id: "halloween", label: "Halloween", from: [10, 1], to: [10, 31] }
];

function inWindow(season, now) {
  const here = (now.getMonth() + 1) * 100 + now.getDate();
  const from = season.from[0] * 100 + season.from[1];
  const to = season.to[0] * 100 + season.to[1];
  return from <= to ? here >= from && here <= to : here >= from || here <= to;
}

/** The season that is on today, or null. localStorage "river:forceSeason" is for testing only. */
export function seasonToday(now = new Date()) {
  try {
    const forced = window.localStorage.getItem("river:forceSeason");
    if (forced) return SEASONS.find((s) => s.id === forced) || null;
  } catch {}
  return SEASONS.find((s) => inWindow(s, now)) || null;
}

/** Seasons whose theme can be picked: today's, plus any earned for good in an event. */
export function pickableSeasons(keptSeasons = [], now = new Date()) {
  const today = seasonToday(now);
  const kept = SEASONS.filter((s) => keptSeasons.includes(s.id) && s.id !== today?.id);
  return today ? [today, ...kept] : kept;
}

/** The season the launcher should be dressed for: only when its theme is the one picked. */
export function activeSeason(launcherTheme, keptSeasons = [], now = new Date()) {
  return pickableSeasons(keptSeasons, now).find((s) => s.id === launcherTheme) || null;
}

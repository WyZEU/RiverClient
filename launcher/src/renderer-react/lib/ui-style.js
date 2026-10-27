import { createContext, useContext } from "react";

/**
 * Which layout the launcher is drawn in: "classic", the original flat one, or "modern".
 * Picked in Settings > Appearance and provided once by the shell, so shared components
 * (Page, Field, the friends panel) can draw themselves for either without a prop on every
 * call site.
 */
export const UiStyleContext = createContext("classic");

export function useModern() {
  return useContext(UiStyleContext) === "modern";
}

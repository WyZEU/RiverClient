import { useEffect } from "react";

/**
 * Decoration only runs while the launcher is the window being used.
 *
 * Stamps data-window-focus="on" | "off" on the root. The CSS pauses every keyframe animation
 * while it is "off" (a launcher sitting behind the game has no business redrawing a drifting
 * texture), and the 3D tilt below ignores the pointer then too.
 */
export function useWindowFocusFlag() {
  useEffect(() => {
    const root = document.documentElement;
    const sync = () => {
      const on = document.hasFocus() && document.visibilityState === "visible";
      root.dataset.windowFocus = on ? "on" : "off";
    };
    sync();
    window.addEventListener("focus", sync);
    window.addEventListener("blur", sync);
    document.addEventListener("visibilitychange", sync);
    return () => {
      window.removeEventListener("focus", sync);
      window.removeEventListener("blur", sync);
      document.removeEventListener("visibilitychange", sync);
    };
  }, []);
}

const TILTABLE = ".m-block:not(:disabled), .m-btn[data-3d]:not(:disabled)";

/**
 * Modern's 3D buttons lean towards the cursor and catch a glare under it. One delegated
 * listener sets --rx/--ry/--mx/--my on whichever button is under the pointer; the CSS does
 * the rest. Nothing happens while the window is unfocused or when reduced motion is asked for.
 */
export function useButtonTilt(enabled) {
  useEffect(() => {
    if (!enabled) return undefined;
    const reduced = window.matchMedia?.("(prefers-reduced-motion: reduce)");
    let current = null;

    const reset = () => {
      if (!current) return;
      for (const name of ["--rx", "--ry", "--mx", "--my", "--glare"]) current.style.removeProperty(name);
      current = null;
    };
    const onMove = (event) => {
      if (reduced?.matches || document.documentElement.dataset.windowFocus === "off") return reset();
      const target = event.target instanceof Element ? event.target.closest(TILTABLE) : null;
      if (target !== current) {
        reset();
        current = target;
      }
      if (!target) return;
      const rect = target.getBoundingClientRect();
      const x = Math.min(1, Math.max(0, (event.clientX - rect.left) / rect.width));
      const y = Math.min(1, Math.max(0, (event.clientY - rect.top) / rect.height));
      const amp = target.classList.contains("m-block") ? 8 : 4;
      target.style.setProperty("--ry", `${((x - 0.5) * 2 * amp).toFixed(2)}deg`);
      target.style.setProperty("--rx", `${(-(y - 0.5) * 2 * amp).toFixed(2)}deg`);
      target.style.setProperty("--mx", `${(x * 100).toFixed(1)}%`);
      target.style.setProperty("--my", `${(y * 100).toFixed(1)}%`);
      target.style.setProperty("--glare", "0.26");
    };
    document.addEventListener("pointermove", onMove, { passive: true });
    document.addEventListener("pointerleave", reset);
    window.addEventListener("blur", reset);
    return () => {
      document.removeEventListener("pointermove", onMove);
      document.removeEventListener("pointerleave", reset);
      window.removeEventListener("blur", reset);
      reset();
    };
  }, [enabled]);
}

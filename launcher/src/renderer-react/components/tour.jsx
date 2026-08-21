import React, { useCallback, useEffect, useLayoutEffect, useRef, useState } from "react";
import { X } from "lucide-react";
import { cn } from "../lib/utils";
import { Button } from "./ui/button";

/**
 * First-run guided tour.
 *
 * Steps point at real elements through `data-tour` markers rather than hard-coded
 * coordinates, so the tour keeps working when the layout moves. A step may name a
 * `view`, which the tour switches to first - that is the point of the thing: you
 * see each destination as it is described. A step with no target is a plain centred
 * card (the welcome and the sign-off).
 *
 * Runs once on first launch and is replayable from Settings, so nobody is stuck
 * having skipped it.
 */

const STEPS = [
  {
    title: "Welcome to River",
    body: "A quick tour of where everything lives. It takes about twenty seconds, and you can replay it any time from Settings."
  },
  {
    target: "launch",
    view: "home",
    title: "Launch the game",
    body: "This starts Minecraft with River loaded. The bar along the bottom reports what the launcher is doing while it prepares - downloads, mod checks and startup."
  },
  {
    target: "rail-home",
    view: "home",
    title: "Home",
    body: "Your launch screen. Partner and recent servers are listed here, and joining one launches the game straight into it."
  },
  {
    target: "rail-instances",
    view: "instances",
    title: "Instances",
    body: "Separate setups, each with its own mods, worlds and settings. Add content browses Modrinth and CurseForge for mods, resource packs and shaders, and Repair checks your mods and installs any updates."
  },
  {
    target: "rail-wardrobe",
    view: "wardrobe",
    title: "Wardrobe",
    body: "Your skins and capes, with a 3D preview. Everything here is cosmetic only."
  },
  {
    target: "rail-settings",
    view: "settings",
    title: "Settings",
    body: "Memory, theme, account and updates. You can replay this tour from here whenever you want."
  }
];

const CARD_WIDTH = 330;
const GAP = 12;
const EDGE = 16;

export function Tour({ open, onClose, setView }) {
  const [index, setIndex] = useState(0);
  const [rect, setRect] = useState(null);
  const cardRef = useRef(null);
  const [cardSize, setCardSize] = useState({ width: CARD_WIDTH, height: 170 });

  const step = STEPS[index] || STEPS[0];
  const isLast = index === STEPS.length - 1;

  // Reset to the first step every time the tour is opened, so a replay starts at the top.
  useEffect(() => {
    if (open) setIndex(0);
  }, [open]);

  // Switch to the view a step describes before measuring its target.
  useEffect(() => {
    if (!open) return;
    if (step.view) setView?.(step.view);
  }, [open, index, step.view, setView]);

  const measure = useCallback(() => {
    if (!step.target) { setRect(null); return; }
    const node = document.querySelector(`[data-tour="${step.target}"]`);
    if (!node) { setRect(null); return; }
    const box = node.getBoundingClientRect();
    setRect({ top: box.top, left: box.left, width: box.width, height: box.height });
  }, [step.target]);

  // The view swap above re-renders the page, so measure on the next frames rather
  // than immediately - otherwise the first measurement catches the outgoing view.
  useEffect(() => {
    if (!open) return undefined;
    measure();
    const raf = requestAnimationFrame(measure);
    const timer = setTimeout(measure, 220);
    window.addEventListener("resize", measure);
    return () => {
      cancelAnimationFrame(raf);
      clearTimeout(timer);
      window.removeEventListener("resize", measure);
    };
  }, [open, index, measure]);

  useLayoutEffect(() => {
    if (!open || !cardRef.current) return;
    const box = cardRef.current.getBoundingClientRect();
    setCardSize({ width: box.width, height: box.height });
  }, [open, index, rect]);

  const next = useCallback(() => {
    if (isLast) onClose?.();
    else setIndex((value) => Math.min(STEPS.length - 1, value + 1));
  }, [isLast, onClose]);

  const back = useCallback(() => setIndex((value) => Math.max(0, value - 1)), []);

  useEffect(() => {
    if (!open) return undefined;
    const onKey = (event) => {
      if (event.key === "Escape") { event.preventDefault(); onClose?.(); }
      else if (event.key === "ArrowRight" || event.key === "Enter") { event.preventDefault(); next(); }
      else if (event.key === "ArrowLeft") { event.preventDefault(); back(); }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open, next, back, onClose]);

  if (!open) return null;

  // Card placement: beside a rail target, otherwise below it, flipping above when the
  // bottom edge would clip. Always clamped inside the window.
  let cardStyle;
  if (rect) {
    const railTarget = rect.left < 140;
    let left = railTarget ? rect.left + rect.width + GAP : rect.left;
    let top = railTarget ? rect.top - 8 : rect.top + rect.height + GAP;
    if (!railTarget && top + cardSize.height > window.innerHeight - EDGE) {
      top = rect.top - cardSize.height - GAP;
    }
    left = Math.max(EDGE, Math.min(left, window.innerWidth - cardSize.width - EDGE));
    top = Math.max(EDGE, Math.min(top, window.innerHeight - cardSize.height - EDGE));
    cardStyle = { position: "fixed", left, top, width: CARD_WIDTH };
  } else {
    cardStyle = {
      position: "fixed",
      left: "50%",
      top: "50%",
      width: CARD_WIDTH,
      transform: "translate(-50%, -50%)"
    };
  }

  return (
    <div className="fixed inset-0 z-[60]">
      {/* No target: plain dimmer. With a target: the dimmer IS the spotlight, drawn as a
          giant shadow around the highlighted element so the element itself stays lit. */}
      {rect ? (
        <div
          className="pointer-events-none absolute rounded-md ring-2 ring-primary transition-all duration-200"
          style={{
            top: rect.top - 4,
            left: rect.left - 4,
            width: rect.width + 8,
            height: rect.height + 8,
            boxShadow: "0 0 0 9999px rgb(0 0 0 / 0.66)"
          }}
        />
      ) : (
        <div className="absolute inset-0 bg-black/66" />
      )}

      <div
        ref={cardRef}
        style={cardStyle}
        className="rounded-lg border border-border bg-popover p-4 shadow-xl animate-in fade-in zoom-in-95 duration-150"
      >
        <div className="flex items-start justify-between gap-3">
          <div className="text-sm font-semibold">{step.title}</div>
          <button
            onClick={onClose}
            aria-label="Skip tour"
            className="-mr-1 -mt-1 text-muted-foreground hover:text-foreground"
          >
            <X className="size-3.5" />
          </button>
        </div>

        <p className="mt-1.5 text-xs leading-relaxed text-muted-foreground">{step.body}</p>

        <div className="mt-4 flex items-center justify-between gap-3">
          <div className="flex items-center gap-1">
            {STEPS.map((_, i) => (
              <span
                key={i}
                className={cn(
                  "size-1.5 rounded-full transition-colors",
                  i === index ? "bg-primary" : "bg-muted-foreground/30"
                )}
              />
            ))}
          </div>

          <div className="flex items-center gap-2">
            {index > 0 ? (
              <Button size="sm" variant="ghost" onClick={back}>Back</Button>
            ) : (
              <Button size="sm" variant="ghost" onClick={onClose}>Skip</Button>
            )}
            <Button size="sm" onClick={next}>{isLast ? "Done" : "Next"}</Button>
          </div>
        </div>
      </div>
    </div>
  );
}

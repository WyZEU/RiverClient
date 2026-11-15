import React from "react";
import { cn } from "../lib/utils";

/*
 * Halloween for the Modern layout.
 *
 * Classic hangs its decorations on the window itself: webs in the window corners, bats over
 * the page header, a pumpkin on the rail. Among Modern's rounded cards those read as stickers
 * on top of the app. So here the same art the game's Halloween title screen uses lives inside
 * the cards instead, and each card clips it to its own rounded edge: the night scene with its
 * moon, mist, bats and pumpkins in the Home banner, webs caught in card corners, a pumpkin on
 * the wardrobe stage. Everything is decoration only: no pointer events, hidden from readers.
 */

/** The night forest from the game's title screen, with its moon. */
export function NightSky({ drift = false, moon = true, className }) {
  return (
    <div className={cn("pointer-events-none absolute inset-0 select-none", className)} aria-hidden>
      <img
        src="../assets/home-bg-halloween.jpg"
        alt=""
        draggable={false}
        className={cn("size-full object-cover", drift && "hero-drift")}
      />
      {moon ? (
        <div className="absolute left-[60%] top-7">
          <img src="../assets/season/moon_glow.png" alt="" draggable={false} className="absolute -left-14 -top-14 size-40 max-w-none opacity-80" />
          <img src="../assets/season/moon.png" alt="" draggable={false} className="relative size-12 [image-rendering:pixelated]" />
        </div>
      ) : null}
    </div>
  );
}

/** Low mist drifting along the bottom edge of whatever it is placed in. */
export function Mist({ className }) {
  return <div className={cn("m-mist", className)} aria-hidden />;
}

/** A jack o'lantern whose candle flickers. Drawn at 32 or 64 so its pixels stay square. */
export function Pumpkin({ size = 64, className, style }) {
  return (
    <div className={cn("m-pumpkin", className)} style={{ width: size, height: size, ...style }} aria-hidden>
      <span className="m-pumpkin-glow" />
      <img src="../assets/season/pumpkin.png" alt="" draggable={false} />
      <img src="../assets/season/pumpkin_light.png" alt="" draggable={false} className="m-pumpkin-light" />
    </div>
  );
}

/** A cobweb caught in a card's top corner. The card's overflow clips it to the rounded edge. */
export function CornerWeb({ corner = "left", size = 120, className }) {
  return (
    <img
      src={`../assets/season/web_${corner === "right" ? "right" : "left"}.png`}
      alt=""
      aria-hidden
      draggable={false}
      className={cn(
        "pointer-events-none absolute top-0 select-none opacity-40",
        corner === "right" ? "right-0" : "left-0",
        className
      )}
      style={{ width: size, height: size }}
    />
  );
}

/** Two bats crossing now and then, from the same sprite sheet Classic uses. */
export function Bats() {
  return (
    <div className="pointer-events-none absolute inset-0" aria-hidden>
      <div className="season-bat season-bat-a" style={{ top: 34 }} />
      <div className="season-bat season-bat-b" style={{ top: 70 }} />
    </div>
  );
}

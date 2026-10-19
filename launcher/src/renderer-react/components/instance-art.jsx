import React, { useEffect, useState } from "react";
import { Loader2, Play } from "lucide-react";
import { api } from "../lib/useStatus";
import { cn } from "../lib/utils";
import { useSeasonId } from "../lib/seasons";

/** River's own art, for an instance with no screenshots and no worlds yet. */
const FALLBACK_ART = ["../assets/home-bg-1.jpg", "../assets/home-bg-2.jpg"];

/** Same instance, same picture: picked from the id rather than at random on every render. */
function fallbackFor(id) {
  let hash = 0;
  for (const ch of String(id || "")) hash = (hash * 31 + ch.charCodeAt(0)) >>> 0;
  return FALLBACK_ART[hash % FALLBACK_ART.length];
}

/**
 * Card art and counts for every instance (launcher:get-instance-cards in main): the newest
 * screenshot taken in it, or failing that the icon of its newest world, plus how many mods
 * and worlds it has. Fetched again when the list changes or an instance is played, since a
 * session is when a new screenshot can have appeared.
 */
export function useInstanceCards(instances, enabled = true) {
  const [cards, setCards] = useState({});
  const key = (Array.isArray(instances) ? instances : [])
    .map((instance) => `${instance.id}:${instance.lastPlayedAt || 0}`)
    .join("|");

  useEffect(() => {
    if (!enabled) return undefined;
    let alive = true;
    api()?.getInstanceCards?.()
      .then((res) => { if (alive && res && typeof res === "object") setCards(res); })
      .catch(() => {});
    return () => { alive = false; };
  }, [key, enabled]);

  return cards;
}

/** The picture behind an instance card or the Home hero. */
export function InstanceArt({ instanceId, card, drift = false, className }) {
  const halloween = useSeasonId() === "halloween";
  const src = card?.art || (halloween ? "../assets/home-bg-halloween.jpg" : fallbackFor(instanceId));
  return (
    <img
      src={src}
      alt=""
      draggable={false}
      className={cn(
        "pointer-events-none size-full select-none object-cover",
        // A world icon is 64px. Drawn blocky on purpose, it reads as Minecraft instead of as blur.
        card?.artKind === "world" && "[image-rendering:pixelated]",
        drift && "hero-drift",
        className
      )}
    />
  );
}

const BLOCK_SIZES = {
  lg: "h-14 min-w-48 px-7 text-[16px] [&_svg]:size-5",
  md: "h-10 px-4 text-[10px] [&_svg]:size-4",
  sm: "h-8 px-3 text-[8px] [&_svg]:size-3.5"
};

/**
 * The block-style action button of the Modern layout (.m-block in styles.css).
 *
 * tone="quiet" is the same block in the neutral surface colour, lighting up in the accent
 * only under the cursor. A screen has one main action; the Play on every instance card, the
 * Join on every server and the Install on every mod are not it, and eight bright blue blocks
 * on one page meant none of them stood out.
 */
export const PlayBlock = React.forwardRef(function PlayBlock(
  { size = "md", busy = false, danger = false, tone = "primary", icon: Icon = Play, children, className, ...props },
  ref
) {
  return (
    <button
      ref={ref}
      type="button"
      className={cn("m-block shrink-0 uppercase", danger && "m-block-danger", tone === "quiet" && !danger && "m-block-quiet", BLOCK_SIZES[size], className)}
      {...props}
    >
      {busy ? <Loader2 className="animate-spin" /> : <Icon className={Icon === Play ? "fill-current" : ""} />}
      {/* The pixel face sits a hair high in its line box; this centres it on the icon. */}
      <span className="translate-y-px">{children}</span>
    </button>
  );
});

import React, { useEffect, useState } from "react";
import { cn } from "../lib/utils";

/**
 * Flat pictures of a skin and a cape, for grids where a 3D model per tile would be too much.
 *
 * Both are cut straight out of the texture with background-position, the same way PlayerHead
 * draws a head, so there is no canvas and no network call. Sizes are in texture pixels times
 * `scale`; the background is always stretched to 64 texture pixels across, so an HD texture
 * (a 512px cape, say) lines up exactly like a 64px one.
 */

/** True for an old 64x32 skin, which has no separate left limbs and no outer body layers. */
function useLegacySkin(src) {
  const [legacy, setLegacy] = useState(false);
  useEffect(() => {
    if (!src) return undefined;
    let alive = true;
    const probe = new window.Image();
    probe.onload = () => { if (alive) setLegacy(probe.naturalHeight * 2 === probe.naturalWidth); };
    probe.src = src;
    return () => { alive = false; };
  }, [src]);
  return legacy;
}

/** A skin seen from the front: head, body, arms and legs, with the outer layer on top. */
export function SkinFront({ src, slim = false, scale = 5, className }) {
  const legacy = useLegacySkin(src);
  if (!src) return null;
  const s = scale;
  const arm = slim ? 3 : 4;

  const part = (u, v, w, h, x, y, mirror = false) => (
    <div
      key={`${u}:${v}:${x}:${y}`}
      className="absolute"
      style={{
        left: x * s,
        top: y * s,
        width: w * s,
        height: h * s,
        backgroundImage: `url(${src})`,
        backgroundSize: `${64 * s}px ${(legacy ? 32 : 64) * s}px`,
        backgroundPosition: `-${u * s}px -${v * s}px`,
        backgroundRepeat: "no-repeat",
        imageRendering: "pixelated",
        transform: mirror ? "scaleX(-1)" : undefined
      }}
    />
  );

  // The player's right side is on the viewer's left. A legacy skin draws its left arm and leg
  // by mirroring the right ones, the way the game does.
  const parts = [
    part(8, 8, 8, 8, 4, 0),
    part(20, 20, 8, 12, 4, 8),
    part(44, 20, arm, 12, 4 - arm, 8),
    legacy ? part(44, 20, arm, 12, 12, 8, true) : part(36, 52, arm, 12, 12, 8),
    part(4, 20, 4, 12, 4, 20),
    legacy ? part(4, 20, 4, 12, 8, 20, true) : part(20, 52, 4, 12, 8, 20),
    part(40, 8, 8, 8, 4, 0)
  ];
  if (!legacy) {
    parts.push(
      part(20, 36, 8, 12, 4, 8),
      part(44, 36, arm, 12, 4 - arm, 8),
      part(52, 52, arm, 12, 12, 8),
      part(4, 36, 4, 12, 4, 20),
      part(4, 52, 4, 12, 8, 20)
    );
  }

  return (
    <div className={cn("relative shrink-0", className)} style={{ width: 16 * s, height: 32 * s }} aria-hidden>
      {parts}
    </div>
  );
}

/** The decorated outer side of a cape, as it hangs on a player's back. */
export function CapeFront({ src, scale = 6, className }) {
  if (!src) return null;
  const s = scale;
  return (
    <div
      className={cn("shrink-0", className)}
      aria-hidden
      style={{
        width: 10 * s,
        height: 16 * s,
        backgroundImage: `url(${src})`,
        backgroundSize: `${64 * s}px ${32 * s}px`,
        backgroundPosition: `-${1 * s}px -${1 * s}px`,
        backgroundRepeat: "no-repeat",
        imageRendering: "pixelated"
      }}
    />
  );
}

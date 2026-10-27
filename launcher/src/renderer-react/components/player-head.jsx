import React, { useState } from "react";
import { cn } from "../lib/utils";

/**
 * The player's head, cropped straight out of their skin texture.
 *
 * Deliberately does NOT use a third-party avatar service (crafatar and friends):
 * those are unreachable on some networks and left a broken-image glyph in the
 * header. The skin PNG is already on disk and shipped in status.skinHistory as a
 * data URL, so the head is a pure CSS crop with no network call at all.
 *
 * Minecraft skin layout (64x64): the head's front face is the 8x8 block at
 * (8,8); the hat/overlay layer is the matching 8x8 block at (40,8). Scaling by
 * size/8 maps one skin pixel onto one on-screen block.
 */
export function PlayerHead({ skinUrl, name, size = 36, className }) {
  const [failed, setFailed] = useState(false);
  const scale = size / 8;
  const usable = skinUrl && !failed;

  const layer = (offsetX) => ({
    backgroundImage: `url(${skinUrl})`,
    backgroundSize: `${64 * scale}px ${64 * scale}px`,
    backgroundPosition: `-${offsetX * scale}px -${8 * scale}px`,
    imageRendering: "pixelated"
  });

  return (
    <div
      className={cn("relative shrink-0 overflow-hidden rounded-md bg-accent", className)}
      style={{ width: size, height: size }}
      title={name || undefined}
    >
      {usable ? (
        <>
          <div className="absolute inset-0" style={layer(8)} />
          {/* Hat layer sits on top so hoods and hair render like they do in game. */}
          <div className="absolute inset-0" style={layer(40)} />
          {/* Probe the texture separately: background-image has no error event. */}
          <img src={skinUrl} alt="" className="hidden" onError={() => setFailed(true)} />
        </>
      ) : (
        <div className="flex size-full items-center justify-center text-xs font-semibold text-muted-foreground">
          {(name || "?").slice(0, 1).toUpperCase()}
        </div>
      )}
    </div>
  );
}

/** Picks the best locally-available skin texture for [status]. */
export function resolveSkinTexture(status) {
  const history = Array.isArray(status?.skinHistory) ? status.skinHistory : [];
  const active = history.find((entry) => entry.active) || history[0];
  // Local data URL first - it always works, even offline.
  return active?.previewDataUrl || active?.previewFileUrl || status?.auth?.profile?.skinUrl || "";
}

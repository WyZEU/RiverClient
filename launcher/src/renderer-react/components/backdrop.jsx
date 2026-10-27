import React from "react";

/**
 * Modern layout: a Minecraft landscape behind the whole window, with every surface above it
 * drawn as frosted glass (see the glass tokens in styles.css). The river valley from River's
 * title screen; the Halloween forest while that season is on.
 *
 * It does not move. Everything on top is blurred through it, so a moving image would have the
 * launcher re-blur the whole window every frame for a bit of decoration.
 */
export function Backdrop({ season }) {
  const src = season === "halloween" ? "../assets/backdrop-halloween.jpg" : "../assets/backdrop.jpg";
  return (
    <div aria-hidden className="pointer-events-none fixed inset-0 -z-10 overflow-hidden">
      <img src={src} alt="" draggable={false} className="size-full select-none object-cover" />
      {/* Darker toward the rail and the bottom, where most of the text sits. */}
      <div className="app-backdrop-shade absolute inset-0" />
    </div>
  );
}

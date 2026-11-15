import React from "react";

/*
 * Halloween in the launcher, made from the same art as the game's title screen
 * (assets/season): shadow bats crossing the top now and then, silk cobwebs in the
 * corners, and a jack o'lantern above the settings cog whose candle flickers. All of it
 * sits behind clicks, and the bats and flicker stop for anyone who asked for less motion.
 */
export function SeasonalDecor({ season, view, modern = false }) {
  // Modern puts its Halloween inside the cards instead (components/modern-season.jsx).
  if (season?.id !== "halloween" || modern) return null;
  // Home has the friends panel down its right side, so its web hangs in the corner of the
  // main column; every other page uses the full width and the web goes in the real corner.
  return (
    <div className={`season-decor ${view === "home" ? "season-home" : "season-wide"}`} aria-hidden="true">
      <img src="../assets/season/web_left.png" alt="" className="season-web season-web-left" draggable={false} />
      <img src="../assets/season/web_right.png" alt="" className="season-web season-web-right" draggable={false} />
      <div className="season-bat season-bat-a" />
      <div className="season-bat season-bat-b" />
      <div className="season-bat season-bat-c" />
      <div className="season-pumpkin">
        <span className="season-pumpkin-glow" />
        <img src="../assets/season/pumpkin.png" alt="" draggable={false} />
        <img src="../assets/season/pumpkin_light.png" alt="" className="season-pumpkin-light" draggable={false} />
      </div>
    </div>
  );
}

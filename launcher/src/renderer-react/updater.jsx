import React, { useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import { Minus, X } from "lucide-react";

// This window loads only updater.css, not the compiled Tailwind stylesheet the main
// launcher window uses - so shadcn components (styled through Tailwind utility classes)
// render as bare HTML here. Everything is plain markup styled by updater.css instead.

// Everything this window can reach is declared in preload-updater.js. It has no Node
// access of its own.
const bridge = window.riverUpdater;
const windowAction = (action) => bridge.window(action);

function clampPercent(value) {
  return Math.max(2, Math.min(100, Math.round(Number(value) || 0)));
}

function UpdaterApp() {
  const [activity, setActivity] = useState({
    title: "Preparing update",
    detail: "Waiting for the updater to start."
  });

  useEffect(() => {
    // One argument: the bridge already unwrapped the IPC event.
    const handler = (payload) => setActivity(payload || {});
    return bridge.onActivity(handler);
  }, []);

  const total = Number(activity.total || 0);
  const current = Number(activity.current || 0);
  const done = Boolean(activity.done);
  const error = Boolean(activity.error);
  const percent = done
    ? 100
    : Number(activity.percent || 0) > 0
      ? clampPercent(activity.percent)
      : total > 0
        ? clampPercent((current / total) * 100)
        : 8;

  const metaRight = activity.unit === "bytes"
    ? [activity.speed || "", activity.eta ? `ETA ${activity.eta}` : ""].filter(Boolean).join("  ·  ")
    : total > 0
      ? `${Math.min(current, total)} of ${total}`
      : "";

  const state = done ? (error ? "error" : "success") : "busy";

  return (
    <>
      <header className="titlebar">
        <div className="brand"><strong>River Client</strong><span>Updater</span></div>
        <div className="window-actions">
          <button className="wbtn" title="Minimize" onClick={() => windowAction("minimize")}><Minus /></button>
          <button className="wbtn wbtn-close" title="Close" onClick={() => windowAction("close")}><X /></button>
        </div>
      </header>

      <main className="body">
        <div className="line">
          {!done && <span className="dot busy" aria-hidden />}
          {done && <span className={`dot ${state}`} aria-hidden />}
          <h1 className="title">{activity.title || "Updating River Client"}</h1>
        </div>
        <p className="detail">{activity.detail || ""}</p>

        <div className={`track ${state}`}>
          <div className={`fill ${state}`} style={{ width: `${percent}%` }} />
        </div>

        <div className={`meta ${state}`}>
          <span className="pct">{Math.round(percent)}%</span>
          <span className="right">{metaRight}</span>
        </div>

        {done && (
          <div className="actions">
            {error && (
              <button className="btn ghost" onClick={() => bridge.retry()}>
                Retry
              </button>
            )}
            <button className="btn primary" onClick={() => windowAction("close")}>
              {error ? "Close" : "Done"}
            </button>
          </div>
        )}
      </main>
    </>
  );
}

createRoot(document.getElementById("root")).render(<UpdaterApp />);

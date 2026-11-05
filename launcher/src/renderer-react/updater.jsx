import React, { useEffect, useRef, useState } from "react";
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

/** No update event for this long reads as stuck: a live download reports four times a second. */
const STALL_MS = 20000;

/**
 * "Downloading stuck?" - uploads a full diagnostic report of the update (see
 * launcher:report-update-stuck) and shows the id it was filed under, so the player can
 * hand it over on Discord and the exact report can be pulled up on the dashboard.
 */
function StuckReport({ stalled }) {
  const [report, setReport] = useState({ state: "idle", text: "" });

  const send = async () => {
    setReport({ state: "sending", text: "" });
    try {
      const res = await bridge.reportStuck();
      if (res?.ok) setReport({ state: "sent", text: res.id || "" });
      else setReport({ state: "failed", text: res?.message || "The report could not be sent." });
    } catch (e) {
      setReport({ state: "failed", text: String(e?.message || e) });
    }
  };

  if (report.state === "sent") {
    return (
      <div className="stuck">
        <span className="stuck-msg ok">
          Report sent. Your code: <b>{report.text}</b> - post it in the River Discord.
        </span>
      </div>
    );
  }
  if (report.state === "failed") {
    return (
      <div className="stuck">
        <span className="stuck-msg err">{report.text} </span>
        <button className="link" onClick={send}>Try again</button>
      </div>
    );
  }
  return (
    <div className="stuck">
      <button className={`link${stalled ? " emphasized" : ""}`} onClick={send} disabled={report.state === "sending"}>
        {report.state === "sending"
          ? "Sending report..."
          : stalled ? "Looks stuck? Send us a report" : "Downloading stuck?"}
      </button>
    </div>
  );
}

function UpdaterApp() {
  const [activity, setActivity] = useState({
    title: "Preparing update",
    detail: "Waiting for the updater to start."
  });
  const lastEventAt = useRef(Date.now());
  const [stalled, setStalled] = useState(false);

  useEffect(() => {
    // One argument: the bridge already unwrapped the IPC event.
    const handler = (payload) => {
      lastEventAt.current = Date.now();
      setStalled(false);
      setActivity(payload || {});
    };
    return bridge.onActivity(handler);
  }, []);

  // A frozen update sends nothing at all, so silence is the signal.
  useEffect(() => {
    const timer = setInterval(() => setStalled(Date.now() - lastEventAt.current > STALL_MS), 2000);
    return () => clearInterval(timer);
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

        {/* While it runs, and after it fails - never over a successful finish. */}
        {(!done || error) && <StuckReport stalled={stalled || error} />}
      </main>
    </>
  );
}

createRoot(document.getElementById("root")).render(<UpdaterApp />);
// The pre-React splash in the shell is a sibling of #root; take it down now that
// this window has rendered its own first frame.
document.getElementById("boot")?.remove();

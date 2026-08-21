import React, { useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import { AlertTriangle, CheckCircle2, Loader2, Minus, RefreshCcw, X } from "lucide-react";

// This window loads only updater.css, not the compiled Tailwind stylesheet the main
// launcher window uses - so the shadcn Button/Card/Progress components (styled entirely
// through Tailwind utility classes) rendered as bare, unstyled HTML here. Plain markup
// styled by updater.css's own rules instead, matching this file's "self-contained" intent.

// The updater window runs with nodeIntegration, so require() is available at runtime.
const { ipcRenderer } = window.require("electron");
const windowAction = (action) => ipcRenderer.invoke("launcher:window", action);

function clampPercent(value) {
  return Math.max(3, Math.min(100, Math.round(Number(value) || 0)));
}

function UpdaterApp() {
  const [activity, setActivity] = useState({
    title: "Preparing update",
    detail: "Waiting for the updater to start."
  });

  useEffect(() => {
    const handler = (_event, payload) => setActivity(payload || {});
    ipcRenderer.on("launcher:activity", handler);
    return () => ipcRenderer.removeListener("launcher:activity", handler);
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
        : 12;

  const metaRight = activity.unit === "bytes"
    ? [activity.speed || "", activity.eta ? `ETA ${activity.eta}` : ""].filter(Boolean).join(" • ")
    : total > 0
      ? `${Math.min(current, total)} / ${total}`
      : "";

  const StatusIcon = done ? (error ? AlertTriangle : CheckCircle2) : Loader2;
  const statusClass = done ? (error ? "error" : "success") : "spin";

  return (
    <>
      <header className="updater-titlebar">
        <div className="updater-brand"><strong>River Client</strong> Updater</div>
        <div className="updater-window-actions">
          <button className="wbtn" title="Minimize" onClick={() => windowAction("minimize")}><Minus /></button>
          <button className="wbtn wbtn-close" title="Close" onClick={() => windowAction("close")}><X /></button>
        </div>
      </header>

      <main className="updater-content">
        <div className="updater-card">
          <div className="updater-card-content">
            <div className="updater-head">
              <span className={`updater-icon-ring ${statusClass}`}>
                <StatusIcon className={`updater-icon ${statusClass}`} />
              </span>
              <div>
                <h1 className="updater-title">{activity.title || "Updating River Client"}</h1>
                <p className="updater-detail">{activity.detail || ""}</p>
              </div>
            </div>

            <div className="updater-progress-track">
              <div
                className={`updater-progress-fill ${done ? statusClass : ""}`.trim()}
                style={{ width: `${percent}%` }}
              />
            </div>

            <div className={`updater-meta ${done ? statusClass : ""}`.trim()}>
              <span>{Math.round(percent)}%</span>
              <span>{metaRight}</span>
            </div>

            {done && (
              <div className="updater-actions">
                {error && (
                  <button className="updater-btn updater-btn-outline" onClick={() => ipcRenderer.invoke("launcher:retry-updater")}>
                    <RefreshCcw /> Retry
                  </button>
                )}
                <button className="updater-btn updater-btn-primary" onClick={() => windowAction("close")}>
                  Close
                </button>
              </div>
            )}
          </div>
        </div>
      </main>
    </>
  );
}

createRoot(document.getElementById("root")).render(<UpdaterApp />);

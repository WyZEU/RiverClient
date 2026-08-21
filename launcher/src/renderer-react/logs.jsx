import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import { ArrowDown, Copy, FolderOpen, Minus, Search, Square, Trash2, X } from "lucide-react";

// Like the updater window, this one runs with nodeIntegration, so require() is available.
const { ipcRenderer } = window.require("electron");
const windowAction = (action) => ipcRenderer.invoke("logs:window", action);

/**
 * Ring buffer size. Minecraft is happy to emit tens of thousands of lines in a session
 * and a DOM node per line will eventually stall the window, so the oldest lines are
 * dropped rather than virtualising the list. 5k is far more than anyone scrolls back
 * through, and the full log is still on disk via "Open folder".
 */
const MAX_LINES = 5000;

const LEVELS = ["error", "warn", "info", "debug"];

const LEVEL_LABEL = { error: "ERROR", warn: "WARN", info: "INFO", debug: "DEBUG" };

/**
 * Minecraft logs look like `[00:18:52] [Render thread/INFO] (Minecraft) message`, and the
 * launcher's own lines look like `[launcher] message`. Anything we can't classify is INFO,
 * except continuation lines of a Java stack trace, which belong to the error above them.
 */
const MC_LEVEL = /\[[^\]]*\/(INFO|WARN|WARNING|ERROR|SEVERE|FATAL|DEBUG|TRACE)\]/i;
const BARE_LEVEL = /^\s*(?:\[[^\]]*\]\s*)*(INFO|WARN|WARNING|ERROR|FATAL|DEBUG)\b/i;
const STACK_FRAME = /^\s+(at\s|\.{3}\s\d+\smore|Caused by:|Suppressed:)/;
const EXCEPTION_LINE = /(^|\s)(java|javax|kotlin|net\.minecraft|dev\.wyz)[\w.$]*(Exception|Error)\b|^\s*Exception in thread/;

function normalizeLevel(raw) {
  const value = String(raw || "").toLowerCase();
  if (value === "warning") return "warn";
  if (value === "severe" || value === "fatal") return "error";
  if (value === "trace") return "debug";
  return value;
}

function classify(line, previousLevel) {
  if (STACK_FRAME.test(line)) return previousLevel === "error" ? "error" : previousLevel || "info";
  const mc = line.match(MC_LEVEL);
  if (mc) return normalizeLevel(mc[1]);
  if (EXCEPTION_LINE.test(line)) return "error";
  const bare = line.match(BARE_LEVEL);
  if (bare) return normalizeLevel(bare[1]);
  return "info";
}

let nextId = 0;

function LogsApp() {
  const [lines, setLines] = useState([]);
  const [query, setQuery] = useState("");
  const [hidden, setHidden] = useState(() => new Set());
  const [pinned, setPinned] = useState(true);
  const [maximized, setMaximized] = useState(false);

  const scrollRef = useRef(null);
  // Read by the IPC handler without re-subscribing on every keystroke.
  const levelRef = useRef("info");

  useEffect(() => {
    const onLog = (_event, payload) => {
      const text = typeof payload === "string" ? payload : String(payload?.message ?? "");
      const incoming = text.replace(/\r/g, "").split("\n").filter((l) => l.length > 0);
      if (!incoming.length) return;
      setLines((prev) => {
        const next = prev.length > MAX_LINES ? prev.slice(prev.length - MAX_LINES) : prev.slice();
        for (const raw of incoming) {
          const level = classify(raw, levelRef.current);
          levelRef.current = level;
          next.push({ id: nextId++, text: raw, level });
        }
        return next.length > MAX_LINES ? next.slice(next.length - MAX_LINES) : next;
      });
    };
    const onCleared = () => setLines([]);
    ipcRenderer.on("launcher:log", onLog);
    ipcRenderer.on("logs:cleared", onCleared);
    ipcRenderer.invoke("logs:ready").catch(() => {});
    return () => {
      ipcRenderer.removeListener("launcher:log", onLog);
      ipcRenderer.removeListener("logs:cleared", onCleared);
    };
  }, []);

  useEffect(() => {
    const onMax = (_event, value) => setMaximized(Boolean(value));
    ipcRenderer.on("logs:maximized", onMax);
    return () => ipcRenderer.removeListener("logs:maximized", onMax);
  }, []);

  const counts = useMemo(() => {
    const tally = { error: 0, warn: 0, info: 0, debug: 0 };
    for (const line of lines) tally[line.level] = (tally[line.level] || 0) + 1;
    return tally;
  }, [lines]);

  const visible = useMemo(() => {
    const needle = query.trim().toLowerCase();
    return lines.filter((line) => {
      if (hidden.has(line.level)) return false;
      if (needle && !line.text.toLowerCase().includes(needle)) return false;
      return true;
    });
  }, [lines, hidden, query]);

  // Never fight the user's scroll: only stick to the bottom while they're already there.
  useEffect(() => {
    if (!pinned) return;
    const el = scrollRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [visible, pinned]);

  const onScroll = useCallback(() => {
    const el = scrollRef.current;
    if (!el) return;
    const atBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 24;
    setPinned(atBottom);
  }, []);

  const jumpToLatest = useCallback(() => {
    const el = scrollRef.current;
    if (el) el.scrollTop = el.scrollHeight;
    setPinned(true);
  }, []);

  const toggleLevel = useCallback((level) => {
    setHidden((prev) => {
      const next = new Set(prev);
      if (next.has(level)) next.delete(level);
      else next.add(level);
      return next;
    });
  }, []);

  const copyVisible = useCallback(() => {
    ipcRenderer.invoke("logs:copy", visible.map((l) => l.text).join("\n")).catch(() => {});
  }, [visible]);

  useEffect(() => {
    const onKey = (event) => {
      if (event.key === "Escape") windowAction("close");
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "f") {
        event.preventDefault();
        document.getElementById("logs-search")?.focus();
      }
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "l") {
        event.preventDefault();
        setLines([]);
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  return (
    <div className="logs-shell">
      <header className="logs-titlebar">
        <div className="logs-title">
          <span className="logs-title-name">Game log</span>
          <span className="logs-title-meta">{lines.length ? `${lines.length} lines` : "Waiting for output"}</span>
        </div>
        <div className="logs-window-controls">
          <button type="button" onClick={() => windowAction("minimize")} aria-label="Minimize"><Minus size={14} /></button>
          <button type="button" onClick={() => windowAction("maximize")} aria-label={maximized ? "Restore" : "Maximize"}><Square size={12} /></button>
          <button type="button" className="close" onClick={() => windowAction("close")} aria-label="Close"><X size={14} /></button>
        </div>
      </header>

      <div className="logs-toolbar">
        <div className="logs-filters">
          {LEVELS.map((level) => (
            <button
              key={level}
              type="button"
              className={`logs-chip ${level}${hidden.has(level) ? " off" : ""}`}
              onClick={() => toggleLevel(level)}
              title={`${hidden.has(level) ? "Show" : "Hide"} ${LEVEL_LABEL[level]} lines`}
            >
              <span className="logs-chip-dot" />
              {LEVEL_LABEL[level]}
              <span className="logs-chip-count">{counts[level] || 0}</span>
            </button>
          ))}
        </div>
        <div className="logs-search">
          <Search size={13} />
          <input
            id="logs-search"
            type="text"
            value={query}
            spellCheck={false}
            placeholder="Filter lines"
            onChange={(event) => setQuery(event.target.value)}
          />
        </div>
        <div className="logs-actions">
          <button type="button" onClick={copyVisible} title="Copy visible lines"><Copy size={13} /> Copy</button>
          <button type="button" onClick={() => ipcRenderer.invoke("logs:open-folder").catch(() => {})} title="Open the log folder"><FolderOpen size={13} /> Folder</button>
          <button type="button" onClick={() => setLines([])} title="Clear this view (Ctrl+L)"><Trash2 size={13} /> Clear</button>
        </div>
      </div>

      <div className="logs-body" ref={scrollRef} onScroll={onScroll}>
        {visible.length === 0 ? (
          <p className="logs-empty">
            {lines.length === 0
              ? "Nothing yet. Output appears here as soon as the game starts writing."
              : "No lines match the current filter."}
          </p>
        ) : (
          <ol className="logs-list">
            {visible.map((line) => (
              <li key={line.id} className={`logs-line ${line.level}`}>
                <span className="logs-line-text">{line.text}</span>
              </li>
            ))}
          </ol>
        )}
      </div>

      {!pinned && (
        <button type="button" className="logs-jump" onClick={jumpToLatest}>
          <ArrowDown size={13} /> Scroll to latest
        </button>
      )}
    </div>
  );
}

createRoot(document.getElementById("root")).render(<LogsApp />);

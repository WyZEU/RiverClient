import { useCallback, useEffect, useState } from "react";

const api = () => (typeof window !== "undefined" ? window.clientcore : null);

/**
 * Single source of truth for launcher state. The main process owns it and pushes
 * every change over `launcher:status`, so the UI never derives or caches its own
 * copy - it renders whatever the backend last said.
 */
export function useStatus() {
  const [status, setStatus] = useState(null);
  const [error, setError] = useState("");

  const refresh = useCallback(async () => {
    const bridge = api();
    if (!bridge) return;
    try {
      setStatus(await bridge.getStatus());
    } catch (e) {
      setError(String(e?.message || e));
    }
  }, []);

  useEffect(() => {
    const bridge = api();
    if (!bridge) {
      setError("Launcher bridge unavailable.");
      return undefined;
    }
    refresh();
    return bridge.onStatus((next) => setStatus(next));
  }, [refresh]);

  return { status, error, refresh };
}

/** Rolling tail of launcher log lines, used for the launch progress strip. */
export function useLatestLog() {
  const [line, setLine] = useState("");
  useEffect(() => {
    const bridge = api();
    if (!bridge) return undefined;
    return bridge.onLog((next) => setLine(String(next || "").trim()));
  }, []);
  return line;
}

export { api };

import React, { useCallback, useEffect, useRef, useState } from "react";
import { Loader2, CircleCheck, AlertCircle } from "lucide-react";
import { api } from "../lib/useStatus";
import { cn } from "../lib/utils";

/**
 * Live progress strip.
 *
 * The main process narrates everything it does - launch preparation, downloads,
 * repairs, exports and mod updates on "launcher:activity", and first-boot setup on
 * "launcher:boot". Nothing in the UI ever subscribed to either, so every long
 * operation looked like the launcher had frozen and startup looked like a dead
 * window. This is the single place all of that surfaces.
 *
 * Deliberately one slim strip at the bottom rather than progress scattered per
 * button: whatever the launcher is doing, it is always reported in the same place.
 */

/** Percent when the payload can give one, or null for an indeterminate task. */
function computePercent(payload) {
  const percent = Number(payload?.percent || 0);
  if (percent > 0) return Math.min(100, percent);
  const total = Number(payload?.total || 0);
  const current = Number(payload?.current || 0);
  if (total > 0) return Math.min(100, (current / total) * 100);
  return null;
}

export function ActivityBar() {
  const [state, setState] = useState(null);
  const hideTimer = useRef(null);

  const show = useCallback((next) => {
    if (hideTimer.current) clearTimeout(hideTimer.current);
    setState(next);
    // Finished work clears itself; failures linger long enough to actually be read.
    if (next.done || next.error) {
      hideTimer.current = setTimeout(() => setState(null), next.error ? 9000 : 2500);
    }
  }, []);

  useEffect(() => {
    const offActivity = api()?.onActivity?.((payload) =>
      show({
        title: payload?.title || "Working",
        detail: payload?.detail || "",
        percent: computePercent(payload),
        done: Boolean(payload?.done),
        error: Boolean(payload?.error)
      })
    );
    const offBoot = api()?.onBoot?.((payload) =>
      show({
        title: payload?.step || "Preparing River Client",
        detail: payload?.detail || "",
        percent: null,
        done: Boolean(payload?.done),
        error: Boolean(payload?.error)
      })
    );
    return () => {
      if (typeof offActivity === "function") offActivity();
      if (typeof offBoot === "function") offBoot();
      if (hideTimer.current) clearTimeout(hideTimer.current);
    };
  }, [show]);

  if (!state) return null;

  const indeterminate = state.percent == null && !state.done && !state.error;

  return (
    <div className="shrink-0 border-t border-border bg-card">
      <div className="h-0.5 w-full bg-accent">
        <div
          className={cn(
            "h-full transition-[width] duration-300 ease-out",
            state.error ? "bg-destructive" : state.done ? "bg-success" : "bg-primary",
            indeterminate && "animate-pulse"
          )}
          style={{ width: state.done || state.error ? "100%" : `${state.percent ?? 100}%` }}
        />
      </div>

      <div className="flex items-center gap-2 px-3 py-1.5">
        {state.error ? (
          <AlertCircle className="size-3.5 shrink-0 text-destructive" />
        ) : state.done ? (
          <CircleCheck className="size-3.5 shrink-0 text-success" />
        ) : (
          <Loader2 className="size-3.5 shrink-0 animate-spin text-muted-foreground" />
        )}

        <span className="shrink-0 text-xs font-medium">{state.title}</span>
        {state.detail ? (
          <span className="truncate text-xs text-muted-foreground">{state.detail}</span>
        ) : null}
        {state.percent != null && !state.done && !state.error ? (
          <span className="ml-auto shrink-0 text-xs tabular-nums text-muted-foreground">
            {Math.round(state.percent)}%
          </span>
        ) : null}
      </div>
    </div>
  );
}

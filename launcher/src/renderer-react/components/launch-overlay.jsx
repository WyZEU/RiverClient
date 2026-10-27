import React, { useCallback, useEffect, useState } from "react";
import { Loader2 } from "lucide-react";
import { api } from "../lib/useStatus";
import { cn } from "../lib/utils";
import { Button } from "./ui/button";
import { InstanceArt, useInstanceCards } from "./instance-art";
import { useModern } from "../lib/ui-style";
import { useSeasonId } from "../lib/seasons";
import { Mist, NightSky } from "./modern-season";
import { useT } from "../i18n/context.jsx";

const HERO_BG = "../assets/home-bg-2.jpg";

/**
 * The launch screen.
 *
 * Pressing Launch used to hand the user an empty desktop: the launcher hid itself the
 * instant the JVM was spawned, and on a modded instance Minecraft's window does not
 * appear for another 30-60 seconds. Nothing on screen, no progress, no way to tell a
 * slow launch from a failed one.
 *
 * So the launcher stays up and shows this until the game is actually on screen - the
 * main process holds `launchState` at "launching" until Minecraft's window exists, and
 * only then hides the launcher. Everything the launcher narrates on the way there
 * (preparation steps first, then the game's own log) is echoed on the stage line, so
 * the wait always has something moving in it.
 */

/** Percent when the step count gives one, or null while the work is open-ended. */
function computePercent(payload) {
  const percent = Number(payload?.percent || 0);
  if (percent > 0) return Math.min(100, percent);
  const total = Number(payload?.total || 0);
  const current = Number(payload?.current || 0);
  if (total > 0) return Math.min(100, (current / total) * 100);
  return null;
}

/** "[12:34:56] [Render thread/INFO]: Backend library: LWJGL" -> "Backend library: LWJGL". */
function tidyLogLine(line) {
  const text = String(line || "").trim();
  if (!text) return "";
  const body = text
    .replace(/^\[\d{1,2}:\d{2}:\d{2}\]\s*/, "")
    .replace(/^\[[^\]]+\]:?\s*/, "")
    .trim();
  if (!body) return "";
  return body.length > 110 ? `${body.slice(0, 109).trimEnd()}…` : body;
}

/**
 * The Modern launch screen's progress: a row of blocks. Known progress fills them left to
 * right; open-ended work runs a wave along them instead, so it never sits at a fake value.
 */
function BlockProgress({ percent }) {
  const count = 24;
  const lit = percent == null ? -1 : Math.round((percent / 100) * count);
  return (
    <div className="flex gap-[3px]" aria-hidden>
      {Array.from({ length: count }, (_, i) => (
        <span
          key={i}
          className={cn("h-3 w-2.5 rounded-[2px]", percent == null ? "m-wave" : i < lit ? "bg-primary" : "bg-secondary")}
          style={percent == null ? { animationDelay: `${i * 55}ms` } : undefined}
        />
      ))}
    </div>
  );
}

export function LaunchOverlay({ status, notify }) {
  const t = useT();
  const modern = useModern();
  const halloween = useSeasonId() === "halloween";
  const open = status?.launchState === "launching";
  // Only the Modern screen shows the instance's own picture.
  const cards = useInstanceCards(status?.instances, modern && open);
  const [stage, setStage] = useState("");
  const [percent, setPercent] = useState(null);
  // { index, count, label } from main; only its preparation sets it, never the game's log.
  const [step, setStep] = useState(null);
  const [stopping, setStopping] = useState(false);

  // Both feeds write the same line, so whichever spoke last is what is shown: the
  // preparation steps while the launcher is working, the game's own log after that.
  useEffect(() => {
    if (!open) return undefined;
    const bridge = api();
    if (!bridge) return undefined;
    const offActivity = bridge.onActivity?.((payload) => {
      setStage(String(payload?.detail || payload?.title || ""));
      setPercent(computePercent(payload));
      if (payload?.step?.index) setStep(payload.step);
    });
    const offLog = bridge.onLog?.((line) => {
      const tidy = tidyLogLine(line);
      if (!tidy) return;
      setStage(tidy);
      setPercent(null);
    });
    return () => {
      if (typeof offActivity === "function") offActivity();
      if (typeof offLog === "function") offLog();
    };
  }, [open]);

  // A launch screen that outlives its launch would be a trap, so it resets on the
  // way out and the next launch starts from a clean line rather than a stale one.
  useEffect(() => {
    if (open) return;
    setStage("");
    setPercent(null);
    setStep(null);
    setStopping(false);
  }, [open]);

  const cancel = useCallback(async () => {
    setStopping(true);
    try {
      const res = await api()?.stopClient();
      // Before the process exists there is nothing to kill; say so rather than
      // leaving the button looking stuck.
      if (res && res.ok === false) notify?.(res.message || t("Nothing to cancel yet."), "error");
    } catch (e) {
      notify?.(String(e?.message || e), "error");
    } finally {
      setStopping(false);
    }
  }, [notify, t]);

  if (!open) return null;

  const indeterminate = percent == null;
  const stepLine = step ? t("Step {index} of {count}: {label}", { index: step.index, count: step.count, label: t(step.label) }) : "";
  // Steps before Minecraft starts mean files are being fetched, which happens once per version.
  const firstRunHint = step && step.index < step.count ? t("Only the first launch of a version downloads all this.") : "";

  /*
    Modern: the whole window, behind the title bar's controls, with the instance's own
    picture, its name large, and the block progress row.
  */
  if (modern) {
    const selected = status?.selectedInstance;
    return (
      <div
        role="status"
        aria-live="polite"
        className="fixed inset-0 z-40 flex items-center justify-center overflow-hidden bg-background animate-in fade-in duration-200"
      >
        {/* Halloween: the same night the game's title screen opens on. */}
        <div className={cn("pointer-events-none absolute inset-0 select-none", halloween ? "opacity-80" : "opacity-60")}>
          {halloween ? <NightSky drift /> : <InstanceArt instanceId={selected?.id} card={cards[selected?.id]} drift />}
        </div>
        <div
          className={cn(
            "pointer-events-none absolute inset-0 bg-gradient-to-b",
            halloween ? "from-background/40 via-background/70 to-background/95" : "from-background/80 via-background/90 to-background"
          )}
        />
        {halloween ? <Mist className="opacity-50" /> : null}

        <div className="relative flex flex-col items-center gap-6 px-8 text-center">
          <img src="../assets/river-logo.png" alt="" draggable={false} className="size-14 select-none object-contain" />
          <div>
            <p className="m-kicker text-primary">{t("Launching")}</p>
            <div className="mt-3 max-w-xl truncate text-[34px] font-extrabold leading-tight tracking-[-0.025em]">
              {selected?.name || "River Client"}
            </div>
          </div>
          <div className="flex flex-col items-center gap-2">
            {stepLine ? <p className="text-sm font-semibold">{stepLine}</p> : null}
            <BlockProgress percent={percent} />
          </div>
          <div className="flex h-8 w-96 items-start justify-center">
            <p className="line-clamp-2 text-xs leading-snug text-muted-foreground">
              {stage || t("This can take a minute on the first launch.")}
            </p>
          </div>
          {firstRunHint ? <p className="-mt-3 text-[11px] text-muted-foreground/80">{firstRunHint}</p> : null}
          <Button variant="outline" onClick={cancel} disabled={stopping}>
            {t("Cancel")}
          </Button>
        </div>
      </div>
    );
  }

  return (
    <div
      role="status"
      aria-live="polite"
      className="fixed inset-x-0 bottom-0 top-8 z-40 flex flex-col items-center justify-center gap-5 overflow-hidden bg-background animate-in fade-in duration-200"
    >
      {/* Same drifting art as the Home launch panel, so the wait still looks like River. */}
      <div className="pointer-events-none absolute inset-0 select-none">
        <img src={HERO_BG} alt="" draggable={false} className="hero-drift size-full object-cover opacity-70" />
        <div className="absolute inset-0 bg-gradient-to-b from-background/85 via-background/92 to-background" />
      </div>

      <div className="relative flex flex-col items-center gap-5 px-8 text-center">
        <img
          src="../assets/river-logo.png"
          alt=""
          draggable={false}
          className="size-16 select-none object-contain"
        />

        <div className="space-y-1">
          <div className="text-lg font-semibold leading-tight">
            {status?.selectedInstance?.name || "River Client"}
          </div>
          <div className="flex items-center justify-center gap-2 text-xs text-muted-foreground">
            <Loader2 className="size-3.5 animate-spin" />
            {stepLine || t("Starting Minecraft...")}
          </div>
        </div>

        <div className="h-1 w-64 overflow-hidden rounded-full bg-secondary">
          <div
            className={cn("h-full rounded-full bg-primary", indeterminate ? "launch-sweep w-1/3" : "transition-[width] duration-300 ease-out")}
            style={indeterminate ? undefined : { width: `${percent}%` }}
          />
        </div>

        {/* Fixed height: the line changes constantly and must not shift the layout. */}
        <div className="flex h-8 w-80 items-start justify-center">
          <p className="line-clamp-2 text-[11px] leading-snug text-muted-foreground">
            {stage || t("This can take a minute on the first launch.")}
          </p>
        </div>

        {firstRunHint ? <p className="-mt-3 text-[11px] text-muted-foreground/80">{firstRunHint}</p> : null}
        <Button variant="ghost" size="sm" onClick={cancel} disabled={stopping}>
          {t("Cancel")}
        </Button>
      </div>
    </div>
  );
}

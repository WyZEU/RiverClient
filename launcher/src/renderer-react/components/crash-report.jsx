import React, { useEffect, useState } from "react";
import {
  AlertTriangle, Download, PowerOff, FileText, Copy, Loader2, ShieldCheck, ChevronDown
} from "lucide-react";
import { api } from "../lib/useStatus";
import { cn } from "../lib/utils";
import { Button } from "./ui/button";
import { Badge } from "./ui/badge";
import { Separator } from "./ui/separator";

const CONFIDENCE_COPY = {
  likely: "One mod stands out as the likely cause.",
  narrowed: "Narrowed to these mods - all of them patched the code that crashed and are out of date.",
  unclear: "These mods patched the code that crashed."
};

/**
 * Post-crash help.
 *
 * Handing someone a stack trace and 200 mods is not help. This names what broke,
 * narrows the candidates using the crash report's own mixin list, and - most
 * importantly - offers the fixes inline so the player never has to leave and go
 * hunting through folders.
 */
export function CrashReport({ status, refresh, notify }) {
  const [analysis, setAnalysis] = useState(null);
  const [busy, setBusy] = useState("");
  const [expanded, setExpanded] = useState(false);

  const crashPath = status?.crashInfo?.latest?.path || "";

  useEffect(() => {
    if (!crashPath) { setAnalysis(null); return; }
    api()?.analyzeCrash?.().then(setAnalysis).catch(() => setAnalysis(null));
  }, [crashPath]);

  // Dismissal is stored against the specific report rather than held in component
  // state: this component unmounts whenever you leave Home, so local state would
  // resurrect the panel on every navigation (and on every restart). Keying it to the
  // file name also means the next crash still gets through.
  const dismissed =
    Boolean(analysis?.report?.file) &&
    status?.settings?.dismissedCrashReport === analysis.report.file;

  const dismiss = () => {
    if (!analysis?.report?.file) return;
    api()?.updateSettings({ dismissedCrashReport: analysis.report.file });
    refresh();
  };

  // Only surface after a crash that actually ended a session, not on every boot.
  const failed = Boolean(status?.lastLaunchFailure) || Boolean(status?.crashInfo?.latest);
  if (!analysis || !failed || dismissed) return null;

  const run = async (key, fn, okMessage) => {
    setBusy(key);
    try {
      const res = await fn();
      if (res && res.ok === false) notify(res.message || "That did not work.", "error");
      else if (okMessage) notify(okMessage, "info");
      await refresh();
    } catch (e) {
      notify(String(e?.message || e), "error");
    } finally {
      setBusy("");
    }
  };

  const outdated = analysis.suspects.filter((s) => s.outdated);
  const shown = expanded ? analysis.suspects : analysis.suspects.slice(0, 4);

  return (
    <section className="rounded-lg border border-destructive/40 bg-card">
      <div className="flex items-start gap-3 p-4">
        <AlertTriangle className="mt-0.5 size-5 shrink-0 text-destructive" />
        <div className="min-w-0 flex-1">
          <h2 className="text-sm font-semibold">The game crashed</h2>
          <p className="mt-0.5 text-xs leading-relaxed text-muted-foreground">{analysis.failure.detail}</p>
        </div>
        <Button variant="ghost" size="sm" onClick={dismiss}>Dismiss</Button>
      </div>

      {analysis.findings.length ? (
        <div className="space-y-2 px-4 pb-3">
          {analysis.findings.map((finding) => (
            <div key={finding.title} className="rounded-md border border-border bg-background/40 px-3 py-2">
              <div className="flex items-center gap-2">
                <span className={cn("size-1.5 rounded-full", finding.severity === "error" ? "bg-destructive" : "bg-warning")} />
                <span className="text-xs font-medium">{finding.title}</span>
              </div>
              <p className="mt-1 pl-3.5 text-[11px] leading-relaxed text-muted-foreground">{finding.detail}</p>
            </div>
          ))}
        </div>
      ) : null}

      {analysis.suspects.length ? (
        <>
          <Separator />
          <div className="p-4">
            <p className="text-xs text-muted-foreground">
              {CONFIDENCE_COPY[analysis.confidence] || CONFIDENCE_COPY.unclear}
            </p>

            <div className="mt-2.5 space-y-1.5">
              {shown.map((suspect) => (
                <div
                  key={suspect.id}
                  className="flex items-center gap-2 rounded-md border border-border bg-background/40 px-3 py-2"
                >
                  <span className="min-w-0 flex-1 truncate text-xs font-medium">{suspect.id}</span>
                  {suspect.outdated ? <Badge variant="warning">Out of date</Badge> : null}
                  {suspect.disabled ? <Badge variant="outline">Disabled</Badge> : null}
                  {suspect.file && !suspect.disabled && !suspect.required ? (
                    <Button
                      size="sm"
                      variant="ghost"
                      disabled={Boolean(busy)}
                      onClick={() => run(
                        `off:${suspect.id}`,
                        () => api()?.setModEnabled({ file: suspect.file }, false),
                        `${suspect.id} disabled.`
                      )}
                    >
                      {busy === `off:${suspect.id}` ? <Loader2 className="size-3.5 animate-spin" /> : <PowerOff className="size-3.5" />}
                      Disable
                    </Button>
                  ) : null}
                </div>
              ))}
            </div>

            {analysis.suspects.length > 4 ? (
              <button
                onClick={() => setExpanded((v) => !v)}
                className="mt-2 inline-flex items-center gap-1 text-[11px] text-muted-foreground transition-colors hover:text-foreground"
              >
                <ChevronDown className={cn("size-3 transition-transform", expanded && "rotate-180")} />
                {expanded ? "Show fewer" : `Show all ${analysis.suspects.length}`}
              </button>
            ) : null}
          </div>
        </>
      ) : null}

      <Separator />
      <div className="flex flex-wrap items-center gap-2 p-4">
        {outdated.length ? (
          <Button
            size="sm"
            disabled={Boolean(busy)}
            onClick={() => run("update", () => api()?.updateAllMods({}), "Mod updates finished.")}
          >
            {busy === "update" ? <Loader2 className="size-3.5 animate-spin" /> : <Download className="size-3.5" />}
            Update {outdated.length} outdated {outdated.length === 1 ? "mod" : "mods"}
          </Button>
        ) : null}

        <Button
          size="sm"
          variant="outline"
          disabled={Boolean(busy)}
          title="Verify game files and River's own mods"
          onClick={() => run("repair", () => api()?.repairInstance({ instanceId: status?.selectedInstance?.id || "" }), "Repair complete.")}
        >
          {busy === "repair" ? <Loader2 className="size-3.5 animate-spin" /> : <ShieldCheck className="size-3.5" />}
          Repair instance
        </Button>

        <Button
          size="sm"
          variant="ghost"
          onClick={() => api()?.openPath(analysis.report?.path)}
        >
          <FileText className="size-3.5" />
          Open report
        </Button>

        <Button
          size="sm"
          variant="ghost"
          onClick={() => {
            const lines = [
              `River Client ${status?.version || ""}`.trim(),
              `Problem: ${analysis.failure.detail}`,
              analysis.suspects.length
                ? `Candidates: ${analysis.suspects.map((s) => s.id + (s.outdated ? " (out of date)" : "")).join(", ")}`
                : "",
              ...analysis.findings.map((f) => `${f.title}: ${f.detail}`)
            ].filter(Boolean);
            navigator.clipboard?.writeText(lines.join("\n"));
            notify("Crash summary copied - paste it when asking for help.", "info");
          }}
        >
          <Copy className="size-3.5" />
          Copy summary
        </Button>
      </div>
    </section>
  );
}

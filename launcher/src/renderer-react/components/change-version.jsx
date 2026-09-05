import React, { useState } from "react";
import { ArrowRight, CheckCircle2, Loader2, PauseCircle, RefreshCw, TriangleAlert } from "lucide-react";
import { api } from "../lib/useStatus";
import { cn } from "../lib/utils";
import { Button } from "./ui/button";
import { Badge } from "./ui/badge";
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle
} from "./ui/dialog";

/**
 * Switches an instance to another Minecraft version.
 *
 * Deliberately two-step: the plan is fetched and shown BEFORE anything is touched, because
 * the interesting part is what will stop working. Mods without a build for the target are
 * disabled, never deleted, so the switch is always reversible by switching back.
 */
export function ChangeVersionDialog({ open, onOpenChange, instance, versions, notify, onDone }) {
  const available = (Array.isArray(versions) && versions.length ? versions : [{ id: "1.21.11" }, { id: "1.21.4" }])
    .filter((entry) => entry.id !== instance?.version);

  const [target, setTarget] = useState("");
  const [plan, setPlan] = useState(null);
  const [busy, setBusy] = useState("");

  const reset = () => { setTarget(""); setPlan(null); setBusy(""); };

  const check = async (version) => {
    setTarget(version);
    setPlan(null);
    setBusy("check");
    try {
      const res = await api()?.previewVersionChange({ instanceId: instance.id, version });
      if (res && res.ok === false) { notify(res.message || "Could not check that version.", "error"); setTarget(""); }
      else setPlan(res?.plan || null);
    } catch (e) {
      notify(String(e?.message || e), "error");
      setTarget("");
    } finally {
      setBusy("");
    }
  };

  const apply = async () => {
    setBusy("apply");
    try {
      const res = await api()?.changeInstanceVersion({ instanceId: instance.id, version: target });
      if (res && res.ok === false) notify(res.message || "Could not change version.", "error");
      else {
        notify(res?.message || `Now on ${target}.`, "info");
        onOpenChange(false);
        reset();
        onDone?.();
      }
    } catch (e) {
      notify(String(e?.message || e), "error");
    } finally {
      setBusy("");
    }
  };

  const willDisable = plan ? [...(plan.disable || []), ...(plan.unverified || [])] : [];
  const group = (items, tone, Icon, title, note) => (items.length ? (
    <section className="space-y-1.5">
      <h3 className={cn("flex items-center gap-2 text-xs font-semibold", tone)}>
        <Icon className="size-3.5" />{title} ({items.length})
      </h3>
      {note ? <p className="text-[11px] leading-relaxed text-muted-foreground">{note}</p> : null}
      <ul className="space-y-1">
        {items.map((entry) => (
          <li key={entry.contentType + entry.file} className="truncate rounded border border-border bg-card px-2.5 py-1.5 text-[11px]">
            {entry.title}
            {entry.newVersionNumber ? (
              <span className="text-muted-foreground"> → {entry.newVersionNumber}</span>
            ) : null}
          </li>
        ))}
      </ul>
    </section>
  ) : null);

  return (
    <Dialog open={open} onOpenChange={(next) => { onOpenChange(next); if (!next) reset(); }}>
      <DialogContent className="flex max-h-[80vh] max-w-lg flex-col">
        <DialogHeader>
          <DialogTitle>Change Minecraft version</DialogTitle>
          <DialogDescription>
            {instance?.name} is on {instance?.version || "an unknown version"}. Mods are updated to
            the new version automatically where a build exists.
          </DialogDescription>
        </DialogHeader>

        <div className="min-h-0 flex-1 space-y-4 overflow-y-auto">
          <div className="flex flex-wrap gap-1.5">
            {available.map((entry) => (
              <button
                key={entry.id}
                type="button"
                disabled={Boolean(busy)}
                onClick={() => check(entry.id)}
                className={cn(
                  "flex items-center gap-2 rounded-md border px-3 py-2 text-sm font-medium transition-colors",
                  target === entry.id
                    ? "border-primary/50 bg-accent text-foreground"
                    : "border-border bg-card text-muted-foreground hover:bg-accent/40"
                )}
              >
                {instance?.version} <ArrowRight className="size-3.5" /> {entry.id}
              </button>
            ))}
          </div>

          {busy === "check" ? (
            <div className="flex items-center gap-2 py-6 text-xs text-muted-foreground">
              <Loader2 className="size-4 animate-spin" />Checking every mod for a {target} build…
            </div>
          ) : null}

          {plan ? (
            <div className="space-y-4">
              {group(plan.update || [], "text-success", RefreshCw, "Will be updated",
                "Updated to the new version.")}
              {group(plan.keep || [], "text-muted-foreground", CheckCircle2, "Already compatible",
                "Already work. Left alone.")}
              {group(willDisable, "text-warning", PauseCircle, "Will be turned off",
                "No build for this version. Disabled, not deleted, switch back and they return.")}

              {!(plan.update || []).length && !(plan.keep || []).length && !willDisable.length ? (
                <p className="py-6 text-center text-xs text-muted-foreground">
                  Nothing installed to move over. The switch is safe.
                </p>
              ) : null}
            </div>
          ) : null}
        </div>

        <DialogFooter className="flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
          {willDisable.length ? (
            <span className="flex items-center gap-1.5 text-[11px] text-warning">
              <TriangleAlert className="size-3.5" />
              {willDisable.length} {willDisable.length === 1 ? "item" : "items"} will stop loading
            </span>
          ) : <span />}
          <div className="flex gap-2">
            <Button variant="ghost" size="sm" disabled={Boolean(busy)} onClick={() => onOpenChange(false)}>Cancel</Button>
            <Button size="sm" disabled={!plan || Boolean(busy)} onClick={apply}>
              {busy === "apply" ? <Loader2 className="size-3.5 animate-spin" /> : null}
              Switch to {target || "…"}
            </Button>
          </div>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

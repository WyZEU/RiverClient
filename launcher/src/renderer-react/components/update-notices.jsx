import React, { useEffect, useRef, useState } from "react";
import { Download, Loader2, Sparkles, X } from "lucide-react";
import { api } from "../lib/useStatus";
import { Button } from "./ui/button";
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle
} from "./ui/dialog";
import { useT } from "../i18n/context.jsx";

/** #updates on the River Discord - where every release's changes are posted. */
const UPDATES_CHANNEL_URL = "https://discord.com/channels/1501139838311596103/1541128335529345114";

const DISMISS_KEY = "river:dismissedUpdateVersion";

function readDismissed() {
  try { return window.localStorage.getItem(DISMISS_KEY) || ""; } catch { return ""; }
}

function writeDismissed(version) {
  try { window.localStorage.setItem(DISMISS_KEY, version); } catch {}
}

function isMandatory(update) {
  return Boolean(update?.available && (update.blocking || update.required));
}

/**
 * "An update is available" - for the optional ones.
 *
 * Mandatory updates already take over the screen (UpdateGate). Optional ones used to
 * live only in Settings, so unless someone went looking, they never knew there was a
 * newer River. This is a slim bar across the top instead: one click to update, or
 * dismissed until the next version comes out - never nagging about the same one twice.
 */
export function UpdateBanner({ status, notify }) {
  const t = useT();
  const update = status?.launcherUpdate;
  const [dismissed, setDismissed] = useState(readDismissed);
  const [installing, setInstalling] = useState(false);

  const latest = update?.latestVersion || "";
  if (!update?.available || isMandatory(update) || !latest || dismissed === latest) return null;

  const install = async () => {
    setInstalling(true);
    try {
      const res = await api()?.installLauncherUpdate();
      if (res && res.ok === false) {
        notify?.(res.message || t("The update could not be installed."), "error");
        setInstalling(false);
      }
      // On success the app relaunches into updater mode, so leave the spinner up.
    } catch (e) {
      notify?.(String(e?.message || e), "error");
      setInstalling(false);
    }
  };

  const dismiss = () => {
    writeDismissed(latest);
    setDismissed(latest);
  };

  return (
    <div className="flex shrink-0 items-center gap-3 border-b border-border bg-primary/10 px-4 py-1.5 animate-in fade-in slide-in-from-top-1 duration-200">
      <Download className="size-3.5 shrink-0 text-primary" />
      <span className="min-w-0 flex-1 truncate text-xs">
        {t("River Client {version} is available.", { version: latest })}
      </span>
      <Button size="sm" className="h-6 px-3 text-xs" onClick={install} disabled={installing}>
        {installing ? <Loader2 className="size-3 animate-spin" /> : null}
        {t("Update now")}
      </Button>
      <button
        aria-label={t("Dismiss")}
        onClick={dismiss}
        disabled={installing}
        className="no-drag text-muted-foreground transition-colors hover:text-foreground"
      >
        <X className="size-3.5" />
      </button>
    </div>
  );
}

/**
 * Shown once after an update is installed: what changed lives in #updates on Discord,
 * so this points there rather than repeating the notes in a second place that would
 * drift out of step with them.
 *
 * A brand-new install has nothing to catch up on, so its first run just records the
 * version. The test for "brand new" is the tutorial: someone who finished it has used
 * River before, even if this version stamp is the first they have ever had - which is
 * everyone, the first time this ships.
 */
export function WhatsNewDialog({ status, refresh }) {
  const t = useT();
  const current = status?.appVersion || "";
  const settings = status?.settings;
  const [open, setOpen] = useState(false);
  const decided = useRef(false);

  useEffect(() => {
    if (decided.current || !current || !settings) return;
    // A required update owns the screen; this would only stack on top of it.
    if (isMandatory(status?.launcherUpdate)) return;
    decided.current = true;

    const seen = String(settings.lastSeenChangelogVersion || "");
    if (seen === current) return;
    if (!seen && !settings.tutorialCompleted) {
      api()?.updateSettings({ lastSeenChangelogVersion: current }).catch?.(() => {});
      return;
    }
    setOpen(true);
  }, [current, settings, status?.launcherUpdate]);

  const close = async () => {
    setOpen(false);
    try {
      await api()?.updateSettings({ lastSeenChangelogVersion: current });
      await refresh?.();
    } catch {}
  };

  const openUpdates = () => {
    api()?.openExternal(UPDATES_CHANNEL_URL);
    close();
  };

  return (
    <Dialog open={open} onOpenChange={(next) => { if (!next) close(); }}>
      <DialogContent className="max-w-sm">
        <DialogHeader>
          <div className="mb-1 flex size-9 items-center justify-center rounded-md bg-primary/15">
            <Sparkles className="size-4 text-primary" />
          </div>
          <DialogTitle>{t("River Client {version} installed", { version: current })}</DialogTitle>
          <DialogDescription>
            {t("See everything that changed in #updates on our Discord.")}
          </DialogDescription>
        </DialogHeader>
        <DialogFooter>
          <Button variant="outline" onClick={close}>{t("Later")}</Button>
          <Button onClick={openUpdates}>{t("Open #updates")}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

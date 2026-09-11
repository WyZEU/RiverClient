import React, { useState } from "react";
import { Download, Loader2 } from "lucide-react";
import { api } from "../lib/useStatus";
import { Button } from "./ui/button";
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle
} from "./ui/dialog";

/**
 * Blocking launcher update.
 *
 * The main process can only install an update when the renderer calls
 * installLauncherUpdate - runUpdaterMode just picks up the job that call writes.
 * So if the UI never offers it, a user on a version below minimumVersion is
 * stranded with no way forward. This is that way forward.
 */
export function UpdateGate({ status, notify }) {
  const [installing, setInstalling] = useState(false);
  const update = status?.launcherUpdate;

  // Only take over the screen when the update is actually mandatory; optional
  // ones live quietly in Settings.
  const mustUpdate = Boolean(update?.available && (update.blocking || update.required));
  if (!mustUpdate) return null;

  const install = async () => {
    setInstalling(true);
    try {
      const res = await api()?.installLauncherUpdate();
      if (res && res.ok === false) {
        notify?.(res.message || "The update could not be installed.", "error");
        setInstalling(false);
      }
      // On success the app relaunches into updater mode, so leave the spinner up.
    } catch (e) {
      notify?.(String(e?.message || e), "error");
      setInstalling(false);
    }
  };

  return (
    <Dialog open>
      <DialogContent className="max-w-sm [&>button]:hidden">
        <DialogHeader>
          <DialogTitle>Update required</DialogTitle>
          <DialogDescription>
            Install River Client {update.latestVersion} before you can play.
          </DialogDescription>
        </DialogHeader>

        <div className="grid grid-cols-2 gap-2">
          <div className="rounded-md border border-border bg-card px-3 py-2">
            <div className="text-[11px] text-muted-foreground">Installed</div>
            <div className="text-sm font-semibold">{update.currentVersion || status?.version || "-"}</div>
          </div>
          <div className="rounded-md border border-border bg-card px-3 py-2">
            <div className="text-[11px] text-muted-foreground">Latest</div>
            <div className="text-sm font-semibold">{update.latestVersion || "-"}</div>
          </div>
        </div>

        <DialogFooter>
          <Button className="w-full" onClick={install} disabled={installing}>
            {installing ? <Loader2 className="size-4 animate-spin" /> : <Download className="size-4" />}
            {installing ? "Installing…" : "Install update"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/** Inline "update available" action for Settings - optional updates only. */
export function UpdateAction({ status, notify }) {
  const [installing, setInstalling] = useState(false);
  const update = status?.launcherUpdate;
  if (!update?.available) return null;

  return (
    <Button
      size="sm"
      disabled={installing}
      onClick={async () => {
        setInstalling(true);
        try {
          const res = await api()?.installLauncherUpdate();
          if (res && res.ok === false) {
            notify?.(res.message || "The update could not be installed.", "error");
            setInstalling(false);
          }
        } catch (e) {
          notify?.(String(e?.message || e), "error");
          setInstalling(false);
        }
      }}
    >
      {installing ? <Loader2 className="size-3.5 animate-spin" /> : <Download className="size-3.5" />}
      Install {update.latestVersion}
    </Button>
  );
}

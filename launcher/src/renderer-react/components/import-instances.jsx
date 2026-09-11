import React, { useEffect, useState } from "react";
import { Download, Loader2, FolderInput, Boxes, FileArchive, TriangleAlert } from "lucide-react";
import { api } from "../lib/useStatus";
import { Button } from "./ui/button";
import { Badge } from "./ui/badge";
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle
} from "./ui/dialog";

/**
 * Import from another launcher. The backend already scans Prism, Modrinth, MultiMC,
 * CurseForge, ATLauncher, GDLauncher and more and copies mods/packs/shaders/worlds -
 * this just surfaces what it found and lets the user pick.
 */
export function ImportInstancesDialog({ open, onOpenChange, refresh, notify }) {
  const [detected, setDetected] = useState([]);
  const [scanning, setScanning] = useState(false);
  const [importingKey, setImportingKey] = useState("");

  useEffect(() => {
    if (!open) return;
    setScanning(true);
    api()?.detectExternalInstances?.()
      .then((res) => setDetected(res?.ok ? res.instances || [] : []))
      .catch(() => setDetected([]))
      .finally(() => setScanning(false));
  }, [open]);

  const importOne = async (entry) => {
    setImportingKey(entry.gameDir);
    try {
      const res = await api()?.importExternalInstance(entry);
      if (res && res.ok === false) notify(res.message || "Import failed.", "error");
      // A River-incompatible import still succeeded, so it is not an error - but the user
      // does need to know River will not load in game there, so that message wins.
      else if (res?.warning) notify(res.warning, "error");
      else notify(res?.message || `Imported ${entry.name}.`, "info");
      await refresh();
    } catch (e) {
      notify(String(e?.message || e), "error");
    } finally {
      setImportingKey("");
    }
  };

  const importFile = async (kind) => {
    setImportingKey(kind);
    try {
      const res = kind === "modpack"
        ? await api()?.importModpackFile()
        : await api()?.importInstance();
      if (res && res.ok === false) notify(res.message || "Import failed.", "error");
      else if (res) { notify(res.message || "Imported.", "info"); await refresh(); onOpenChange(false); }
    } catch (e) {
      notify(String(e?.message || e), "error");
    } finally {
      setImportingKey("");
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle>Import an instance</DialogTitle>
          <DialogDescription>
            Bring mods, resource packs, shaders and worlds over from another launcher.
            Each instance keeps its own Minecraft version and loader.
          </DialogDescription>
        </DialogHeader>

        <div className="max-h-72 space-y-2 overflow-y-auto">
          {scanning ? (
            <div className="flex h-20 items-center justify-center text-xs text-muted-foreground">
              <Loader2 className="mr-2 size-4 animate-spin" />Scanning for launchers…
            </div>
          ) : detected.length ? (
            detected.map((entry) => (
              <div key={entry.gameDir} className="flex items-center gap-3 rounded-md border border-border bg-card px-3 py-2.5">
                <Boxes className="size-4 shrink-0 text-muted-foreground" />
                <div className="min-w-0 flex-1">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="truncate text-sm font-medium">{entry.name}</span>
                    <Badge variant="outline">{entry.launcher}</Badge>
                    {entry.version ? (
                      <Badge variant="secondary">
                        {[entry.loader, entry.version].filter(Boolean).join(" ")}
                      </Badge>
                    ) : null}
                  </div>
                  <div className="truncate text-[11px] text-muted-foreground">{entry.gameDir}</div>
                  {/* Shown before importing, so the choice is informed rather than a surprise
                      afterwards. The import itself still works - only River in game does not. */}
                  {entry.riverSupported === false && entry.riverWarning ? (
                    <div className="mt-1 flex items-start gap-1.5 text-[11px] leading-relaxed text-warning">
                      <TriangleAlert className="mt-px size-3 shrink-0" />
                      <span>{entry.riverWarning}</span>
                    </div>
                  ) : null}
                </div>
                <Button size="sm" disabled={Boolean(importingKey)} onClick={() => importOne(entry)}>
                  {importingKey === entry.gameDir ? <Loader2 className="size-3.5 animate-spin" /> : <Download className="size-3.5" />}
                  Import
                </Button>
              </div>
            ))
          ) : (
            <div className="rounded-md border border-dashed border-border py-8 text-center text-xs text-muted-foreground">
              No other launchers found automatically. Import from a file below.
            </div>
          )}
        </div>

        <DialogFooter className="flex-col gap-2 sm:flex-row sm:justify-between">
          <div className="flex gap-2">
            <Button variant="outline" size="sm" disabled={Boolean(importingKey)} onClick={() => importFile("modpack")}>
              {importingKey === "modpack" ? <Loader2 className="size-3.5 animate-spin" /> : <FileArchive className="size-3.5" />}
              From .mrpack / .zip
            </Button>
            <Button variant="outline" size="sm" disabled={Boolean(importingKey)} onClick={() => importFile("rvr")}>
              {importingKey === "rvr" ? <Loader2 className="size-3.5 animate-spin" /> : <FolderInput className="size-3.5" />}
              From .rvr
            </Button>
          </div>
          <Button variant="ghost" size="sm" onClick={() => onOpenChange(false)}>Done</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

import React, { useEffect, useState } from "react";
import { Download, Loader2, FolderInput, FileArchive, Package, Check, Info } from "lucide-react";
import { api } from "../lib/useStatus";
import { Button } from "./ui/button";
import {
  Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle
} from "./ui/dialog";
import { useT } from "../i18n/context.jsx";

/**
 * Import from another launcher. The backend already scans Prism, Modrinth, MultiMC,
 * CurseForge, ATLauncher, GDLauncher and more and copies mods/packs/shaders/worlds -
 * this just surfaces what it found and lets the user pick.
 */
export function ImportInstancesDialog({ open, onOpenChange, refresh, notify, targetVersion = "1.21.11", availableVersions = [] }) {
  const t = useT();
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
      const res = await api()?.importExternalInstance({ ...entry, migrateToRiver: true, preferSourceVersion: true, targetVersion });
      if (res && res.ok === false) notify(res.message || t("Import failed."), "error");
      else if (res) {
        notify(res.warning ? `${res.message} ${res.warning}` : res.message || t("Imported {name}.", { name: entry.name }), "info");
        await refresh();
        onOpenChange(false);
      }
    } catch (e) {
      notify(String(e?.message || e), "error");
    } finally {
      setImportingKey("");
    }
  };

  const browseFolder = async () => {
    setImportingKey("folder");
    try {
      const res = await api()?.migrateFolder(targetVersion);
      if (res?.cancelled) return;
      if (res?.ok === false) notify(res.message || t("Import failed."), "error");
      else if (res) {
        notify(res.warning ? `${res.message} ${res.warning}` : res.message, "info");
        await refresh();
        onOpenChange(false);
      }
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
      if (res && res.ok === false) notify(res.message || t("Import failed."), "error");
      else if (res) { notify(res.message || t("Imported."), "info"); await refresh(); onOpenChange(false); }
    } catch (e) {
      notify(String(e?.message || e), "error");
    } finally {
      setImportingKey("");
    }
  };

  // Profiles whose mods come across first, then grouped by the launcher they came from.
  const rows = detected.map((entry) => {
    const version = availableVersions.includes(entry.version) ? entry.version : targetVersion;
    const copyMods = entry.version === version && entry.loader === "fabric" && !/^(feather|lunar client|badlion)$/i.test(entry.launcher);
    return { entry, version, copyMods };
  });
  const groups = [];
  for (const row of [...rows].sort((a, b) => Number(b.copyMods) - Number(a.copyMods))) {
    const name = row.entry.launcher || t("Other");
    let group = groups.find((g) => g.name === name);
    if (!group) groups.push(group = { name, rows: [] });
    group.rows.push(row);
  }

  const spinOr = (key, Icon) => importingKey === key ? <Loader2 className="size-3.5 animate-spin" /> : <Icon className="size-3.5" />;

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-lg gap-0 p-0">
        <DialogHeader className="px-6 pb-4 pt-6">
          <DialogTitle>{t("Switch to River")}</DialogTitle>
          <DialogDescription>
            {t("Pick where you played before. Your old files are never changed.")}
          </DialogDescription>
        </DialogHeader>

        <div className="max-h-[min(22rem,50vh)] overflow-y-auto border-y border-border px-6 py-4">
          {scanning ? (
            <div className="flex h-24 items-center justify-center text-xs text-muted-foreground">
              <Loader2 className="mr-2 size-4 animate-spin" />{t("Scanning for launchers…")}
            </div>
          ) : groups.length ? (
            <div className="space-y-4">
              {groups.map((group) => (
                <section key={group.name}>
                  <h3 className="mb-1.5 text-[11px] font-semibold uppercase tracking-wide text-muted-foreground">
                    {group.name} <span className="font-normal">· {group.rows.length}</span>
                  </h3>
                  <div className="divide-y divide-border overflow-hidden rounded-md border border-border bg-card">
                    {group.rows.map(({ entry, version, copyMods }) => (
                      <div key={entry.gameDir} className="flex items-center gap-3 px-3 py-2.5">
                        <div className="min-w-0 flex-1">
                          <div className="flex items-center gap-2">
                            <span className="truncate text-sm font-medium" title={entry.gameDir}>{entry.name}</span>
                            {entry.version ? (
                              <span className="shrink-0 text-[11px] text-muted-foreground">
                                {[entry.loader, entry.version].filter(Boolean).join(" ")}
                              </span>
                            ) : null}
                          </div>
                          <div className={`mt-0.5 flex items-center gap-1 text-[11px] ${copyMods ? "text-success" : "text-muted-foreground"}`}>
                            {copyMods ? <Check className="size-3 shrink-0" /> : <Info className="size-3 shrink-0" />}
                            <span className="truncate">
                              {copyMods
                                ? t("Everything, mods included · {version}", { version })
                                : t("Everything except mods · plays on {version}", { version })}
                            </span>
                          </div>
                        </div>
                        <Button size="sm" variant={copyMods ? "default" : "secondary"} disabled={Boolean(importingKey)} onClick={() => importOne(entry)}>
                          {spinOr(entry.gameDir, Download)}
                          {t("Switch")}
                        </Button>
                      </div>
                    ))}
                  </div>
                </section>
              ))}
              <p className="text-[11px] leading-relaxed text-muted-foreground">
                {t("Worlds, settings, controls, hotbars, servers and packs always come across. Mods only do when they were made for the same Fabric version, so the game can't break.")}
              </p>
            </div>
          ) : (
            <div className="rounded-md border border-dashed border-border py-8 text-center text-xs text-muted-foreground">
              {t("No profiles found automatically. Choose your game folder below.")}
            </div>
          )}
        </div>

        <div className="px-6 py-4">
          <div className="mb-2 text-[11px] font-semibold uppercase tracking-wide text-muted-foreground">{t("Not in the list?")}</div>
          <div className="grid grid-cols-3 gap-2">
            <Button variant="outline" size="sm" disabled={Boolean(importingKey)} onClick={browseFolder}>
              {spinOr("folder", FolderInput)}{t("Game folder")}
            </Button>
            <Button variant="outline" size="sm" disabled={Boolean(importingKey)} onClick={() => importFile("modpack")}>
              {spinOr("modpack", FileArchive)}{t(".mrpack / .zip")}
            </Button>
            <Button variant="outline" size="sm" disabled={Boolean(importingKey)} onClick={() => importFile("rvr")}>
              {spinOr("rvr", Package)}{t(".rvr file")}
            </Button>
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
}

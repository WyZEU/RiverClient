import React, { useCallback, useEffect, useState } from "react";
import {
  ArrowLeft, Blocks, Boxes, Copy, FolderOpen, Globe, Image, Loader2, Package,
  Pencil, Play, RefreshCw, ScrollText, Sparkles, TriangleAlert, Trash2, Upload, Wrench
} from "lucide-react";
import { api } from "../lib/useStatus";
import { cn } from "../lib/utils";
import { Button } from "../components/ui/button";
import { Badge } from "../components/ui/badge";
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle
} from "../components/ui/dialog";
import { ContentBrowser } from "../components/content-browser";
import { ChangeVersionDialog } from "../components/change-version";

const TABS = [
  { id: "mods", label: "Mods", icon: Package, key: "mods" },
  { id: "resourcepacks", label: "Resource packs", icon: Image, key: "resourcepacks" },
  { id: "shaders", label: "Shaders", icon: Sparkles, key: "shaders" },
  { id: "worlds", label: "Worlds", icon: Globe, key: "worlds" }
];

const FOLDERS = [
  { sub: "", label: "Instance folder", icon: FolderOpen },
  { sub: "saves", label: "Saves", icon: Globe },
  { sub: "config", label: "Config", icon: Wrench },
  { sub: "screenshots", label: "Screenshots", icon: Image },
  { sub: "logs", label: "Logs", icon: ScrollText }
];

function formatSize(bytes) {
  const n = Number(bytes) || 0;
  if (n >= 1024 * 1024 * 1024) return `${(n / 1073741824).toFixed(1)} GB`;
  if (n >= 1024 * 1024) return `${Math.round(n / 1048576)} MB`;
  if (n > 0) return `${Math.max(1, Math.round(n / 1024))} KB`;
  return "empty";
}

function formatWhen(ms) {
  const t = Number(ms) || 0;
  if (!t) return "never played";
  const days = Math.floor((Date.now() - t) / 86400000);
  if (days <= 0) return "played today";
  if (days === 1) return "played yesterday";
  if (days < 30) return `played ${days}d ago`;
  return `played ${new Date(t).toLocaleDateString()}`;
}

/** Display name for an installed file, preferring real metadata over the raw filename. */
function contentName(entry) {
  const meta = entry.metadata || {};
  if (meta.title) return meta.title;
  if (meta.name) return meta.name;
  return String(entry.file || "").replace(/\.(jar|zip)(\.disabled)?$/i, "");
}

function ContentRow({ entry, kind, onToggle, onRemove, busy }) {
  const meta = entry.metadata || {};
  const disabled = Boolean(entry.disabled);
  const update = entry.update && entry.update.available ? entry.update : null;
  const conflicts = Array.isArray(entry.conflicts) ? entry.conflicts : [];

  return (
    <div className={cn(
      "flex items-center gap-3 rounded-md border px-3 py-2.5",
      disabled ? "border-border bg-card/50 opacity-60" : "border-border bg-card"
    )}>
      <div className="flex size-9 shrink-0 items-center justify-center overflow-hidden rounded-md bg-accent">
        {meta.iconUrl
          ? <img src={meta.iconUrl} alt="" className="size-full object-cover" />
          : <Package className="size-4 text-muted-foreground" />}
      </div>

      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-2">
          <span className="truncate text-sm font-medium">{contentName(entry)}</span>
          {entry.required ? <Badge variant="secondary">Required by River</Badge> : null}
          {disabled ? <Badge variant="outline">Disabled</Badge> : null}
          {update ? <Badge variant="success">Update available</Badge> : null}
        </div>
        <div className="truncate text-[11px] text-muted-foreground">{entry.file}</div>
        {conflicts.length ? (
          <div className="mt-1 flex items-start gap-1.5 text-[11px] text-warning">
            <TriangleAlert className="mt-px size-3 shrink-0" />
            <span>{conflicts.join(", ")}</span>
          </div>
        ) : null}
      </div>

      <div className="flex shrink-0 items-center gap-1">
        {/* River's own mod is what makes the instance a River instance, so it is never
            togglable or removable from here - repairing re-adds it anyway. */}
        {kind === "mods" && !entry.required ? (
          <Button variant="ghost" size="sm" disabled={busy} onClick={() => onToggle(entry)}>
            {disabled ? "Enable" : "Disable"}
          </Button>
        ) : null}
        {!entry.required ? (
          <Button variant="ghost" size="icon" title="Remove" disabled={busy} onClick={() => onRemove(entry)}>
            <Trash2 className="size-3.5 text-destructive" />
          </Button>
        ) : null}
      </div>
    </div>
  );
}

function WorldRow({ world, onOpen, onDelete, busy }) {
  return (
    <div className="flex items-center gap-3 rounded-md border border-border bg-card px-3 py-2.5">
      <div className="flex size-9 shrink-0 items-center justify-center overflow-hidden rounded-md bg-accent">
        {world.icon
          ? <img src={world.icon} alt="" className="size-full object-cover" />
          : <Globe className="size-4 text-muted-foreground" />}
      </div>
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-2">
          <span className="truncate text-sm font-medium">{world.name}</span>
          {world.hardcore ? <Badge variant="destructive">Hardcore</Badge> : null}
          {world.gameMode ? <Badge variant="outline">{world.gameMode}</Badge> : null}
        </div>
        <div className="truncate text-[11px] text-muted-foreground">
          {formatWhen(world.lastPlayed)} · {formatSize(world.sizeBytes)}
          {world.folder !== world.name ? ` · ${world.folder}` : ""}
        </div>
      </div>
      <div className="flex shrink-0 items-center gap-1">
        <Button variant="ghost" size="icon" title="Open folder" onClick={() => onOpen(world)}>
          <FolderOpen className="size-3.5" />
        </Button>
        <Button variant="ghost" size="icon" title="Delete world" disabled={busy} onClick={() => onDelete(world)}>
          <Trash2 className="size-3.5 text-destructive" />
        </Button>
      </div>
    </div>
  );
}

/**
 * One instance, in full: what is installed, what worlds it has, and every action that used
 * to require digging through the folder by hand.
 */
export function InstanceView({ instanceId, status, refresh, notify, onBack, onRename, onDuplicate, onExport, onDelete }) {
  const [details, setDetails] = useState(null);
  const [loading, setLoading] = useState(true);
  const [tab, setTab] = useState("mods");
  const [busy, setBusy] = useState(false);
  const [addOpen, setAddOpen] = useState(false);
  const [confirmWorld, setConfirmWorld] = useState(null);
  const [versionOpen, setVersionOpen] = useState(false);

  const load = useCallback(async () => {
    if (!instanceId) return;
    try {
      const res = await api()?.getInstanceDetails({ instanceId });
      if (res && res.ok === false) { notify(res.message || "Could not read that instance.", "error"); setDetails(null); }
      else setDetails(res || null);
    } catch (e) {
      notify(String(e?.message || e), "error");
    } finally {
      setLoading(false);
    }
  }, [instanceId, notify]);

  useEffect(() => { setLoading(true); load(); }, [load]);

  const instance = details?.instance;

  const act = async (fn, successMessage) => {
    setBusy(true);
    try {
      const res = await fn();
      if (res && res.ok === false) notify(res.message || "That didn't work.", "error");
      else if (successMessage || res?.message) notify(successMessage || res.message, "info");
      await load();
      await refresh();
    } catch (e) {
      notify(String(e?.message || e), "error");
    } finally {
      setBusy(false);
    }
  };

  // Content actions target the selected instance in the backend, so make sure this one is
  // selected before mutating it - otherwise a toggle would silently hit another instance.
  const ensureSelected = async () => {
    if (details?.selected) return;
    await api()?.selectInstance(instanceId);
    await refresh();
  };

  const toggleMod = async (entry) => {
    await ensureSelected();
    await act(() => api()?.setModEnabled({ file: entry.file }, Boolean(entry.disabled)));
  };

  const removeEntry = async (entry) => {
    await ensureSelected();
    await act(() => (tab === "mods"
      ? api()?.removeMod({ file: entry.file })
      : api()?.removeContent({ file: entry.file, contentType: tab === "shaders" ? "shader" : "resourcepack" })));
  };

  const play = async () => {
    await ensureSelected();
    await api()?.launchClient({});
    notify("Launching…", "info");
  };

  if (loading) {
    return (
      <div className="flex h-full items-center justify-center text-xs text-muted-foreground">
        <Loader2 className="mr-2 size-4 animate-spin" />Reading instance…
      </div>
    );
  }

  if (!instance) {
    return (
      <div className="flex h-full flex-col items-center justify-center gap-3 text-xs text-muted-foreground">
        That instance could not be opened.
        <Button size="sm" variant="outline" onClick={onBack}><ArrowLeft className="size-3.5" />Back</Button>
      </div>
    );
  }

  const list = tab === "worlds" ? (details.worlds || []) : (details[tab] || []);

  return (
    <div className="flex h-full flex-col overflow-hidden">
      <header className="border-b border-border px-6 pb-4 pt-5">
        <button onClick={onBack} className="mb-2 flex items-center gap-1.5 text-xs text-muted-foreground transition-colors hover:text-foreground">
          <ArrowLeft className="size-3.5" />All instances
        </button>

        <div className="flex flex-wrap items-start justify-between gap-3">
          <div className="min-w-0">
            <div className="flex flex-wrap items-center gap-2">
              <h1 className="truncate text-lg font-semibold">{instance.name}</h1>
              <button
                type="button"
                onClick={() => setVersionOpen(true)}
                title="Change Minecraft version"
                className="rounded-full border border-border px-2.5 py-0.5 text-xs text-muted-foreground transition-colors hover:border-primary/50 hover:text-foreground"
              >
                {[instance.loader, instance.version].filter(Boolean).join(" ") || "unknown version"}
                <RefreshCw className="ml-1.5 inline size-3" />
              </button>
              {details.selected ? <Badge variant="success">Selected</Badge> : null}
            </div>
            {instance.riverSupported === false ? (
              <div className="mt-1.5 flex items-start gap-1.5 text-[11px] leading-relaxed text-warning">
                <TriangleAlert className="mt-px size-3 shrink-0" />
                <span>{instance.riverWarning || "River will not load in game on this instance."}</span>
              </div>
            ) : null}
          </div>

          <div className="flex flex-wrap items-center gap-2">
            <Button size="sm" onClick={play} disabled={busy}><Play className="size-3.5" />Play</Button>
            <Button size="sm" variant="outline" onClick={() => setAddOpen(true)}>
              <Blocks className="size-3.5" />Add content
            </Button>
            <Button size="sm" variant="outline" disabled={busy}
              onClick={() => act(() => api()?.repairInstance({ instanceId }), "Repair finished.")}>
              <Wrench className="size-3.5" />Repair
            </Button>
          </div>
        </div>

        <div className="mt-3 flex flex-wrap items-center gap-1">
          {FOLDERS.map((folder) => {
            const Icon = folder.icon;
            return (
              <Button key={folder.sub || "root"} variant="ghost" size="sm"
                onClick={() => api()?.openInstancePath({ instanceId, sub: folder.sub })}>
                <Icon className="size-3.5" />{folder.label}
              </Button>
            );
          })}
          <span className="mx-1 h-4 w-px bg-border" />
          <Button variant="ghost" size="sm" onClick={() => onRename?.(instance)}><Pencil className="size-3.5" />Rename</Button>
          <Button variant="ghost" size="sm" onClick={() => onDuplicate?.(instance)}><Copy className="size-3.5" />Duplicate</Button>
          <Button variant="ghost" size="sm" onClick={() => onExport?.(instance)}><Upload className="size-3.5" />Export</Button>
          <Button variant="ghost" size="sm" onClick={() => onDelete?.(instance)}>
            <Trash2 className="size-3.5 text-destructive" />Delete
          </Button>
        </div>
      </header>

      <div className="flex items-center gap-1 border-b border-border px-6 py-2">
        {TABS.map((entry) => {
          const Icon = entry.icon;
          const count = (entry.key === "worlds" ? details.worlds : details[entry.key])?.length || 0;
          return (
            <button
              key={entry.id}
              onClick={() => setTab(entry.id)}
              className={cn(
                "flex items-center gap-2 rounded-md px-3 py-1.5 text-xs font-medium transition-colors",
                tab === entry.id ? "bg-accent text-foreground" : "text-muted-foreground hover:bg-accent/50"
              )}
            >
              <Icon className="size-3.5" />
              {entry.label}
              <span className="text-muted-foreground/70">{count}</span>
            </button>
          );
        })}
        <Button variant="ghost" size="sm" className="ml-auto" disabled={busy} onClick={load}>
          <RefreshCw className="size-3.5" />Refresh
        </Button>
      </div>

      <div className="min-h-0 flex-1 space-y-2 overflow-y-auto px-6 py-4">
        {list.length ? (
          tab === "worlds"
            ? list.map((world) => (
                <WorldRow key={world.folder} world={world} busy={busy}
                  onOpen={() => api()?.openInstancePath({ instanceId, sub: "saves" })}
                  onDelete={setConfirmWorld} />
              ))
            : list.map((entry) => (
                <ContentRow key={entry.file} entry={entry} kind={tab} busy={busy}
                  onToggle={toggleMod} onRemove={removeEntry} />
              ))
        ) : (
          <div className="rounded-md border border-dashed border-border py-10 text-center text-xs text-muted-foreground">
            {tab === "worlds" ? "No worlds in this instance yet." : `No ${TABS.find((t) => t.id === tab)?.label.toLowerCase()} installed.`}
          </div>
        )}
      </div>

      <Dialog open={addOpen} onOpenChange={(open) => { setAddOpen(open); if (!open) load(); }}>
        <DialogContent className="flex h-[80vh] max-w-3xl flex-col">
          <DialogHeader>
            <DialogTitle>Add content to {instance.name}</DialogTitle>
            <DialogDescription>Browse Modrinth and CurseForge for mods, resource packs and shaders.</DialogDescription>
          </DialogHeader>
          <div className="min-h-0 flex-1">
            <ContentBrowser status={status} refresh={refresh} notify={notify} />
          </div>
        </DialogContent>
      </Dialog>

      <ChangeVersionDialog
        open={versionOpen}
        onOpenChange={setVersionOpen}
        instance={instance}
        versions={status?.versions}
        notify={notify}
        onDone={() => { load(); refresh(); }}
      />

      <Dialog open={Boolean(confirmWorld)} onOpenChange={(open) => !open && setConfirmWorld(null)}>
        <DialogContent className="max-w-sm">
          <DialogHeader>
            <DialogTitle>Delete {confirmWorld?.name}?</DialogTitle>
            <DialogDescription>
              This permanently deletes the world folder and everything in it
              ({formatSize(confirmWorld?.sizeBytes)}). It cannot be undone.
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="ghost" onClick={() => setConfirmWorld(null)}>Cancel</Button>
            <Button variant="destructive" onClick={() => {
              const folder = confirmWorld.folder;
              setConfirmWorld(null);
              act(() => api()?.deleteWorld({ instanceId, folder }));
            }}>Delete</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

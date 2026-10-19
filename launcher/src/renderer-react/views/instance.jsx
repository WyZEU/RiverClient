import React, { useCallback, useEffect, useState } from "react";
import {
  ArrowLeft, Blocks, Boxes, Copy, FolderOpen, Globe, Image, Loader2, Package,
  Pencil, Play, RefreshCw, ScrollText, Search, Sparkles, TriangleAlert, Trash2, Upload, Wrench
} from "lucide-react";
import { api } from "../lib/useStatus";
import { cn } from "../lib/utils";
import { Button } from "../components/ui/button";
import { Badge } from "../components/ui/badge";
import { Switch } from "../components/ui/switch";
import { Input } from "../components/ui/input";
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle
} from "../components/ui/dialog";
import { ContentDialog } from "../components/content-browser";
import { ChangeVersionDialog } from "../components/change-version";
import { RenameInstanceDialog } from "./instances";
import { InstanceArt, PlayBlock, useInstanceCards } from "../components/instance-art";
import { instanceVersionLabel, isPlainInstance } from "../components/instance-card";
import { useModern } from "../lib/ui-style";
import { useSeasonId } from "../lib/seasons";
import { CornerWeb } from "../components/modern-season";
import { useT } from "../i18n/context.jsx";

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

function formatSize(bytes, t) {
  const n = Number(bytes) || 0;
  if (n >= 1024 * 1024 * 1024) return `${(n / 1073741824).toFixed(1)} GB`;
  if (n >= 1024 * 1024) return `${Math.round(n / 1048576)} MB`;
  if (n > 0) return `${Math.max(1, Math.round(n / 1024))} KB`;
  return t("empty");
}

function formatWhen(ms, t) {
  const when = Number(ms) || 0;
  if (!when) return t("never played");
  const days = Math.floor((Date.now() - when) / 86400000);
  if (days <= 0) return t("played today");
  if (days === 1) return t("played yesterday");
  if (days < 30) return t("played {days}d ago", { days });
  return t("played {date}", { date: new Date(when).toLocaleDateString() });
}

/** "Today", "Yesterday", "5d ago" or a date, for the Modern page's Last played tile. */
function sinceLabel(ms, t) {
  const when = Number(ms) || 0;
  if (!when) return t("Never");
  const days = Math.floor((Date.now() - when) / 86400000);
  if (days <= 0) return t("Today");
  if (days === 1) return t("Yesterday");
  if (days < 30) return t("{days}d ago", { days });
  return new Date(when).toLocaleDateString();
}

/** Display name for an installed file, preferring real metadata over the raw filename. */
function contentName(entry) {
  const meta = entry.metadata || {};
  if (meta.title) return meta.title;
  if (meta.name) return meta.name;
  return String(entry.file || "").replace(/\.(jar|zip)(\.disabled)?$/i, "");
}

function ContentRow({ entry, kind, onToggle, onRemove, busy }) {
  const t = useT();
  const modern = useModern();
  const meta = entry.metadata || {};
  const disabled = Boolean(entry.disabled);
  const update = entry.update && entry.update.available ? entry.update : null;
  const conflicts = Array.isArray(entry.conflicts) ? entry.conflicts : [];

  return (
    <div className={cn(
      "flex items-center gap-3",
      // Modern: a row in one list panel, divided from its neighbours, rather than its own card.
      modern ? "px-4 py-3 transition-colors hover:bg-accent/40" : "rounded-md border border-border px-3 py-2.5",
      disabled ? (modern ? "opacity-60" : "bg-card/50 opacity-60") : (modern ? "" : "bg-card")
    )}>
      <div className={cn("flex shrink-0 items-center justify-center overflow-hidden bg-accent", modern ? "size-10 rounded-lg" : "size-9 rounded-md")}>
        {meta.iconUrl
          ? <img src={meta.iconUrl} alt="" className="size-full object-cover" />
          : <Package className="size-4 text-muted-foreground" />}
      </div>

      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-2">
          <span className={cn("truncate text-sm", modern ? "font-bold" : "font-medium")}>{contentName(entry)}</span>
          {entry.required ? <Badge variant="secondary">{t("Required by River")}</Badge> : null}
          {disabled ? <Badge variant="outline">{t("Disabled")}</Badge> : null}
          {update ? <Badge variant="success">{t("Update available")}</Badge> : null}
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
          modern ? (
            <Switch
              checked={!disabled}
              disabled={busy}
              onCheckedChange={() => onToggle(entry)}
              title={disabled ? t("Enable") : t("Disable")}
              aria-label={disabled ? t("Enable") : t("Disable")}
            />
          ) : (
            <Button variant="ghost" size="sm" disabled={busy} onClick={() => onToggle(entry)}>
              {disabled ? t("Enable") : t("Disable")}
            </Button>
          )
        ) : null}
        {!entry.required ? (
          <Button variant="ghost" size="icon" title={t("Remove")} disabled={busy} onClick={() => onRemove(entry)}>
            <Trash2 className="size-3.5 text-destructive" />
          </Button>
        ) : null}
      </div>
    </div>
  );
}

function WorldRow({ world, onOpen, onDelete, busy }) {
  const t = useT();
  const modern = useModern();
  return (
    <div className={cn("flex items-center gap-3", modern ? "px-4 py-3 transition-colors hover:bg-accent/40" : "rounded-md border border-border bg-card px-3 py-2.5")}>
      <div className={cn("flex shrink-0 items-center justify-center overflow-hidden bg-accent", modern ? "size-12 rounded-lg" : "size-9 rounded-md")}>
        {world.icon
          ? <img src={world.icon} alt="" className={cn("size-full object-cover", modern && "[image-rendering:pixelated]")} />
          : <Globe className="size-4 text-muted-foreground" />}
      </div>
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-2">
          <span className={cn("truncate text-sm", modern ? "font-bold" : "font-medium")}>{world.name}</span>
          {world.hardcore ? <Badge variant="destructive">{t("Hardcore")}</Badge> : null}
          {world.gameMode ? <Badge variant="outline">{world.gameMode}</Badge> : null}
        </div>
        <div className="truncate text-[11px] text-muted-foreground">
          {formatWhen(world.lastPlayed, t)} · {formatSize(world.sizeBytes, t)}
          {world.folder !== world.name ? ` · ${world.folder}` : ""}
        </div>
      </div>
      <div className="flex shrink-0 items-center gap-1">
        <Button variant="ghost" size="icon" title={t("Open folder")} onClick={() => onOpen(world)}>
          <FolderOpen className="size-3.5" />
        </Button>
        <Button variant="ghost" size="icon" title={t("Delete world")} disabled={busy} onClick={() => onDelete(world)}>
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
/**
 * Copies this instance's mods into another one. Main swaps each mod for the build made for
 * the other instance's Minecraft version and loader, so they still load there.
 */
function CopyModsDialog({ open, onOpenChange, instance, instances, notify, refresh }) {
  const t = useT();
  const targets = (instances || []).filter((item) => item.id !== instance?.id && String(item.loader || "fabric") !== "vanilla");
  const [targetId, setTargetId] = useState("");
  const [busy, setBusy] = useState(false);
  useEffect(() => { if (open) setTargetId(targets[0]?.id || ""); }, [open]); // eslint-disable-line react-hooks/exhaustive-deps
  const target = targets.find((item) => item.id === targetId);

  const copy = async () => {
    if (!target) return;
    setBusy(true);
    try {
      const res = await api()?.transferMods({ fromInstanceId: instance.id, toInstanceId: target.id });
      if (!res) return;
      if (res.ok === false) notify(res.message || t("Could not copy the mods."), "error");
      else {
        notify(res.warning ? `${res.message} ${res.warning}` : res.message, res.warning ? "error" : "info");
        onOpenChange(false);
      }
      await refresh?.();
    } catch (e) {
      notify(String(e?.message || e), "error");
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={(next) => { if (!busy) onOpenChange(next); }}>
      <DialogContent className="max-w-md">
        <DialogHeader>
          <DialogTitle>{t("Copy mods to another instance")}</DialogTitle>
          <DialogDescription>
            {t("Each mod is swapped for the build made for that instance's Minecraft version and loader, dependencies included.")}
          </DialogDescription>
        </DialogHeader>
        {targets.length ? (
          <div className="max-h-64 space-y-1.5 overflow-y-auto" role="radiogroup" aria-label={t("Copy to")}>
            {targets.map((item) => {
              const on = item.id === targetId;
              return (
                <button
                  key={item.id}
                  type="button"
                  role="radio"
                  aria-checked={on}
                  onClick={() => setTargetId(item.id)}
                  className={cn(
                    "flex w-full items-center justify-between gap-3 rounded-md border px-3 py-2.5 text-left text-sm transition-colors",
                    on ? "border-primary/60 bg-primary/10" : "border-border hover:bg-accent/50"
                  )}
                >
                  <span className="truncate font-medium">{item.name}</span>
                  <span className="shrink-0 text-xs text-muted-foreground">{instanceVersionLabel(item)}</span>
                </button>
              );
            })}
          </div>
        ) : (
          <p className="text-sm text-muted-foreground">{t("Make another instance first, then copy the mods into it.")}</p>
        )}
        <DialogFooter>
          <Button variant="ghost" onClick={() => onOpenChange(false)} disabled={busy}>{t("Cancel")}</Button>
          <Button onClick={copy} disabled={busy || !target}>
            {busy ? <Loader2 className="size-4 animate-spin" /> : <Copy className="size-4" />}
            {t("Copy mods")}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

export function InstanceView({ instanceId, status, refresh, notify, onBack }) {
  const t = useT();
  const modern = useModern();
  const halloween = useSeasonId() === "halloween";
  // Modern draws the instance's picture behind the header; Classic does not need it.
  const cards = useInstanceCards(status?.instances, modern);
  const [details, setDetails] = useState(null);
  const [loading, setLoading] = useState(true);
  const [pickedTab, setTab] = useState("mods");
  const [busy, setBusy] = useState(false);
  const [addOpen, setAddOpen] = useState(false);
  const [copyOpen, setCopyOpen] = useState(false);
  const [confirmWorld, setConfirmWorld] = useState(null);
  const [versionOpen, setVersionOpen] = useState(false);
  const [renaming, setRenaming] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);
  // Modern only: narrows the list on the right, which runs to a hundred mods on a real setup.
  const [query, setQuery] = useState("");

  const load = useCallback(async () => {
    if (!instanceId) return;
    try {
      const res = await api()?.getInstanceDetails({ instanceId });
      if (res && res.ok === false) { notify(res.message || t("Could not read that instance."), "error"); setDetails(null); }
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
      if (res && res.ok === false) notify(res.message || t("That didn't work."), "error");
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

  /*
    Rename, Duplicate, Export and Delete. These buttons used to call handlers the page was
    never given, so all four did nothing. They now do here what the same actions do on the
    Instances page, through the same backend calls.
  */
  const duplicate = () => act(() => api()?.duplicateInstance({ instanceId }));

  const exportInstance = async () => {
    try {
      const res = await api()?.exportInstance(instanceId);
      if (res?.ok === false) {
        // A cancelled save dialog is a normal outcome, not an error.
        if (res.message && res.message !== "Cancelled.") notify(res.message, "error");
      } else {
        notify(res?.message || t("Exported instance."), "info");
      }
    } catch (e) {
      notify(String(e?.message || e), "error");
    }
  };

  const remove = async () => {
    setConfirmDelete(false);
    setBusy(true);
    try {
      const res = await api()?.deleteInstance({ instanceId, deleteFiles: true });
      if (res && res.ok === false) {
        notify(res.message || t("Could not delete the instance."), "error");
        return;
      }
      notify(res?.message || t("Instance deleted."), "info");
      await refresh();
      onBack();
    } catch (e) {
      notify(String(e?.message || e), "error");
    } finally {
      setBusy(false);
    }
  };

  const play = async () => {
    await ensureSelected();
    await api()?.launchClient({});
    notify(t("Launching…"), "info");
  };

  if (loading) {
    return (
      <div className="flex h-full items-center justify-center text-xs text-muted-foreground">
        <Loader2 className="mr-2 size-4 animate-spin" />{t("Reading instance…")}
      </div>
    );
  }

  if (!instance) {
    return (
      <div className="flex h-full flex-col items-center justify-center gap-3 text-xs text-muted-foreground">
        {t("That instance could not be opened.")}
        <Button size="sm" variant="outline" onClick={onBack}><ArrowLeft className="size-3.5" />{t("Back")}</Button>
      </div>
    );
  }

  // Forge and vanilla run without River on purpose, and vanilla has no mods or shaders.
  const plainInstance = isPlainInstance(instance);
  const tabs = instance.loader === "vanilla" ? TABS.filter((entry) => entry.id === "resourcepacks" || entry.id === "worlds") : TABS;
  const tab = tabs.some((entry) => entry.id === pickedTab) ? pickedTab : tabs[0].id;
  const list = tab === "worlds" ? (details.worlds || []) : (details[tab] || []);
  const repair = () => act(() => api()?.repairInstance({ instanceId }), t("Repair finished."));
  const emptyText = tab === "worlds"
    ? t("No worlds in this instance yet.")
    : t("No {kind} installed.", { kind: TABS.find((entry) => entry.id === tab)?.label.toLowerCase() });

  const dialogs = (
    <>
      <CopyModsDialog
        open={copyOpen}
        onOpenChange={setCopyOpen}
        instance={instance}
        instances={status?.instances}
        notify={notify}
        refresh={refresh}
      />
      <ContentDialog
        open={addOpen}
        onOpenChange={(open) => { setAddOpen(open); if (!open) load(); }}
        title={t("Add content to {name}", { name: instance.name })}
        description={t("Mods, resource packs and shaders from Modrinth.")}
        status={status}
        refresh={refresh}
        notify={notify}
      />

      <ChangeVersionDialog
        open={versionOpen}
        onOpenChange={setVersionOpen}
        instance={instance}
        versions={status?.versions}
        notify={notify}
        onDone={() => { load(); refresh(); }}
      />

      <RenameInstanceDialog
        instance={renaming ? instance : null}
        onOpenChange={(open) => { if (!open) setRenaming(false); }}
        onRenamed={() => { load(); refresh(); }}
        notify={notify}
      />

      <Dialog open={confirmDelete} onOpenChange={setConfirmDelete}>
        <DialogContent className="max-w-sm">
          <DialogHeader>
            <DialogTitle>{t("Delete {name}?", { name: instance.name })}</DialogTitle>
            <DialogDescription>
              {t("This permanently removes the instance folder, including its worlds and mods. It cannot be undone.")}
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="ghost" onClick={() => setConfirmDelete(false)}>{t("Cancel")}</Button>
            <Button variant="destructive" onClick={remove}>{t("Delete")}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={Boolean(confirmWorld)} onOpenChange={(open) => !open && setConfirmWorld(null)}>
        <DialogContent className="max-w-sm">
          <DialogHeader>
            <DialogTitle>{t("Delete {name}?", { name: confirmWorld?.name })}</DialogTitle>
            <DialogDescription>
              {t("This permanently deletes the world folder and everything in it ({size}). It cannot be undone.", { size: formatSize(confirmWorld?.sizeBytes, t) })}
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="ghost" onClick={() => setConfirmWorld(null)}>{t("Cancel")}</Button>
            <Button variant="destructive" onClick={() => {
              const folder = confirmWorld.folder;
              setConfirmWorld(null);
              act(() => api()?.deleteWorld({ instanceId, folder }));
            }}>{t("Delete")}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );

  /*
    Modern: two columns. The instance itself down the left (its picture, Play, a few facts,
    its folders and everything you can do to it), and what is inside it on the right, with a
    filter box, since a real instance runs to a hundred mods.
  */
  if (modern) {
    const needle = query.trim().toLowerCase();
    const shown = needle
      ? list.filter((item) => (tab === "worlds"
        ? `${item.name} ${item.folder}`
        : `${contentName(item)} ${item.file}`).toLowerCase().includes(needle))
      : list;
    const kind = t(TABS.find((entry) => entry.id === tab)?.label || "").toLowerCase();
    const created = Date.parse(instance.createdAt || "") || 0;

    const sideButton = (key, Icon, label, onClick, danger = false) => (
      <button
        key={key}
        type="button"
        disabled={busy}
        onClick={onClick}
        className={cn(
          "flex w-full items-center gap-3 rounded-lg px-2.5 py-2 text-left text-[13px] font-semibold transition-colors disabled:opacity-50",
          danger ? "text-destructive hover:bg-destructive/10" : "text-muted-foreground hover:bg-accent hover:text-foreground"
        )}
      >
        <Icon className="size-4 shrink-0" />
        {label}
      </button>
    );

    return (
      <div className="flex h-full min-h-0 gap-6 px-8 pb-6 pt-3">
        {/* A plain block, not a flex column: in a column the card (which clips its picture)
            was the one item allowed to shrink, and it shrank to nothing but the picture. */}
        <aside className="w-[260px] shrink-0 space-y-4 overflow-y-auto pb-1 pr-1 xl:w-[290px]">
          <button
            type="button"
            onClick={onBack}
            className="inline-flex items-center gap-1.5 text-xs font-semibold text-muted-foreground transition-colors hover:text-foreground"
          >
            <ArrowLeft className="size-3.5" />{t("All instances")}
          </button>

          <div className="overflow-hidden rounded-xl border border-border/60 bg-card">
            <div className="relative h-36">
              <InstanceArt instanceId={instance.id} card={cards[instance.id]} />
              <div className="absolute inset-0 bg-gradient-to-t from-card via-card/10 to-transparent" />
              {halloween ? <CornerWeb corner="left" size={88} className="opacity-35" /> : null}
              {details.selected ? (
                <span className="absolute right-4 top-4 rounded-md bg-primary px-2 py-1 text-[11px] font-semibold text-primary-foreground">
                  {t("Selected")}
                </span>
              ) : null}
            </div>
            <div className="space-y-4 px-4 pb-4">
              <div className="min-w-0">
                <h1 className="truncate text-[22px] font-extrabold leading-tight tracking-[-0.02em]" title={instance.name}>
                  {instance.name}
                </h1>
                <button
                  type="button"
                  onClick={() => setVersionOpen(true)}
                  disabled={plainInstance}
                  title={plainInstance ? undefined : t("Change Minecraft version")}
                  className="mt-2 inline-flex items-center gap-1.5 rounded-md border border-border/70 px-2 py-1 text-xs font-semibold text-muted-foreground transition-colors hover:border-primary/50 hover:text-foreground"
                >
                  {instanceVersionLabel(instance) || t("unknown version")}
                  {plainInstance ? null : <RefreshCw className="size-3" />}
                </button>
                {plainInstance ? (
                  <p className="mt-2 text-[11px] text-muted-foreground">{t("Without River in game")}</p>
                ) : instance.riverSupported === false ? (
                  <div className="mt-2 flex items-start gap-1.5 text-[11px] leading-relaxed text-warning">
                    <TriangleAlert className="mt-px size-3 shrink-0" />
                    <span>{instance.riverWarning || t("River will not load in game on this instance.")}</span>
                  </div>
                ) : null}
              </div>
              <PlayBlock size="lg" className="w-full min-w-0" onClick={play} disabled={busy}>{t("Play")}</PlayBlock>
              <div className="grid grid-cols-2 gap-2">
                <Button variant="outline" onClick={() => setAddOpen(true)}>
                  <Blocks className="size-4" />{t("Add content")}
                </Button>
                <Button variant="outline" disabled={busy} onClick={repair}>
                  <Wrench className="size-4" />{t("Repair")}
                </Button>
                {instance.loader !== "vanilla" ? (
                  <Button variant="outline" className="col-span-2" disabled={busy} onClick={() => setCopyOpen(true)}>
                    <Copy className="size-4" />{t("Copy mods to another instance")}
                  </Button>
                ) : null}
              </div>
            </div>
          </div>

          <dl className="grid grid-cols-2 gap-2">
            {[
              [t("Last played"), sinceLabel(instance.lastPlayedAt, t)],
              [t("Created"), created ? new Date(created).toLocaleDateString() : "-"],
              [t("Worlds"), String((details.worlds || []).length)],
              [t("Screenshots"), String(details.counts?.screenshots || 0)]
            ].map(([label, value]) => (
              <div key={label} className="rounded-xl border border-border/60 bg-card px-4 py-3">
                <dt className="m-kicker">{label}</dt>
                <dd className="mt-1.5 truncate text-sm font-bold">{value}</dd>
              </div>
            ))}
          </dl>

          <section className="rounded-xl border border-border/60 bg-card p-1.5">
            <p className="m-kicker px-2.5 pb-1.5 pt-2.5">{t("Folders")}</p>
            {FOLDERS.map((folder) => sideButton(
              folder.sub || "root",
              folder.icon,
              t(folder.label),
              () => api()?.openInstancePath({ instanceId, sub: folder.sub })
            ))}
          </section>

          <section className="rounded-xl border border-border/60 bg-card p-1.5">
            <p className="m-kicker px-2.5 pb-1.5 pt-2.5">{t("Manage")}</p>
            {sideButton("rename", Pencil, t("Rename"), () => setRenaming(true))}
            {sideButton("duplicate", Copy, t("Duplicate"), duplicate)}
            {sideButton("export", Upload, t("Export"), exportInstance)}
            {sideButton("delete", Trash2, t("Delete"), () => setConfirmDelete(true), true)}
          </section>
        </aside>

        <section className="flex min-w-0 flex-1 flex-col gap-4 pt-8">
          <div className="flex flex-wrap items-center gap-2">
            <div className="inline-flex h-10 items-center gap-1 rounded-xl border border-border/60 bg-card p-1" role="tablist">
              {tabs.map((entry) => {
                const Icon = entry.icon;
                const count = (entry.key === "worlds" ? details.worlds : details[entry.key])?.length || 0;
                const on = tab === entry.id;
                return (
                  <button
                    key={entry.id}
                    type="button"
                    role="tab"
                    aria-selected={on}
                    onClick={() => { setTab(entry.id); setQuery(""); }}
                    className={cn(
                      "flex h-full items-center gap-2 whitespace-nowrap rounded-lg px-3.5 text-[13px] font-semibold transition-colors",
                      on ? "bg-primary/15 text-foreground" : "text-muted-foreground hover:text-foreground"
                    )}
                  >
                    {/* Icons go on narrow windows so all four tabs stay on one line. */}
                    <Icon className="hidden size-3.5 xl:block" />
                    {t(entry.label)}
                    <span className="text-muted-foreground/70">{count}</span>
                  </button>
                );
              })}
            </div>
            <Button variant="ghost" className="ml-auto" disabled={busy} onClick={load}>
              <RefreshCw className="size-4" />{t("Refresh")}
            </Button>
          </div>

          <div className="relative">
            <Search className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
            <Input
              className="pl-9"
              value={query}
              placeholder={t("Filter {kind}…", { kind })}
              onChange={(e) => setQuery(e.target.value)}
            />
          </div>

          <div className="min-h-0 flex-1 overflow-y-auto rounded-xl border border-border/60 bg-card">
            {shown.length ? (
              <div className="divide-y divide-border/60">
                {tab === "worlds"
                  ? shown.map((world) => (
                      <WorldRow key={world.folder} world={world} busy={busy}
                        onOpen={() => api()?.openInstancePath({ instanceId, sub: "saves" })}
                        onDelete={setConfirmWorld} />
                    ))
                  : shown.map((entry) => (
                      <ContentRow key={entry.file} entry={entry} kind={tab} busy={busy}
                        onToggle={toggleMod} onRemove={removeEntry} />
                    ))}
              </div>
            ) : (
              <div className="flex h-40 flex-col items-center justify-center gap-3 text-center">
                <p className="text-xs text-muted-foreground">{needle ? t("Nothing here matches that.") : emptyText}</p>
                {!needle && tab !== "worlds" ? (
                  <Button size="sm" variant="outline" onClick={() => setAddOpen(true)}>
                    <Blocks className="size-3.5" />{t("Add content")}
                  </Button>
                ) : null}
              </div>
            )}
          </div>
        </section>

        {dialogs}
      </div>
    );
  }

  return (
    <div className="flex h-full flex-col overflow-hidden">
      <header className="border-b border-border px-6 pb-4 pt-5">
        <button onClick={onBack} className="mb-2 flex items-center gap-1.5 text-xs text-muted-foreground transition-colors hover:text-foreground">
          <ArrowLeft className="size-3.5" />{t("All instances")}
        </button>

        <div className="flex flex-wrap items-start justify-between gap-3">
          <div className="min-w-0">
            <div className="flex flex-wrap items-center gap-2">
              <h1 className="truncate text-lg font-semibold">{instance.name}</h1>
              <button
                type="button"
                onClick={() => setVersionOpen(true)}
                disabled={plainInstance}
                title={plainInstance ? undefined : t("Change Minecraft version")}
                className="rounded-full border border-border px-2.5 py-0.5 text-xs text-muted-foreground transition-colors hover:border-primary/50 hover:text-foreground"
              >
                {[instance.loader, instance.version].filter(Boolean).join(" ") || t("unknown version")}
                {plainInstance ? null : <RefreshCw className="ml-1.5 inline size-3" />}
              </button>
              {details.selected ? <Badge variant="success">{t("Selected")}</Badge> : null}
            </div>
            {plainInstance ? (
              <p className="mt-1.5 text-[11px] text-muted-foreground">{t("Without River in game")}</p>
            ) : instance.riverSupported === false ? (
              <div className="mt-1.5 flex items-start gap-1.5 text-[11px] leading-relaxed text-warning">
                <TriangleAlert className="mt-px size-3 shrink-0" />
                <span>{instance.riverWarning || t("River will not load in game on this instance.")}</span>
              </div>
            ) : null}
          </div>

          <div className="flex flex-wrap items-center gap-2">
            <Button size="sm" onClick={play} disabled={busy}><Play className="size-3.5" />{t("Play")}</Button>
            <Button size="sm" variant="outline" onClick={() => setAddOpen(true)}>
              <Blocks className="size-3.5" />{t("Add content")}
            </Button>
            <Button size="sm" variant="outline" disabled={busy} onClick={repair}>
              <Wrench className="size-3.5" />{t("Repair")}
            </Button>
            {instance.loader !== "vanilla" ? (
              <Button size="sm" variant="outline" disabled={busy} onClick={() => setCopyOpen(true)}>
                <Copy className="size-3.5" />{t("Copy mods")}
              </Button>
            ) : null}
          </div>
        </div>

        <div className="mt-3 flex flex-wrap items-center gap-1">
          {FOLDERS.map((folder) => {
            const Icon = folder.icon;
            return (
              <Button key={folder.sub || "root"} variant="ghost" size="sm"
                onClick={() => api()?.openInstancePath({ instanceId, sub: folder.sub })}>
                <Icon className="size-3.5" />{t(folder.label)}
              </Button>
            );
          })}
          <span className="mx-1 h-4 w-px bg-border" />
          <Button variant="ghost" size="sm" disabled={busy} onClick={() => setRenaming(true)}><Pencil className="size-3.5" />{t("Rename")}</Button>
          <Button variant="ghost" size="sm" disabled={busy} onClick={duplicate}><Copy className="size-3.5" />{t("Duplicate")}</Button>
          <Button variant="ghost" size="sm" disabled={busy} onClick={exportInstance}><Upload className="size-3.5" />{t("Export")}</Button>
          <Button variant="ghost" size="sm" disabled={busy} onClick={() => setConfirmDelete(true)}>
            <Trash2 className="size-3.5 text-destructive" />{t("Delete")}
          </Button>
        </div>
      </header>

      <div className="flex items-center gap-1 border-b border-border px-6 py-2">
        {tabs.map((entry) => {
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
              {t(entry.label)}
              <span className="text-muted-foreground/70">{count}</span>
            </button>
          );
        })}
        <Button variant="ghost" size="sm" className="ml-auto" disabled={busy} onClick={load}>
          <RefreshCw className="size-3.5" />{t("Refresh")}
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
            {emptyText}
          </div>
        )}
      </div>

      {dialogs}
    </div>
  );
}

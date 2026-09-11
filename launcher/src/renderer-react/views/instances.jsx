import React, { useEffect, useState } from "react";
import { Boxes, Plus, Check, Folder, Copy, Wrench, Trash2, Loader2, Download, Upload, Blocks, CircleCheck, TriangleAlert, Info, Pencil } from "lucide-react";
import { api } from "../lib/useStatus";
import { cn } from "../lib/utils";
import { Page, EmptyState } from "../components/shell";
import { Button } from "../components/ui/button";
import { Badge } from "../components/ui/badge";
import { Input } from "../components/ui/input";
import { Label } from "../components/ui/label";
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle
} from "../components/ui/dialog";
import { ContentBrowser } from "../components/content-browser";
import { ImportInstancesDialog } from "../components/import-instances";

function CreateInstanceDialog({ open, onOpenChange, onCreated, notify, versions }) {
  const available = Array.isArray(versions) && versions.length
    ? versions
    : [{ id: "1.21.11", status: "Primary" }, { id: "1.21.4", status: "Supported" }];
  const defaultVersion = available.find((v) => v.selected)?.id || available[0].id;
  const [name, setName] = useState("");
  const [version, setVersion] = useState(defaultVersion);
  const [busy, setBusy] = useState(false);

  // Reset the picked version to the current default each time the dialog opens.
  useEffect(() => { if (open) setVersion(defaultVersion); }, [open, defaultVersion]);

  const create = async () => {
    setBusy(true);
    try {
      const res = await api()?.createInstance({ name: name.trim() || undefined, version });
      if (res && res.ok === false) {
        notify(res.message || "Could not create the instance.", "error");
        return;
      }
      onOpenChange(false);
      setName("");
      onCreated?.();
    } catch (e) {
      notify(String(e?.message || e), "error");
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-sm">
        <DialogHeader>
          <DialogTitle>New instance</DialogTitle>
          <DialogDescription>A separate mods, worlds and settings folder on its own Fabric version.</DialogDescription>
        </DialogHeader>
        <div className="space-y-2">
          <Label htmlFor="instance-name">Name</Label>
          <Input
            id="instance-name"
            value={name}
            placeholder={`River ${version}`}
            onChange={(e) => setName(e.target.value)}
            onKeyDown={(e) => { if (e.key === "Enter" && !busy) create(); }}
          />
        </div>
        <div className="space-y-2">
          <Label>Minecraft version</Label>
          {/*
            A grid rather than one flex row. The row was sized for five versions and
            could not shrink below the widest chip, so adding 26.1.2 and 26.2 pushed
            the name field and the whole strip out past the edge of the dialog.
          */}
          <div className="grid grid-cols-3 gap-1.5">
            {available.map((v) => (
              <button
                key={v.id}
                type="button"
                onClick={() => setVersion(v.id)}
                className={cn(
                  "min-w-0 rounded-md border px-2 py-2 text-sm font-medium transition-colors",
                  version === v.id
                    ? "border-primary/50 bg-accent text-foreground"
                    : "border-border bg-card text-muted-foreground hover:bg-accent/40"
                )}
              >
                <span className="flex items-center justify-center gap-1.5 truncate">
                  {v.id}
                  {/*
                    Every version other than the primary one read "Supported", so the
                    badge said nothing and was the widest thing in each chip. Kept for
                    a status that actually distinguishes a version.
                  */}
                  {v.status && v.status !== "Primary" && v.status !== "Supported" ? (
                    <Badge variant="secondary" className="text-[10px]">{v.status}</Badge>
                  ) : null}
                </span>
              </button>
            ))}
          </div>
        </div>
        <DialogFooter>
          <Button variant="ghost" onClick={() => onOpenChange(false)} disabled={busy}>Cancel</Button>
          <Button onClick={create} disabled={busy}>
            {busy ? <Loader2 className="size-4 animate-spin" /> : null}
            Create
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/**
 * updateInstance already existed in main - there was just nowhere in the UI to reach it,
 * so an instance kept whatever name it was created with forever.
 */
function RenameInstanceDialog({ instance, onOpenChange, onRenamed, notify }) {
  const [name, setName] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => { setName(instance?.name || ""); }, [instance]);

  const open = Boolean(instance);

  const save = async () => {
    const trimmed = name.trim();
    if (!trimmed || trimmed === instance.name) { onOpenChange(false); return; }
    setBusy(true);
    try {
      const res = await api()?.updateInstance({ id: instance.id, name: trimmed });
      if (res && res.ok === false) notify?.(res.message || "Could not rename the instance.", "error");
      onOpenChange(false);
      onRenamed?.();
    } catch (e) {
      notify?.(String(e?.message || e), "error");
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-sm">
        <DialogHeader>
          <DialogTitle>Rename instance</DialogTitle>
        </DialogHeader>
        <div className="space-y-2">
          <Label htmlFor="rename-instance">Name</Label>
          <Input
            id="rename-instance"
            value={name}
            autoFocus
            onChange={(e) => setName(e.target.value)}
            onKeyDown={(e) => { if (e.key === "Enter" && !busy) save(); }}
          />
        </div>
        <DialogFooter>
          <Button variant="ghost" onClick={() => onOpenChange(false)} disabled={busy}>Cancel</Button>
          <Button onClick={save} disabled={busy || !name.trim()}>
            {busy ? <Loader2 className="size-4 animate-spin" /> : null}
            Save
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function InstanceRow({ instance, selected, onSelect, onAction, busyId, onOpen }) {
  const busy = busyId === instance.id;
  return (
    <div
      className={cn(
        "lift group flex items-center gap-3 rounded-md border px-3 py-2.5",
        selected ? "border-primary/50 bg-accent/50" : "border-border bg-card hover:bg-accent/40"
      )}
    >
      <button
        onClick={() => onOpen(instance)}
        className="flex min-w-0 flex-1 items-center gap-3 text-left"
        aria-label={`Open ${instance.name}`}
      >
        <div className="flex size-9 shrink-0 items-center justify-center rounded-md bg-accent">
          {selected ? <Check className="size-4 text-primary" /> : <Boxes className="size-4 text-muted-foreground" />}
        </div>
        <div className="min-w-0">
          <div className="flex items-center gap-2">
            <span className="truncate text-sm font-medium">{instance.name}</span>
            {selected ? <Badge variant="success">Selected</Badge> : null}
          </div>
          <div className="mt-0.5 flex items-center gap-2 text-xs text-muted-foreground">
            <span>{[instance.loader, instance.version].filter(Boolean).join(" ") || "unknown version"}</span>
            {/* Only ever set explicitly on import - existing River instances leave it
                undefined, so they are not mislabelled as unsupported. */}
            {instance.riverSupported === false ? (
              <span className="flex items-center gap-1 text-warning">
                <TriangleAlert className="size-3" />
                River not available in game
              </span>
            ) : null}
          </div>
        </div>
      </button>

      <div className="flex shrink-0 items-center gap-1 opacity-0 transition-opacity group-hover:opacity-100 focus-within:opacity-100">
        {busy ? <Loader2 className="size-4 animate-spin text-muted-foreground" /> : null}
        {!selected ? (
          <Button variant="ghost" size="sm" title="Use this instance" onClick={() => onSelect(instance)}>
            Select
          </Button>
        ) : null}
        <Button variant="ghost" size="icon" title="Add content" onClick={() => onAction("content", instance)}>
          <Blocks className="size-3.5" />
        </Button>
        <Button variant="ghost" size="icon" title="Open folder" onClick={() => onAction("open", instance)}>
          <Folder className="size-3.5" />
        </Button>
        <Button variant="ghost" size="icon" title="Rename" onClick={() => onAction("rename", instance)}>
          <Pencil className="size-3.5" />
        </Button>
        <Button variant="ghost" size="icon" title="Duplicate" onClick={() => onAction("duplicate", instance)}>
          <Copy className="size-3.5" />
        </Button>
        <Button variant="ghost" size="icon" title="Export as .rvr" onClick={() => onAction("export", instance)}>
          <Upload className="size-3.5" />
        </Button>
        <Button variant="ghost" size="icon" title="Repair" onClick={() => onAction("repair", instance)}>
          <Wrench className="size-3.5" />
        </Button>
        <Button variant="ghost" size="icon" title="Delete" onClick={() => onAction("delete", instance)}>
          <Trash2 className="size-3.5 text-destructive" />
        </Button>
      </div>
    </div>
  );
}

/**
 * Shown after Repair finishes. Splits what happened into three clear buckets: what got
 * updated, what genuinely needs the user's attention (hard incompatibilities), and
 * soft-conflict notes that are advisory only - the last section is deliberately worded
 * so it never reads as "these two mods can't work together" for mods that actually can.
 */
function RepairResultDialog({ result, onOpenChange }) {
  const open = Boolean(result);
  const updated = result?.updated || [];
  const blockers = result?.blockers || [];
  const conflicts = result?.conflicts || [];
  const allClear = !updated.length && !blockers.length && !conflicts.length;

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle>Repaired {result?.name}</DialogTitle>
          <DialogDescription>
            Folders and required mods were checked, and every Modrinth-tracked mod was checked for updates.
          </DialogDescription>
        </DialogHeader>

        <div className="max-h-[50vh] space-y-4 overflow-y-auto pr-1">
          {allClear ? (
            <div className="flex items-center gap-2 rounded-md border border-border bg-card px-3 py-3 text-sm">
              <CircleCheck className="size-4 text-success" />
              Everything checks out. No updates were needed and no incompatibilities were found.
            </div>
          ) : null}

          {updated.length ? (
            <section className="space-y-2">
              <h3 className="flex items-center gap-2 text-sm font-semibold">
                <CircleCheck className="size-4 text-success" />
                Updated {updated.length} mod{updated.length === 1 ? "" : "s"}
              </h3>
              <ul className="space-y-1 text-xs text-muted-foreground">
                {updated.map((name, i) => <li key={i} className="truncate">{name}</li>)}
              </ul>
            </section>
          ) : null}

          {blockers.length ? (
            <section className="space-y-2">
              <h3 className="flex items-center gap-2 text-sm font-semibold text-destructive">
                <TriangleAlert className="size-4" />
                Needs your attention
              </h3>
              <ul className="space-y-1.5">
                {blockers.map((issue, i) => (
                  <li key={i} className="rounded-md border border-destructive/40 bg-destructive/5 px-3 py-2">
                    <div className="text-xs font-medium">{issue.title}</div>
                    <div className="mt-0.5 text-xs text-muted-foreground">{issue.message}</div>
                  </li>
                ))}
              </ul>
            </section>
          ) : null}

          {conflicts.length ? (
            <section className="space-y-2">
              <h3 className="flex items-center gap-2 text-sm font-semibold">
                <Info className="size-4 text-muted-foreground" />
                Good to know
              </h3>
              <p className="text-xs text-muted-foreground">
                These mods declare a soft conflict. They usually run together fine and nothing is blocked - it is only worth a look if one of them starts misbehaving.
              </p>
              <ul className="space-y-1.5">
                {conflicts.map((issue, i) => (
                  <li key={i} className="rounded-md border border-border bg-card px-3 py-2">
                    <div className="text-xs font-medium">{issue.title}</div>
                    <div className="mt-0.5 text-xs text-muted-foreground">{issue.message}</div>
                  </li>
                ))}
              </ul>
            </section>
          ) : null}
        </div>

        <DialogFooter>
          <Button onClick={() => onOpenChange(false)}>Done</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

export function InstancesView({ status, refresh, notify, openInstance }) {
  const [creating, setCreating] = useState(false);
  const [importing, setImporting] = useState(false);
  const [contentFor, setContentFor] = useState(null);
  const [busyId, setBusyId] = useState("");
  const [confirmDelete, setConfirmDelete] = useState(null);
  const [repairResult, setRepairResult] = useState(null);
  const [renaming, setRenaming] = useState(null);

  const instances = Array.isArray(status?.instances) ? status.instances : [];
  const selectedId = status?.selectedInstance?.id;

  const select = async (instance) => {
    setBusyId(instance.id);
    try { await api()?.selectInstance(instance.id); await refresh(); } finally { setBusyId(""); }
  };

  // Adding content targets the selected instance, so select it first if needed.
  const openContent = async (instance) => {
    if (instance.id !== selectedId) {
      setBusyId(instance.id);
      try { await api()?.selectInstance(instance.id); await refresh(); } finally { setBusyId(""); }
    }
    setContentFor(instance);
  };

  const run = async (action, instance) => {
    if (action === "delete") { setConfirmDelete(instance); return; }
    if (action === "rename") { setRenaming(instance); return; }
    if (action === "content") { openContent(instance); return; }
    setBusyId(instance.id);
    try {
      const bridge = api();
      if (action === "open") await bridge?.openPath(instance.path);
      if (action === "duplicate") await bridge?.duplicateInstance({ instanceId: instance.id });
      if (action === "export") {
        const res = await bridge?.exportInstance(instance.id);
        if (res?.ok === false) {
          // A cancelled save dialog is a normal outcome, not an error to shout about.
          if (res.message && res.message !== "Cancelled.") notify(res.message, "error");
        } else {
          notify(res?.message || "Exported instance.", "info");
        }
      }
      if (action === "repair") {
        const res = await bridge?.repairInstance({ instanceId: instance.id });
        if (res?.ok === false) {
          notify(res.message || "Repair failed.", "error");
        } else {
          setRepairResult({ name: res?.instanceName || instance.name, ...res });
        }
      }
      await refresh();
    } catch (e) {
      notify(String(e?.message || e), "error");
    } finally {
      setBusyId("");
    }
  };

  const doDelete = async () => {
    const instance = confirmDelete;
    if (!instance) return;
    setBusyId(instance.id);
    setConfirmDelete(null);
    try {
      const res = await api()?.deleteInstance(instance.id);
      if (res && res.ok === false) notify(res.message || "Could not delete the instance.", "error");
      await refresh();
    } catch (e) {
      notify(String(e?.message || e), "error");
    } finally {
      setBusyId("");
    }
  };

  return (
    <Page
      title="Instances"
      description="Separate mods, worlds and settings per instance."
      actions={
        <>
          <Button size="sm" variant="outline" onClick={() => setImporting(true)}>
            <Download className="size-3.5" />
            Import
          </Button>
          <Button size="sm" onClick={() => setCreating(true)}>
            <Plus className="size-3.5" />
            New
          </Button>
        </>
      }
    >
      {instances.length ? (
        <div className="space-y-2">
          {instances.map((instance) => (
            <InstanceRow
              key={instance.id}
              instance={instance}
              selected={instance.id === selectedId}
              onSelect={select}
              onOpen={(item) => openInstance?.(item.id)}
              onAction={run}
              busyId={busyId}
            />
          ))}
        </div>
      ) : (
        <EmptyState
          icon={Boxes}
          title="No instances"
          description="Make one to get started."
          action={<Button size="sm" onClick={() => setCreating(true)}><Plus className="size-3.5" />New instance</Button>}
        />
      )}

      <CreateInstanceDialog open={creating} onOpenChange={setCreating} onCreated={refresh} notify={notify} versions={status?.versions} />

      <ImportInstancesDialog open={importing} onOpenChange={setImporting} refresh={refresh} notify={notify} />

      <RepairResultDialog result={repairResult} onOpenChange={(v) => !v && setRepairResult(null)} />

      <RenameInstanceDialog
        instance={renaming}
        onOpenChange={(v) => !v && setRenaming(null)}
        onRenamed={refresh}
        notify={notify}
      />

      <Dialog open={Boolean(contentFor)} onOpenChange={(v) => !v && setContentFor(null)}>
        <DialogContent className="flex h-[80vh] max-w-3xl flex-col">
          <DialogHeader>
            <DialogTitle>Add content to {contentFor?.name}</DialogTitle>
            <DialogDescription>
              Browse Modrinth and CurseForge for mods, resource packs and shaders.
            </DialogDescription>
          </DialogHeader>
          <div className="min-h-0 flex-1">
            {contentFor ? <ContentBrowser status={status} refresh={refresh} notify={notify} /> : null}
          </div>
        </DialogContent>
      </Dialog>

      <Dialog open={Boolean(confirmDelete)} onOpenChange={(v) => !v && setConfirmDelete(null)}>
        <DialogContent className="max-w-sm">
          <DialogHeader>
            <DialogTitle>Delete {confirmDelete?.name}?</DialogTitle>
            <DialogDescription>
              This permanently removes the instance folder, including its worlds and mods. It cannot be undone.
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="ghost" onClick={() => setConfirmDelete(null)}>Cancel</Button>
            <Button variant="destructive" onClick={doDelete}>Delete</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </Page>
  );
}

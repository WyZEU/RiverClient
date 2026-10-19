import React, { useEffect, useState } from "react";
import { Boxes, Package, Plus, Check, Folder, Copy, Wrench, Trash2, Loader2, Download, Upload, Blocks, CircleCheck, TriangleAlert, Info, Pencil, MoreHorizontal, MousePointerClick } from "lucide-react";
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
import { ContentDialog } from "../components/content-browser";
import { ImportInstancesDialog } from "../components/import-instances";
import { ModernInstanceCard } from "../components/instance-card";
import { InstanceArt, PlayBlock, useInstanceCards } from "../components/instance-art";
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuSeparator, DropdownMenuTrigger
} from "../components/ui/dropdown-menu";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "../components/ui/select";
import { useModern } from "../lib/ui-style";
import { useT } from "../i18n/context.jsx";

/** Fabric's and Forge's marks, as Modrinth draws them for its loader filters. */
function FabricIcon({ className }) {
  return (
    <svg viewBox="0 0 24 24" className={className} fill="none" stroke="currentColor" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <path strokeWidth="23" transform="matrix(.08671 0 0 .0867 -49.8 -56)" d="m820 761-85.6-87.6c-4.6-4.7-10.4-9.6-25.9 1-19.9 13.6-8.4 21.9-5.2 25.4 8.2 9 84.1 89 97.2 104 2.5 2.8-20.3-22.5-6.5-39.7 5.4-7 18-12 26-3 6.5 7.3 10.7 18-3.4 29.7-24.7 20.4-102 82.4-127 103-12.5 10.3-28.5 2.3-35.8-6-7.5-8.9-30.6-34.6-51.3-58.2-5.5-6.3-4.1-19.6 2.3-25 35-30.3 91.9-73.8 111.9-90.8" />
    </svg>
  );
}

function ForgeIcon({ className }) {
  return (
    <svg viewBox="0 0 24 24" className={className} fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <path d="M2 7.5h8v-2h12v2s-7 3.4-7 6 3.1 3.1 3.1 3.1l.9 3.9H5l1-4.1s3.8.1 4-2.9c.2-2.7-6.5-.7-8-6Z" />
    </svg>
  );
}

function NeoForgeIcon({ className }) {
  return (
    <svg viewBox="0 0 24 24" className={className} fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <path d="m12 19.2v2m0-2v2" />
      <path d="m8.4 1.3c0.5 1.5 0.7 3 0.1 4.6-0.2 0.5-0.9 1.5-1.6 1.5m8.7-6.1c-0.5 1.5-0.7 3-0.1 4.6 0.2 0.6 0.9 1.5 1.6 1.5" />
      <path d="m3.6 15.8h-1.7m18.5 0h1.7" />
      <path d="m3.2 12.1h-1.7m19.3 0h1.8" />
      <path d="m8.1 12.7v1.6m7.8-1.6v1.6" />
      <path d="m10.8 18h1.2m0 1.2-1.2-1.2m2.4 0h-1.2m0 1.2 1.2-1.2" />
      <path d="m4 9.7c-0.5 1.2-0.8 2.4-0.8 3.7 0 3.1 2.9 6.3 5.3 8.2 0.9 0.7 2.2 1.1 3.4 1.1m0.1-17.8c-1.1 0-2.1 0.2-3.2 0.7m11.2 4.1c0.5 1.2 0.8 2.4 0.8 3.7 0 3.1-2.9 6.3-5.3 8.2-0.9 0.7-2.2 1.1-3.4 1.1m-0.1-17.8c1.1 0 2.1 0.2 3.2 0.7" />
      <path d="m4 9.7c-0.2-1.8-0.3-3.7 0.5-5.5s2.2-2.6 3.9-3m11.6 8.5c0.2-1.9 0.3-3.7-0.5-5.5s-2.2-2.6-3.9-3" />
      <path d="m12 21.2-2.4 0.4m2.4-0.4 2.4 0.4" />
    </svg>
  );
}

function QuiltIcon({ className }) {
  const square = "M442.5 233.9c0-6.4-5.2-11.6-11.6-11.6h-197c-6.4 0-11.6 5.2-11.6 11.6v197c0 6.4 5.2 11.6 11.6 11.6h197c6.4 0 11.6-5.2 11.6-11.7v-197Z";
  return (
    <svg viewBox="0 0 24 24" className={className} fill="none" stroke="currentColor" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <path d={square} strokeWidth="65.6" transform="matrix(.03053 0 0 .03046 -3.2 -3.2)" />
      <path d={square} strokeWidth="65.6" transform="matrix(.03053 0 0 .03046 -3.2 7)" />
      <path d={square} strokeWidth="65.6" transform="matrix(.03053 0 0 .03046 6.9 -3.2)" />
      <path strokeWidth="70.4" transform="rotate(45 3.5 24) scale(.02843 .02835)" d="M442.5 234.8c0-7-5.6-12.5-12.5-12.5H234.7c-6.8 0-12.4 5.6-12.4 12.5V430c0 6.9 5.6 12.5 12.4 12.5H430c6.9 0 12.5-5.6 12.5-12.5V234.8Z" />
    </svg>
  );
}

function LoaderIcon({ id, className }) {
  if (id === "river") {
    return (
      <span className="inline-flex items-center gap-1">
        <FabricIcon className={className} />
        <img src="../assets/river-logo.png" alt="" draggable={false} className="size-6 object-contain" />
      </span>
    );
  }
  if (id === "fabric") return <FabricIcon className={className} />;
  if (id === "forge") return <ForgeIcon className={className} />;
  if (id === "neoforge") return <NeoForgeIcon className={className} />;
  if (id === "quilt") return <QuiltIcon className={className} />;
  return <img src="../assets/loaders/grass-block.png" alt="" draggable={false} className={cn("object-contain", className)} />;
}

/*
  What a new instance runs. Only "Fabric + River" gets River in game; plain Fabric, Forge and
  vanilla are Minecraft without it. `api` is what main lists versions under.
*/
const LOADERS = [
  { id: "river", label: "Fabric + River", prefix: "River", loader: "fabric", api: "fabric" },
  { id: "fabric", label: "Fabric", prefix: "Fabric", loader: "fabric", api: "fabric-plain" },
  { id: "forge", label: "Forge", prefix: "Forge", loader: "forge", api: "forge" },
  { id: "neoforge", label: "NeoForge", prefix: "NeoForge", loader: "neoforge", api: "neoforge" },
  { id: "quilt", label: "Quilt", prefix: "Quilt", loader: "quilt", api: "quilt" },
  { id: "vanilla", label: "Vanilla", prefix: "Minecraft", loader: "vanilla", api: "vanilla" }
];

function loaderPrefix(loader) {
  return LOADERS.find((item) => item.id === loader)?.prefix || "River";
}

function loaderOption(id) {
  return LOADERS.find((item) => item.id === id) || LOADERS[0];
}

/** "Fabric", "Forge" or "Vanilla", as the instance card shows it. */
export function loaderName(loader) {
  const id = String(loader || "fabric").toLowerCase();
  return id === "forge" ? "Forge" : id === "vanilla" ? "Vanilla" : id === "neoforge" ? "NeoForge" : id === "quilt" ? "Quilt" : "Fabric";
}

function LoaderPicker({ loader, onChange }) {
  const t = useT();
  const modern = useModern();
  return (
    <div className={cn("grid grid-cols-3 gap-1.5", modern && "gap-2")} role="radiogroup" aria-label={t("Loader")}>
      {LOADERS.map((item) => {
        const on = loader === item.id;
        return (
          <button
            key={item.id}
            type="button"
            role="radio"
            aria-checked={on}
            onClick={() => onChange(item.id)}
            className={cn(
              "flex min-w-0 flex-col items-center justify-center gap-1.5 border text-xs font-semibold transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
              modern ? "h-16 rounded-lg px-2" : "h-[58px] rounded-md px-1.5 font-medium",
              on
                ? modern ? "border-primary/60 bg-primary/15 text-foreground" : "border-primary/50 bg-accent text-foreground"
                : modern ? "border-border/60 bg-card text-muted-foreground hover:border-border hover:text-foreground" : "border-border bg-card text-muted-foreground hover:bg-accent/40"
            )}
          >
            <LoaderIcon id={item.id} className={cn("size-6", on ? "text-primary" : "")} />
            <span className="max-w-full truncate">{item.label}</span>
          </button>
        );
      })}
    </div>
  );
}

/** Every Forge or vanilla release since 1.8, newest first, fetched when the loader is picked. */
function usePlainVersions(open, loader) {
  const [lists, setLists] = useState({});
  const [error, setError] = useState("");
  useEffect(() => {
    if (!open || loader === "river" || lists[loader]) return;
    let live = true;
    setError("");
    api()?.getGameVersions?.(loaderOption(loader).api).then((res) => {
      if (!live) return;
      if (res?.ok && res.versions?.length) setLists((prev) => ({ ...prev, [loader]: res.versions }));
      else setError(res?.message || "Could not load the version list.");
    }).catch((e) => live && setError(String(e?.message || e)));
    return () => { live = false; };
  }, [open, loader, lists]);
  return { list: lists[loader] || null, error };
}

function PlainVersionSelect({ loader, list, error, value, onChange }) {
  const t = useT();
  if (error) return <p className="text-sm text-destructive">{t(error)}</p>;
  if (!list) {
    return (
      <p className="flex items-center gap-2 text-sm text-muted-foreground">
        <Loader2 className="size-4 animate-spin" />{t("Loading versions...")}
      </p>
    );
  }
  return (
    <Select value={value} onValueChange={onChange}>
      <SelectTrigger aria-label={t("Minecraft version")}><SelectValue /></SelectTrigger>
      <SelectContent className="max-h-72">
        {list.map((v) => (
          <SelectItem key={v.id} value={v.id}>
            {loader === "vanilla" ? v.id : `${v.id}  ·  ${loader === "fabric" || loader === "quilt" ? "Loader" : loaderOption(loader).label} ${v.loaderVersion}`}
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  );
}

/** "26.2" and "26.1.2" under 26.x, "1.21.11" and "1.21" under 1.21.x, in the order given. */
function versionGroups(list) {
  const groups = [];
  for (const entry of list) {
    const parts = String(entry.id).split(".");
    const key = parts[0] === "1" ? `${parts[0]}.${parts[1]}` : parts[0];
    let group = groups.find((item) => item.key === key);
    if (!group) {
      group = { key, label: `${key}.x`, versions: [] };
      groups.push(group);
    }
    group.versions.push(entry);
  }
  return groups;
}

function CreateInstanceDialog({ open, onOpenChange, onCreated, notify, versions }) {
  const t = useT();
  const modern = useModern();
  const available = Array.isArray(versions) && versions.length
    ? versions
    : [{ id: "1.21.11", status: "Primary" }, { id: "1.21.4", status: "Supported" }];
  const defaultVersion = available.find((v) => v.selected)?.id || available[0].id;
  const [name, setName] = useState("");
  const [loader, setLoader] = useState("river");
  const [fabricVersion, setFabricVersion] = useState(defaultVersion);
  const [plainPick, setPlainPick] = useState({});
  const [busy, setBusy] = useState(false);
  const plain = usePlainVersions(open, loader);

  // Reset the picked version to the current default each time the dialog opens.
  useEffect(() => { if (open) { setFabricVersion(defaultVersion); setLoader("river"); } }, [open, defaultVersion]);

  // Forge defaults to the newest version with a recommended build, vanilla to the newest release.
  const plainEntry = plain.list
    ? plain.list.find((v) => v.id === plainPick[loader]) || plain.list.find((v) => v.recommended) || plain.list[0]
    : null;
  const version = loader === "river" ? fabricVersion : plainEntry?.id || "";
  const setVersion = (id) => (loader === "river" ? setFabricVersion(id) : setPlainPick((prev) => ({ ...prev, [loader]: id })));
  const tag = loader === "vanilla" ? version : `${loader === "river" ? "Fabric" : loaderOption(loader).label} ${version}`;
  const placeholder = `${loaderPrefix(loader)} ${version}`.trim();
  const description = loader === "river"
    ? t("A separate mods, worlds and settings folder on its own Fabric version.")
    : loader === "fabric"
      ? t("Plain Fabric with your own mods, without River in game.")
      : loader === "forge" || loader === "neoforge" || loader === "quilt"
      ? t("Plain {loader} with your own mods, without River in game.", { loader: loaderOption(loader).label })
        : t("Plain Minecraft, without River in game.");

  const create = async () => {
    if (!version) return;
    setBusy(true);
    try {
      const res = await api()?.createInstance({
        name: name.trim() || undefined,
        version,
        loader: loaderOption(loader).loader,
        river: loader === "river",
        loaderVersion: loader === "river" || loader === "vanilla" ? undefined : plainEntry?.loaderVersion
      });
      if (res && res.ok === false) {
        notify(res.message || t("Could not create the instance."), "error");
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

  /*
    Modern: the top of the dialog previews the card the new instance will get (picture,
    version and name, updating as you type), and versions are grouped by release line with
    the recommended one marked, instead of twelve identical buttons in a grid.
  */
  if (modern) {
    const shownName = name.trim() || placeholder;
    return (
      <Dialog open={open} onOpenChange={onOpenChange}>
        <DialogContent className="max-w-xl gap-0 overflow-hidden p-0">
          <div className="relative h-32 overflow-hidden">
            <InstanceArt instanceId={shownName} />
            <div className="absolute inset-0 bg-gradient-to-t from-popover via-popover/45 to-popover/5" />
            <div className="absolute inset-x-6 bottom-4 min-w-0">
              <p className="m-kicker text-primary">{t("New instance")}</p>
              <div className="mt-2 flex min-w-0 items-center gap-2.5">
                <span className="truncate text-xl font-extrabold tracking-[-0.015em]">{shownName}</span>
                {version ? <span className="shrink-0 rounded-md bg-background/85 px-2 py-1 text-[11px] font-semibold">{tag}</span> : null}
              </div>
            </div>
          </div>

          <div className="space-y-5 p-6">
            <DialogHeader>
              <DialogTitle className="sr-only">{t("New instance")}</DialogTitle>
              <DialogDescription>{description}</DialogDescription>
            </DialogHeader>

            <div className="space-y-2">
              <Label>{t("Loader")}</Label>
              <LoaderPicker loader={loader} onChange={setLoader} />
            </div>

            <div className="space-y-2">
              <Label htmlFor="instance-name">{t("Name")}</Label>
              <Input
                id="instance-name"
                value={name}
                placeholder={placeholder}
                onChange={(e) => setName(e.target.value)}
                onKeyDown={(e) => { if (e.key === "Enter" && !busy) create(); }}
              />
            </div>

            {loader !== "river" ? (
              <div className="space-y-2">
                <Label>{t("Minecraft version")}</Label>
                <PlainVersionSelect loader={loader} list={plain.list} error={plain.error} value={version} onChange={setVersion} />
              </div>
            ) : (
            <div className="space-y-4">
              <Label>{t("Minecraft version")}</Label>
              {versionGroups(available).map((group) => (
                <div key={group.key} className="space-y-2.5">
                  <p className="m-kicker">{group.label}</p>
                  <div className="flex flex-wrap gap-2" role="radiogroup" aria-label={group.label}>
                    {group.versions.map((v) => {
                      const on = version === v.id;
                      return (
                        <button
                          key={v.id}
                          type="button"
                          role="radio"
                          aria-checked={on}
                          onClick={() => setVersion(v.id)}
                          className={cn(
                            "inline-flex h-10 items-center gap-2 rounded-lg border px-3.5 text-sm font-semibold transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
                            on
                              ? "border-primary/60 bg-primary/15 text-foreground"
                              : "border-border/60 bg-card text-muted-foreground hover:border-border hover:text-foreground"
                          )}
                        >
                          {on ? <Check className="size-3.5 text-primary" /> : null}
                          {v.id}
                          {v.status === "Primary" ? (
                            <span className="rounded bg-primary/20 px-1.5 py-0.5 text-[10px] font-bold uppercase tracking-wide text-primary">
                              {t("Recommended")}
                            </span>
                          ) : v.status && v.status !== "Supported" ? (
                            <Badge variant="secondary" className="text-[10px]">{t(v.status)}</Badge>
                          ) : null}
                        </button>
                      );
                    })}
                  </div>
                </div>
              ))}
            </div>
            )}

            <DialogFooter className="pt-1">
              <Button variant="ghost" onClick={() => onOpenChange(false)} disabled={busy}>{t("Cancel")}</Button>
              <PlayBlock size="md" icon={Plus} busy={busy} disabled={busy || !version} onClick={create}>{t("Create")}</PlayBlock>
            </DialogFooter>
          </div>
        </DialogContent>
      </Dialog>
    );
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-md">
        <DialogHeader>
          <DialogTitle>{t("New instance")}</DialogTitle>
          <DialogDescription>{description}</DialogDescription>
        </DialogHeader>
        <div className="space-y-2">
          <Label>{t("Loader")}</Label>
          <LoaderPicker loader={loader} onChange={setLoader} />
        </div>
        <div className="space-y-2">
          <Label htmlFor="instance-name">{t("Name")}</Label>
          <Input
            id="instance-name"
            value={name}
            placeholder={placeholder}
            onChange={(e) => setName(e.target.value)}
            onKeyDown={(e) => { if (e.key === "Enter" && !busy) create(); }}
          />
        </div>
        {loader !== "river" ? (
          <div className="space-y-2">
            <Label>{t("Minecraft version")}</Label>
            <PlainVersionSelect loader={loader} list={plain.list} error={plain.error} value={version} onChange={setVersion} />
          </div>
        ) : (
        <div className="space-y-2">
          <Label>{t("Minecraft version")}</Label>
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
                    <Badge variant="secondary" className="text-[10px]">{t(v.status)}</Badge>
                  ) : null}
                </span>
              </button>
            ))}
          </div>
        </div>
        )}
        <DialogFooter>
          <Button variant="ghost" onClick={() => onOpenChange(false)} disabled={busy}>{t("Cancel")}</Button>
          <Button onClick={create} disabled={busy || !version}>
            {busy ? <Loader2 className="size-4 animate-spin" /> : null}
            {t("Create")}
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
export function RenameInstanceDialog({ instance, onOpenChange, onRenamed, notify }) {
  const t = useT();
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
      if (res && res.ok === false) notify?.(res.message || t("Could not rename the instance."), "error");
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
          <DialogTitle>{t("Rename instance")}</DialogTitle>
        </DialogHeader>
        <div className="space-y-2">
          <Label htmlFor="rename-instance">{t("Name")}</Label>
          <Input
            id="rename-instance"
            value={name}
            autoFocus
            onChange={(e) => setName(e.target.value)}
            onKeyDown={(e) => { if (e.key === "Enter" && !busy) save(); }}
          />
        </div>
        <DialogFooter>
          <Button variant="ghost" onClick={() => onOpenChange(false)} disabled={busy}>{t("Cancel")}</Button>
          <Button onClick={save} disabled={busy || !name.trim()}>
            {busy ? <Loader2 className="size-4 animate-spin" /> : null}
            {t("Save")}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function InstanceRow({ instance, selected, onSelect, onAction, busyId, onOpen }) {
  const t = useT();
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
        aria-label={t("Open {name}", { name: instance.name })}
      >
        <div className="flex size-9 shrink-0 items-center justify-center rounded-md bg-accent">
          {selected ? <Check className="size-4 text-primary" /> : <Boxes className="size-4 text-muted-foreground" />}
        </div>
        <div className="min-w-0">
          <div className="flex items-center gap-2">
            <span className="truncate text-sm font-medium">{instance.name}</span>
            {selected ? <Badge variant="success">{t("Selected")}</Badge> : null}
          </div>
          <div className="mt-0.5 flex items-center gap-2 text-xs text-muted-foreground">
            <span>{[instance.loader, instance.version].filter(Boolean).join(" ") || t("unknown version")}</span>
            {/* Only ever set explicitly on import - existing River instances leave it
                undefined, so they are not mislabelled as unsupported. */}
            {instance.riverSupported === false ? (
              <span className="flex items-center gap-1 text-warning">
                <TriangleAlert className="size-3" />
                {t("River not available in game")}
              </span>
            ) : null}
          </div>
        </div>
      </button>

      <div className="flex shrink-0 items-center gap-1 opacity-0 transition-opacity group-hover:opacity-100 focus-within:opacity-100">
        {busy ? <Loader2 className="size-4 animate-spin text-muted-foreground" /> : null}
        {!selected ? (
          <Button variant="ghost" size="sm" title={t("Use this instance")} onClick={() => onSelect(instance)}>
            {t("Select")}
          </Button>
        ) : null}
        <Button variant="ghost" size="icon" title={t("Add content")} onClick={() => onAction("content", instance)}>
          <Blocks className="size-3.5" />
        </Button>
        <Button variant="ghost" size="icon" title={t("Open folder")} onClick={() => onAction("open", instance)}>
          <Folder className="size-3.5" />
        </Button>
        <Button variant="ghost" size="icon" title={t("Rename")} onClick={() => onAction("rename", instance)}>
          <Pencil className="size-3.5" />
        </Button>
        <Button variant="ghost" size="icon" title={t("Duplicate")} onClick={() => onAction("duplicate", instance)}>
          <Copy className="size-3.5" />
        </Button>
        <Button variant="ghost" size="icon" title={t("Export as .rvr")} onClick={() => onAction("export", instance)}>
          <Upload className="size-3.5" />
        </Button>
        <Button variant="ghost" size="icon" title={t("Repair")} onClick={() => onAction("repair", instance)}>
          <Wrench className="size-3.5" />
        </Button>
        <Button variant="ghost" size="icon" title={t("Delete")} onClick={() => onAction("delete", instance)}>
          <Trash2 className="size-3.5 text-destructive" />
        </Button>
      </div>
    </div>
  );
}

/** Everything the Classic row shows on hover, as one menu on a Modern card. */
function InstanceMenu({ instance, selected, onSelect, onAction }) {
  const t = useT();
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="ghost" size="icon" className="size-8" aria-label={t("More actions for {name}", { name: instance.name })}>
          <MoreHorizontal className="size-4" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        {!selected ? (
          <DropdownMenuItem onSelect={() => onSelect(instance)}><MousePointerClick />{t("Use this instance")}</DropdownMenuItem>
        ) : null}
        <DropdownMenuItem onSelect={() => onAction("content", instance)}><Blocks />{t("Add content")}</DropdownMenuItem>
        <DropdownMenuItem onSelect={() => onAction("open", instance)}><Folder />{t("Open folder")}</DropdownMenuItem>
        <DropdownMenuItem onSelect={() => onAction("rename", instance)}><Pencil />{t("Rename")}</DropdownMenuItem>
        <DropdownMenuItem onSelect={() => onAction("duplicate", instance)}><Copy />{t("Duplicate")}</DropdownMenuItem>
        <DropdownMenuItem onSelect={() => onAction("export", instance)}><Upload />{t("Export as .rvr")}</DropdownMenuItem>
        <DropdownMenuItem onSelect={() => onAction("repair", instance)}><Wrench />{t("Repair")}</DropdownMenuItem>
        <DropdownMenuSeparator />
        <DropdownMenuItem destructive onSelect={() => onAction("delete", instance)}><Trash2 />{t("Delete")}</DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

/**
 * Shown after Repair finishes. Splits what happened into three clear buckets: what got
 * updated, what genuinely needs the user's attention (hard incompatibilities), and
 * soft-conflict notes that are advisory only - the last section is deliberately worded
 * so it never reads as "these two mods can't work together" for mods that actually can.
 */
function RepairResultDialog({ result, onOpenChange }) {
  const t = useT();
  const open = Boolean(result);
  const updated = result?.updated || [];
  const blockers = result?.blockers || [];
  const conflicts = result?.conflicts || [];
  const allClear = !updated.length && !blockers.length && !conflicts.length;

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle>{t("Repaired {name}", { name: result?.name })}</DialogTitle>
          <DialogDescription>
            {t("Folders and required mods were checked, and every Modrinth-tracked mod was checked for updates.")}
          </DialogDescription>
        </DialogHeader>

        <div className="max-h-[50vh] space-y-4 overflow-y-auto pr-1">
          {allClear ? (
            <div className="flex items-center gap-2 rounded-md border border-border bg-card px-3 py-3 text-sm">
              <CircleCheck className="size-4 text-success" />
              {t("Everything checks out. No updates were needed and no incompatibilities were found.")}
            </div>
          ) : null}

          {updated.length ? (
            <section className="space-y-2">
              <h3 className="flex items-center gap-2 text-sm font-semibold">
                <CircleCheck className="size-4 text-success" />
                {t("Updated {count} mod{plural}", { count: updated.length, plural: updated.length === 1 ? "" : "s" })}
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
                {t("Needs your attention")}
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
                {t("Good to know")}
              </h3>
              <p className="text-xs text-muted-foreground">
                {t("These mods declare a soft conflict. They usually run together fine and nothing is blocked - it is only worth a look if one of them starts misbehaving.")}
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
          <Button onClick={() => onOpenChange(false)}>{t("Done")}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

export function InstancesView({ status, refresh, notify, openInstance }) {
  const t = useT();
  const modern = useModern();
  const [creating, setCreating] = useState(false);
  const [importing, setImporting] = useState(false);
  const [contentFor, setContentFor] = useState(null);
  const [busyId, setBusyId] = useState("");
  const [confirmDelete, setConfirmDelete] = useState(null);
  const [repairResult, setRepairResult] = useState(null);
  const [renaming, setRenaming] = useState(null);
  const [packBusy, setPackBusy] = useState(false);

  // A Modrinth .mrpack or CurseForge .zip becomes its own instance on the pack's loader.
  const importPack = async () => {
    setPackBusy(true);
    try {
      const res = await api()?.importModpackFile();
      if (!res || res.cancelled) return;
      if (res.ok === false) notify(res.message || t("Import failed."), "error");
      else notify(res.warning ? `${res.message} ${res.warning}` : res.message, res.warning ? "error" : "info");
      await refresh();
    } catch (e) {
      notify(String(e?.message || e), "error");
    } finally {
      setPackBusy(false);
    }
  };

  const instances = Array.isArray(status?.instances) ? status.instances : [];
  const selectedId = status?.selectedInstance?.id;
  // Pictures and mod counts are only drawn by the Modern cards, so Classic skips the fetch.
  const cards = useInstanceCards(instances, modern);
  const gameBusy = Boolean(status?.running) || status?.launchState === "launching";

  // Play straight from a card: the backend launches the selected instance, so select first.
  const play = async (instance) => {
    setBusyId(instance.id);
    try {
      if (instance.id !== selectedId) { await api()?.selectInstance(instance.id); await refresh(); }
      const res = await api()?.launchClient({});
      if (res && res.ok === false) notify(res.message || t("Launch failed."), "error");
    } catch (e) {
      notify(String(e?.message || e), "error");
    } finally {
      setBusyId("");
    }
  };

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
          notify(res?.message || t("Exported instance."), "info");
        }
      }
      if (action === "repair") {
        const res = await bridge?.repairInstance({ instanceId: instance.id });
        if (res?.ok === false) {
          notify(res.message || t("Repair failed."), "error");
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
      // The dialog promises the folder goes too, so ask the backend to delete it.
      const res = await api()?.deleteInstance({ instanceId: instance.id, deleteFiles: true });
      if (res && res.ok === false) notify(res.message || t("Could not delete the instance."), "error");
      else notify(res?.message || t("Instance deleted."), "info");
      await refresh();
    } catch (e) {
      notify(String(e?.message || e), "error");
    } finally {
      setBusyId("");
    }
  };

  return (
    <Page
      title={t("Instances")}
      description={t("Separate mods, worlds and settings per instance.")}
      actions={
        <>
          <Button size="sm" variant="outline" onClick={() => setImporting(true)}>
            <Download className="size-3.5" />
            {t("Import")}
          </Button>
          <Button size="sm" variant="outline" onClick={importPack} disabled={packBusy}>
            {packBusy ? <Loader2 className="size-3.5 animate-spin" /> : <Package className="size-3.5" />}
            {t("Modpack")}
          </Button>
          <Button size="sm" onClick={() => setCreating(true)}>
            <Plus className="size-3.5" />
            {t("New")}
          </Button>
        </>
      }
    >
      {instances.length && modern ? (
        <div className="grid grid-cols-[repeat(auto-fill,minmax(240px,1fr))] gap-4">
          {instances.map((instance) => (
            <ModernInstanceCard
              key={instance.id}
              instance={instance}
              card={cards[instance.id]}
              selected={instance.id === selectedId}
              busy={busyId === instance.id}
              disabled={gameBusy || Boolean(busyId)}
              onOpen={() => openInstance?.(instance.id)}
              onPlay={() => play(instance)}
              actions={<InstanceMenu instance={instance} selected={instance.id === selectedId} onSelect={select} onAction={run} />}
            />
          ))}
          <button
            type="button"
            onClick={() => setCreating(true)}
            className="flex min-h-52 flex-col items-center justify-center gap-2 rounded-xl border border-dashed border-border/70 text-muted-foreground transition-colors hover:border-primary/60 hover:text-foreground"
          >
            <Plus className="size-6" />
            <span className="text-sm font-semibold">{t("New instance")}</span>
          </button>
        </div>
      ) : instances.length ? (
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
          title={t("No instances")}
          description={t("Make one to get started.")}
          action={<Button size="sm" onClick={() => setCreating(true)}><Plus className="size-3.5" />{t("New instance")}</Button>}
        />
      )}

      <CreateInstanceDialog open={creating} onOpenChange={setCreating} onCreated={refresh} notify={notify} versions={status?.versions} />

      <ImportInstancesDialog open={importing} onOpenChange={setImporting} refresh={refresh} notify={notify} targetVersion={status?.selectedVersion || "1.21.11"} availableVersions={status?.versions?.map((version) => version.id) || []} />

      <RepairResultDialog result={repairResult} onOpenChange={(v) => !v && setRepairResult(null)} />

      <RenameInstanceDialog
        instance={renaming}
        onOpenChange={(v) => !v && setRenaming(null)}
        onRenamed={refresh}
        notify={notify}
      />

      <ContentDialog
        open={Boolean(contentFor)}
        onOpenChange={(v) => !v && setContentFor(null)}
        title={t("Add content to {name}", { name: contentFor?.name })}
        description={t("Browse Modrinth and CurseForge for mods, resource packs and shaders.")}
        status={status}
        refresh={refresh}
        notify={notify}
      />

      <Dialog open={Boolean(confirmDelete)} onOpenChange={(v) => !v && setConfirmDelete(null)}>
        <DialogContent className="max-w-sm">
          <DialogHeader>
            <DialogTitle>{t("Delete {name}?", { name: confirmDelete?.name })}</DialogTitle>
            <DialogDescription>
              {t("This permanently removes the instance folder, including its worlds and mods. It cannot be undone.")}
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="ghost" onClick={() => setConfirmDelete(null)}>{t("Cancel")}</Button>
            <Button variant="destructive" onClick={doDelete}>{t("Delete")}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </Page>
  );
}

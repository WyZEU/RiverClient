import React, { useCallback, useEffect, useState } from "react";
import { Search, Download, Check, Loader2, ExternalLink, Package, Image, Sparkles, Trash2, Power, Lock } from "lucide-react";
import { api } from "../lib/useStatus";
import { cn } from "../lib/utils";
import { useModern } from "../lib/ui-style";
import { Button } from "./ui/button";
import { Badge } from "./ui/badge";
import { Input } from "./ui/input";
import { Switch } from "./ui/switch";
import { Tabs, TabsList, TabsTrigger } from "./ui/tabs";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "./ui/select";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "./ui/dialog";
import { PlayBlock } from "./instance-art";
import { useT } from "../i18n/context.jsx";

const CONTENT_TABS = [
  { id: "mod", label: "Mods", icon: Package },
  { id: "resourcepack", label: "Resource Packs", icon: Image },
  { id: "shader", label: "Shaders", icon: Sparkles }
];

// Modrinth's own category facets, per content type - these are the filters that
// actually narrow a search, wired to the backend's `tags` param.
const TAG_OPTIONS = {
  mod: ["optimization", "utility", "adventure", "decoration", "worldgen", "library"],
  resourcepack: ["realistic", "vanilla-like", "themed", "16x", "32x", "64x", "256x"],
  shader: ["fantasy", "realistic", "vibrant", "cartoon", "potato", "high"]
};

function formatCount(n) {
  const v = Number(n) || 0;
  if (v >= 1_000_000) return `${(v / 1_000_000).toFixed(1)}M`;
  if (v >= 1_000) return `${(v / 1_000).toFixed(1)}k`;
  return String(v);
}

/** "worldgen" -> "Worldgen", "vanilla-like" -> "Vanilla like". */
function tagLabel(tagId) {
  const text = tagId.replace(/-/g, " ");
  return text.charAt(0).toUpperCase() + text.slice(1);
}

/** One installed item: icon, name, enable toggle, and remove. Core mods are locked. */
function InstalledRow({ item, contentType, onToggle, onRemove, busy }) {
  const t = useT();
  const modern = useModern();
  const meta = item.metadata || {};
  const title = meta.title || item.file.replace(/\.jar(\.disabled)?$/i, "").replace(/\.(zip|disabled)$/gi, "");
  const icon = meta.iconUrl || meta.remoteIconUrl || "";
  return (
    <div
      className={cn(
        "flex items-center gap-3 border bg-card",
        modern ? "rounded-xl border-border/60 px-4 py-3" : "rounded-md border-border px-3 py-2.5",
        modern && item.disabled && "opacity-60"
      )}
    >
      <div className={cn("flex shrink-0 items-center justify-center overflow-hidden bg-accent", modern ? "size-10 rounded-lg" : "size-9 rounded-md")}>
        {icon ? <img src={icon} alt="" className="size-full object-cover" /> : <Package className="size-4 text-muted-foreground" />}
      </div>
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-2">
          <span className={cn("truncate text-sm", modern ? "font-bold" : "font-medium", item.disabled && "text-muted-foreground line-through")}>{title}</span>
          {item.required ? <Badge variant="outline"><Lock className="size-3" />{t("Required")}</Badge> : null}
          {item.disabled ? <Badge variant="outline">{t("Disabled")}</Badge> : null}
        </div>
        <div className="truncate text-[11px] text-muted-foreground">{item.file}</div>
      </div>

      <div className="flex shrink-0 items-center gap-2">
        {/* Only mods can be enabled/disabled in place; packs and shaders are just removed. */}
        {contentType === "mod" && !item.required ? (
          <Switch
            checked={!item.disabled}
            disabled={busy}
            onCheckedChange={(on) => onToggle(item, on)}
            title={item.disabled ? t("Enable") : t("Disable")}
          />
        ) : null}
        {item.required ? (
          <Lock className="size-4 text-muted-foreground" />
        ) : (
          <Button size="icon" variant="ghost" title={t("Remove")} disabled={busy} onClick={() => onRemove(item)}>
            <Trash2 className="size-3.5 text-destructive" />
          </Button>
        )}
      </div>
    </div>
  );
}

function ResultRow({ item, onInstall, installing }) {
  const t = useT();
  return (
    <div className="lift flex items-center gap-3 rounded-md border border-border bg-card px-3 py-2.5 hover:bg-accent/40">
      <div className="flex size-10 shrink-0 items-center justify-center overflow-hidden rounded-md bg-accent">
        {item.iconUrl ? (
          <img src={item.iconUrl} alt="" className="size-full object-cover" />
        ) : (
          <Package className="size-4 text-muted-foreground" />
        )}
      </div>

      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-2">
          <span className="truncate text-sm font-medium">{item.title}</span>
          <Badge variant="outline">{item.source === "curseforge" ? "CurseForge" : "Modrinth"}</Badge>
          {item.conflict ? <Badge variant="warning">{t("Conflict")}</Badge> : null}
        </div>
        <p className="mt-0.5 line-clamp-1 text-xs text-muted-foreground">{item.description}</p>
        <div className="mt-0.5 flex items-center gap-2 text-[11px] text-muted-foreground">
          <span>{item.author}</span>
          <span aria-hidden>·</span>
          <span className="inline-flex items-center gap-1"><Download className="size-3" />{formatCount(item.downloads)}</span>
        </div>
      </div>

      <div className="flex shrink-0 items-center gap-1.5">
        <Button variant="ghost" size="icon" title={t("Open page")} onClick={() => api()?.openExternal(item.url)}>
          <ExternalLink className="size-3.5" />
        </Button>
        {item.installed ? (
          <Badge variant="success"><Check className="size-3" />{t("Installed")}</Badge>
        ) : (
          <Button size="sm" disabled={installing} onClick={() => onInstall(item)}>
            {installing ? <Loader2 className="size-3.5 animate-spin" /> : <Download className="size-3.5" />}
            {t("Install")}
          </Button>
        )}
      </div>
    </div>
  );
}

/** A search result in the Modern layout: a card in a grid, with room for two lines of description. */
function ResultCard({ item, onInstall, installing }) {
  const t = useT();
  return (
    <div className="flex flex-col gap-3 rounded-xl border border-border/60 bg-card p-4 transition-colors hover:border-border">
      <div className="flex items-start gap-3">
        <div className="flex size-12 shrink-0 items-center justify-center overflow-hidden rounded-xl bg-accent">
          {item.iconUrl ? (
            <img src={item.iconUrl} alt="" className="size-full object-cover" />
          ) : (
            <Package className="size-5 text-muted-foreground" />
          )}
        </div>
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2">
            <span className="truncate text-[15px] font-bold leading-snug">{item.title}</span>
            {item.conflict ? <Badge variant="warning">{t("Conflict")}</Badge> : null}
          </div>
          <div className="truncate text-xs text-muted-foreground">{t("by {author}", { author: item.author || "?" })}</div>
        </div>
        <Button variant="ghost" size="icon" className="-mr-1 -mt-1 size-8 shrink-0" title={t("Open page")} onClick={() => api()?.openExternal(item.url)}>
          <ExternalLink className="size-3.5" />
        </Button>
      </div>
      <p className="line-clamp-2 min-h-[2lh] text-xs leading-relaxed text-muted-foreground">{item.description}</p>
      <div className="mt-auto flex items-center justify-between gap-2">
        <span className="inline-flex items-center gap-1.5 text-xs text-muted-foreground">
          <Download className="size-3.5" />
          {formatCount(item.downloads)}
          <span aria-hidden>·</span>
          {item.source === "curseforge" ? "CurseForge" : "Modrinth"}
        </span>
        {item.installed ? (
          <Badge variant="success" className="h-8 px-3"><Check className="size-3" />{t("Installed")}</Badge>
        ) : (
          <PlayBlock size="sm" tone="quiet" icon={Download} busy={installing} disabled={installing} onClick={() => onInstall(item)}>
            {t("Install")}
          </PlayBlock>
        )}
      </div>
    </div>
  );
}

/** Placeholder cards while a Modern search runs, shaped like the results they stand in for. */
function ResultSkeletons() {
  return (
    <div className="grid grid-cols-[repeat(auto-fill,minmax(300px,1fr))] gap-3" aria-hidden>
      {Array.from({ length: 6 }, (_, i) => (
        <div key={i} className="flex animate-pulse flex-col gap-3 rounded-xl border border-border/60 bg-card p-4">
          <div className="flex items-center gap-3">
            <div className="size-12 rounded-xl bg-accent" />
            <div className="flex-1 space-y-2">
              <div className="h-3.5 w-1/2 rounded bg-accent" />
              <div className="h-3 w-1/3 rounded bg-accent/70" />
            </div>
          </div>
          <div className="space-y-1.5">
            <div className="h-3 w-full rounded bg-accent/70" />
            <div className="h-3 w-4/5 rounded bg-accent/70" />
          </div>
          <div className="mt-1 h-8 w-24 self-end rounded-md bg-accent" />
        </div>
      ))}
    </div>
  );
}

const INSTALLED_KEY = { mod: "installedMods", resourcepack: "installedResourcePacks", shader: "installedShaders" };

export function ContentBrowser({ status, refresh, notify }) {
  const t = useT();
  const modern = useModern();
  const [mode, setMode] = useState("browse");
  // Content goes into the selected instance, so search for its version and loader.
  // Vanilla takes no mods or shaders, only resource packs.
  const targetLoader = String(status?.selectedInstance?.loader || "fabric").toLowerCase() || "fabric";
  const targetVersion = status?.selectedInstance?.version || status?.selectedVersion || "1.21.11";
  const tabs = targetLoader === "vanilla" ? CONTENT_TABS.filter((tab) => tab.id === "resourcepack") : CONTENT_TABS;
  const [pickedType, setContentType] = useState("mod");
  const contentType = tabs.some((tab) => tab.id === pickedType) ? pickedType : tabs[0].id;
  const [source, setSource] = useState("modrinth");
  const [checkingUpdates, setCheckingUpdates] = useState(false);
  const [query, setQuery] = useState("");
  const [tag, setTag] = useState("all");
  const [results, setResults] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [requiresKey, setRequiresKey] = useState(false);
  const [installingId, setInstallingId] = useState("");
  const [busyFile, setBusyFile] = useState("");
  // Modern only: narrows the installed list, which runs to a hundred-odd mods on a real setup.
  const [installedFilter, setInstalledFilter] = useState("");

  const installed = Array.isArray(status?.[INSTALLED_KEY[contentType]]) ? status[INSTALLED_KEY[contentType]] : [];
  const installedCount = installed.length;
  // Only surface an Update button when the client actually knows of a newer file it
  // can fetch - no permanently-there button that does nothing.
  const updatable = installed.filter((item) => item.update && item.update.available);
  const filterText = installedFilter.trim().toLowerCase();
  const shownInstalled = modern && filterText
    ? installed.filter((item) => `${item.metadata?.title || ""} ${item.file}`.toLowerCase().includes(filterText))
    : installed;

  const checkUpdates = async () => {
    setCheckingUpdates(true);
    try {
      const res = await api()?.checkModUpdates({});
      await refresh();
      const n = res && typeof res.count === "number" ? res.count : null;
      notify(n ? t("{count} updates available.", { count: n }) : t("Everything is up to date."), "info");
    } catch (e) {
      notify(String(e?.message || e), "error");
    } finally {
      setCheckingUpdates(false);
    }
  };

  const updateAll = async () => {
    setCheckingUpdates(true);
    try {
      const res = await api()?.updateAllMods({});
      if (res && res.ok === false) notify(res.message || t("Update failed."), "error");
      else notify(res?.message || t("Updated installed content."), "info");
      await refresh();
    } catch (e) {
      notify(String(e?.message || e), "error");
    } finally {
      setCheckingUpdates(false);
    }
  };

  const removeItem = async (item) => {
    setBusyFile(item.file);
    try {
      const res = await api()?.removeContent({ contentType, file: item.file });
      if (res && res.ok === false) notify(res.message || t("Could not remove it."), "error");
      else notify(res?.message || t("Removed {file}.", { file: item.file }), "info");
      await refresh();
    } catch (e) {
      notify(String(e?.message || e), "error");
    } finally {
      setBusyFile("");
    }
  };

  const toggleItem = async (item, enabled) => {
    setBusyFile(item.file);
    try {
      const res = await api()?.setModEnabled({ file: item.file }, enabled);
      if (res && res.ok === false) notify(res.message || t("Could not change it."), "error");
      await refresh();
    } catch (e) {
      notify(String(e?.message || e), "error");
    } finally {
      setBusyFile("");
    }
  };

  const runSearch = useCallback(async () => {
    setLoading(true);
    setError("");
    setRequiresKey(false);
    try {
      const res = await api()?.searchMods({
        source,
        contentType,
        query,
        version: targetVersion,
        loader: targetLoader,
        tags: tag === "all" ? "" : tag
      });
      if (!res) { setResults([]); return; }
      if (res.requiresKey) { setRequiresKey(true); setResults([]); return; }
      if (res.ok === false) { setError(res.message || t("Search failed.")); setResults([]); return; }
      setResults(Array.isArray(res.results) ? res.results : []);
    } catch (e) {
      setError(String(e?.message || e));
    } finally {
      setLoading(false);
    }
  }, [source, contentType, query, tag, status?.selectedVersion]);

  // Re-search whenever the type, source or filter changes; debounce text.
  useEffect(() => {
    const id = setTimeout(runSearch, query ? 350 : 0);
    return () => clearTimeout(id);
  }, [runSearch, query]);

  const install = async (item) => {
    setInstallingId(item.projectId || String(item.id));
    try {
      const res = await api()?.downloadMod(item);
      if (res && res.ok === false) notify(res.message || t("Install failed."), "error");
      else notify(t("Installed {title}.", { title: item.title }), "info");
      await refresh();
      await runSearch();
    } catch (e) {
      notify(String(e?.message || e), "error");
    } finally {
      setInstallingId("");
    }
  };

  const tags = TAG_OPTIONS[contentType] || [];
  const kindLabel = t(CONTENT_TABS.find((entry) => entry.id === contentType)?.label || "").toLowerCase();

  const typeTabs = (
    <Tabs value={contentType} onValueChange={(v) => { setContentType(v); setTag("all"); setInstalledFilter(""); }}>
      <TabsList>
        {tabs.map((tab) => (
          <TabsTrigger key={tab.id} value={tab.id}>
            <tab.icon className={cn("size-3.5", modern ? "mr-1.5" : "mr-1")} />
            {t(tab.label)}
          </TabsTrigger>
        ))}
      </TabsList>
    </Tabs>
  );

  // Browse installs new content; Installed manages what's already there.
  const modeTabs = (
    <Tabs value={mode} onValueChange={setMode}>
      <TabsList>
        <TabsTrigger value="browse">{t("Browse")}</TabsTrigger>
        <TabsTrigger value="installed">
          {t("Installed")}{installedCount ? ` (${installedCount})` : ""}
        </TabsTrigger>
      </TabsList>
    </Tabs>
  );

  const updateActions = (
    <>
      {/* Update actions appear only when the client can act: a check always does
          something real, and "Update N" shows only when updates were found. */}
      {updatable.length ? (
        <Button size="sm" className={modern ? "h-10 px-4" : undefined} disabled={checkingUpdates} onClick={updateAll}>
          {checkingUpdates ? <Loader2 className="size-3.5 animate-spin" /> : <Download className="size-3.5" />}
          {t("Update {count}", { count: updatable.length })}
        </Button>
      ) : null}
      <Button size="sm" variant="outline" className={modern ? "h-10 px-4" : undefined} disabled={checkingUpdates} onClick={checkUpdates}>
        {checkingUpdates ? <Loader2 className="size-3.5 animate-spin" /> : null}
        {t("Check for updates")}
      </Button>
    </>
  );

  const installedList = shownInstalled.length ? (
    <div className="space-y-2">
      {shownInstalled.map((item) => (
        <InstalledRow
          key={item.file}
          item={item}
          contentType={contentType}
          busy={busyFile === item.file}
          onRemove={removeItem}
          onToggle={toggleItem}
        />
      ))}
    </div>
  ) : (
    <div className="flex h-24 items-center justify-center text-xs text-muted-foreground">
      {installed.length ? t("Nothing installed matches that.") : t("Nothing installed here yet. Switch to Browse to add some.")}
    </div>
  );

  const unavailable = requiresKey ? (
    <div className={cn("border border-dashed border-border p-6 text-center", modern ? "rounded-xl" : "rounded-md")}>
      <p className="text-sm font-medium">{t("CurseForge search is unavailable")}</p>
      <p className="mx-auto mt-1 max-w-sm text-xs text-muted-foreground">
        {t("River no longer includes an API key in the launcher. Search Modrinth instead.")}
      </p>
      <Button className="mt-3" size="sm" variant="outline" onClick={() => setSource("modrinth")}>
        {t("Search Modrinth")}
      </Button>
    </div>
  ) : null;

  const sourceSelect = (
    <Select value={source} onValueChange={setSource}>
      <SelectTrigger className={cn("w-36", modern && "h-10")}><SelectValue /></SelectTrigger>
      <SelectContent>
        <SelectItem value="modrinth">Modrinth</SelectItem>
        <SelectItem value="curseforge">CurseForge</SelectItem>
      </SelectContent>
    </Select>
  );

  /*
    Modern: the same browser, laid out for a wider dialog. Categories are chips you can see
    all of at once rather than a dropdown, results are cards in a grid with two lines of
    description, and a search shows placeholder cards instead of a spinner line.
  */
  if (modern) {
    return (
      <div className="flex h-full flex-col gap-4">
        <div className="flex flex-wrap items-center justify-between gap-2">
          {typeTabs}
          {modeTabs}
        </div>

        {mode === "installed" ? (
          <div className="flex min-h-0 flex-1 flex-col gap-3">
            <div className="flex items-center gap-2">
              <div className="relative flex-1">
                <Search className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
                <Input
                  className="pl-9"
                  placeholder={t("Filter installed {kind}…", { kind: kindLabel })}
                  value={installedFilter}
                  onChange={(e) => setInstalledFilter(e.target.value)}
                />
              </div>
              {updateActions}
            </div>
            <div className="min-h-0 flex-1 overflow-y-auto pr-1">{installedList}</div>
          </div>
        ) : (
          <>
            <div className="space-y-3">
              <div className="flex items-center gap-2">
                <div className="relative flex-1">
                  <Search className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
                  <Input
                    className="pl-9"
                    placeholder={t("Search {kind}…", { kind: kindLabel })}
                    value={query}
                    onChange={(e) => setQuery(e.target.value)}
                    onKeyDown={(e) => { if (e.key === "Enter") runSearch(); }}
                  />
                </div>
                {sourceSelect}
              </div>
              <div className="flex flex-wrap gap-1.5" role="radiogroup" aria-label={t("Category")}>
                {["all", ...tags].map((tagId) => {
                  const on = tag === tagId;
                  return (
                    <button
                      key={tagId}
                      type="button"
                      role="radio"
                      aria-checked={on}
                      onClick={() => setTag(tagId)}
                      className={cn(
                        "h-8 rounded-lg border px-3 text-xs font-semibold transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
                        on
                          ? "border-primary/50 bg-primary/15 text-foreground"
                          : "border-border/60 bg-card text-muted-foreground hover:border-border hover:text-foreground"
                      )}
                    >
                      {tagId === "all" ? t("All categories") : tagLabel(tagId)}
                    </button>
                  );
                })}
              </div>
            </div>

            <div className="min-h-0 flex-1 overflow-y-auto pr-1">
              {loading ? (
                <ResultSkeletons />
              ) : unavailable ? (
                unavailable
              ) : error ? (
                <div className="rounded-xl border border-destructive/40 p-4 text-center text-xs text-destructive">{error}</div>
              ) : results.length ? (
                <div className="grid grid-cols-[repeat(auto-fill,minmax(300px,1fr))] gap-3">
                  {results.map((item) => (
                    <ResultCard
                      key={`${item.source}:${item.projectId || item.id}`}
                      item={item}
                      installing={installingId === (item.projectId || String(item.id))}
                      onInstall={install}
                    />
                  ))}
                </div>
              ) : (
                <div className="flex h-32 flex-col items-center justify-center gap-2 rounded-xl border border-dashed border-border/70 text-center">
                  <Search className="size-5 text-muted-foreground" />
                  <p className="text-xs text-muted-foreground">
                    {query ? t("No results.") : t("Type to search, or pick a category.")}
                  </p>
                </div>
              )}
            </div>
          </>
        )}
      </div>
    );
  }

  return (
    <div className="flex h-full flex-col gap-3">
      <div className="flex items-center justify-between gap-2">
        {typeTabs}
        {modeTabs}
      </div>

      {mode === "installed" ? (
        <div className="flex min-h-0 flex-1 flex-col gap-2">
          <div className="flex items-center justify-end gap-2">{updateActions}</div>
          <div className="min-h-0 flex-1 overflow-y-auto">{installedList}</div>
        </div>
      ) : (
      <>
      <div className="flex items-center gap-2">
        <div className="relative flex-1">
          <Search className="pointer-events-none absolute left-2.5 top-1/2 size-3.5 -translate-y-1/2 text-muted-foreground" />
          <Input
            className="pl-8"
            placeholder={t("Search {kind}…", { kind: kindLabel })}
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            onKeyDown={(e) => { if (e.key === "Enter") runSearch(); }}
          />
        </div>

        {sourceSelect}

        <Select value={tag} onValueChange={setTag}>
          <SelectTrigger className="w-40"><SelectValue placeholder="All" /></SelectTrigger>
          <SelectContent>
            <SelectItem value="all">{t("All categories")}</SelectItem>
            {tags.map((tagId) => (
              <SelectItem key={tagId} value={tagId}>{tagId.replace(/-/g, " ")}</SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      <div className="min-h-0 flex-1 overflow-y-auto">
        {loading ? (
          <div className="flex h-24 items-center justify-center text-xs text-muted-foreground">
            <Loader2 className="mr-2 size-4 animate-spin" />{t("Searching…")}
          </div>
        ) : unavailable ? (
          unavailable
        ) : error ? (
          <div className="rounded-md border border-destructive/40 p-4 text-center text-xs text-destructive">{error}</div>
        ) : results.length ? (
          <div className="space-y-2">
            {results.map((item) => (
              <ResultRow
                key={`${item.source}:${item.projectId || item.id}`}
                item={item}
                installing={installingId === (item.projectId || String(item.id))}
                onInstall={install}
              />
            ))}
          </div>
        ) : (
          <div className="flex h-24 items-center justify-center text-xs text-muted-foreground">
            {query ? t("No results.") : t("Type to search, or pick a category.")}
          </div>
        )}
      </div>
      </>
      )}
    </div>
  );
}

/**
 * The Add content dialog, shared by Home, Instances and the instance page. Modern gets a wider,
 * taller dialog so the result grid has two columns; Classic keeps its original size.
 */
export function ContentDialog({ open, onOpenChange, title, description, status, refresh, notify }) {
  const modern = useModern();
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className={cn("flex flex-col", modern ? "h-[86vh] max-w-5xl" : "h-[80vh] max-w-3xl")}>
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          {description ? <DialogDescription>{description}</DialogDescription> : null}
        </DialogHeader>
        <div className="min-h-0 flex-1">
          {open ? <ContentBrowser status={status} refresh={refresh} notify={notify} /> : null}
        </div>
      </DialogContent>
    </Dialog>
  );
}

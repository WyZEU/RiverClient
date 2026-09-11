import React, { useCallback, useEffect, useState } from "react";
import { Search, Download, Check, Loader2, ExternalLink, Package, Image, Sparkles, Trash2, Power, Lock } from "lucide-react";
import { api } from "../lib/useStatus";
import { cn } from "../lib/utils";
import { Button } from "./ui/button";
import { Badge } from "./ui/badge";
import { Input } from "./ui/input";
import { Switch } from "./ui/switch";
import { Tabs, TabsList, TabsTrigger } from "./ui/tabs";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "./ui/select";

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

/** One installed item: icon, name, enable toggle, and remove. Core mods are locked. */
function InstalledRow({ item, contentType, onToggle, onRemove, busy }) {
  const meta = item.metadata || {};
  const title = meta.title || item.file.replace(/\.jar(\.disabled)?$/i, "").replace(/\.(zip|disabled)$/gi, "");
  const icon = meta.iconUrl || meta.remoteIconUrl || "";
  return (
    <div className="flex items-center gap-3 rounded-md border border-border bg-card px-3 py-2.5">
      <div className="flex size-9 shrink-0 items-center justify-center overflow-hidden rounded-md bg-accent">
        {icon ? <img src={icon} alt="" className="size-full object-cover" /> : <Package className="size-4 text-muted-foreground" />}
      </div>
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-2">
          <span className={cn("truncate text-sm font-medium", item.disabled && "text-muted-foreground line-through")}>{title}</span>
          {item.required ? <Badge variant="outline"><Lock className="size-3" />Required</Badge> : null}
          {item.disabled ? <Badge variant="outline">Disabled</Badge> : null}
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
            title={item.disabled ? "Enable" : "Disable"}
          />
        ) : null}
        {item.required ? (
          <Lock className="size-4 text-muted-foreground" />
        ) : (
          <Button size="icon" variant="ghost" title="Remove" disabled={busy} onClick={() => onRemove(item)}>
            <Trash2 className="size-3.5 text-destructive" />
          </Button>
        )}
      </div>
    </div>
  );
}

function ResultRow({ item, onInstall, installing }) {
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
          {item.conflict ? <Badge variant="warning">Conflict</Badge> : null}
        </div>
        <p className="mt-0.5 line-clamp-1 text-xs text-muted-foreground">{item.description}</p>
        <div className="mt-0.5 flex items-center gap-2 text-[11px] text-muted-foreground">
          <span>{item.author}</span>
          <span aria-hidden>·</span>
          <span className="inline-flex items-center gap-1"><Download className="size-3" />{formatCount(item.downloads)}</span>
        </div>
      </div>

      <div className="flex shrink-0 items-center gap-1.5">
        <Button variant="ghost" size="icon" title="Open page" onClick={() => api()?.openExternal(item.url)}>
          <ExternalLink className="size-3.5" />
        </Button>
        {item.installed ? (
          <Badge variant="success"><Check className="size-3" />Installed</Badge>
        ) : (
          <Button size="sm" disabled={installing} onClick={() => onInstall(item)}>
            {installing ? <Loader2 className="size-3.5 animate-spin" /> : <Download className="size-3.5" />}
            Install
          </Button>
        )}
      </div>
    </div>
  );
}

const INSTALLED_KEY = { mod: "installedMods", resourcepack: "installedResourcePacks", shader: "installedShaders" };

export function ContentBrowser({ status, refresh, notify }) {
  const [mode, setMode] = useState("browse");
  const [contentType, setContentType] = useState("mod");
  // CurseForge browsing is disabled for now ("Soon"), so never start on it.
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

  const installed = Array.isArray(status?.[INSTALLED_KEY[contentType]]) ? status[INSTALLED_KEY[contentType]] : [];
  const installedCount = installed.length;
  // Only surface an Update button when the client actually knows of a newer file it
  // can fetch - no permanently-there button that does nothing.
  const updatable = installed.filter((item) => item.update && item.update.available);

  const checkUpdates = async () => {
    setCheckingUpdates(true);
    try {
      const res = await api()?.checkModUpdates({});
      await refresh();
      const n = res && typeof res.count === "number" ? res.count : null;
      notify(n ? `${n} update${n === 1 ? "" : "s"} available.` : "Everything is up to date.", "info");
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
      if (res && res.ok === false) notify(res.message || "Update failed.", "error");
      else notify(res?.message || "Updated installed content.", "info");
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
      if (res && res.ok === false) notify(res.message || "Could not remove it.", "error");
      else notify(res?.message || `Removed ${item.file}.`, "info");
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
      if (res && res.ok === false) notify(res.message || "Could not change it.", "error");
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
        version: status?.selectedVersion || "1.21.11",
        loader: "fabric",
        tags: tag === "all" ? "" : tag
      });
      if (!res) { setResults([]); return; }
      if (res.requiresKey) { setRequiresKey(true); setResults([]); return; }
      if (res.ok === false) { setError(res.message || "Search failed."); setResults([]); return; }
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
      if (res && res.ok === false) notify(res.message || "Install failed.", "error");
      else notify(`Installed ${item.title}.`, "info");
      await refresh();
      await runSearch();
    } catch (e) {
      notify(String(e?.message || e), "error");
    } finally {
      setInstallingId("");
    }
  };

  const tags = TAG_OPTIONS[contentType] || [];

  return (
    <div className="flex h-full flex-col gap-3">
      <div className="flex items-center justify-between gap-2">
        <Tabs value={contentType} onValueChange={(v) => { setContentType(v); setTag("all"); }}>
          <TabsList>
            {CONTENT_TABS.map((t) => (
              <TabsTrigger key={t.id} value={t.id}>
                <t.icon className="mr-1 size-3.5" />
                {t.label}
              </TabsTrigger>
            ))}
          </TabsList>
        </Tabs>

        {/* Browse installs new content; Installed manages what's already there. */}
        <Tabs value={mode} onValueChange={setMode}>
          <TabsList>
            <TabsTrigger value="browse">Browse</TabsTrigger>
            <TabsTrigger value="installed">
              Installed{installedCount ? ` (${installedCount})` : ""}
            </TabsTrigger>
          </TabsList>
        </Tabs>
      </div>

      {mode === "installed" ? (
        <div className="flex min-h-0 flex-1 flex-col gap-2">
          {/* Update actions appear only when the client can act: a check always does
              something real, and "Update N" shows only when updates were found. */}
          <div className="flex items-center justify-end gap-2">
            {updatable.length ? (
              <Button size="sm" disabled={checkingUpdates} onClick={updateAll}>
                {checkingUpdates ? <Loader2 className="size-3.5 animate-spin" /> : <Download className="size-3.5" />}
                Update {updatable.length}
              </Button>
            ) : null}
            <Button size="sm" variant="outline" disabled={checkingUpdates} onClick={checkUpdates}>
              {checkingUpdates ? <Loader2 className="size-3.5 animate-spin" /> : null}
              Check for updates
            </Button>
          </div>
          <div className="min-h-0 flex-1 overflow-y-auto">
          {installed.length ? (
            <div className="space-y-2">
              {installed.map((item) => (
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
              Nothing installed here yet. Switch to Browse to add some.
            </div>
          )}
          </div>
        </div>
      ) : (
      <>
      <div className="flex items-center gap-2">
        <div className="relative flex-1">
          <Search className="pointer-events-none absolute left-2.5 top-1/2 size-3.5 -translate-y-1/2 text-muted-foreground" />
          <Input
            className="pl-8"
            placeholder={`Search ${CONTENT_TABS.find((t) => t.id === contentType)?.label.toLowerCase()}…`}
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            onKeyDown={(e) => { if (e.key === "Enter") runSearch(); }}
          />
        </div>

        <Select value={source} onValueChange={setSource}>
          <SelectTrigger className="w-36"><SelectValue /></SelectTrigger>
          <SelectContent>
            <SelectItem value="modrinth">Modrinth</SelectItem>
            <SelectItem value="curseforge" disabled>CurseForge · Soon</SelectItem>
          </SelectContent>
        </Select>

        <Select value={tag} onValueChange={setTag}>
          <SelectTrigger className="w-40"><SelectValue placeholder="All" /></SelectTrigger>
          <SelectContent>
            <SelectItem value="all">All categories</SelectItem>
            {tags.map((t) => (
              <SelectItem key={t} value={t}>{t.replace(/-/g, " ")}</SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      <div className="min-h-0 flex-1 overflow-y-auto">
        {loading ? (
          <div className="flex h-24 items-center justify-center text-xs text-muted-foreground">
            <Loader2 className="mr-2 size-4 animate-spin" />Searching…
          </div>
        ) : requiresKey ? (
          <div className="rounded-md border border-dashed border-border p-6 text-center">
            <p className="text-sm font-medium">CurseForge needs an API key</p>
            <p className="mx-auto mt-1 max-w-sm text-xs text-muted-foreground">
              CurseForge requires each app to use its own key. Add one in Settings, or search Modrinth instead (no key needed).
            </p>
            <Button className="mt-3" size="sm" variant="outline" onClick={() => setSource("modrinth")}>
              Search Modrinth
            </Button>
          </div>
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
            {query ? "No results." : "Type to search, or pick a category."}
          </div>
        )}
      </div>
      </>
      )}
    </div>
  );
}

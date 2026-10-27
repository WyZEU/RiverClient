import React, { useCallback, useEffect, useState } from "react";
import { Loader2, FolderOpen, LogOut, RotateCcw, Download, GraduationCap, HardDrive, FileText, Trash2, Save, UserPlus, Repeat, UserRound, Palette, Gamepad2, Rocket, Wrench } from "lucide-react";
import { api } from "../lib/useStatus";
import { Page, Field } from "../components/shell";
import { cn } from "../lib/utils";
import { useModern } from "../lib/ui-style";
import { pickableSeasons } from "../lib/seasons";
import { PlayerHead } from "../components/player-head";
import { Button } from "../components/ui/button";
import { Input } from "../components/ui/input";
import { Switch } from "../components/ui/switch";
import { Slider } from "../components/ui/slider";
import { Separator } from "../components/ui/separator";
import { Badge } from "../components/ui/badge";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "../components/ui/select";
import { UpdateAction } from "../components/update-gate";
import { useT } from "../i18n/context.jsx";
import { SUPPORTED_LANGUAGES } from "../i18n/translate.js";

function Section({ title, children }) {
  const modern = useModern();
  if (modern) {
    return (
      <section className="space-y-3">
        <h2 className="text-lg font-extrabold tracking-[-0.01em]">{title}</h2>
        <div className="divide-y divide-border/60 rounded-xl border border-border/60 bg-card px-6">{children}</div>
      </section>
    );
  }
  return (
    <section className="space-y-1">
      <h2 className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">{title}</h2>
      <div className="divide-y divide-border rounded-lg border border-border bg-card px-4">{children}</div>
    </section>
  );
}

/** A thumbnail sketch of each layout, drawn in the current theme's colours. */
function LayoutSketch({ kind }) {
  if (kind === "modern") {
    return (
      <div className="flex h-[70px] gap-1 bg-background p-1.5" aria-hidden>
        <div className="w-3.5 rounded-[3px] bg-card" />
        <div className="flex flex-1 flex-col gap-1">
          <div className="relative h-7 rounded-[4px] bg-card">
            <span className="absolute bottom-1 left-1 h-2 w-5 rounded-[2px] bg-primary" />
          </div>
          <div className="flex gap-1">
            <div className="h-4 flex-1 rounded-[3px] bg-card" />
            <div className="h-4 flex-1 rounded-[3px] bg-card" />
          </div>
          <div className="h-2.5 rounded-[3px] bg-card" />
        </div>
        <div className="w-6 rounded-[4px] bg-card" />
      </div>
    );
  }
  return (
    <div className="flex h-[70px] gap-1 bg-background p-1.5" aria-hidden>
      <div className="w-2.5 bg-card" />
      <div className="flex flex-1 flex-col gap-1">
        <div className="relative h-5 border border-border bg-card">
          <span className="absolute right-1 top-1/2 h-1.5 w-4 -translate-y-1/2 bg-primary" />
        </div>
        <div className="h-2.5 border border-border bg-card" />
        <div className="h-2.5 border border-border bg-card" />
        <div className="h-2.5 border border-border bg-card" />
      </div>
      <div className="w-6 border-l border-border bg-card" />
    </div>
  );
}

/**
 * Classic or Modern. Two pictures rather than a dropdown, since the choice is about how the
 * launcher looks and a name alone does not show that. Applies immediately, no restart.
 */
function InterfacePicker({ value, onChange, disabled }) {
  const t = useT();
  const options = [
    { id: "classic", label: t("Classic") },
    { id: "modern", label: t("Modern"), beta: true }
  ];
  return (
    <div className="flex gap-2.5" role="radiogroup" aria-label={t("Interface")}>
      {options.map((option) => {
        const active = value === option.id;
        return (
          <button
            key={option.id}
            type="button"
            role="radio"
            aria-checked={active}
            disabled={disabled}
            onClick={() => { if (!active) onChange(option.id); }}
            className={cn(
              "w-[136px] overflow-hidden rounded-md border text-left transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
              active ? "border-primary ring-1 ring-primary" : "border-border hover:border-muted-foreground/50"
            )}
          >
            <LayoutSketch kind={option.id} />
            <div className="flex items-center justify-between gap-2 border-t border-border px-2.5 py-1.5">
              <span className="text-xs font-semibold">{option.label}</span>
              {option.beta ? <Badge variant="outline" className="px-1.5 py-0 text-[10px]">{t("Beta")}</Badge> : null}
            </div>
          </button>
        );
      })}
    </div>
  );
}

function formatBytes(bytes) {
  const n = Number(bytes) || 0;
  if (n <= 0) return "0 MB";
  const mb = n / (1024 * 1024);
  if (mb >= 1024) return `${(mb / 1024).toFixed(1)} GB`;
  return `${mb.toFixed(0)} MB`;
}

/**
 * getStorageInfo/clearCache/clearLogs/getLogFiles/deleteLogFile/exportLogFile all
 * already existed in main with no Settings surface at all - there was no way to see
 * where disk space was going or to clear any of it short of finding the folders by hand.
 */
function StorageSection({ notify }) {
  const t = useT();
  const [info, setInfo] = useState(null);
  const [logs, setLogs] = useState([]);
  const [showLogs, setShowLogs] = useState(false);
  const [busy, setBusy] = useState("");

  const load = useCallback(async () => {
    try {
      const [storageInfo, logFiles] = await Promise.all([api()?.getStorageInfo(), api()?.getLogFiles()]);
      setInfo(storageInfo || null);
      setLogs(Array.isArray(logFiles) ? logFiles : []);
    } catch {}
  }, []);

  useEffect(() => { load(); }, [load]);

  const clearCache = async () => {
    setBusy("cache");
    try {
      const res = await api()?.clearCache();
      notify?.(res?.message || t("Cache cleared."), res?.ok === false ? "error" : "info");
      await load();
    } finally { setBusy(""); }
  };

  const clearLogs = async () => {
    setBusy("logs");
    try {
      const res = await api()?.clearLogs();
      notify?.(res?.message || t("Logs cleared."), res?.ok === false ? "error" : "info");
      await load();
    } finally { setBusy(""); }
  };

  const deleteLog = async (file) => {
    setBusy(file.path);
    try {
      const res = await api()?.deleteLogFile(file.path);
      if (res?.ok === false) notify?.(res.message || t("Could not delete that log."), "error");
      await load();
    } finally { setBusy(""); }
  };

  const exportLog = async (file) => {
    try {
      const res = await api()?.exportLogFile(file.path);
      if (res && res.message && res.message !== "Export cancelled.") notify?.(res.message, res.ok === false ? "error" : "info");
    } catch (e) { notify?.(String(e?.message || e), "error"); }
  };

  const total = (info?.instanceSize || 0) + (info?.cacheSize || 0) + (info?.logsSize || 0);

  return (
    <Section title={t("Storage")}>
      <div className="flex items-center justify-between gap-6 py-2.5">
        <div className="flex items-center gap-2 text-sm font-medium">
          <HardDrive className="size-3.5 text-muted-foreground" />
          {t("Used by River Client")}
        </div>
        <span className="font-mono text-xs text-muted-foreground">{formatBytes(total)}</span>
      </div>

      <Field label={t("Instance files")} hint={t("Mods, worlds, packs, shaders")}>
        <span className="font-mono text-xs text-muted-foreground">{formatBytes(info?.instanceSize)}</span>
      </Field>

      <Field label={t("Cache")} hint={t("Mod metadata and thumbnails")}>
        <span className="font-mono text-xs text-muted-foreground">{formatBytes(info?.cacheSize)}</span>
        <Button variant="outline" size="sm" disabled={busy === "cache"} onClick={clearCache}>
          {busy === "cache" ? <Loader2 className="size-3.5 animate-spin" /> : <Trash2 className="size-3.5" />}
          {t("Clear")}
        </Button>
      </Field>

      <Field label={t("Logs")} hint={`${logs.length} file${logs.length === 1 ? "" : "s"} from past sessions.`}>
        <span className="font-mono text-xs text-muted-foreground">{formatBytes(info?.logsSize)}</span>
        <Button variant="outline" size="sm" onClick={() => setShowLogs((v) => !v)}>
          <FileText className="size-3.5" />
          {showLogs ? t("Hide") : t("View")}
        </Button>
        <Button variant="outline" size="sm" disabled={busy === "logs" || !logs.length} onClick={clearLogs}>
          {busy === "logs" ? <Loader2 className="size-3.5 animate-spin" /> : <Trash2 className="size-3.5" />}
          {t("Clear")}
        </Button>
      </Field>

      {showLogs ? (
        <div className="space-y-1 py-2.5">
          {logs.length ? logs.slice(0, 8).map((file) => (
            <div key={file.path} className="flex items-center justify-between gap-2 rounded-md border border-border bg-background px-2.5 py-1.5">
              <span className="truncate text-xs">{file.file}</span>
              <div className="flex shrink-0 items-center gap-2">
                <span className="font-mono text-xs text-muted-foreground">{formatBytes(file.size)}</span>
                <button title={t("Export")} onClick={() => exportLog(file)} className="text-muted-foreground hover:text-foreground">
                  <Save className="size-3.5" />
                </button>
                <button
                  title={t("Delete")}
                  disabled={busy === file.path}
                  onClick={() => deleteLog(file)}
                  className="text-muted-foreground hover:text-destructive"
                >
                  {busy === file.path ? <Loader2 className="size-3.5 animate-spin" /> : <Trash2 className="size-3.5" />}
                </button>
              </div>
            </div>
          )) : (
            <p className="py-1 text-xs text-muted-foreground">{t("No log files yet.")}</p>
          )}
        </div>
      ) : null}
    </Section>
  );
}

/**
 * Saved accounts (multi-profile switching). getProfiles/saveProfile/switchProfile/
 * removeProfile all existed in main - a whole saved-account system with no UI, so it
 * was impossible to keep more than one Microsoft account on this launcher without
 * doing the full device-code sign-in over again every time.
 */
function SavedAccounts({ status, refresh, notify }) {
  const t = useT();
  const [profiles, setProfiles] = useState([]);
  const [activeId, setActiveId] = useState(null);
  const [busy, setBusy] = useState("");

  const load = useCallback(async () => {
    try {
      const res = await api()?.getProfiles();
      setProfiles(Array.isArray(res?.profiles) ? res.profiles : []);
      setActiveId(res?.activeId || null);
    } catch {}
  }, []);

  useEffect(() => { load(); }, [load, status?.auth?.signedIn]);

  const currentId = status?.auth?.profile?.id;
  const currentAlreadySaved = profiles.some((p) => p.id === currentId);

  const save = async () => {
    setBusy("save");
    try {
      const res = await api()?.saveProfile();
      if (res?.ok === false) notify?.(res.message || t("Could not save this account."), "error");
      else notify?.(res?.message || t("Account saved."), "info");
      await load();
      await refresh();
    } finally { setBusy(""); }
  };

  const switchTo = async (profile) => {
    setBusy(profile.id);
    try {
      const res = await api()?.switchProfile(profile.id);
      if (res?.ok === false) notify?.(res.message || t("Could not switch accounts."), "error");
      await load();
      await refresh();
    } finally { setBusy(""); }
  };

  const remove = async (profile) => {
    setBusy(`remove-${profile.id}`);
    try {
      await api()?.removeProfile(profile.id);
      await load();
    } finally { setBusy(""); }
  };

  if (!status?.auth?.signedIn && !profiles.length) return null;

  return (
    <Field label={t("Saved accounts")}>
      <div className="flex flex-col items-end gap-2">
        {status?.auth?.signedIn && !currentAlreadySaved ? (
          <Button variant="outline" size="sm" disabled={busy === "save"} onClick={save}>
            {busy === "save" ? <Loader2 className="size-3.5 animate-spin" /> : <UserPlus className="size-3.5" />}
            {t("Save this account")}
          </Button>
        ) : null}

        {profiles.length ? (
          <div className="w-64 space-y-1.5">
            {profiles.map((profile) => {
              const active = profile.id === activeId;
              return (
                <div key={profile.id} className="flex items-center gap-2 rounded-md border border-border bg-background px-2 py-1.5">
                  <PlayerHead skinUrl={profile.auth?.profile?.skinUrl} name={profile.name} size={22} />
                  <span className="min-w-0 flex-1 truncate text-xs font-medium">{profile.name}</span>
                  {active ? (
                    <Badge variant="success">{t("Active")}</Badge>
                  ) : (
                    <button
                      title={t("Switch to this account")}
                      disabled={busy === profile.id}
                      onClick={() => switchTo(profile)}
                      className="text-muted-foreground hover:text-foreground"
                    >
                      {busy === profile.id ? <Loader2 className="size-3.5 animate-spin" /> : <Repeat className="size-3.5" />}
                    </button>
                  )}
                  <button
                    title={t("Remove")}
                    disabled={busy === `remove-${profile.id}`}
                    onClick={() => remove(profile)}
                    className="text-muted-foreground hover:text-destructive"
                  >
                    {busy === `remove-${profile.id}` ? <Loader2 className="size-3.5 animate-spin" /> : <Trash2 className="size-3.5" />}
                  </button>
                </div>
              );
            })}
          </div>
        ) : null}
      </div>
    </Field>
  );
}

export function SettingsView({ status, refresh, notify, startTour }) {
  const t = useT();
  const settings = status?.settings || {};
  const modern = useModern();
  const [memoryMb, setMemoryMb] = useState(settings.memoryMb || 4096);
  const [busy, setBusy] = useState(false);
  const [section, setSection] = useState("account");

  // Track the backend value unless the user is mid-drag on the slider.
  useEffect(() => { setMemoryMb(settings.memoryMb || 4096); }, [settings.memoryMb]);

  const maxMemoryMb = Math.max(2048, Number(status?.memoryLimitMb) || 8192);
  const recommended = Number(status?.recommendedMemoryMb) || 0;

  const patch = async (next) => {
    setBusy(true);
    try { await api()?.updateSettings(next); await refresh(); }
    catch (e) { notify(String(e?.message || e), "error"); }
    finally { setBusy(false); }
  };

  const pickFolder = async () => {
    const res = await api()?.pickFolder({});
    const dir = typeof res === "string" ? res : res?.path;
    if (dir) await patch({ instancePath: dir });
  };

  const signedIn = Boolean(status?.auth?.signedIn);

  const sections = [
    { id: "account", title: t("Account"), icon: UserRound, body: (
        <>
          <Field
            label={signedIn ? status?.auth?.profile?.name || t("Signed in") : t("Not signed in")}
            hint={signedIn ? t("Microsoft account") : t("Sign in to play online and use the wardrobe.")}
          >
            {signedIn ? (
              <Button variant="outline" size="sm" disabled={busy} onClick={async () => {
                setBusy(true);
                try { await api()?.microsoftLogout(); await refresh(); } finally { setBusy(false); }
              }}>
                <LogOut className="size-3.5" />
                {t("Sign out")}
              </Button>
            ) : (
              <Button size="sm" disabled={busy} onClick={async () => {
                setBusy(true);
                try { await api()?.microsoftLogin(); await refresh(); } finally { setBusy(false); }
              }}>
                {t("Sign in")}
              </Button>
            )}
          </Field>
          <SavedAccounts status={status} refresh={refresh} notify={notify} />
        </>
      ) },
    { id: "appearance", title: t("Appearance"), icon: Palette, body: (
        <>
          <Field label={t("Language")}>
            <Select
              value={settings.launcherLanguage === "cs" ? "cs" : "en"}
              onValueChange={(v) => patch({ launcherLanguage: v })}
            >
              <SelectTrigger className="w-32"><SelectValue /></SelectTrigger>
              <SelectContent>
                {SUPPORTED_LANGUAGES.map((lang) => (
                  <SelectItem key={lang.value} value={lang.value}>{lang.label}</SelectItem>
                ))}
              </SelectContent>
            </Select>
          </Field>

          <Field label={t("Theme")}>
            <Select
              value={settings.launcherTheme === "light" ? "light" : pickableSeasons(settings.keptSeasons || []).some((s) => s.id === settings.launcherTheme) ? settings.launcherTheme : "dark"}
              onValueChange={(v) => patch({ launcherTheme: v })}
            >
              <SelectTrigger className="w-32"><SelectValue /></SelectTrigger>
              <SelectContent>
                <SelectItem value="dark">{t("Dark")}</SelectItem>
                <SelectItem value="light">{t("Light")}</SelectItem>
                {pickableSeasons(settings.keptSeasons || []).map((s) => <SelectItem key={s.id} value={s.id}>{t(s.label)}</SelectItem>)}
              </SelectContent>
            </Select>
          </Field>

          <Field label={t("Interface")} hint={t("Switches right away. Modern is new and still in beta.")}>
            <InterfacePicker
              value={settings.interfaceStyle === "modern" ? "modern" : "classic"}
              disabled={busy}
              onChange={(v) => patch({ interfaceStyle: v })}
            />
          </Field>

          <Field
            label={t("Interface size")}
            hint={t("Auto fits your monitor")}
          >
            <Select value={settings.uiScale || "auto"} onValueChange={(v) => patch({ uiScale: v })}>
              <SelectTrigger className="w-32"><SelectValue /></SelectTrigger>
              <SelectContent>
                <SelectItem value="auto">{t("Auto")}</SelectItem>
                {["90", "100", "110", "125", "150", "175", "200"].map((v) => (
                  <SelectItem key={v} value={v}>{v}%</SelectItem>
                ))}
              </SelectContent>
            </Select>
          </Field>
        </>
      ) },
    { id: "game", title: t("Game"), icon: Gamepad2, body: (
        <>
          <div className="py-3">
            <div className="flex items-center justify-between gap-6">
              <div className="space-y-0.5">
                <div className="text-sm font-medium">{t("Memory")}</div>
                <div className="text-xs text-muted-foreground">
                  {(memoryMb / 1024).toFixed(1)} {t("GB allocated")}
                  {recommended ? ` · ${(recommended / 1024).toFixed(1)} ${t("GB recommended")}` : ""}
                </div>
              </div>
              <span className="shrink-0 font-mono text-xs text-muted-foreground">
                {Math.round(maxMemoryMb / 1024)} {t("GB max")}
              </span>
            </div>
            <Slider
              className="mt-3"
              min={1024}
              max={maxMemoryMb}
              step={512}
              value={[memoryMb]}
              onValueChange={([v]) => setMemoryMb(v)}
              onValueCommit={([v]) => patch({ memoryMb: v })}
            />
          </div>

          {/*
            These two were the reason River looked slower than other launchers: a fresh
            profile ships capped at 120 with vsync on, and River had a maxFps setting that
            nothing ever read. They are written into the instance's options.txt at launch.
          */}
          <Field
            label={t("Frame rate limit")}
            hint={t("Set once when an instance is new. Change it in the game and River keeps yours.")}
          >
            <Select
              value={String(settings.maxFps ?? 165)}
              onValueChange={(v) => patch({ maxFps: Number(v) })}
            >
              <SelectTrigger className="w-36"><SelectValue /></SelectTrigger>
              <SelectContent>
                <SelectItem value="60">60 fps</SelectItem>
                <SelectItem value="120">120 fps</SelectItem>
                <SelectItem value="144">144 fps</SelectItem>
                <SelectItem value="165">165 fps</SelectItem>
                <SelectItem value="240">240 fps</SelectItem>
                <SelectItem value="360">360 fps</SelectItem>
              </SelectContent>
            </Select>
          </Field>

          <Field label={t("VSync")} hint={t("Caps the game at your monitor's refresh rate")}>
            <Switch checked={Boolean(settings.vsync)} onCheckedChange={(v) => patch({ vsync: v })} />
          </Field>

          <Field label={t("Game folder")} hint={settings.instancePath || ""}>
            <Button variant="outline" size="sm" onClick={pickFolder} disabled={busy}>
              <FolderOpen className="size-3.5" />
              {t("Change")}
            </Button>
          </Field>

          <Field label={t("Java path")} hint={t("Blank uses the bundled runtime")}>
            <Input
              className="w-64"
              defaultValue={settings.javaPath || ""}
              placeholder={t("Automatic")}
              onBlur={(e) => {
                const next = e.target.value.trim();
                if (next !== (settings.javaPath || "")) patch({ javaPath: next });
              }}
            />
          </Field>

          <Field label={t("Window size")}>
            <Select
              value={settings.windowPreset || "1280x720"}
              onValueChange={(v) => patch({ windowPreset: v })}
            >
              <SelectTrigger className="w-40"><SelectValue /></SelectTrigger>
              <SelectContent>
                {["1280x720", "1600x900", "1920x1080", "2560x1440"].map((v) => (
                  <SelectItem key={v} value={v}>{v}</SelectItem>
                ))}
              </SelectContent>
            </Select>
          </Field>

          <Field label={t("Fullscreen")}>
            <Switch checked={Boolean(settings.fullscreen)} onCheckedChange={(v) => patch({ fullscreen: v })} />
          </Field>
        </>
      ) },
    { id: "launcher", title: t("Launcher"), icon: Rocket, body: (
        <>
          <Field label={t("Start with Windows")} hint={t("Opens minimized when you sign in")}>
            <Switch checked={Boolean(settings.launchOnStartup)} onCheckedChange={(v) => patch({ launchOnStartup: v })} />
          </Field>

          <Field label={t("Game log window")} hint={t("Separate window at launch")}>
            <Switch
              checked={settings.showGameLogWindow !== false}
              onCheckedChange={(v) => patch({ showGameLogWindow: v })}
            />
          </Field>

          <Field label={t("Keep launcher open")}>
            <Switch checked={Boolean(settings.keepLauncherOpen)} onCheckedChange={(v) => patch({ keepLauncherOpen: v })} />
          </Field>

          <Field label={t("Close to tray")} hint={t("Minimize instead of quit")}>
            <Switch checked={settings.closeToTray !== false} onCheckedChange={(v) => patch({ closeToTray: v })} />
          </Field>

          <Field label={t("Message notifications")} hint={t("Only when the game is closed")}>
            <Switch
              checked={settings.dmNotifications !== false}
              onCheckedChange={(v) => patch({ dmNotifications: v })}
            />
          </Field>

          <Field label={t("Discord presence")}>
            <Switch checked={settings.discordRpc !== false} onCheckedChange={(v) => patch({ discordRpc: v })} />
          </Field>

          <Field label={t("Automatic updates")} hint={t("On startup")}>
            <Switch checked={settings.autoCheckUpdates !== false} onCheckedChange={(v) => patch({ autoCheckUpdates: v })} />
          </Field>
        </>
      ) },
    { id: "storage", title: t("Storage"), icon: HardDrive, own: true },
    { id: "maintenance", title: t("Maintenance"), icon: Wrench, body: (
        <>
          <Field label={t("Check for updates")} hint={status?.launcherUpdate?.available
            ? t("An update is available.")
            : status?.launcherUpdate?.checkFailed
              ? t("Couldn't reach the update server. Check your internet, VPN or antivirus.")
              : t("You are on the latest version.")}>
            <UpdateAction status={status} notify={notify} />
            <Button variant="outline" size="sm" disabled={busy} onClick={async () => {
              setBusy(true);
              try {
                const res = await api()?.checkLauncherUpdates();
                if (res?.available) notify(t("An update is available."), "info");
                else if (res?.checkFailed) notify(t("Couldn't check for updates. Check your internet, VPN or antivirus."), "error");
                else notify(t("You are up to date."), "info");
                await refresh();
              } finally { setBusy(false); }
            }}>
              <Download className="size-3.5" />
              {t("Check")}
            </Button>
          </Field>

          <Field
            label={t("Update channel")}
            hint={t("Tester builds, if you're allowlisted")}
          >
            <Select value={settings.updateChannel || "stable"} onValueChange={(v) => patch({ updateChannel: v })}>
              <SelectTrigger className="w-32"><SelectValue /></SelectTrigger>
              <SelectContent>
                <SelectItem value="stable">{t("Stable")}</SelectItem>
                <SelectItem value="beta">{t("Beta")}</SelectItem>
              </SelectContent>
            </Select>
          </Field>

          <Field label={t("Tutorial")} hint={t("Replay the tour")}>
            <Button variant="outline" size="sm" onClick={() => startTour?.()}>
              <GraduationCap className="size-3.5" />
              {t("Show tutorial")}
            </Button>
          </Field>

          <Field label={t("Repair all instances")} hint={t("Re-verify game files")}>
            <Button variant="outline" size="sm" disabled={busy} onClick={async () => {
              setBusy(true);
              try {
                const res = await api()?.repairAll();
                notify(res?.ok === false ? (res.message || t("Repair failed.")) : t("Repair complete."), res?.ok === false ? "error" : "info");
                await refresh();
              } finally { setBusy(false); }
            }}>
              {busy ? <Loader2 className="size-3.5 animate-spin" /> : null}
              {t("Repair")}
            </Button>
          </Field>

          <Field label={t("Reset settings")}>
            <Button variant="outline" size="sm" disabled={busy} onClick={async () => {
              setBusy(true);
              try { await api()?.resetSettings(); await refresh(); notify(t("Settings reset."), "info"); }
              finally { setBusy(false); }
            }}>
              <RotateCcw className="size-3.5" />
              {t("Reset")}
            </Button>
          </Field>
        </>
      ) }
  ];
  const draw = (entry) => (entry.own
    ? <StorageSection key={entry.id} notify={notify} />
    : <Section key={entry.id} title={entry.title}>{entry.body}</Section>);

  /*
    Modern: one category at a time, picked from a list on the left, instead of one long page.
    The categories are the same sections Classic stacks, so nothing exists in only one layout.
  */
  if (modern) {
    const active = sections.find((entry) => entry.id === section) || sections[0];
    return (
      <Page title={t("Settings")} description={`River Client ${status?.version || ""}`.trim()}>
        <div className="flex items-start gap-8">
          <nav className="sticky top-0 flex w-52 shrink-0 flex-col gap-1" aria-label={t("Settings")}>
            {sections.map((entry) => {
              const Icon = entry.icon;
              const on = entry.id === active.id;
              return (
                <button
                  key={entry.id}
                  type="button"
                  aria-current={on ? "page" : undefined}
                  onClick={() => setSection(entry.id)}
                  className={cn(
                    "flex w-full items-center gap-3 rounded-lg px-3 py-2.5 text-left text-sm font-semibold transition-colors",
                    on ? "bg-primary/15 text-foreground" : "text-muted-foreground hover:bg-accent hover:text-foreground"
                  )}
                >
                  <Icon className={cn("size-4 shrink-0", on && "text-primary")} />
                  {entry.title}
                </button>
              );
            })}
            <p className="mt-4 px-3 text-[11px] text-muted-foreground">{t("River Client by WyZ_EU")}</p>
          </nav>
          <div className="min-w-0 max-w-3xl flex-1">{draw(active)}</div>
        </div>
      </Page>
    );
  }

  return (
    <Page title={t("Settings")} description={`River Client ${status?.version || ""}`.trim()}>
      <div className="space-y-6">
        {sections.map(draw)}

        <Separator />
        <p className="pb-2 text-center text-xs text-muted-foreground">{t("River Client by WyZ_EU")}</p>
      </div>
    </Page>
  );
}

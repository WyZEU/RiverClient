import React, { useCallback, useEffect, useState } from "react";
import { Loader2, FolderOpen, LogOut, RotateCcw, Download, GraduationCap, HardDrive, FileText, Trash2, Save, UserPlus, Repeat } from "lucide-react";
import { api } from "../lib/useStatus";
import { Page, Field } from "../components/shell";
import { PlayerHead } from "../components/player-head";
import { Button } from "../components/ui/button";
import { Input } from "../components/ui/input";
import { Switch } from "../components/ui/switch";
import { Slider } from "../components/ui/slider";
import { Separator } from "../components/ui/separator";
import { Badge } from "../components/ui/badge";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "../components/ui/select";
import { UpdateAction } from "../components/update-gate";

function Section({ title, children }) {
  return (
    <section className="space-y-1">
      <h2 className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">{title}</h2>
      <div className="divide-y divide-border rounded-lg border border-border bg-card px-4">{children}</div>
    </section>
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
      notify?.(res?.message || "Cache cleared.", res?.ok === false ? "error" : "info");
      await load();
    } finally { setBusy(""); }
  };

  const clearLogs = async () => {
    setBusy("logs");
    try {
      const res = await api()?.clearLogs();
      notify?.(res?.message || "Logs cleared.", res?.ok === false ? "error" : "info");
      await load();
    } finally { setBusy(""); }
  };

  const deleteLog = async (file) => {
    setBusy(file.path);
    try {
      const res = await api()?.deleteLogFile(file.path);
      if (res?.ok === false) notify?.(res.message || "Could not delete that log.", "error");
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
    <Section title="Storage">
      <div className="flex items-center justify-between gap-6 py-2.5">
        <div className="flex items-center gap-2 text-sm font-medium">
          <HardDrive className="size-3.5 text-muted-foreground" />
          Used by River Client
        </div>
        <span className="font-mono text-xs text-muted-foreground">{formatBytes(total)}</span>
      </div>

      <Field label="Instance files" hint="Mods, worlds, resource packs and shaders.">
        <span className="font-mono text-xs text-muted-foreground">{formatBytes(info?.instanceSize)}</span>
      </Field>

      <Field label="Cache" hint="Downloaded mod metadata and thumbnails.">
        <span className="font-mono text-xs text-muted-foreground">{formatBytes(info?.cacheSize)}</span>
        <Button variant="outline" size="sm" disabled={busy === "cache"} onClick={clearCache}>
          {busy === "cache" ? <Loader2 className="size-3.5 animate-spin" /> : <Trash2 className="size-3.5" />}
          Clear
        </Button>
      </Field>

      <Field label="Logs" hint={`${logs.length} file${logs.length === 1 ? "" : "s"} from past sessions.`}>
        <span className="font-mono text-xs text-muted-foreground">{formatBytes(info?.logsSize)}</span>
        <Button variant="outline" size="sm" onClick={() => setShowLogs((v) => !v)}>
          <FileText className="size-3.5" />
          {showLogs ? "Hide" : "View"}
        </Button>
        <Button variant="outline" size="sm" disabled={busy === "logs" || !logs.length} onClick={clearLogs}>
          {busy === "logs" ? <Loader2 className="size-3.5 animate-spin" /> : <Trash2 className="size-3.5" />}
          Clear
        </Button>
      </Field>

      {showLogs ? (
        <div className="space-y-1 py-2.5">
          {logs.length ? logs.slice(0, 8).map((file) => (
            <div key={file.path} className="flex items-center justify-between gap-2 rounded-md border border-border bg-background px-2.5 py-1.5">
              <span className="truncate text-xs">{file.file}</span>
              <div className="flex shrink-0 items-center gap-2">
                <span className="font-mono text-xs text-muted-foreground">{formatBytes(file.size)}</span>
                <button title="Export" onClick={() => exportLog(file)} className="text-muted-foreground hover:text-foreground">
                  <Save className="size-3.5" />
                </button>
                <button
                  title="Delete"
                  disabled={busy === file.path}
                  onClick={() => deleteLog(file)}
                  className="text-muted-foreground hover:text-destructive"
                >
                  {busy === file.path ? <Loader2 className="size-3.5 animate-spin" /> : <Trash2 className="size-3.5" />}
                </button>
              </div>
            </div>
          )) : (
            <p className="py-1 text-xs text-muted-foreground">No log files yet.</p>
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
      if (res?.ok === false) notify?.(res.message || "Could not save this account.", "error");
      else notify?.(res?.message || "Account saved.", "info");
      await load();
      await refresh();
    } finally { setBusy(""); }
  };

  const switchTo = async (profile) => {
    setBusy(profile.id);
    try {
      const res = await api()?.switchProfile(profile.id);
      if (res?.ok === false) notify?.(res.message || "Could not switch accounts.", "error");
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
    <Field label="Saved accounts" hint="Switch between Microsoft accounts without signing in again.">
      <div className="flex flex-col items-end gap-2">
        {status?.auth?.signedIn && !currentAlreadySaved ? (
          <Button variant="outline" size="sm" disabled={busy === "save"} onClick={save}>
            {busy === "save" ? <Loader2 className="size-3.5 animate-spin" /> : <UserPlus className="size-3.5" />}
            Save this account
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
                    <Badge variant="success">Active</Badge>
                  ) : (
                    <button
                      title="Switch to this account"
                      disabled={busy === profile.id}
                      onClick={() => switchTo(profile)}
                      className="text-muted-foreground hover:text-foreground"
                    >
                      {busy === profile.id ? <Loader2 className="size-3.5 animate-spin" /> : <Repeat className="size-3.5" />}
                    </button>
                  )}
                  <button
                    title="Remove"
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
  const settings = status?.settings || {};
  const [memoryMb, setMemoryMb] = useState(settings.memoryMb || 4096);
  const [busy, setBusy] = useState(false);

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

  return (
    <Page title="Settings" description={`River Client ${status?.version || ""}`.trim()}>
      <div className="space-y-6">
        <Section title="Account">
          <Field
            label={signedIn ? status?.auth?.profile?.name || "Signed in" : "Not signed in"}
            hint={signedIn ? "Microsoft account" : "Sign in to play online and use the wardrobe."}
          >
            {signedIn ? (
              <Button variant="outline" size="sm" disabled={busy} onClick={async () => {
                setBusy(true);
                try { await api()?.microsoftLogout(); await refresh(); } finally { setBusy(false); }
              }}>
                <LogOut className="size-3.5" />
                Sign out
              </Button>
            ) : (
              <Button size="sm" disabled={busy} onClick={async () => {
                setBusy(true);
                try { await api()?.microsoftLogin(); await refresh(); } finally { setBusy(false); }
              }}>
                Sign in
              </Button>
            )}
          </Field>
          <SavedAccounts status={status} refresh={refresh} notify={notify} />
        </Section>

        <Section title="Appearance">
          {/* TODO: a Language picker goes here, right next to Theme. Blocked on
              the strings still being hardcoded through the whole renderer -
              there is a longer note next to launcherTheme in main.js. */}
          <Field label="Theme" hint="Switch the launcher between a dark and light look.">
            <Select
              value={(settings.launcherTheme === "light") ? "light" : "dark"}
              onValueChange={(v) => patch({ launcherTheme: v })}
            >
              <SelectTrigger className="w-32"><SelectValue /></SelectTrigger>
              <SelectContent>
                <SelectItem value="dark">Dark</SelectItem>
                <SelectItem value="light">Light</SelectItem>
              </SelectContent>
            </Select>
          </Field>

          <Field
            label="Interface size"
            hint="Auto matches the launcher to your monitor, so a 4K screen looks like a 1080p one."
          >
            <Select value={settings.uiScale || "auto"} onValueChange={(v) => patch({ uiScale: v })}>
              <SelectTrigger className="w-32"><SelectValue /></SelectTrigger>
              <SelectContent>
                <SelectItem value="auto">Auto</SelectItem>
                {["90", "100", "110", "125", "150", "175", "200"].map((v) => (
                  <SelectItem key={v} value={v}>{v}%</SelectItem>
                ))}
              </SelectContent>
            </Select>
          </Field>
        </Section>

        <Section title="Game">
          <div className="py-3">
            <div className="flex items-center justify-between gap-6">
              <div className="space-y-0.5">
                <div className="text-sm font-medium">Memory</div>
                <div className="text-xs text-muted-foreground">
                  {(memoryMb / 1024).toFixed(1)} GB allocated
                  {recommended ? ` · ${(recommended / 1024).toFixed(1)} GB recommended` : ""}
                </div>
              </div>
              <span className="shrink-0 font-mono text-xs text-muted-foreground">
                {Math.round(maxMemoryMb / 1024)} GB max
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

          <Field label="Game folder" hint={settings.instancePath || ""}>
            <Button variant="outline" size="sm" onClick={pickFolder} disabled={busy}>
              <FolderOpen className="size-3.5" />
              Change
            </Button>
          </Field>

          <Field label="Java path" hint="Leave empty to use the bundled runtime.">
            <Input
              className="w-64"
              defaultValue={settings.javaPath || ""}
              placeholder="Automatic"
              onBlur={(e) => {
                const next = e.target.value.trim();
                if (next !== (settings.javaPath || "")) patch({ javaPath: next });
              }}
            />
          </Field>

          <Field label="Window size">
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

          <Field label="Fullscreen" hint="Start Minecraft in fullscreen.">
            <Switch checked={Boolean(settings.fullscreen)} onCheckedChange={(v) => patch({ fullscreen: v })} />
          </Field>
        </Section>

        <Section title="Launcher">
          <Field label="Game log window" hint="Opens a separate log window when the game starts.">
            <Switch
              checked={settings.showGameLogWindow !== false}
              onCheckedChange={(v) => patch({ showGameLogWindow: v })}
            />
          </Field>

          <Field label="Keep launcher open" hint="Stay open after the game launches.">
            <Switch checked={Boolean(settings.keepLauncherOpen)} onCheckedChange={(v) => patch({ keepLauncherOpen: v })} />
          </Field>

          <Field label="Close to tray" hint="Minimize to the tray instead of quitting.">
            <Switch checked={settings.closeToTray !== false} onCheckedChange={(v) => patch({ closeToTray: v })} />
          </Field>

          <Field label="Discord presence" hint="Show River Client in your Discord status.">
            <Switch checked={settings.discordRpc !== false} onCheckedChange={(v) => patch({ discordRpc: v })} />
          </Field>

          <Field label="Automatic updates" hint="Check for launcher updates on start.">
            <Switch checked={settings.autoCheckUpdates !== false} onCheckedChange={(v) => patch({ autoCheckUpdates: v })} />
          </Field>
        </Section>

        <Section title="Content">
          <Field
            label="CurseForge"
            hint="CurseForge browsing is being reworked. Modrinth works fully in the meantime."
          >
            <Badge variant="outline">Soon</Badge>
          </Field>
          <div className="py-2.5">
            <Input
              type="password"
              className="w-full font-mono"
              defaultValue={settings.curseForgeApiKey || ""}
              placeholder="CurseForge API key (coming soon)"
              disabled
            />
          </div>
        </Section>

        <StorageSection notify={notify} />

        <Section title="Maintenance">
          <Field label="Check for updates" hint={status?.launcherUpdate?.available ? "An update is available." : "You are on the latest version."}>
            <UpdateAction status={status} notify={notify} />
            <Button variant="outline" size="sm" disabled={busy} onClick={async () => {
              setBusy(true);
              try {
                const res = await api()?.checkLauncherUpdates();
                notify(res?.available ? "An update is available." : "You are up to date.", "info");
                await refresh();
              } finally { setBusy(false); }
            }}>
              <Download className="size-3.5" />
              Check
            </Button>
          </Field>

          <Field
            label="Update channel"
            hint="Beta receives tester builds before release, if your account is on the tester list."
          >
            <Select value={settings.updateChannel || "stable"} onValueChange={(v) => patch({ updateChannel: v })}>
              <SelectTrigger className="w-32"><SelectValue /></SelectTrigger>
              <SelectContent>
                <SelectItem value="stable">Stable</SelectItem>
                <SelectItem value="beta">Beta</SelectItem>
              </SelectContent>
            </Select>
          </Field>

          <Field label="Tutorial" hint="Replay the guided tour of the launcher.">
            <Button variant="outline" size="sm" onClick={() => startTour?.()}>
              <GraduationCap className="size-3.5" />
              Show tutorial
            </Button>
          </Field>

          <Field label="Repair all instances" hint="Re-verify game files and River's mods.">
            <Button variant="outline" size="sm" disabled={busy} onClick={async () => {
              setBusy(true);
              try {
                const res = await api()?.repairAll();
                notify(res?.ok === false ? (res.message || "Repair failed.") : "Repair complete.", res?.ok === false ? "error" : "info");
                await refresh();
              } finally { setBusy(false); }
            }}>
              {busy ? <Loader2 className="size-3.5 animate-spin" /> : null}
              Repair
            </Button>
          </Field>

          <Field label="Reset settings" hint="Restore every launcher setting to its default.">
            <Button variant="outline" size="sm" disabled={busy} onClick={async () => {
              setBusy(true);
              try { await api()?.resetSettings(); await refresh(); notify("Settings reset.", "info"); }
              finally { setBusy(false); }
            }}>
              <RotateCcw className="size-3.5" />
              Reset
            </Button>
          </Field>
        </Section>

        <Separator />
        <p className="pb-2 text-center text-xs text-muted-foreground">River Client by WyZ_EU</p>
      </div>
    </Page>
  );
}

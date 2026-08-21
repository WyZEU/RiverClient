import React, { useCallback, useEffect, useState } from "react";
import { Play, Square, Loader2, Users, ExternalLink, Server, Clock, LogIn } from "lucide-react";
import { api, useLatestLog } from "../lib/useStatus";
import { cn, formatLastPlayed } from "../lib/utils";
import { Page, EmptyState } from "../components/shell";
import { PlayerHead, resolveSkinTexture } from "../components/player-head";
import { FriendsPanel } from "../components/friends-panel";
import { CrashReport } from "../components/crash-report";
import { Button } from "../components/ui/button";
import { Badge } from "../components/ui/badge";
import { Separator } from "../components/ui/separator";

/**
 * One of the two river shots that ship in assets/, picked once per launcher session the
 * way the in-game menu picks a panorama, so Home has some variety without shuffling
 * underneath you while you use it.
 */
const HERO_BG = `../assets/home-bg-${1 + Math.floor(Math.random() * 2)}.jpg`;

/** "142h 06m" for anything over an hour, "6m" under it, so a fresh install doesn't show "0h 00m". */
function formatPlaytime(ms) {
  const totalMinutes = Math.floor(Math.max(0, ms) / 60000);
  const hours = Math.floor(totalMinutes / 60);
  const minutes = totalMinutes % 60;
  if (hours <= 0) return `${minutes}m`;
  return `${hours}h ${String(minutes).padStart(2, "0")}m`;
}

/**
 * Total time played, inline and compact: one small line of text that doubles as a
 * toggle - click it to flip between River-only and every other Minecraft client
 * detected on this PC (vanilla, Lunar, Feather, Prism, CurseForge, ...). Combined
 * uses a slow one-time disk scan (cached in main), so it is fetched once here rather
 * than on every status poll.
 */
function PlaytimeStat({ settings, notify }) {
  const [summary, setSummary] = useState(null);
  const mode = settings?.playtimeDisplayMode !== "river" ? "combined" : "river";

  useEffect(() => {
    let alive = true;
    api()?.getPlaytimeSummary?.()
      .then((res) => { if (alive) setSummary(res || null); })
      .catch(() => {});
    return () => { alive = false; };
  }, []);

  if (!summary) return null;

  const shownMs = mode === "river" ? summary.riverMs : summary.combinedMs;
  const toggle = async () => {
    try { await api()?.updateSettings({ playtimeDisplayMode: mode === "river" ? "combined" : "river" }); }
    catch (e) { notify?.(String(e?.message || e), "error"); }
  };

  return (
    <button
      onClick={toggle}
      className="inline-flex items-center gap-1 text-xs text-muted-foreground transition-colors hover:text-foreground"
      title={mode === "river" ? "River Client only - click to include every Minecraft client on this PC" : "Every Minecraft client on this PC - click to show River only"}
    >
      <Clock className="size-3" />
      {formatPlaytime(shownMs || 0)} {mode === "river" ? "on River" : "played"}
    </button>
  );
}

/**
 * One server row. Partner servers sort to the top and keep their Discord link
 * visible at all times - that visibility is the deal, so it is not hidden behind
 * a hover or an overflow menu.
 */
function ServerRow({ server, onJoin, disabled }) {
  const players = server.players;
  const joinable = Boolean(server.ip);
  return (
    <div className="lift flex items-center gap-3 rounded-md border border-border bg-card px-3 py-2.5 hover:bg-accent/40">
      <div className="flex size-9 shrink-0 items-center justify-center overflow-hidden rounded-md bg-accent">
        {server.icon ? (
          <img src={server.icon} alt="" className="size-full object-cover" />
        ) : (
          <Server className="size-4 text-muted-foreground" />
        )}
      </div>

      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-2">
          <span className="truncate text-sm font-medium">{server.name}</span>
          {server.partner ? <Badge variant="default">Partner</Badge> : null}
          {server.type ? <Badge variant="outline">{server.type}</Badge> : null}
        </div>
        <div className="mt-0.5 flex items-center gap-2 text-xs text-muted-foreground">
          <span className={cn("size-1.5 rounded-full", server.online ? "bg-success" : "bg-muted-foreground/50")} />
          <span>{server.online ? "Online" : "Offline"}</span>
          {players ? (
            <>
              <span aria-hidden>·</span>
              <span className="inline-flex items-center gap-1">
                <Users className="size-3" />
                {players.online}/{players.max}
              </span>
            </>
          ) : null}
          {server.ip ? (
            <>
              <span aria-hidden>·</span>
              <span className="truncate font-mono">{server.ip}</span>
            </>
          ) : null}
        </div>
      </div>

      <div className="flex shrink-0 items-center gap-1.5">
        {server.discord ? (
          <Button
            variant="ghost"
            size="sm"
            onClick={() => api()?.openExternal(server.discord)}
            title="Open Discord"
          >
            <ExternalLink className="size-3.5" />
            Discord
          </Button>
        ) : null}
        <Button size="sm" disabled={disabled || !joinable} onClick={() => onJoin(server)} title={joinable ? `Join ${server.name}` : "No address configured yet"}>
          <Play className="size-3.5" />
          Join
        </Button>
      </div>
    </div>
  );
}

/**
 * Signed-out users had nothing to act on here: the only Sign in buttons lived in
 * Settings and Wardrobe, so Home was simultaneously empty and a dead end.
 */
function SignInCard({ notify }) {
  const [busy, setBusy] = useState(false);

  const signIn = async () => {
    setBusy(true);
    try {
      const res = await api()?.microsoftLogin();
      if (res && res.ok === false) notify?.(res.message || "Sign-in failed.", "error");
    } catch (e) {
      notify?.(String(e?.message || e), "error");
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className="flex items-center gap-4 rounded-lg border border-border bg-card p-4">
      <div className="flex size-10 shrink-0 items-center justify-center rounded-md bg-accent">
        <LogIn className="size-4 text-muted-foreground" />
      </div>
      <div className="min-w-0 flex-1">
        <div className="text-sm font-medium">Sign in to play</div>
        <div className="mt-0.5 text-xs text-muted-foreground">
          Connect your Microsoft account to launch the game, use the wardrobe and see friends.
        </div>
      </div>
      <Button size="sm" onClick={signIn} disabled={busy}>
        {busy ? <Loader2 className="size-3.5 animate-spin" /> : <LogIn className="size-3.5" />}
        Sign in
      </Button>
    </section>
  );
}

export function HomeView({ status, refresh, notify }) {
  const [servers, setServers] = useState([]);
  const [busy, setBusy] = useState(false);
  const log = useLatestLog();

  const running = Boolean(status?.running);
  const launching = status?.launchState === "launching";
  const profile = status?.auth?.profile;
  const lastPlayed = formatLastPlayed(status?.selectedInstance?.lastPlayedAt);

  useEffect(() => {
    let alive = true;
    const bridge = api();
    if (!bridge) return undefined;

    Promise.allSettled([bridge.getPartners?.(), bridge.getRecentServers?.()])
      .then(([partnerResult, recentResult]) => {
        if (!alive) return;
        const partners = partnerResult.status === "fulfilled" && Array.isArray(partnerResult.value)
          ? partnerResult.value
          : [];
        const recent = recentResult.status === "fulfilled" && Array.isArray(recentResult.value)
          ? recentResult.value
          : [];

        // Partners always sit on top; the servers you actually play follow, in
        // most-recent order. A partner you have also joined must not appear twice,
        // so recent entries matching a partner address are dropped.
        const partnerAddresses = new Set(
          partners.map((server) => String(server.ip || "").toLowerCase()).filter(Boolean)
        );
        const rest = recent.filter(
          (server) => !partnerAddresses.has(String(server.ip || "").toLowerCase())
        );

        setServers([...partners, ...rest]);
      })
      .catch(() => {});

    return () => { alive = false; };
  }, []);

  const launch = useCallback(async (joinAddress = "") => {
    setBusy(true);
    try {
      const res = await api()?.launchClient(joinAddress ? { joinAddress } : {});
      if (res && res.ok === false) notify(res.message || "Launch failed.", "error");
    } catch (e) {
      notify(String(e?.message || e), "error");
    } finally {
      setBusy(false);
    }
  }, [notify]);

  const stop = useCallback(async () => {
    setBusy(true);
    try { await api()?.stopClient(); } finally { setBusy(false); }
  }, []);

  const disabled = busy || launching || running;

  return (
    <div className="flex h-full min-h-0">
      <div className="min-w-0 flex-1">
    <Page
      title={profile ? `Welcome back, ${profile.name}` : "Welcome to River"}
      description={status?.mode || `Fabric ${status?.selectedVersion || "1.21.11"}`}
      actions={<PlayerHead skinUrl={resolveSkinTexture(status)} name={profile?.name} size={36} />}
    >
      <div className="space-y-6">
        <CrashReport status={status} refresh={refresh} notify={notify} />

        <section className="relative overflow-hidden rounded-lg border border-border bg-card">
          {/* River art that already shipped with the launcher but was never wired up.
              The scrim keeps it as texture so the label and Launch stay high-contrast,
              and it is built from theme tokens so light mode works the same way. */}
          <div className="pointer-events-none absolute inset-0 select-none">
            <img src={HERO_BG} alt="" draggable={false} className="hero-drift size-full object-cover" />
            <div className="absolute inset-0 bg-gradient-to-r from-background via-background/88 to-background/35" />
            <div className="absolute inset-0 bg-gradient-to-t from-background/85 via-transparent to-background/20" />
          </div>

          <div className="relative flex min-h-32 items-center gap-4 p-6">
            <div className="min-w-0 flex-1">
              <div className="truncate text-xl font-semibold leading-tight">
                {status?.selectedInstance?.name || "River Default"}
              </div>
              <div className="mt-1.5 flex items-center gap-2 truncate text-xs text-muted-foreground">
                {/* Only shown while something is actually happening - idle needs no
                    "ready" label, and a status dot with no text says nothing either. */}
                {launching || running ? (
                  <>
                    <span
                      className={cn(
                        "size-1.5 shrink-0 rounded-full",
                        running ? "bg-success" : "bg-warning"
                      )}
                    />
                    <span className="truncate">
                      {launching ? (log || "Starting Minecraft...") : "Game running"}
                    </span>
                    <span aria-hidden>·</span>
                  </>
                ) : null}
                <PlaytimeStat settings={status?.settings} notify={notify} />
                {/* Only the selected instance - this line is about the thing you are
                    about to launch, so other instances' history is not relevant here.
                    Empty right after a session, and while the game is running. */}
                {lastPlayed ? (
                  <>
                    <span aria-hidden>·</span>
                    <span>{lastPlayed}</span>
                  </>
                ) : null}
              </div>
            </div>

            {running ? (
              <Button variant="destructive" size="lg" onClick={stop} disabled={busy}>
                <Square className="size-4" />
                Stop
              </Button>
            ) : (
              <Button
                size="lg"
                data-tour="launch"
                className={cn("min-w-44", !disabled && "glow-accent")}
                onClick={() => launch("")}
                disabled={disabled}
              >
                {launching || busy ? <Loader2 className="size-4 animate-spin" /> : <Play className="size-4" />}
                {launching || busy ? "Launching" : "Launch"}
              </Button>
            )}
          </div>
        </section>

        {!profile ? <SignInCard notify={notify} /> : null}

        <section className="space-y-3">
          <div className="flex items-center justify-between">
            <h2 className="text-sm font-semibold">Servers</h2>
          </div>
          <Separator />
          {servers.length ? (
            <div className="space-y-2">
              {servers.map((server) => (
                <ServerRow
                  key={`${server.ip || "?"}:${server.name}`}
                  server={server}
                  disabled={disabled}
                  onJoin={(s) => launch(s.ip)}
                />
              ))}
            </div>
          ) : (
            <EmptyState
              icon={Server}
              title="No servers yet"
              description="Servers you join show up here, with partners pinned to the top."
            />
          )}
        </section>
      </div>
    </Page>
      </div>
      <FriendsPanel status={status} refresh={refresh} notify={notify} />
    </div>
  );
}

import React, { useMemo, useState } from "react";
import { ArrowRight, ChevronDown, ExternalLink, FolderInput, Package, Server, Shirt, Square, Users } from "lucide-react";
import { api, useLatestLog } from "../lib/useStatus";
import { cn, formatLastPlayed } from "../lib/utils";
import { PlayerHead, resolveSkinTexture } from "../components/player-head";
import { SkinViewer } from "../components/skin-viewer";
import { FriendsPanel } from "../components/friends-panel";
import { CrashReport } from "../components/crash-report";
import { ModCompatibility } from "../components/mod-compatibility";
import { ImportInstancesDialog } from "../components/import-instances";
import { ContentDialog } from "../components/content-browser";
import { InstanceArt, PlayBlock, useInstanceCards } from "../components/instance-art";
import { ModernInstanceCard, instanceVersionLabel } from "../components/instance-card";
import { Button } from "../components/ui/button";
import { Badge } from "../components/ui/badge";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "../components/ui/select";
import { PlaytimeStat, ServerAddress, SignInCard, useLauncherActions, useServerList } from "./home";
import { Bats, CornerWeb, Mist, NightSky, Pumpkin } from "../components/modern-season";
import { useSeasonId } from "../lib/seasons";
import { useT } from "../i18n/context.jsx";

function SectionHead({ title, action }) {
  return (
    <div className="flex items-end justify-between gap-3">
      <h2 className="text-[17px] font-extrabold tracking-[-0.01em]">{title}</h2>
      {action}
    </div>
  );
}

/**
 * Which instance Play starts, switchable right here instead of on the Instances page. Only
 * drawn when there is more than one to pick from.
 */
function InstancePicker({ instances, selectedId, onPick, disabled }) {
  const t = useT();
  if (instances.length < 2) return null;
  const selected = instances.find((instance) => instance.id === selectedId);
  return (
    <Select value={selectedId} onValueChange={onPick} disabled={disabled}>
      <SelectTrigger
        aria-label={t("Instance to play")}
        className="h-14 w-auto min-w-48 max-w-72 gap-4 border-border/70 bg-background/75 px-4 text-left hover:bg-background [&>span]:min-w-0"
      >
        <span className="flex min-w-0 flex-col leading-tight">
          <span className="text-[11px] font-medium text-muted-foreground">{t("Instance")}</span>
          <span className="truncate text-sm font-semibold">{selected?.name || t("Choose")}</span>
        </span>
      </SelectTrigger>
      <SelectContent>
        {instances.map((instance) => (
          <SelectItem key={instance.id} value={instance.id}>
            {instance.name}
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  );
}

/** The selected instance, front and centre: its picture, your skin, and Play. */
function Hero({ status, card, busy, launch, stop, onPick, notify }) {
  const t = useT();
  const log = useLatestLog();
  const selected = status?.selectedInstance;
  const instances = Array.isArray(status?.instances) ? status.instances : [];
  const running = Boolean(status?.running);
  const launching = status?.launchState === "launching";
  const disabled = busy || launching || running;
  const lastPlayed = formatLastPlayed(selected?.lastPlayedAt);

  const profile = status?.auth?.profile;
  const skins = Array.isArray(status?.skinHistory) ? status.skinHistory : [];
  const activeSkin = skins.find((skin) => skin.active) || skins[0];
  const capes = Array.isArray(profile?.capes) ? profile.capes : [];
  const cape = capes.find((entry) => entry.active || entry.state === "ACTIVE");
  const skin = profile ? resolveSkinTexture(status) : "";

  // Only says something when something is happening; idle needs no label.
  const state = running ? t("In game") : launching ? t("Starting") : "";
  // Halloween: the banner becomes the game's own Halloween title screen, so the launcher and
  // the menu you land in look like one place.
  const halloween = useSeasonId() === "halloween";

  return (
    <section className="relative isolate overflow-hidden rounded-xl border border-border/60 bg-card">
      <div className="absolute inset-0 -z-10">
        {halloween ? <NightSky drift /> : <InstanceArt instanceId={selected?.id} card={card} drift />}
        <div
          className={cn(
            "absolute inset-0 bg-gradient-to-r",
            halloween ? "from-background/95 via-background/55 to-transparent" : "from-background via-background/80 to-background/20"
          )}
        />
        <div
          className={cn(
            "absolute inset-0 bg-gradient-to-t",
            halloween ? "from-background/80 via-transparent to-transparent" : "from-background/90 via-background/10 to-transparent"
          )}
        />
        {halloween ? (
          <>
            <Mist className="opacity-40" />
            <Bats />
          </>
        ) : null}
      </div>

      <div className="flex min-h-[264px]">
        <div className="flex min-w-0 flex-1 flex-col justify-end px-6 py-7">
          {state ? <p className={cn("m-kicker mb-3", running ? "text-success" : "text-primary")}>{state}</p> : null}
          <h2 className="truncate text-[32px] font-extrabold leading-[1.05] tracking-[-0.025em]">
            {selected?.name || "River Default"}
          </h2>
          <div className="mt-2 flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-muted-foreground">
            {launching ? (
              <span className="max-w-md truncate">{log || t("Starting Minecraft...")}</span>
            ) : (
              <>
                <span>{instanceVersionLabel(selected) || status?.mode}</span>
                <span aria-hidden>·</span>
                <PlaytimeStat settings={status?.settings} notify={notify} />
                {lastPlayed ? (
                  <>
                    <span aria-hidden>·</span>
                    <span>{lastPlayed}</span>
                  </>
                ) : null}
                {card ? (
                  <>
                    <span aria-hidden>·</span>
                    <span>{t("{count} mods", { count: card.modCount || 0 })}</span>
                  </>
                ) : null}
              </>
            )}
          </div>

          <div className="mt-6 flex flex-wrap items-center gap-3">
            {running ? (
              <PlayBlock size="lg" danger icon={Square} busy={busy} disabled={busy} onClick={stop}>
                {t("Stop")}
              </PlayBlock>
            ) : (
              <PlayBlock
                size="lg"
                data-tour="launch"
                busy={launching || busy}
                disabled={disabled}
                onClick={() => launch("")}
              >
                {launching || busy ? t("Launching") : t("Play")}
              </PlayBlock>
            )}
            <InstancePicker
              instances={instances}
              selectedId={selected?.id || ""}
              onPick={onPick}
              disabled={disabled}
            />
          </div>
        </div>

        {/* Your skin, wearing your cape, playing its idle animation. Drag to turn it; it does not
            spin, since the picture behind it already moves. */}
        {skin ? (
          <div className="relative hidden w-[230px] shrink-0 lg:block">
            <SkinViewer
              texture={skin}
              slim={activeSkin?.variant === "slim"}
              cape={cape?.url || ""}
              spin={false}
              idle
              scale={7}
              className="absolute inset-0"
            />
          </div>
        ) : null}
      </div>

      {/* In front of the scene and the skin: a web in the corner, pumpkins on the ground
          beside the player, and a wisp of mist around their feet. */}
      {halloween ? (
        <>
          <CornerWeb corner="left" size={128} />
          <Pumpkin size={64} className="bottom-3 right-[214px] hidden lg:block" />
          <Pumpkin size={32} className="bottom-4 right-6" />
          <Mist className="left-1/2 opacity-55" />
        </>
      ) : null}
    </section>
  );
}

/** Mods tile: the selected instance's own mod icons as its picture, opening the browser. */
function ModsTile({ status, count, onOpen }) {
  const name = status?.selectedInstance?.name || "River Default";
  const t = useT();
  const icons = useMemo(() => {
    const seen = new Set();
    const list = [];
    for (const entry of Array.isArray(status?.installedMods) ? status.installedMods : []) {
      const url = entry?.metadata?.iconUrl;
      if (url && !seen.has(url)) { seen.add(url); list.push(url); }
      if (list.length >= 15) break;
    }
    return list;
  }, [status?.installedMods]);

  return (
    <button
      type="button"
      onClick={onOpen}
      className="group relative flex h-[136px] overflow-hidden rounded-xl border border-border/60 bg-card p-6 text-left transition-colors hover:border-primary/50"
    >
      {/* Two rows of four: wide enough to read as a wall of mods, narrow enough to stay
          clear of the text on the left. */}
      {icons.length ? (
        <div className="pointer-events-none absolute right-6 top-1/2 grid -translate-y-1/2 grid-cols-4 gap-2 opacity-85 [mask-image:linear-gradient(to_right,transparent,black_35%)]">
          {icons.slice(0, 8).map((url) => (
            <img key={url} src={url} alt="" draggable={false} className="size-10 rounded-lg object-cover" />
          ))}
        </div>
      ) : (
        <Package className="pointer-events-none absolute right-6 top-1/2 size-16 -translate-y-1/2 text-muted-foreground/30" />
      )}
      {/* The text keeps to the half the icons fade out of. */}
      <div className="relative flex min-w-0 max-w-[46%] flex-col justify-between">
        <p className="m-kicker truncate" title={name}>{name}</p>
        <div>
          <div className="text-xl font-extrabold tracking-[-0.01em]">{t("Mods")}</div>
          <div className="mt-0.5 flex items-center gap-1.5 text-xs text-muted-foreground transition-colors group-hover:text-foreground">
            {t("{count} installed", { count })}
            <ArrowRight className="size-3.5" />
          </div>
        </div>
      </div>
    </button>
  );
}

/** Wardrobe tile: the heads of your saved skins, opening the wardrobe. */
function WardrobeTile({ status, onOpen }) {
  const t = useT();
  const signedIn = Boolean(status?.auth?.signedIn);
  const saved = Array.isArray(status?.skinHistory) ? status.skinHistory : [];
  const skins = saved.slice(0, 4);

  return (
    <button
      type="button"
      onClick={onOpen}
      className="group relative flex h-[136px] overflow-hidden rounded-xl border border-border/60 bg-card p-6 text-left transition-colors hover:border-primary/50"
    >
      {skins.length ? (
        <div className="pointer-events-none absolute right-6 top-1/2 flex -translate-y-1/2 -space-x-3">
          {skins.map((skin) => (
            <PlayerHead
              key={skin.id}
              skinUrl={skin.previewDataUrl || skin.previewFileUrl}
              name={skin.name}
              size={52}
              className="rounded-xl border-2 border-card"
            />
          ))}
        </div>
      ) : (
        <Shirt className="pointer-events-none absolute right-6 top-1/2 size-16 -translate-y-1/2 text-muted-foreground/30" />
      )}
      <div className="relative flex min-w-0 flex-col justify-between">
        <p className="m-kicker">{t("Skins and capes")}</p>
        <div>
          <div className="text-xl font-extrabold tracking-[-0.01em]">{t("Wardrobe")}</div>
          <div className="mt-0.5 flex items-center gap-1.5 text-xs text-muted-foreground transition-colors group-hover:text-foreground">
            {signedIn ? t("{count} saved", { count: saved.length }) : t("Sign in to change your skin")}
            <ArrowRight className="size-3.5" />
          </div>
        </div>
      </div>
    </button>
  );
}

function ServerTile({ server, onJoin, disabled }) {
  const t = useT();
  const players = server.players;
  // Same three states as the Classic list: a failed lookup is unknown, not offline.
  const known = server.statusKnown !== false;
  const offline = known && !server.online;
  const joinable = Boolean(server.ip) && !offline;

  return (
    <div
      className={cn(
        "flex items-center gap-3 rounded-xl border border-border/60 bg-card px-4 py-3 transition-colors hover:border-border",
        offline && "opacity-60"
      )}
    >
      <div className="flex size-11 shrink-0 items-center justify-center overflow-hidden rounded-lg bg-accent">
        {server.icon ? (
          <img src={server.icon} alt="" className="size-full object-cover [image-rendering:pixelated]" />
        ) : (
          <Server className="size-4 text-muted-foreground" />
        )}
      </div>

      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-2">
          <span className="truncate text-sm font-bold">{server.name}</span>
          {server.partner ? <Badge variant="default">{t("Partner")}</Badge> : null}
        </div>
        <div className="mt-1 flex items-center gap-2 text-xs text-muted-foreground">
          <span
            className={cn(
              "size-1.5 shrink-0 rounded-full",
              server.online ? "bg-success" : offline ? "bg-muted-foreground/40" : "bg-muted-foreground/25"
            )}
          />
          {players ? (
            <span className="inline-flex items-center gap-1">
              <Users className="size-3" />
              {players.online}/{players.max}
            </span>
          ) : (
            <span>{server.online ? t("Online") : offline ? t("Offline") : t("Checking...")}</span>
          )}
          {server.ip ? (
            <>
              <span aria-hidden>·</span>
              <ServerAddress ip={server.ip} partner={server.partner} />
            </>
          ) : null}
        </div>
      </div>

      {server.discord ? (
        <Button
          variant="ghost"
          size="icon"
          onClick={() => api()?.openExternal(server.discord)}
          title={t("Open Discord")}
        >
          <ExternalLink className="size-3.5" />
        </Button>
      ) : null}
      <PlayBlock
        size="sm"
        tone="quiet"
        disabled={disabled || !joinable}
        onClick={() => onJoin(server)}
        title={
          !server.ip ? t("No address configured yet")
            : offline ? t("{name} is offline", { name: server.name })
            : t("Join {name}", { name: server.name })
        }
      >
        {t("Join")}
      </PlayBlock>
    </div>
  );
}

/**
 * Home in the Modern layout. Same data and actions as the Classic one (useServerList and
 * useLauncherActions are shared), arranged around pictures: the selected instance as a large
 * hero with your skin and Play, tiles for mods and the wardrobe, your other instances as
 * cards, then servers. Friends sit in a card down the right.
 */
export function ModernHomeView({ status, refresh, notify, go, openInstance }) {
  const t = useT();
  const { servers, reachableServers, offlineServers } = useServerList();
  const { busy, launch, stop } = useLauncherActions(notify);
  const [showOffline, setShowOffline] = useState(false);
  const [switching, setSwitching] = useState(false);
  const [contentOpen, setContentOpen] = useState(false);

  const instances = Array.isArray(status?.instances) ? status.instances : [];
  const cards = useInstanceCards(instances);
  const selected = status?.selectedInstance;
  const profile = status?.auth?.profile;
  const running = Boolean(status?.running);
  const launching = status?.launchState === "launching";
  const disabled = busy || launching || running;

  const pick = async (id) => {
    if (!id || id === selected?.id) return;
    try { await api()?.selectInstance(id); await refresh(); }
    catch (e) { notify(String(e?.message || e), "error"); }
  };

  const playInstance = async (instance) => {
    if (instance.id !== selected?.id) await pick(instance.id);
    await launch("");
  };

  // Most recently played first. Hidden with a single instance: the hero already shows it.
  const recent = [...instances]
    .sort((a, b) => (Number(b.lastPlayedAt) || 0) - (Number(a.lastPlayedAt) || 0))
    .slice(0, 3);

  const modCount = cards[selected?.id]?.modCount ?? (Array.isArray(status?.installedMods) ? status.installedMods.length : 0);

  return (
    <div className="flex h-full min-h-0">
      <div className="min-w-0 flex-1 overflow-y-auto px-8 pb-8">
        <header className="pb-6 pt-3">
          <h1 className="truncate text-[26px] font-extrabold leading-tight tracking-[-0.02em]">
            {profile ? (
              <>
                {t("Welcome back,")} <span className="text-primary">{profile.name}</span>
              </>
            ) : t("Welcome to River")}
          </h1>
        </header>

        <div className="space-y-8">
          <ModCompatibility status={status} refresh={refresh} notify={notify} />
          <CrashReport status={status} refresh={refresh} notify={notify} />
          {!profile ? <SignInCard notify={notify} /> : null}

          <div className="space-y-4">
            <Hero
              status={status}
              card={cards[selected?.id]}
              busy={busy}
              launch={launch}
              stop={stop}
              onPick={pick}
              notify={notify}
            />
            <div className="grid gap-4 lg:grid-cols-2">
              <ModsTile status={status} count={modCount} onOpen={() => setContentOpen(true)} />
              <WardrobeTile status={status} onOpen={() => go("wardrobe")} />
            </div>
          </div>

          {instances.length > 1 ? (
            <section className="space-y-4">
              <SectionHead
                title={t("Your instances")}
                action={(
                  <button
                    type="button"
                    onClick={() => go("instances")}
                    className="flex items-center gap-1 text-xs font-semibold text-muted-foreground transition-colors hover:text-foreground"
                  >
                    {t("See all")}<ArrowRight className="size-3.5" />
                  </button>
                )}
              />
              <div className="grid grid-cols-[repeat(auto-fill,minmax(220px,1fr))] gap-4">
                {recent.map((instance) => (
                  <ModernInstanceCard
                    key={instance.id}
                    instance={instance}
                    card={cards[instance.id]}
                    selected={instance.id === selected?.id}
                    disabled={disabled}
                    onOpen={() => openInstance?.(instance.id)}
                    onPlay={() => playInstance(instance)}
                  />
                ))}
              </div>
            </section>
          ) : null}

          <section className="space-y-4">
            <SectionHead title={t("Servers")} />
            {servers.length ? (
              <div className="space-y-3">
                <div className="grid gap-3 xl:grid-cols-2">
                  {reachableServers.map((server) => (
                    <ServerTile
                      key={`${server.ip || "?"}:${server.name}`}
                      server={server}
                      disabled={disabled}
                      onJoin={(s) => launch(s.ip)}
                    />
                  ))}
                </div>
                {offlineServers.length ? (
                  <div>
                    <button
                      type="button"
                      onClick={() => setShowOffline((v) => !v)}
                      className="flex w-full items-center justify-between rounded-md px-1 py-1.5 text-xs text-muted-foreground transition-colors hover:text-foreground"
                    >
                      <span>{t("Offline")}</span>
                      <span className="flex items-center gap-1.5">
                        {offlineServers.length}
                        <ChevronDown className={cn("size-3.5 transition-transform", showOffline && "rotate-180")} />
                      </span>
                    </button>
                    {showOffline ? (
                      <div className="mt-2 grid gap-3 xl:grid-cols-2">
                        {offlineServers.map((server) => (
                          <ServerTile
                            key={`${server.ip || "?"}:${server.name}`}
                            server={server}
                            disabled={disabled}
                            onJoin={(s) => launch(s.ip)}
                          />
                        ))}
                      </div>
                    ) : null}
                  </div>
                ) : null}
              </div>
            ) : (
              <div className="flex flex-col items-center gap-2 rounded-xl border border-dashed border-border/70 py-12 text-center">
                <Server className="size-6 text-muted-foreground" />
                <div className="text-sm font-medium">{t("No servers yet")}</div>
                <p className="text-xs text-muted-foreground">{t("Servers you join show up here.")}</p>
              </div>
            )}
          </section>

          <section className="flex flex-wrap items-center justify-between gap-3 border-t border-border/60 pt-6">
            <div className="flex min-w-0 items-center gap-3">
              <FolderInput className="size-4 shrink-0 text-muted-foreground" />
              <div className="min-w-0">
                <h2 className="text-sm font-semibold">{t("Coming from another launcher?")}</h2>
                <p className="mt-0.5 text-xs text-muted-foreground">
                  {t("Bring your worlds, packs and servers into River. Your old profile stays untouched.")}
                </p>
              </div>
            </div>
            <Button variant="outline" size="sm" onClick={() => setSwitching(true)}>
              {t("Switch to River")}
            </Button>
          </section>
        </div>
      </div>

      <div className="flex w-[256px] shrink-0 flex-col pb-6 pr-8 pt-3 xl:w-[288px]">
        <FriendsPanel status={status} refresh={refresh} notify={notify} variant="card" />
      </div>

      <ImportInstancesDialog
        open={switching}
        onOpenChange={setSwitching}
        refresh={refresh}
        notify={notify}
        targetVersion={status?.selectedVersion || "1.21.11"}
        availableVersions={status?.versions?.map((version) => version.id) || []}
      />

      <ContentDialog
        open={contentOpen}
        onOpenChange={setContentOpen}
        title={t("Add content to {name}", { name: selected?.name || "River Default" })}
        description={t("Browse Modrinth and CurseForge for mods, resource packs and shaders.")}
        status={status}
        refresh={refresh}
        notify={notify}
      />
    </div>
  );
}

import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { UserPlus, Users, Trash2, Loader2, Check, ChevronDown, MessageSquare, X, Ban } from "lucide-react";
import { api } from "../lib/useStatus";
import { cn } from "../lib/utils";
import { PlayerHead } from "./player-head";
import { FriendsChat } from "./friends-chat";
import { Button } from "../components/ui/button";
import { Input } from "../components/ui/input";
import { Label } from "../components/ui/label";
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle
} from "../components/ui/dialog";

const RANK = { online: 0, idle: 1, dnd: 2, busy: 2, unknown: 3, offline: 4 };

/** Online first, then idle, then do-not-disturb, then offline; alphabetical inside each. */
function sortFriends(friends) {
  return [...friends].sort((a, b) => {
    const byStatus = (RANK[a.status] ?? 3) - (RANK[b.status] ?? 3);
    if (byStatus !== 0) return byStatus;
    return a.name.localeCompare(b.name);
  });
}

/** Colour per state, straight from the theme tokens so light mode follows automatically. */
const STATUS_COLOR = {
  online: "var(--success)",
  idle: "var(--warning)",
  dnd: "var(--destructive)",
  busy: "var(--warning)",
  invisible: "var(--muted-foreground)",
  offline: "var(--muted-foreground)",
  unknown: "var(--muted-foreground)"
};

/** Which shape a state draws: filled, crescent, barred, or hollow ring. */
const STATUS_SHAPE = {
  online: "full",
  idle: "idle",
  dnd: "dnd",
  busy: "dnd",
  invisible: "ring",
  offline: "ring",
  unknown: "ring"
};

/**
 * Status badge shapes rather than four identical dots in different colours: at 10px the
 * colour alone is easy to misread, and the silhouette stays legible for anyone who cannot
 * separate the red and green.
 *
 * The cutouts are SVG masks, so the bite in the crescent and the hole in the ring are
 * genuinely transparent. Painting them in the card colour instead would show as a solid
 * blob wherever the badge overlaps the avatar or a hovered row.
 */
function StatusIcon({ status, size = 10, className }) {
  const id = React.useId();
  const shape = STATUS_SHAPE[status] || "ring";
  const color = STATUS_COLOR[status] || STATUS_COLOR.offline;
  const maskId = `status-${id}`;
  const dim = status === "offline" || status === "unknown" || status === "invisible";

  return (
    <svg
      viewBox="0 0 24 24"
      width={size}
      height={size}
      className={className}
      style={{ opacity: dim ? 0.55 : 1 }}
      aria-hidden
    >
      <mask id={maskId}>
        <rect width="24" height="24" fill="black" />
        <circle cx="12" cy="12" r="12" fill="white" />
        {/* Offset circle bites a crescent out of the top-right. */}
        {shape === "idle" ? <circle cx="19" cy="6" r="10" fill="black" /> : null}
        {shape === "dnd" ? <rect x="4.5" y="9.5" width="15" height="5" rx="2.5" fill="black" /> : null}
        {shape === "ring" ? <circle cx="12" cy="12" r="6" fill="black" /> : null}
      </mask>
      <circle cx="12" cy="12" r="12" fill={color} mask={`url(#${maskId})`} />
    </svg>
  );
}

const STATUS_LABEL = {
  online: "Online",
  idle: "Idle",
  dnd: "Do Not Disturb",
  invisible: "Invisible",
  offline: "Offline",
  busy: "Busy",
  unknown: "Unknown"
};

/**
 * A friend's real head, cropped from their Minecraft skin.
 *
 * The texture URL is resolved in the main process through Mojang's own API (and cached
 * there), rather than pointing an <img> at an avatar service - the same reasoning as
 * PlayerHead itself, which exists because those services are unreachable on some networks
 * and left a broken glyph. Falls back to the initial automatically when lookup fails.
 */
function FriendHead({ name, size = 28 }) {
  const [skinUrl, setSkinUrl] = useState("");

  useEffect(() => {
    let alive = true;
    api()?.getPlayerSkin?.(name)
      .then((res) => { if (alive && res?.ok) setSkinUrl(res.url); })
      .catch(() => {});
    return () => { alive = false; };
  }, [name]);

  return <PlayerHead skinUrl={skinUrl} name={name} size={size} />;
}

function FriendRow({ friend, onOpen, onRemove }) {
  return (
    <div
      onClick={onOpen}
      className="group flex cursor-pointer items-center gap-2.5 rounded-md px-2 py-1.5 transition-colors hover:bg-accent/50"
    >
      <div className="relative shrink-0">
        <FriendHead name={friend.name} size={28} />
        {/* Card-coloured collar keeps the badge readable against the skin behind it. */}
        <span className="absolute -bottom-1 -right-1 rounded-full bg-card p-[2px] leading-none">
          <StatusIcon status={friend.status} size={10} />
        </span>
      </div>

      <div className="min-w-0 flex-1">
        <div className="truncate text-xs font-medium">{friend.name}</div>
        <div className="truncate text-[11px] text-muted-foreground">
          {STATUS_LABEL[friend.status] || "Offline"}
        </div>
      </div>

      <div className="flex shrink-0 items-center opacity-0 transition-opacity group-hover:opacity-100 focus-within:opacity-100">
        <Button size="icon" variant="ghost" className="size-6" title="Message" onClick={(e) => { e.stopPropagation(); onOpen(); }}>
          <MessageSquare className="size-3" />
        </Button>
        <Button size="icon" variant="ghost" className="size-6" title="Remove" onClick={(e) => { e.stopPropagation(); onRemove(friend); }}>
          <Trash2 className="size-3 text-destructive" />
        </Button>
      </div>
    </div>
  );
}

/**
 * Your own availability. Everything here is broadcast to friends by the main process on a
 * heartbeat, so these are real states rather than a local label: Invisible actually
 * removes you from the roster, and Idle is also what auto-idle flips you to.
 */
function StatusPicker({ status, refresh, notify }) {
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const current = status?.settings?.socialStatus || "online";
  const profile = status?.auth?.profile;

  const pick = async (next) => {
    setOpen(false);
    if (next === current) return;
    setBusy(true);
    try {
      const res = await api()?.setSocialStatus?.(next);
      if (res && res.ok === false && res.message) notify?.(res.message, "error");
      await refresh();
    } catch (e) {
      notify?.(String(e?.message || e), "error");
    } finally {
      setBusy(false);
    }
  };

  if (!profile?.name) return null;

  return (
    <div className="relative border-t border-border px-2 py-2">
      <button
        onClick={() => setOpen((v) => !v)}
        disabled={busy}
        className="flex w-full items-center gap-2.5 rounded-md px-1.5 py-1.5 transition-colors hover:bg-accent/50"
      >
        <div className="relative shrink-0">
          <FriendHead name={profile.name} size={28} />
          <span className="absolute -bottom-1 -right-1 rounded-full bg-card p-[2px] leading-none">
            <StatusIcon status={current} size={10} />
          </span>
        </div>
        <div className="min-w-0 flex-1 text-left">
          <div className="truncate text-xs font-medium">{profile.name}</div>
          <div className="truncate text-[11px] text-muted-foreground">{STATUS_LABEL[current]}</div>
        </div>
        {busy ? <Loader2 className="size-3 animate-spin text-muted-foreground" /> : <ChevronDown className="size-3 text-muted-foreground" />}
      </button>

      {open ? (
        <div className="absolute bottom-full left-2 right-2 z-20 mb-1 overflow-hidden rounded-md border border-border bg-popover shadow-lg">
          {["online", "idle", "dnd", "invisible"].map((value) => (
            <button
              key={value}
              onClick={() => pick(value)}
              className="flex w-full items-center gap-2 px-2.5 py-1.5 text-left text-xs transition-colors hover:bg-accent"
            >
              <StatusIcon status={value} size={11} className="shrink-0" />
              <span className="flex-1">{STATUS_LABEL[value]}</span>
              {current === value ? <Check className="size-3 text-primary" /> : null}
            </button>
          ))}
        </div>
      ) : null}
    </div>
  );
}

export function FriendsPanel({ status, refresh, notify }) {
  const [adding, setAdding] = useState(false);
  const [name, setName] = useState("");
  const [busy, setBusy] = useState(false);

  // Backed by the same verified service as the in-game client, so this is one shared
  // friends list rather than the local settings.friends list this panel used to keep.
  const [roster, setRoster] = useState({ friends: [], requests: [] });
  const [blocked, setBlocked] = useState([]);
  const [showBlocked, setShowBlocked] = useState(false);
  const [openChat, setOpenChat] = useState(null);
  const [reachable, setReachable] = useState(true);
  const seenMessages = useRef(new Set());
  const dnd = status?.settings?.socialStatus === "dnd";

  const poll = useCallback(async () => {
    try {
      const res = await api()?.riverSocial?.("roster");
      if (!res || res.ok === false) { setReachable(false); return; }
      setReachable(true);
      setRoster({ friends: res.friends || [], requests: res.requests || [] });

      // The backend drains its inbox as it reports it, so each message is announced once.
      // Do Not Disturb suppresses the alert but still consumes it - otherwise turning DND
      // off later would dump a backlog.
      for (const notice of res.inbox || []) {
        const key = `${notice.from}:${notice.at}`;
        if (seenMessages.current.has(key)) continue;
        seenMessages.current.add(key);
        if (!dnd) notify?.(`${notice.fromName}: ${notice.text}`, "info");
      }
    } catch {
      setReachable(false);
    }
  }, [dnd, notify]);

  useEffect(() => {
    poll();
    const timer = setInterval(poll, 15000);
    return () => clearInterval(timer);
  }, [poll]);

  const act = async (action, payload, successMessage) => {
    setBusy(true);
    try {
      const res = await api()?.riverSocial?.(action, payload);
      if (res?.ok === false) notify?.(res.message || "That did not work.", "error");
      else if (successMessage) notify?.(res?.message || successMessage, "info");
      await poll();
      if (showBlocked) await loadBlocked();
      return res;
    } finally { setBusy(false); }
  };

  const loadBlocked = useCallback(async () => {
    const res = await api()?.riverSocial?.("blocked");
    if (Array.isArray(res?.blocked)) setBlocked(res.blocked);
  }, []);

  const friends = useMemo(() => sortFriends(roster.friends), [roster.friends]);
  const onlineCount = friends.filter((f) => f.online).length;

  const add = async () => {
    const trimmed = name.trim();
    if (!trimmed) return;
    const res = await act("add", { name: trimmed });
    if (res?.ok !== false) { setName(""); setAdding(false); }
  };

  // A conversation takes over the whole panel: at 256px wide there is no room for a list
  // and a thread side by side, and the back arrow keeps the roster one click away.
  if (openChat) {
    return (
      <aside className="flex w-64 shrink-0 flex-col border-l border-border bg-card">
        <FriendsChat
          friend={openChat}
          onBack={() => setOpenChat(null)}
          notify={notify}
          onRemove={() => { act("remove", { uuid: openChat.uuid }, "Friend removed."); setOpenChat(null); }}
          onBlock={() => { act("block", { uuid: openChat.uuid }, "Player blocked."); setOpenChat(null); }}
        />
      </aside>
    );
  }

  return (
    <aside className="flex w-64 shrink-0 flex-col border-l border-border bg-card">
      <div className="flex items-center justify-between gap-2 px-3 py-3">
        <div className="min-w-0">
          <h2 className="text-sm font-semibold">{showBlocked ? "Blocked" : "Friends"}</h2>
          <p className="text-[11px] text-muted-foreground">
            {!reachable
              ? "River unreachable"
              : showBlocked
                ? `${blocked.length} blocked`
                : !friends.length
                  ? "No friends yet"
                  : `${onlineCount} online`}
          </p>
        </div>
        <Button
          size="icon" variant="ghost" className="size-7"
          title={showBlocked ? "Back to friends" : "Blocked players"}
          onClick={() => { const next = !showBlocked; setShowBlocked(next); if (next) loadBlocked(); }}
        >
          <Ban className={cn("size-3.5", showBlocked && "text-primary")} />
        </Button>
        <Button size="icon" variant="ghost" className="size-7" title="Add friend" onClick={() => setAdding(true)}>
          <UserPlus className="size-3.5" />
        </Button>
      </div>

      <div className="min-h-0 flex-1 overflow-y-auto px-2 pb-3">
        {/* Incoming requests sit above the roster: they are the thing needing an answer. */}
        {roster.requests.length ? (
          <div className="mb-2 space-y-1">
            <div className="px-1 text-[10px] font-medium uppercase tracking-wide text-muted-foreground">
              Pending requests
            </div>
            {roster.requests.map((request) => (
              <div key={request.from} className="flex items-center gap-2 rounded-md border border-border bg-background px-2 py-1.5">
                <FriendHead name={request.name} size={22} />
                <span className="min-w-0 flex-1 truncate text-xs font-medium">{request.name}</span>
                <Button size="icon" variant="ghost" className="size-6" title="Accept"
                  onClick={() => act("accept", { uuid: request.from }, "Friend added.")}>
                  <Check className="size-3 text-success" />
                </Button>
                <Button size="icon" variant="ghost" className="size-6" title="Decline"
                  onClick={() => act("decline", { uuid: request.from }, "Request declined.")}>
                  <X className="size-3" />
                </Button>
              </div>
            ))}
          </div>
        ) : null}

        {showBlocked ? (
          <div className="space-y-1">
            {blocked.length ? blocked.map((entry) => (
              <div key={entry.uuid} className="flex items-center gap-2 rounded-md px-2 py-1.5">
                <FriendHead name={entry.name} size={22} />
                <span className="min-w-0 flex-1 truncate text-xs text-muted-foreground">{entry.name}</span>
                <Button size="sm" variant="outline" className="h-6 px-2 text-[11px]"
                  onClick={() => act("unblock", { uuid: entry.uuid }, "Player unblocked.")}>
                  Unblock
                </Button>
              </div>
            )) : (
              <p className="px-2 py-6 text-center text-[11px] text-muted-foreground">You have not blocked anyone.</p>
            )}
          </div>
        ) : friends.length ? (
          <div className="space-y-0.5">
            {friends.map((friend) => (
              <FriendRow
                key={friend.uuid}
                friend={friend}
                onOpen={() => setOpenChat(friend)}
                onRemove={(f) => act("remove", { uuid: f.uuid }, "Friend removed.")}
              />
            ))}
          </div>
        ) : (
          <div className="flex flex-col items-center gap-2 px-3 py-10 text-center">
            <Users className="size-5 text-muted-foreground" />
            <p className="text-[11px] leading-relaxed text-muted-foreground">
              Add a friend to see when they are online.
            </p>
            <Button size="sm" variant="outline" onClick={() => setAdding(true)}>
              <UserPlus className="size-3.5" />
              Add friend
            </Button>
          </div>
        )}
      </div>

      <StatusPicker status={status} refresh={refresh} notify={notify} />

      {status?.settings?.friendCode ? (
        <div className="border-t border-border px-3 py-2.5">
          <div className="text-[11px] text-muted-foreground">Your friend code</div>
          <button
            onClick={() => {
              navigator.clipboard?.writeText(status.settings.friendCode);
              notify("Friend code copied.", "info");
            }}
            className="mt-0.5 w-full truncate text-left font-mono text-xs transition-colors hover:text-primary"
            title="Click to copy"
          >
            {status.settings.friendCode}
          </button>
        </div>
      ) : null}

      <Dialog open={adding} onOpenChange={setAdding}>
        <DialogContent className="max-w-sm">
          <DialogHeader>
            <DialogTitle>Add friend</DialogTitle>
            <DialogDescription>Enter their Minecraft name or River friend code.</DialogDescription>
          </DialogHeader>
          <div className="space-y-2">
            <Label htmlFor="friend-name">Name</Label>
            <Input
              id="friend-name"
              value={name}
              placeholder="Notch"
              onChange={(e) => setName(e.target.value)}
              onKeyDown={(e) => { if (e.key === "Enter" && !busy) add(); }}
            />
          </div>
          <DialogFooter>
            <Button variant="ghost" onClick={() => setAdding(false)} disabled={busy}>Cancel</Button>
            <Button onClick={add} disabled={busy || !name.trim()}>
              {busy ? <Loader2 className="size-4 animate-spin" /> : null}
              Add
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </aside>
  );
}

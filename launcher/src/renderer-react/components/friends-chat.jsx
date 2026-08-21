import React, { useCallback, useEffect, useRef, useState } from "react";
import { ArrowLeft, Send, Loader2, Ban, UserMinus } from "lucide-react";
import { api } from "../lib/useStatus";
import { cn } from "../lib/utils";
import { PlayerHead } from "./player-head";
import { Button } from "./ui/button";
import { Input } from "./ui/input";

/**
 * One-to-one chat with a friend, sharing the same backend as the in-game client - so a
 * conversation started in game continues here and vice versa.
 *
 * Polls while open rather than holding a socket: the panel is small, the traffic is tiny,
 * and it avoids a reconnect story for something the user has open for a minute at a time.
 */
export function FriendsChat({ friend, skinUrl, onBack, onRemove, onBlock, notify }) {
  const [messages, setMessages] = useState([]);
  const [draft, setDraft] = useState("");
  const [loading, setLoading] = useState(true);
  const [sending, setSending] = useState(false);
  const bottomRef = useRef(null);

  const load = useCallback(async () => {
    try {
      const res = await api()?.riverSocial?.("history", { uuid: friend.uuid });
      if (Array.isArray(res?.messages)) setMessages(res.messages);
    } catch {} finally { setLoading(false); }
  }, [friend.uuid]);

  useEffect(() => {
    setLoading(true);
    setMessages([]);
    load();
    const timer = setInterval(load, 5000);
    return () => clearInterval(timer);
  }, [load]);

  useEffect(() => { bottomRef.current?.scrollIntoView({ block: "end" }); }, [messages.length]);

  const send = async () => {
    const text = draft.trim();
    if (!text || sending) return;
    setSending(true);
    setDraft("");
    try {
      const res = await api()?.riverSocial?.("send", { uuid: friend.uuid, text });
      if (res?.ok === false) notify?.(res.message || "Could not send that.", "error");
      await load();
    } finally { setSending(false); }
  };

  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="flex items-center gap-2 border-b border-border px-2 py-2">
        <Button size="icon" variant="ghost" className="size-7" onClick={onBack} title="Back">
          <ArrowLeft className="size-3.5" />
        </Button>
        <PlayerHead skinUrl={skinUrl} name={friend.name} size={22} />
        <div className="min-w-0 flex-1">
          <div className="truncate text-xs font-medium">{friend.name}</div>
          <div className="truncate text-[11px] text-muted-foreground">
            {friend.online ? (friend.server || "Online") : "Offline"}
          </div>
        </div>
        <Button size="icon" variant="ghost" className="size-7" title="Remove friend" onClick={onRemove}>
          <UserMinus className="size-3.5" />
        </Button>
        <Button size="icon" variant="ghost" className="size-7" title="Block" onClick={onBlock}>
          <Ban className="size-3.5 text-destructive" />
        </Button>
      </div>

      <div className="min-h-0 flex-1 space-y-2 overflow-y-auto px-3 py-2">
        {loading ? (
          <div className="flex items-center gap-2 text-[11px] text-muted-foreground">
            <Loader2 className="size-3 animate-spin" /> Loading messages…
          </div>
        ) : !messages.length ? (
          <p className="text-[11px] text-muted-foreground">No messages yet. Say hello.</p>
        ) : (
          messages.map((message) => {
            const mine = message.from !== friend.uuid;
            return (
              <div key={message.id} className={cn("flex", mine ? "justify-end" : "justify-start")}>
                <div
                  className={cn(
                    "max-w-[85%] rounded-md px-2.5 py-1.5 text-xs leading-relaxed",
                    mine ? "bg-primary text-primary-foreground" : "bg-accent"
                  )}
                >
                  {message.text}
                </div>
              </div>
            );
          })
        )}
        <div ref={bottomRef} />
      </div>

      <div className="flex items-center gap-1.5 border-t border-border p-2">
        <Input
          value={draft}
          placeholder={`Message ${friend.name}`}
          className="h-8 text-xs"
          maxLength={512}
          onChange={(e) => setDraft(e.target.value)}
          onKeyDown={(e) => { if (e.key === "Enter") send(); }}
        />
        <Button size="icon" className="size-8 shrink-0" onClick={send} disabled={sending || !draft.trim()}>
          {sending ? <Loader2 className="size-3.5 animate-spin" /> : <Send className="size-3.5" />}
        </Button>
      </div>
    </div>
  );
}

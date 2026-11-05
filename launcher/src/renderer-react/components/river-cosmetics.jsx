import React, { useEffect, useState } from "react";
import { Ban, Check, Loader2 } from "lucide-react";
import { api } from "../lib/useStatus";
import { cn } from "../lib/utils";
import { Switch } from "./ui/switch";
import { CapeFront } from "./skin-art";
import { useT } from "../i18n/context.jsx";

function CapeCard({ label, active, disabled, onClick, children }) {
  return (
    <button
      type="button"
      disabled={disabled}
      onClick={onClick}
      className={cn(
        "relative flex flex-col items-center gap-2 rounded-lg border bg-card px-3 py-3 transition-colors disabled:opacity-60",
        active ? "border-primary" : "border-border/60 hover:border-border"
      )}
    >
      {active ? <Check className="absolute right-2 top-2 size-3.5 text-primary" /> : null}
      <div className="flex h-24 items-center justify-center">{children}</div>
      <span className="truncate text-xs font-semibold">{label}</span>
    </button>
  );
}

/**
 * River's own cape and badge, the same ones as the in-game Cosmetics screen. They live in each
 * instance's River profile, so a change here is written to every instance that has one; a game
 * that is open picks it up by itself.
 */
export function RiverCosmetics({ notify }) {
  const t = useT();
  const [state, setState] = useState(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let alive = true;
    api()?.getRiverCosmetics?.()
      .then((res) => { if (alive) setState(res || { ok: false }); })
      .catch(() => { if (alive) setState({ ok: false }); });
    return () => { alive = false; };
  }, []);

  const save = async (patch) => {
    setBusy(true);
    try {
      const res = await api()?.setRiverCosmetics?.(patch);
      if (!res || res.ok === false) notify?.(res?.message || t("Could not save that."), "error");
      else setState((current) => ({ ...current, ...patch }));
    } catch (e) {
      notify?.(String(e?.message || e), "error");
    } finally {
      setBusy(false);
    }
  };

  if (!state) {
    return <div className="flex h-40 items-center justify-center"><Loader2 className="size-4 animate-spin text-muted-foreground" /></div>;
  }
  if (state.ok === false) {
    return <p className="py-10 text-center text-xs text-muted-foreground">{state.message || t("Could not load cosmetics.")}</p>;
  }
  if (!state.hasProfile) {
    return <p className="py-10 text-center text-xs text-muted-foreground">{t("Play a River instance once to use cosmetics.")}</p>;
  }

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-[repeat(auto-fill,minmax(120px,1fr))] gap-3">
        <CapeCard label={t("No cape")} active={!state.capeStyle} disabled={busy} onClick={() => save({ capeStyle: "" })}>
          <Ban className="size-8 text-muted-foreground" />
        </CapeCard>
        {state.capes.map((cape) => (
          <CapeCard key={cape.id} label={cape.label} active={state.capeStyle === cape.id} disabled={busy} onClick={() => save({ capeStyle: cape.id })}>
            <CapeFront src={`../assets/capes/${cape.id}.png`} scale={6} />
          </CapeCard>
        ))}
      </div>
      <div className="flex items-center justify-between gap-3 rounded-lg border border-border/60 bg-card px-4 py-3">
        <div className="min-w-0">
          <div className="text-sm font-semibold">{t("River badge")}</div>
          <div className="text-[11px] text-muted-foreground">{t("Shows next to your name in game.")}</div>
        </div>
        <Switch checked={Boolean(state.badge)} disabled={busy} onCheckedChange={(badge) => save({ badge })} aria-label={t("River badge")} />
      </div>
      <p className="text-[11px] text-muted-foreground">{t("Capes from a code are redeemed in game, in Cosmetics.")}</p>
    </div>
  );
}

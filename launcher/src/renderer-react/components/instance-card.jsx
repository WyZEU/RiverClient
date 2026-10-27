import React from "react";
import { TriangleAlert } from "lucide-react";
import { cn, formatLastPlayed } from "../lib/utils";
import { InstanceArt, PlayBlock } from "./instance-art";
import { useT } from "../i18n/context.jsx";

/** Instances made without River (any loader but Fabric, or plain Fabric) lack it on purpose, so that is no warning there. */
export function isPlainInstance(instance) {
  const loader = String(instance?.loader || "fabric").toLowerCase();
  return (loader !== "fabric" && ["forge", "neoforge", "quilt", "vanilla"].includes(loader)) || instance?.river === false;
}

/** "NeoForge 1.21.1" rather than "neoforge 1.21.1". */
function prettyLoader(loader) {
  const value = String(loader || "");
  if (value === "neoforge") return "NeoForge";
  return value ? value.charAt(0).toUpperCase() + value.slice(1) : "";
}

/** "Fabric 1.21.11" rather than "fabric 1.21.11". */
export function instanceVersionLabel(instance) {
  const name = prettyLoader(instance?.loader);
  return [name, instance?.version].filter(Boolean).join(" ");
}

/**
 * One instance in the Modern layout: its picture, what it runs, when it was last played, and
 * Play. Clicking the picture or the name opens the instance; `actions` is the slot for the
 * buttons beside Play (the Instances page puts its menu there).
 */
export function ModernInstanceCard({ instance, card, selected, busy, disabled, onOpen, onPlay, actions }) {
  const t = useT();
  const lastPlayed = formatLastPlayed(instance.lastPlayedAt);
  const meta = [
    lastPlayed || (instance.lastPlayedAt ? "" : t("Not played yet")),
    card ? t("{count} mods", { count: card.modCount || 0 }) : ""
  ].filter(Boolean).join(" · ");

  return (
    <div
      className={cn(
        "group flex flex-col overflow-hidden rounded-xl border bg-card transition-colors",
        selected ? "border-primary/60" : "border-border/60 hover:border-border"
      )}
    >
      <button
        type="button"
        onClick={onOpen}
        aria-label={t("Open {name}", { name: instance.name })}
        className="relative block h-32 w-full shrink-0 overflow-hidden"
      >
        <InstanceArt instanceId={instance.id} card={card} />
        <div className="absolute inset-0 bg-gradient-to-t from-card via-card/25 to-transparent" />
        <span className="absolute left-4 top-4 rounded-md bg-background/85 px-2 py-1 text-[11px] font-semibold">
          {instanceVersionLabel(instance) || t("unknown version")}
        </span>
        {selected ? (
          <span className="absolute right-4 top-4 rounded-md bg-primary px-2 py-1 text-[11px] font-semibold text-primary-foreground">
            {t("Selected")}
          </span>
        ) : null}
      </button>

      <div className="flex flex-1 flex-col gap-3 px-4 pb-4 pt-1">
        <button type="button" onClick={onOpen} className="min-w-0 text-left" tabIndex={-1}>
          <div className="truncate text-[15px] font-bold leading-snug">{instance.name}</div>
          <div className="mt-0.5 truncate text-xs text-muted-foreground">{meta || " "}</div>
          {instance.riverSupported === false && !isPlainInstance(instance) ? (
            <div className="mt-1 flex items-center gap-1 text-[11px] text-warning">
              <TriangleAlert className="size-3 shrink-0" />
              <span className="truncate">{t("River not available in game")}</span>
            </div>
          ) : null}
        </button>
        <div className="mt-auto flex items-center gap-2">
          <PlayBlock size="sm" tone="quiet" busy={busy} disabled={disabled} onClick={onPlay}>{t("Play")}</PlayBlock>
          <div className="ml-auto flex items-center gap-1">{actions}</div>
        </div>
      </div>
    </div>
  );
}

import React from "react";
import { Home, Boxes, Shirt, Settings, Minus, Square, X, LogIn } from "lucide-react";
import { cn } from "../lib/utils";
import { api } from "../lib/useStatus";
import { useModern } from "../lib/ui-style";
import { PlayerHead, resolveSkinTexture } from "./player-head";
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from "./ui/tooltip";
import { useT } from "../i18n/context.jsx";

export const NAV = [
  { id: "home", label: "Home", icon: Home },
  { id: "instances", label: "Instances", icon: Boxes },
  { id: "wardrobe", label: "Wardrobe", icon: Shirt }
];

/**
 * Narrow icon rail. Settings sits pinned at the bottom, away from the three
 * content views, so the destination you reach least often never competes with
 * the ones you reach constantly.
 */
export function Rail({ view, setView }) {
  const t = useT();
  const item = (entry) => {
    const Icon = entry.icon;
    const active = view === entry.id;
    return (
      <Tooltip key={entry.id} delayDuration={200}>
        <TooltipTrigger asChild>
          <button
            aria-label={t(entry.label)}
            aria-current={active ? "page" : undefined}
            data-tour={`rail-${entry.id}`}
            onClick={() => setView(entry.id)}
            className={cn(
              "no-drag relative flex size-10 shrink-0 items-center justify-center rounded-md transition-colors",
              active ? "bg-accent text-foreground" : "text-muted-foreground hover:bg-accent/60 hover:text-foreground"
            )}
          >
            {/* The active marker is the one accent in the rail. */}
            <span
              className={cn(
                "absolute left-0 h-5 w-0.5 rounded-r-full bg-primary transition-opacity",
                active ? "opacity-100" : "opacity-0"
              )}
            />
            <Icon className="size-[18px]" />
          </button>
        </TooltipTrigger>
        <TooltipContent side="right">{t(entry.label)}</TooltipContent>
      </Tooltip>
    );
  };

  return (
    <TooltipProvider>
      <nav className="flex w-14 shrink-0 flex-col items-center gap-1 overflow-y-auto overflow-x-hidden border-r border-border bg-card py-3 [scrollbar-width:none] [&::-webkit-scrollbar]:hidden">
        <img
          src="../assets/river-logo.png"
          alt="River Client"
          className="mb-2 size-8 shrink-0 select-none object-contain"
          draggable={false}
        />
        {NAV.map(item)}
        <div className="min-h-0 flex-1" />
        {item({ id: "settings", label: "Settings", icon: Settings })}
      </nav>
    </TooltipProvider>
  );
}

/** Colour of the availability dot on the account button, from the theme tokens. */
const PRESENCE_COLOR = {
  online: "var(--success)",
  idle: "var(--warning)",
  dnd: "var(--destructive)",
  invisible: "var(--muted-foreground)"
};

/**
 * The Modern layout's rail: full window height with the logo in the title bar's place, each
 * destination labelled under its icon, and your account at the bottom. Labels instead of
 * tooltips because the rail is wide enough to say where each button goes, and a label is
 * read at a glance where a tooltip has to be hovered for.
 */
export function ModernRail({ view, setView, status }) {
  const t = useT();
  const profile = status?.auth?.profile;
  const presence = status?.settings?.socialStatus || "online";

  const item = (entry) => {
    const Icon = entry.icon;
    const active = view === entry.id;
    return (
      <button
        key={entry.id}
        aria-label={t(entry.label)}
        aria-current={active ? "page" : undefined}
        data-tour={`rail-${entry.id}`}
        onClick={() => setView(entry.id)}
        className={cn(
          "no-drag flex h-[58px] w-[60px] shrink-0 flex-col items-center justify-center gap-1.5 rounded-xl transition-colors",
          active ? "bg-primary/15 text-primary" : "text-muted-foreground hover:bg-accent hover:text-foreground"
        )}
      >
        <Icon className="size-5" strokeWidth={active ? 2.25 : 2} />
        <span className={cn("max-w-full truncate px-1 text-[10px] font-semibold leading-none", active && "text-foreground")}>
          {t(entry.label)}
        </span>
      </button>
    );
  };

  return (
    <nav className="flex w-[76px] shrink-0 flex-col items-center gap-1.5 border-r border-border/60 bg-card pb-3">
      {/* Stands in for the title bar on this side of the window, so it drags the window too. */}
      <div className="drag-region flex h-16 w-full shrink-0 items-center justify-center">
        <img
          src="../assets/river-logo.png"
          alt="River Client"
          className="size-8 select-none object-contain"
          draggable={false}
        />
      </div>
      {NAV.map(item)}
      <div className="min-h-0 flex-1" />
      {item({ id: "settings", label: "Settings", icon: Settings })}

      {profile ? (
        <button
          onClick={() => setView("wardrobe")}
          title={`${profile.name} · ${t("Wardrobe")}`}
          aria-label={t("Wardrobe")}
          className="no-drag relative mt-1.5 rounded-lg outline-offset-2 transition-opacity hover:opacity-85"
        >
          <PlayerHead skinUrl={resolveSkinTexture(status)} name={profile.name} size={36} className="rounded-lg" />
          <span
            className="absolute -bottom-0.5 -right-0.5 size-3 rounded-full border-2 border-card"
            style={{ background: PRESENCE_COLOR[presence] || PRESENCE_COLOR.online }}
          />
        </button>
      ) : (
        <button
          onClick={() => api()?.microsoftLogin()}
          title={t("Sign in")}
          aria-label={t("Sign in")}
          className="no-drag mt-1.5 flex size-9 items-center justify-center rounded-lg border border-dashed border-border text-muted-foreground transition-colors hover:border-primary/60 hover:text-foreground"
        >
          <LogIn className="size-4" />
        </button>
      )}
    </nav>
  );
}

/** Frameless title bar: the whole strip drags, the controls opt out. */
export function TitleBar({ title, modern = false }) {
  const t = useT();
  const send = (action) => api()?.windowAction(action);
  const control = (label, Icon, action, danger) => (
    <button
      aria-label={label}
      onClick={() => send(action)}
      className={cn(
        "no-drag flex h-8 w-11 items-center justify-center text-muted-foreground transition-colors",
        danger ? "hover:bg-destructive hover:text-destructive-foreground" : "hover:bg-accent hover:text-foreground"
      )}
    >
      <Icon className="size-3.5" />
    </button>
  );

  return (
    <header
      className={cn(
        "drag-region flex h-8 shrink-0 items-center justify-between",
        // Modern: the bar is part of the page canvas (the rail already shows the logo), and it
        // sits above the launch screen so the window controls keep working under it.
        modern ? "relative z-50" : "border-b border-border bg-card"
      )}
    >
      <span className="select-none px-3 text-xs font-medium text-muted-foreground">{modern ? "" : title}</span>
      <div className="flex items-center">
        {control(t("Minimize"), Minus, "minimize")}
        {control(t("Maximize"), Square, "maximize")}
        {control(t("Close"), X, "close", true)}
      </div>
    </header>
  );
}

/** Consistent page frame so every view lines up on the same grid. */
export function Page({ title, description, actions, children }) {
  const modern = useModern();
  if (modern) {
    return (
      <div className="flex h-full flex-col overflow-hidden">
        <div className="flex items-end justify-between gap-4 px-8 pb-6 pt-3">
          <div className="min-w-0">
            <h1 className="truncate text-[26px] font-extrabold leading-tight tracking-[-0.02em]">{title}</h1>
            {description ? <p className="mt-1 text-[13px] text-muted-foreground">{description}</p> : null}
          </div>
          {actions ? <div className="flex shrink-0 items-center gap-2">{actions}</div> : null}
        </div>
        <div className="min-h-0 flex-1 overflow-y-auto px-8 pb-8">{children}</div>
      </div>
    );
  }
  return (
    <div className="flex h-full flex-col overflow-hidden">
      <div className="flex items-start justify-between gap-4 px-6 pb-4 pt-5">
        <div className="space-y-0.5">
          <h1 className="text-lg font-semibold leading-tight">{title}</h1>
          {description ? <p className="text-xs text-muted-foreground">{description}</p> : null}
        </div>
        {actions ? <div className="flex shrink-0 items-center gap-2">{actions}</div> : null}
      </div>
      <div className="min-h-0 flex-1 overflow-y-auto px-6 pb-6">{children}</div>
    </div>
  );
}

/** Small labelled row used across Settings and instance detail. */
export function Field({ label, hint, children }) {
  const modern = useModern();
  return (
    <div className={cn("flex items-center justify-between gap-6", modern ? "py-3.5" : "py-2.5")}>
      <div className="min-w-0 space-y-0.5">
        <div className={cn("text-sm", modern ? "font-semibold" : "font-medium")}>{label}</div>
        {hint ? <div className="text-xs text-muted-foreground">{hint}</div> : null}
      </div>
      <div className="flex shrink-0 items-center gap-2">{children}</div>
    </div>
  );
}

export function EmptyState({ icon: Icon, title, description, action }) {
  const modern = useModern();
  return (
    <div
      className={cn(
        "flex flex-col items-center justify-center gap-3 border border-dashed text-center",
        modern ? "rounded-xl border-border/70 py-16" : "rounded-lg border-border py-14"
      )}
    >
      {Icon ? <Icon className="size-7 text-muted-foreground" /> : null}
      <div className="space-y-1">
        <div className="text-sm font-medium">{title}</div>
        {description ? <p className="max-w-sm text-xs text-muted-foreground">{description}</p> : null}
      </div>
      {action}
    </div>
  );
}

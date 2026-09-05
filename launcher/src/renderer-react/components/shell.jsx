import React from "react";
import { Home, Boxes, Shirt, Settings, Minus, Square, X } from "lucide-react";
import { cn } from "../lib/utils";
import { api } from "../lib/useStatus";
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from "./ui/tooltip";

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
  const item = (entry) => {
    const Icon = entry.icon;
    const active = view === entry.id;
    return (
      <Tooltip key={entry.id} delayDuration={200}>
        <TooltipTrigger asChild>
          <button
            aria-label={entry.label}
            aria-current={active ? "page" : undefined}
            data-tour={`rail-${entry.id}`}
            onClick={() => setView(entry.id)}
            className={cn(
              "no-drag relative flex size-10 items-center justify-center rounded-md transition-colors",
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
        <TooltipContent side="right">{entry.label}</TooltipContent>
      </Tooltip>
    );
  };

  return (
    <TooltipProvider>
      <nav className="flex w-14 shrink-0 flex-col items-center gap-1 border-r border-border bg-card py-3">
        <img
          src="../assets/river-logo.png"
          alt="River Client"
          className="mb-2 size-8 shrink-0 select-none object-contain"
          draggable={false}
        />
        {NAV.map(item)}
        <div className="flex-1" />
        {item({ id: "settings", label: "Settings", icon: Settings })}
      </nav>
    </TooltipProvider>
  );
}

/** Frameless title bar: the whole strip drags, the controls opt out. */
export function TitleBar({ title }) {
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
    <header className="drag-region flex h-8 shrink-0 items-center justify-between border-b border-border bg-card">
      <span className="select-none px-3 text-xs font-medium text-muted-foreground">{title}</span>
      <div className="flex items-center">
        {control("Minimize", Minus, "minimize")}
        {control("Maximize", Square, "maximize")}
        {control("Close", X, "close", true)}
      </div>
    </header>
  );
}

/** Consistent page frame so every view lines up on the same grid. */
export function Page({ title, description, actions, children }) {
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
  return (
    <div className="flex items-center justify-between gap-6 py-2.5">
      <div className="min-w-0 space-y-0.5">
        <div className="text-sm font-medium">{label}</div>
        {hint ? <div className="text-xs text-muted-foreground">{hint}</div> : null}
      </div>
      <div className="flex shrink-0 items-center gap-2">{children}</div>
    </div>
  );
}

export function EmptyState({ icon: Icon, title, description, action }) {
  return (
    <div className="flex flex-col items-center justify-center gap-3 rounded-lg border border-dashed border-border py-14 text-center">
      {Icon ? <Icon className="size-7 text-muted-foreground" /> : null}
      <div className="space-y-1">
        <div className="text-sm font-medium">{title}</div>
        {description ? <p className="max-w-sm text-xs text-muted-foreground">{description}</p> : null}
      </div>
      {action}
    </div>
  );
}

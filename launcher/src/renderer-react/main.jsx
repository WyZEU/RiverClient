import React, { useCallback, useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import { AlertCircle, Info, X } from "lucide-react";
import { useStatus, api } from "./lib/useStatus";
import { cn } from "./lib/utils";
import { ModernRail, Rail, TitleBar } from "./components/shell";
import { UiStyleContext, useModern } from "./lib/ui-style";
import { useButtonTilt, useWindowFocusFlag } from "./lib/window-focus";
import { Button } from "./components/ui/button";
import { HomeView } from "./views/home";
import { ModernHomeView } from "./views/modern-home";
import { InstancesView } from "./views/instances";
import { InstanceView } from "./views/instance";
import { WardrobeView } from "./views/wardrobe";
import { SettingsView } from "./views/settings";
import { UpdateGate } from "./components/update-gate";
import { UpdateBanner, WhatsNewDialog } from "./components/update-notices";
import { SeasonalDecor } from "./components/seasonal-decor";
import { activeSeason, SeasonContext } from "./lib/seasons";
import { AuthDialog } from "./components/auth-dialog";
import { ActivityBar } from "./components/activity-bar";
import { LaunchOverlay } from "./components/launch-overlay";
import { Tour } from "./components/tour";
import { I18nProvider } from "./i18n/context.jsx";

// "instance" is a detail page reached from Instances rather than a rail destination,
// so it is routable but deliberately absent from NAV.
const VIEWS = ["home", "instances", "instance", "wardrobe", "settings"];

/**
 * A crashing view must never take the whole launcher down - the shell and the
 * rail stay usable so you can navigate away from whatever broke.
 */
class ViewBoundary extends React.Component {
  constructor(props) {
    super(props);
    this.state = { error: null };
  }

  static getDerivedStateFromError(error) {
    return { error };
  }

  componentDidCatch(error, info) {
    api()?.reportRendererError?.({
      message: String(error?.message || error),
      stack: String(error?.stack || ""),
      componentStack: String(info?.componentStack || "")
    });
  }

  componentDidUpdate(prev) {
    if (prev.resetKey !== this.props.resetKey && this.state.error) this.setState({ error: null });
  }

  render() {
    if (!this.state.error) return this.props.children;
    return (
      <div className="flex h-full flex-col items-center justify-center gap-3 p-6 text-center">
        <AlertCircle className="size-7 text-destructive" />
        <div className="space-y-1">
          <div className="text-sm font-medium">This page failed to load</div>
          <p className="max-w-md text-xs text-muted-foreground">{String(this.state.error?.message || this.state.error)}</p>
        </div>
        <Button size="sm" variant="outline" onClick={() => this.setState({ error: null })}>Try again</Button>
      </div>
    );
  }
}

function Toast({ toast, onDismiss }) {
  const modern = useModern();
  useEffect(() => {
    if (!toast) return undefined;
    const timer = setTimeout(onDismiss, 5000);
    return () => clearTimeout(timer);
  }, [toast, onDismiss]);

  if (!toast) return null;
  const error = toast.tone === "error";

  // Modern: a card with an icon for the kind of message, and a bar along the bottom that
  // runs out over the five seconds the toast stays up.
  if (modern) {
    return (
      <div
        role="status"
        className={cn(
          "fixed bottom-5 right-5 z-50 flex w-[340px] items-start gap-3 overflow-hidden rounded-xl border bg-popover px-4 py-3 shadow-lg animate-in slide-in-from-bottom-2 fade-in",
          error ? "border-destructive/50" : "border-border/70"
        )}
      >
        <span
          className={cn(
            "mt-px flex size-7 shrink-0 items-center justify-center rounded-lg",
            error ? "bg-destructive/15 text-destructive" : "bg-primary/15 text-primary"
          )}
        >
          {error ? <AlertCircle className="size-4" /> : <Info className="size-4" />}
        </span>
        <p className="min-w-0 flex-1 pt-1 text-[13px] leading-snug">{toast.message}</p>
        <button
          onClick={onDismiss}
          aria-label="Dismiss"
          className="-mr-1 flex size-7 shrink-0 items-center justify-center rounded-lg text-muted-foreground transition-colors hover:bg-accent hover:text-foreground"
        >
          <X className="size-3.5" />
        </button>
        <span
          key={toast.at}
          className={cn("toast-timer absolute inset-x-0 bottom-0 h-0.5 origin-left", error ? "bg-destructive/70" : "bg-primary/70")}
        />
      </div>
    );
  }

  return (
    <div
      role="status"
      className={cn(
        "fixed bottom-4 right-4 z-50 flex max-w-sm items-start gap-2 rounded-md border bg-popover px-3 py-2 shadow-lg animate-in slide-in-from-bottom-2 fade-in",
        toast.tone === "error" ? "border-destructive/50" : "border-border"
      )}
    >
      <span className={cn("mt-1 size-1.5 shrink-0 rounded-full", toast.tone === "error" ? "bg-destructive" : "bg-primary")} />
      <p className="flex-1 text-xs leading-relaxed">{toast.message}</p>
      <button onClick={onDismiss} aria-label="Dismiss" className="text-muted-foreground hover:text-foreground">
        <X className="size-3.5" />
      </button>
    </div>
  );
}

/*
  Takes the boot screen down once there is something behind it.

  Waiting for React to mount is not the same as waiting for the launcher to be ready:
  mounting happens with no status yet, so dismissing then would swap the boot screen for
  an empty shell that fills in a moment later. This waits for the first status - or for
  an error, which is also something worth showing rather than hiding behind a spinner.
*/
// The boot screen stays up at least this long from the moment the window began loading. Status
// arrives within a few dozen milliseconds on a warm start, which used to take the screen down
// before it could be seen at all, so opening the launcher looked like it appeared out of nowhere.
const BOOT_MIN_MS = 1300;

function useBootScreen(ready) {
  useEffect(() => {
    if (!ready) return undefined;
    const boot = document.getElementById("boot");
    if (!boot) return undefined;
    let removeTimer = null;
    const hideTimer = setTimeout(() => {
      boot.classList.add("boot-done");
      // Matches the fade in the shell; removing it outright keeps it out of the tab order.
      removeTimer = setTimeout(() => boot.remove(), 300);
    }, Math.max(0, BOOT_MIN_MS - performance.now()));
    return () => {
      clearTimeout(hideTimer);
      clearTimeout(removeTimer);
    };
  }, [ready]);
}

function App() {
  const { status, error, refresh } = useStatus();
  useBootScreen(Boolean(status) || Boolean(error));
  const [view, setView] = useState("home");
  const [toast, setToast] = useState(null);

  // Reflect the launcher theme on the root; light is the only non-dark option,
  // everything else (dark/darker/…) keeps the default dark palette. When the user
  // actually switches, flag a 3s crossfade (but never on the very first apply, so
  // startup doesn't fade in).
  const theme = status?.settings?.launcherTheme || "dark";
  const season = activeSeason(theme, status?.settings?.keptSeasons || []);
  useEffect(() => {
    const root = document.documentElement;
    if (season) root.dataset.season = season.id;
    else delete root.dataset.season;
    // Remembered so the boot screen, drawn before settings load, shows the right logo.
    try {
      if (season) window.localStorage.setItem("river:bootSeason", season.id);
      else window.localStorage.removeItem("river:bootSeason");
    } catch {}
  }, [season?.id]);
  // Classic or Modern (Settings > Appearance). Stamped on the root for the CSS, and handed to
  // shared components through UiStyleContext.
  const uiStyle = status?.settings?.interfaceStyle === "modern" ? "modern" : "classic";
  const modern = uiStyle === "modern";
  useEffect(() => {
    document.documentElement.dataset.ui = uiStyle;
    // Remembered so the next start's boot screen is drawn in the same style before React loads.
    try { window.localStorage.setItem("river:bootUi", uiStyle); } catch {}
  }, [uiStyle]);
  // Decoration only runs while this window is the one being used.
  useWindowFocusFlag();
  useButtonTilt(modern);

  const prevTheme = React.useRef(null);
  useEffect(() => {
    const root = document.documentElement;
    root.dataset.theme = theme === "light" ? "light" : "dark";
    if (prevTheme.current !== null && prevTheme.current !== theme) {
      root.setAttribute("data-theme-anim", "");
      const timer = setTimeout(() => root.removeAttribute("data-theme-anim"), 3200);
      prevTheme.current = theme;
      return () => clearTimeout(timer);
    }
    prevTheme.current = theme;
    return undefined;
  }, [theme]);

  // Do Not Disturb has to actually suppress something or it is just a label. Errors still
  // get through: silencing a failure the user needs to act on would be a bug, not respect.
  const dnd = status?.settings?.socialStatus === "dnd";
  const notify = useCallback((message, tone = "info") => {
    if (!message) return;
    if (dnd && tone !== "error") return;
    setToast({ message: String(message), tone, at: Date.now() });
  }, [dnd]);

  const go = useCallback((next) => setView(VIEWS.includes(next) ? next : "home"), []);

  // First launch gets the guided tour once. Checked a single time per session so
  // finishing it (which writes the setting) can't immediately re-evaluate and reopen,
  // and skipped entirely when a blocking update dialog owns the screen.
  const [tourOpen, setTourOpen] = useState(false);
  const tourChecked = React.useRef(false);
  useEffect(() => {
    if (tourChecked.current || !status?.settings) return;
    tourChecked.current = true;
    if (!status.settings.tutorialCompleted && !status.launcherUpdate?.available) setTourOpen(true);
  }, [status]);

  const startTour = useCallback(() => setTourOpen(true), []);
  const closeTour = useCallback(async () => {
    setTourOpen(false);
    try {
      await api()?.updateSettings({ tutorialCompleted: true });
      await refresh();
    } catch {}
  }, [refresh]);

  // Which instance the detail page is showing. Set by Instances, cleared on the way back.
  const [openInstanceId, setOpenInstanceId] = useState("");
  const openInstance = useCallback((id) => { setOpenInstanceId(String(id || "")); go("instance"); }, [go]);
  const shared = { status, refresh, notify, startTour };
  // The detail page belongs to Instances, so the rail stays lit there.
  const railView = view === "instance" ? "instances" : view;

  const content = error ? (
    <div className="flex h-full items-center justify-center p-6 text-center text-xs text-muted-foreground">{error}</div>
  ) : !status ? (
    <div className="flex h-full items-center justify-center text-xs text-muted-foreground">Loading…</div>
  ) : (
    <ViewBoundary resetKey={view}>
      {/* Keyed on the view (and layout) so each switch replays a quick fade + rise. */}
      <div key={`${uiStyle}:${view}`} className="h-full animate-in fade-in slide-in-from-bottom-2 duration-200 ease-out">
        {view === "home" && (modern
          ? <ModernHomeView {...shared} go={go} openInstance={openInstance} />
          : <HomeView {...shared} />)}
        {view === "instances" && <InstancesView {...shared} openInstance={openInstance} />}
        {view === "instance" && (
          <InstanceView
            {...shared}
            instanceId={openInstanceId}
            onBack={() => go("instances")}
          />
        )}
        {view === "wardrobe" && <WardrobeView {...shared} />}
        {view === "settings" && <SettingsView {...shared} />}
      </div>
    </ViewBoundary>
  );

  const overlays = (
    <>
      <SeasonalDecor season={season} view={view} modern={modern} />
      <UpdateGate status={status} notify={notify} />
      <WhatsNewDialog status={status} refresh={refresh} />
      <Tour open={tourOpen} onClose={closeTour} setView={go} />
      {/* Global: the sign-in flow can be started from Home, Settings or Wardrobe. */}
      <AuthDialog />
      {/* Owns the screen from the moment Launch is pressed until the game is on screen. */}
      <LaunchOverlay status={status} notify={notify} />
      <Toast toast={toast} onDismiss={() => setToast(null)} />
    </>
  );

  return (
    <I18nProvider language={status?.settings?.launcherLanguage || "en"}>
    <UiStyleContext.Provider value={uiStyle}>
    <SeasonContext.Provider value={season?.id || null}>
      {modern ? (
        /* Modern: the rail runs the full height and the title bar only spans the page. */
        <div className="flex h-full overflow-hidden bg-background">
          <ModernRail view={railView} setView={go} status={status} />
          <div className="flex min-w-0 flex-1 flex-col">
            <TitleBar title="River Client" modern />
            <UpdateBanner status={status} notify={notify} />
            <main className="min-h-0 min-w-0 flex-1">{content}</main>
            <ActivityBar />
          </div>
          {overlays}
        </div>
      ) : (
        <div className="flex h-full flex-col overflow-hidden bg-background">
          <TitleBar title="River Client" />
          <UpdateBanner status={status} notify={notify} />
          <div className="flex min-h-0 flex-1">
            <Rail view={railView} setView={go} />
            <main className="min-w-0 flex-1">{content}</main>
          </div>
          {/* Everything the main process is doing reports here, in one fixed place. */}
          <ActivityBar />
          {overlays}
        </div>
      )}
    </SeasonContext.Provider>
    </UiStyleContext.Provider>
    </I18nProvider>
  );
}

createRoot(document.getElementById("root")).render(<App />);

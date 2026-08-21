import React, { useCallback, useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import { AlertCircle, X } from "lucide-react";
import { useStatus, api } from "./lib/useStatus";
import { cn } from "./lib/utils";
import { Rail, TitleBar } from "./components/shell";
import { Button } from "./components/ui/button";
import { HomeView } from "./views/home";
import { InstancesView } from "./views/instances";
import { InstanceView } from "./views/instance";
import { WardrobeView } from "./views/wardrobe";
import { SettingsView } from "./views/settings";
import { UpdateGate } from "./components/update-gate";
import { AuthDialog } from "./components/auth-dialog";
import { ActivityBar } from "./components/activity-bar";
import { Tour } from "./components/tour";

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
  useEffect(() => {
    if (!toast) return undefined;
    const timer = setTimeout(onDismiss, 5000);
    return () => clearTimeout(timer);
  }, [toast, onDismiss]);

  if (!toast) return null;
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

function App() {
  const { status, error, refresh } = useStatus();
  const [view, setView] = useState("home");
  const [toast, setToast] = useState(null);

  // Reflect the launcher theme on the root; light is the only non-dark option,
  // everything else (dark/darker/…) keeps the default dark palette. When the user
  // actually switches, flag a 3s crossfade (but never on the very first apply, so
  // startup doesn't fade in).
  const theme = status?.settings?.launcherTheme || "dark";
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
    setToast({ message: String(message), tone });
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

  return (
    <div className="flex h-full flex-col overflow-hidden bg-background">
      <TitleBar title="River Client" />
      <div className="flex min-h-0 flex-1">
        {/* The detail page belongs to Instances, so the rail stays lit there. */}
        <Rail view={view === "instance" ? "instances" : view} setView={go} />
        <main className="min-w-0 flex-1">
          {error ? (
            <div className="flex h-full items-center justify-center p-6 text-center text-xs text-muted-foreground">{error}</div>
          ) : !status ? (
            <div className="flex h-full items-center justify-center text-xs text-muted-foreground">Loading…</div>
          ) : (
            <ViewBoundary resetKey={view}>
              {/* Keyed on the view so each switch replays a quick fade + rise. */}
              <div key={view} className="h-full animate-in fade-in slide-in-from-bottom-2 duration-200 ease-out">
                {view === "home" && <HomeView {...shared} />}
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
          )}
        </main>
      </div>
      {/* Everything the main process is doing reports here, in one fixed place. */}
      <ActivityBar />
      <UpdateGate status={status} notify={notify} />
      <Tour open={tourOpen} onClose={closeTour} setView={go} />
      {/* Global: the sign-in flow can be started from Home, Settings or Wardrobe. */}
      <AuthDialog />
      <Toast toast={toast} onDismiss={() => setToast(null)} />
    </div>
  );
}

createRoot(document.getElementById("root")).render(<App />);

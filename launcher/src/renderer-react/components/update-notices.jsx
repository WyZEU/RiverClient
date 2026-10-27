import React, { useEffect, useRef, useState } from "react";
import { Bug, Download, Loader2, Minus, Plus, Wrench, X } from "lucide-react";
import { api } from "../lib/useStatus";
import { cn } from "../lib/utils";
import { useModern } from "../lib/ui-style";
import { Button } from "./ui/button";
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle
} from "./ui/dialog";
import { useT } from "../i18n/context.jsx";

/** #updates on the River Discord - where every release's changes are posted. */
const UPDATES_CHANNEL_URL = "https://discord.com/channels/1501139838311596103/1541128335529345114";

const DISMISS_KEY = "river:dismissedUpdateVersion";

/** Same two river shots Home uses for its hero. */
const BANNER = `../assets/home-bg-${1 + Math.floor(Math.random() * 2)}.jpg`;

function readDismissed() {
  try { return window.localStorage.getItem(DISMISS_KEY) || ""; } catch { return ""; }
}

function writeDismissed(version) {
  try { window.localStorage.setItem(DISMISS_KEY, version); } catch {}
}

function isMandatory(update) {
  return Boolean(update?.available && (update.blocking || update.required));
}

/**
 * "An update is available" - for the optional ones.
 *
 * Mandatory updates already take over the screen (UpdateGate). Optional ones used to
 * live only in Settings, so unless someone went looking, they never knew there was a
 * newer River. This is a slim bar across the top instead: one click to update, or
 * dismissed until the next version comes out - never nagging about the same one twice.
 */
export function UpdateBanner({ status, notify }) {
  const t = useT();
  const modern = useModern();
  const update = status?.launcherUpdate;
  const [dismissed, setDismissed] = useState(readDismissed);
  const [installing, setInstalling] = useState(false);

  const latest = update?.latestVersion || "";
  if (!update?.available || isMandatory(update) || !latest || dismissed === latest) return null;

  const install = async () => {
    setInstalling(true);
    try {
      const res = await api()?.installLauncherUpdate();
      if (res && res.ok === false) {
        notify?.(res.message || t("The update could not be installed."), "error");
        setInstalling(false);
      }
      // On success the app relaunches into updater mode, so leave the spinner up.
    } catch (e) {
      notify?.(String(e?.message || e), "error");
      setInstalling(false);
    }
  };

  const dismiss = () => {
    writeDismissed(latest);
    setDismissed(latest);
  };

  return (
    <div className={cn("flex shrink-0 items-center gap-3 border-b border-border bg-primary/10 py-1.5 animate-in fade-in slide-in-from-top-1 duration-200", modern ? "px-8" : "px-4")}>
      <Download className="size-3.5 shrink-0 text-primary" />
      <span className="min-w-0 flex-1 truncate text-xs">
        {t("River Client {version} is available.", { version: latest })}
      </span>
      <Button size="sm" className="h-6 px-3 text-xs" onClick={install} disabled={installing}>
        {installing ? <Loader2 className="size-3 animate-spin" /> : null}
        {t("Update now")}
      </Button>
      <button
        aria-label={t("Dismiss")}
        onClick={dismiss}
        disabled={installing}
        className="no-drag text-muted-foreground transition-colors hover:text-foreground"
      >
        <X className="size-3.5" />
      </button>
    </div>
  );
}

/*
 * The changelog is a flat list of sentences ("Added X: ...", "Fixed Y."). Sorting them by
 * their first word gives the dialog real sections without changing how notes are written:
 * whatever comes before the first colon becomes the bold name, the rest the explanation.
 */
const SECTIONS = [
  { id: "new", label: "New", Icon: Plus, tone: "text-primary", strip: /^added\s+/i, match: /^added\b/i },
  { id: "improved", label: "Improved", Icon: Wrench, tone: "text-foreground/70", strip: null, match: null },
  { id: "fixed", label: "Fixed", Icon: Bug, tone: "text-success", strip: null, match: /^fixed\b/i },
  { id: "removed", label: "Removed", Icon: Minus, tone: "text-destructive", strip: /^removed\s+/i, match: /^removed\b/i }
];

function sortNotes(items) {
  const groups = SECTIONS.map((section) => ({ ...section, entries: [] }));
  for (const raw of items) {
    const text = String(raw || "").trim();
    if (!text) continue;
    const section = groups.find((g) => g.match && g.match.test(text)) || groups.find((g) => g.id === "improved");
    let body = section.strip ? text.replace(section.strip, "") : text;
    body = body.charAt(0).toUpperCase() + body.slice(1);
    const colon = body.indexOf(": ");
    const entry = colon > 0 && colon < 60
      ? { title: body.slice(0, colon), detail: body.charAt(colon + 2).toUpperCase() + body.slice(colon + 3) }
      : { title: "", detail: body };
    section.entries.push(entry);
  }
  return groups.filter((g) => g.entries.length);
}

/**
 * Shown once after an update is installed, with the release's own notes (the same list the
 * website's changelog shows, bundled with this build) so nobody has to leave the launcher
 * to learn what changed. #updates on Discord stays one click away for the full post.
 *
 * A brand-new install has nothing to catch up on, so its first run just records the
 * version. The test for "brand new" is the tutorial: someone who finished it has used
 * River before, even if this version stamp is the first they have ever had - which is
 * everyone, the first time this ships.
 */
export function WhatsNewDialog({ status, refresh }) {
  const t = useT();
  const current = status?.appVersion || "";
  const settings = status?.settings;
  const notes = status?.changelog;
  const items = Array.isArray(notes?.items) && String(notes?.version) === current ? notes.items : [];
  const sections = sortNotes(items);
  const [open, setOpen] = useState(false);
  const decided = useRef(false);

  useEffect(() => {
    if (decided.current || !current || !settings) return;
    // A required update owns the screen; this would only stack on top of it.
    if (isMandatory(status?.launcherUpdate)) return;
    decided.current = true;

    const seen = String(settings.lastSeenChangelogVersion || "");
    if (seen === current) return;
    if (!seen && !settings.tutorialCompleted) {
      api()?.updateSettings({ lastSeenChangelogVersion: current }).catch?.(() => {});
      return;
    }
    setOpen(true);
  }, [current, settings, status?.launcherUpdate]);

  const close = async () => {
    setOpen(false);
    try {
      await api()?.updateSettings({ lastSeenChangelogVersion: current });
      await refresh?.();
    } catch {}
  };

  const openUpdates = () => {
    api()?.openExternal(UPDATES_CHANNEL_URL);
    close();
  };

  return (
    <Dialog open={open} onOpenChange={(next) => { if (!next) close(); }}>
      <DialogContent className="max-w-lg gap-0 overflow-hidden p-0">
        {/* Banner: one of Home's own river shots, fading into the dialog. */}
        <div className="relative h-28 overflow-hidden border-b border-border">
          <img src={document.documentElement.dataset.season === "halloween" ? "../assets/home-bg-halloween.jpg" : BANNER} alt="" className="absolute inset-0 size-full object-cover" />
          <div className="absolute inset-0 bg-gradient-to-t from-popover via-popover/70 to-popover/10" />
          <div className="absolute inset-x-5 bottom-3 flex items-end gap-3">
            <img src="../assets/riv3r-client.png" alt="" className="size-10 shrink-0" />
            <DialogHeader className="min-w-0 gap-0.5 text-left">
              <p className="text-[11px] font-medium uppercase tracking-wide text-muted-foreground">{t("Updated")}</p>
              <DialogTitle className="text-lg leading-tight">{t("River Client {version}", { version: current })}</DialogTitle>
            </DialogHeader>
          </div>
        </div>

        <div className="max-h-[22rem] overflow-y-auto px-5 py-4">
          <DialogDescription className="text-[13px] leading-relaxed text-muted-foreground">
            {items.length && notes?.summary
              ? notes.summary
              : t("See everything that changed in #updates on our Discord.")}
          </DialogDescription>

          {sections.map(({ id, label, Icon, tone, entries }) => (
            <section key={id} className="mt-4">
              <h3 className="mb-1.5 flex items-center gap-1.5 text-[11px] font-semibold uppercase tracking-wide text-muted-foreground">
                <Icon className={`size-3.5 ${tone}`} strokeWidth={2.5} />
                {t(label)}
                <span className="font-normal">{entries.length}</span>
              </h3>
              <ul className="divide-y divide-border rounded-md border border-border bg-card">
                {entries.map((entry, i) => (
                  <li key={i} className="px-3 py-2 text-[13px] leading-snug">
                    {entry.title ? <span className="font-medium text-foreground">{entry.title}. </span> : null}
                    <span className={entry.title ? "text-muted-foreground" : "text-foreground/90"}>{entry.detail}</span>
                  </li>
                ))}
              </ul>
            </section>
          ))}
        </div>

        <DialogFooter className="border-t border-border px-5 py-3">
          <Button variant="outline" onClick={openUpdates}>
            <img src="../assets/discord-icon.png" alt="" className="size-3.5" />
            {t("Read in #updates")}
          </Button>
          <Button onClick={close}>{t("Close")}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

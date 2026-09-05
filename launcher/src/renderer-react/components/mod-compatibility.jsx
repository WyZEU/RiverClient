import React, { useState } from "react";
import { AlertTriangle, PowerOff, Loader2, ShieldCheck, Download, FolderOpen } from "lucide-react";
import { api } from "../lib/useStatus";
import { Button } from "./ui/button";
import { Badge } from "./ui/badge";
import { Separator } from "./ui/separator";

/**
 * Shown when River refuses to launch because mods in the instance cannot load together.
 *
 * River already worked all of this out before it blocked the launch: which mods clash,
 * which rule each one breaks, and which jar to disable to resolve it. None of that used
 * to reach the screen. People were told "1 confirmed mod compatibility issue must be
 * fixed before launch" and left to work out which of a hundred jars it meant, which is
 * why it became the most common reason a launch fails and stays failed.
 *
 * The check itself is not being second guessed here. Every issue it raises is one Fabric
 * would refuse to start on anyway, so the game was never going to open. The difference is
 * that the reason is now visible and, where possible, one click from being fixed.
 */

/** Fabric's own words for these, translated into what actually went wrong. */
const EXPLAIN = {
  "duplicate-mod-id": "Two jars provide the same mod. Fabric only ever loads one, so remove or disable the other.",
  "declared-break": "These two say outright that they cannot run together. One of them has to go.",
  "bad-dependency-version": "This mod needs a different version of something else in the folder.",
  "missing-dependency": "This mod needs something that is not in the folder."
};

export function ModCompatibility({ status, refresh, notify }) {
  const [busy, setBusy] = useState("");

  const failure = status?.lastLaunchFailure;
  if (!failure || failure.requiresAction !== "mod-incompatibility") return null;

  const issues = Array.isArray(failure.issues) ? failure.issues : [];
  if (!issues.length) return null;

  const run = async (key, fn, okMessage) => {
    setBusy(key);
    try {
      const res = await fn();
      if (res && res.ok === false) notify(res.message || "That did not work.", "error");
      else if (okMessage) notify(okMessage, "info");
      await refresh();
    } catch (e) {
      notify(String(e?.message || e), "error");
    } finally {
      setBusy("");
    }
  };

  /*
    Only a top level jar can be disabled. A mod nested inside another one has no file of
    its own to move, so offering a button that cannot work would be worse than offering
    nothing. Those are named and explained instead.
  */
  const disableable = (mod) => Boolean(mod?.file);

  return (
    <section className="rounded-lg border border-warning/40 bg-card">
      <div className="flex items-start gap-3 p-4">
        <AlertTriangle className="mt-0.5 size-5 shrink-0 text-warning" />
        <div className="min-w-0 flex-1">
          <h2 className="text-sm font-semibold">
            {issues.length === 1 ? "A mod conflict is stopping the game" : `${issues.length} mod conflicts are stopping the game`}
          </h2>
          <p className="mt-0.5 text-xs leading-relaxed text-muted-foreground">
            Fabric refuses to start with these, so River stopped before Minecraft did.
          </p>
        </div>
      </div>

      <Separator />

      <div className="space-y-2 p-4">
        {issues.map((issue, index) => (
          <div key={`${issue.type}:${issue.title}:${index}`} className="rounded-md border border-border bg-background/40 p-3">
            <div className="flex items-start gap-2">
              <span className="mt-1.5 size-1.5 shrink-0 rounded-full bg-warning" />
              <div className="min-w-0 flex-1">
                <p className="text-xs font-medium">{issue.title}</p>
                <p className="mt-1 text-[11px] leading-relaxed text-muted-foreground">
                  {EXPLAIN[issue.type] || issue.message}
                </p>

                {issue.missing?.id ? (
                  <p className="mt-1.5 text-[11px] text-muted-foreground">
                    Missing: <span className="text-foreground">{issue.missing.id}</span>
                    {issue.missing.range && issue.missing.range !== "*" ? ` ${issue.missing.range}` : ""}
                  </p>
                ) : null}

                {Array.isArray(issue.mods) && issue.mods.length ? (
                  <div className="mt-2 space-y-1.5">
                    {issue.mods.map((mod) => (
                      <div
                        key={`${issue.title}:${mod.id || mod.file || mod.name}`}
                        className="flex items-center gap-2 rounded-md border border-border bg-card px-2.5 py-1.5"
                      >
                        <span className="min-w-0 flex-1 truncate text-[11px] font-medium">{mod.name}</span>
                        {mod.version ? <Badge variant="outline">{mod.version}</Badge> : null}
                        {disableable(mod) ? (
                          <Button
                            size="sm"
                            variant="ghost"
                            disabled={Boolean(busy)}
                            onClick={() => run(
                              `off:${mod.file}`,
                              () => api()?.setModEnabled({ file: mod.file }, false),
                              `${mod.name} disabled.`
                            )}
                          >
                            {busy === `off:${mod.file}`
                              ? <Loader2 className="size-3.5 animate-spin" />
                              : <PowerOff className="size-3.5" />}
                            Disable
                          </Button>
                        ) : (
                          <span className="text-[11px] text-muted-foreground">bundled inside another mod</span>
                        )}
                      </div>
                    ))}
                  </div>
                ) : null}
              </div>
            </div>
          </div>
        ))}
      </div>

      <Separator />

      <div className="flex flex-wrap items-center gap-2 p-4">
        {/*
          Updating everything is offered because an out of date mod is the usual cause of a
          version rule failing, and it fixes the whole class of problem in one go rather
          than asking somebody to work out which jar is the old one.
        */}
        <Button
          size="sm"
          disabled={Boolean(busy)}
          onClick={() => run("update", () => api()?.updateAllMods({}), "Mod updates finished.")}
        >
          {busy === "update" ? <Loader2 className="size-3.5 animate-spin" /> : <Download className="size-3.5" />}
          Update all mods
        </Button>

        <Button
          size="sm"
          variant="outline"
          disabled={Boolean(busy)}
          title="Verify game files and River's own mods"
          onClick={() => run(
            "repair",
            () => api()?.repairInstance({ instanceId: status?.selectedInstance?.id || "" }),
            "Repair complete."
          )}
        >
          {busy === "repair" ? <Loader2 className="size-3.5 animate-spin" /> : <ShieldCheck className="size-3.5" />}
          Repair instance
        </Button>

        <Button
          size="sm"
          variant="ghost"
          onClick={() => api()?.openPath(status?.instancePath ? `${status.instancePath}\\mods` : "")}
        >
          <FolderOpen className="size-3.5" />
          Open mods folder
        </Button>
      </div>
    </section>
  );
}

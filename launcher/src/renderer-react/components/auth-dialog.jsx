import React, { useEffect, useState } from "react";
import { Copy, Check, ExternalLink, Loader2, AlertCircle } from "lucide-react";
import { api } from "../lib/useStatus";
import { cn } from "../lib/utils";
import { Button } from "./ui/button";
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle
} from "./ui/dialog";

/**
 * Microsoft device-code sign-in.
 *
 * The main process narrates every stage of the flow on "launcher:auth", including the
 * user_code Microsoft asks you to type in. Nothing in the UI ever listened to it, so
 * pressing Sign in opened microsoft.com/link and then asked for a code the launcher
 * never displayed - the flow simply could not be finished by anyone who wasn't already
 * signed in from a stored refresh token. This is that missing surface.
 */
export function AuthDialog() {
  const [state, setState] = useState(null);
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    const off = api()?.onAuth?.((payload) => {
      setCopied(false);
      setState(payload || null);
    });
    return () => { if (typeof off === "function") off(); };
  }, []);

  // A finished sign-in shows its confirmation briefly, then gets out of the way.
  useEffect(() => {
    if (state?.type !== "done") return undefined;
    const timer = setTimeout(() => setState(null), 2000);
    return () => clearTimeout(timer);
  }, [state]);

  if (!state) return null;

  const code = String(state.userCode || "");
  const uri = String(state.verificationUri || "https://microsoft.com/link");
  const failed = state.type === "error";
  const done = state.type === "done";

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(code);
      setCopied(true);
      setTimeout(() => setCopied(false), 1600);
    } catch {}
  };

  return (
    <Dialog open onOpenChange={(open) => { if (!open) setState(null); }}>
      <DialogContent className="max-w-sm">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            {failed ? <AlertCircle className="size-4 text-destructive" /> : null}
            {state.title || "Microsoft sign-in"}
          </DialogTitle>
          <DialogDescription>{state.detail || "Signing in..."}</DialogDescription>
        </DialogHeader>

        {code && !done && !failed ? (
          <div className="space-y-3">
            <div className="space-y-1.5">
              <div className="text-xs text-muted-foreground">Enter this code on the Microsoft page:</div>
              <button
                onClick={copy}
                title="Copy code"
                className="flex w-full items-center justify-between gap-3 rounded-md border border-border bg-popover px-3 py-2.5 hover:bg-accent"
              >
                <span className="font-mono text-xl font-semibold tracking-[0.2em]">{code}</span>
                {copied ? <Check className="size-4 text-success" /> : <Copy className="size-4 text-muted-foreground" />}
              </button>
            </div>

            <Button variant="outline" size="sm" className="w-full" onClick={() => api()?.openExternal(uri)}>
              <ExternalLink className="size-3.5" />
              Open the Microsoft page again
            </Button>

            <div className="flex items-center gap-2 text-xs text-muted-foreground">
              <Loader2 className="size-3 animate-spin" />
              Waiting for you to finish in the browser...
            </div>
          </div>
        ) : null}

        {!code && !done && !failed ? (
          <div className="flex items-center gap-2 text-xs text-muted-foreground">
            <Loader2 className="size-3.5 animate-spin" />
            Talking to Microsoft...
          </div>
        ) : null}

        {(done || failed) ? (
          <DialogFooter>
            <Button
              size="sm"
              variant={failed ? "outline" : "default"}
              className={cn(failed && "w-full")}
              onClick={() => setState(null)}
            >
              {failed ? "Close" : "Done"}
            </Button>
          </DialogFooter>
        ) : null}
      </DialogContent>
    </Dialog>
  );
}

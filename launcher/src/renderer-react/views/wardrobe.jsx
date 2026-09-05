import React, { useMemo, useState } from "react";
import { Shirt, Upload, Check, Trash2, Loader2, LogIn, Download } from "lucide-react";
import { api } from "../lib/useStatus";
import { cn } from "../lib/utils";
import { Page, EmptyState } from "../components/shell";
import { Button } from "../components/ui/button";
import { Badge } from "../components/ui/badge";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "../components/ui/tabs";
import { SkinViewer } from "../components/skin-viewer";

/** Segmented slim/classic toggle. */
function VariantToggle({ value, onChange, disabled }) {
  return (
    <div className="inline-flex rounded-md border border-border p-0.5">
      {["classic", "slim"].map((v) => (
        <button
          key={v}
          disabled={disabled}
          onClick={() => onChange(v)}
          className={cn(
            "rounded px-3 py-1 text-xs font-medium capitalize transition-colors disabled:opacity-50",
            value === v ? "bg-accent text-foreground" : "text-muted-foreground hover:text-foreground"
          )}
        >
          {v}
        </button>
      ))}
    </div>
  );
}

/** Small clickable thumbnail (flat texture preview) for the grid. */
function Thumb({ src, label, sub, active, selected, onClick }) {
  return (
    <button
      onClick={onClick}
      className={cn(
        "group flex flex-col overflow-hidden rounded-md border bg-card text-left transition-colors",
        selected ? "border-primary" : active ? "border-primary/50" : "border-border hover:bg-accent/40"
      )}
    >
      <div className="flex h-24 items-center justify-center bg-background/40 p-2">
        {src ? (
          <img src={src} alt="" className="h-full object-contain [image-rendering:pixelated]" />
        ) : (
          <Shirt className="size-6 text-muted-foreground" />
        )}
      </div>
      <div className="flex items-center justify-between gap-2 border-t border-border px-2.5 py-1.5">
        <div className="min-w-0">
          <div className="truncate text-xs font-medium">{label}</div>
          {sub ? <div className="text-[11px] text-muted-foreground">{sub}</div> : null}
        </div>
        {active ? <Badge variant="success"><Check className="size-3" />Worn</Badge> : null}
      </div>
    </button>
  );
}

export function WardrobeView({ status, refresh, notify }) {
  const [busy, setBusy] = useState(false);
  const [selectedSkinId, setSelectedSkinId] = useState("");
  const [selectedCapeId, setSelectedCapeId] = useState("");

  const profile = status?.auth?.profile;
  const signedIn = Boolean(status?.auth?.signedIn);
  const skins = Array.isArray(status?.skinHistory) ? status.skinHistory : [];
  const capes = Array.isArray(profile?.capes) ? profile.capes : [];

  const activeSkin = skins.find((s) => s.active) || skins[0] || null;
  const selectedSkin = skins.find((s) => s.id === selectedSkinId) || activeSkin;
  const activeCape = capes.find((c) => c.active || c.state === "ACTIVE") || null;
  const selectedCape = capes.find((c) => c.id === selectedCapeId) || activeCape;

  const guard = async (fn) => {
    setBusy(true);
    try {
      const res = await fn();
      if (res && res.ok === false) notify(res.message || "That did not work.", "error");
      await refresh();
    } catch (e) {
      notify(String(e?.message || e), "error");
    } finally {
      setBusy(false);
    }
  };

  const skinTexture = useMemo(
    () => (selectedSkin ? selectedSkin.previewDataUrl || selectedSkin.previewFileUrl : ""),
    [selectedSkin]
  );

  if (!signedIn) {
    return (
      <Page title="Wardrobe" description="Skins and capes.">
        <EmptyState
          icon={LogIn}
          title="Sign in to use the wardrobe"
          description="Sign in to your Microsoft account."
          action={<Button size="sm" onClick={() => guard(() => api()?.microsoftLogin())}>Sign in</Button>}
        />
      </Page>
    );
  }

  return (
    <Page
      title="Wardrobe"
      description={profile?.name ? `Signed in as ${profile.name}` : "Skins and capes"}
      actions={
        <Button size="sm" disabled={busy} onClick={() => guard(() => api()?.chooseSkin("classic"))}>
          {busy ? <Loader2 className="size-3.5 animate-spin" /> : <Upload className="size-3.5" />}
          Upload skin
        </Button>
      }
    >
      <Tabs defaultValue="skins">
        <TabsList>
          <TabsTrigger value="skins">Skins</TabsTrigger>
          <TabsTrigger value="capes">Capes</TabsTrigger>
        </TabsList>

        {/* SKINS */}
        <TabsContent value="skins">
          {skins.length ? (
            <div className="grid grid-cols-1 gap-4 md:grid-cols-[280px_1fr]">
              {/* 3D preview of the selected skin (wearing the active cape). */}
              <div className="flex flex-col gap-3">
                <div className="rounded-lg border border-border bg-card">
                  <SkinViewer
                    className="h-72 w-full"
                    texture={skinTexture}
                    slim={selectedSkin?.variant === "slim"}
                    cape={activeCape?.url || ""}
                  />
                </div>
                <div className="flex items-center justify-between gap-2">
                  <VariantToggle
                    value={selectedSkin?.variant === "slim" ? "slim" : "classic"}
                    disabled={busy || !selectedSkin}
                    onChange={(variant) => {
                      if (!selectedSkin || variant === selectedSkin.variant) return;
                      guard(() => api()?.updateSkinEntry({ skinId: selectedSkin.id, variant }));
                    }}
                  />
                  <div className="flex items-center gap-1.5">
                    {selectedSkin && !selectedSkin.active ? (
                      <Button size="sm" variant="secondary" disabled={busy} onClick={() => guard(() => api()?.equipSkin(selectedSkin.id))}>
                        Wear
                      </Button>
                    ) : null}
                    {selectedSkin ? (
                      <>
                        <Button size="icon" variant="ghost" title="Export PNG" disabled={busy} onClick={() => guard(() => api()?.exportSkin(selectedSkin.id))}>
                          <Download className="size-3.5" />
                        </Button>
                        <Button size="icon" variant="ghost" title="Remove" disabled={busy} onClick={() => guard(() => api()?.removeSkin(selectedSkin.id))}>
                          <Trash2 className="size-3.5 text-destructive" />
                        </Button>
                      </>
                    ) : null}
                  </div>
                </div>
                <p className="text-center text-[11px] text-muted-foreground">Drag the model to rotate.</p>
              </div>

              {/* Saved skins grid. */}
              <div className="grid grid-cols-[repeat(auto-fill,minmax(120px,1fr))] content-start gap-3">
                {skins.map((skin) => (
                  <Thumb
                    key={skin.id}
                    src={skin.previewDataUrl || skin.previewFileUrl}
                    label={skin.name}
                    sub={skin.variant}
                    active={skin.active}
                    selected={selectedSkin?.id === skin.id}
                    onClick={() => setSelectedSkinId(skin.id)}
                  />
                ))}
              </div>
            </div>
          ) : (
            <EmptyState
              icon={Shirt}
              title="No skins yet"
              description="Upload a skin PNG."
              action={<Button size="sm" onClick={() => guard(() => api()?.chooseSkin("classic"))}>Upload skin</Button>}
            />
          )}
        </TabsContent>

        {/* CAPES */}
        <TabsContent value="capes">
          {capes.length ? (
            <div className="grid grid-cols-1 gap-4 md:grid-cols-[280px_1fr]">
              {/* 3D preview of the active skin wearing the selected cape. */}
              <div className="flex flex-col gap-3">
                <div className="rounded-lg border border-border bg-card">
                  <SkinViewer
                    className="h-72 w-full"
                    texture={activeSkin ? activeSkin.previewDataUrl || activeSkin.previewFileUrl : ""}
                    slim={activeSkin?.variant === "slim"}
                    cape={selectedCape?.url || ""}
                  />
                </div>
                <div className="flex items-center justify-between gap-2">
                  <span className="truncate text-xs text-muted-foreground">
                    {selectedCape?.alias || selectedCape?.name || "No cape"}
                  </span>
                  <div className="flex items-center gap-1.5">
                    {selectedCape && !(selectedCape.active || selectedCape.state === "ACTIVE") ? (
                      <Button size="sm" variant="secondary" disabled={busy} onClick={() => guard(() => api()?.equipCape(selectedCape.id))}>
                        Wear
                      </Button>
                    ) : null}
                    <Button size="sm" variant="ghost" disabled={busy} onClick={() => { setSelectedCapeId(""); guard(() => api()?.clearCape()); }}>
                      No cape
                    </Button>
                  </div>
                </div>
                <p className="text-center text-[11px] text-muted-foreground">Drag the model to rotate.</p>
              </div>

              {/* Cape grid. */}
              <div className="grid grid-cols-[repeat(auto-fill,minmax(120px,1fr))] content-start gap-3">
                {capes.map((cape) => (
                  <Thumb
                    key={cape.id}
                    src={cape.url}
                    label={cape.alias || cape.name || "Cape"}
                    active={cape.active || cape.state === "ACTIVE"}
                    selected={selectedCape?.id === cape.id}
                    onClick={() => setSelectedCapeId(cape.id)}
                  />
                ))}
              </div>
            </div>
          ) : (
            <EmptyState
              icon={Shirt}
              title="No capes on this account"
              description="Unlocked capes show up here."
            />
          )}
        </TabsContent>
      </Tabs>
    </Page>
  );
}

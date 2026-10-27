import React, { useMemo, useState } from "react";
import { Shirt, Upload, Check, Trash2, Loader2, LogIn, Download, Ban, Plus } from "lucide-react";
import { api } from "../lib/useStatus";
import { cn } from "../lib/utils";
import { useModern } from "../lib/ui-style";
import { Page, EmptyState } from "../components/shell";
import { Button } from "../components/ui/button";
import { Badge } from "../components/ui/badge";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "../components/ui/tabs";
import { SkinViewer } from "../components/skin-viewer";
import { SkinFront, CapeFront } from "../components/skin-art";
import { PlayBlock } from "../components/instance-art";
import { CornerWeb, Pumpkin } from "../components/modern-season";
import { useSeasonId } from "../lib/seasons";
import { useT } from "../i18n/context.jsx";

/** Segmented slim/classic toggle. */
function VariantToggle({ value, onChange, disabled }) {
  const modern = useModern();
  return (
    <div className={cn("inline-flex border p-0.5", modern ? "h-10 rounded-lg border-border/60 bg-card p-1" : "rounded-md border-border")}>
      {["classic", "slim"].map((v) => (
        <button
          key={v}
          disabled={disabled}
          onClick={() => onChange(v)}
          className={cn(
            "capitalize transition-colors disabled:opacity-50",
            modern ? "rounded-md px-3.5 text-[13px] font-semibold" : "rounded px-3 py-1 text-xs font-medium",
            value === v
              ? modern ? "bg-primary/15 text-foreground" : "bg-accent text-foreground"
              : "text-muted-foreground hover:text-foreground"
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
  const t = useT();
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
        {active ? <Badge variant="success"><Check className="size-3" />{t("Worn")}</Badge> : null}
      </div>
    </button>
  );
}

/**
 * The 3D model on a lit floor: the Modern wardrobe's centrepiece. A faint floor tint and a
 * contact shadow keep the model standing on something instead of floating in a box.
 */
function Stage({ label, children }) {
  const halloween = useSeasonId() === "halloween";
  return (
    <div className="relative h-[420px] overflow-hidden rounded-xl border border-border/60 bg-card">
      <div className="pointer-events-none absolute inset-x-0 bottom-0 h-1/2 bg-gradient-to-t from-accent/70 to-transparent" />
      <div className="pointer-events-none absolute bottom-[54px] left-1/2 h-6 w-44 -translate-x-1/2 rounded-[50%] bg-[radial-gradient(closest-side,rgb(0_0_0/0.45),transparent)]" />
      {halloween ? <CornerWeb corner="right" size={128} className="opacity-30" /> : null}
      {children}
      {/* Halloween: a jack o'lantern on the floor beside you. */}
      {halloween ? <Pumpkin size={64} className="bottom-[46px] left-6" /> : null}
      {label ? <p className="m-kicker pointer-events-none absolute left-5 top-5">{label}</p> : null}
    </div>
  );
}

/** One saved skin or cape in the Modern grid: its picture on a small stage, its name, and state. */
function WardrobeCard({ picture, title, sub, worn, selected, onClick }) {
  const t = useT();
  return (
    <button
      type="button"
      onClick={onClick}
      aria-pressed={selected}
      className={cn(
        "flex flex-col overflow-hidden rounded-xl border bg-card text-left transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
        selected ? "border-primary" : "border-border/60 hover:border-border"
      )}
    >
      <div className="flex h-[150px] items-center justify-center bg-gradient-to-t from-accent/60 to-transparent">
        {picture}
      </div>
      <div className="flex items-center justify-between gap-2 px-3 py-2.5">
        <div className="min-w-0">
          <div className="truncate text-[13px] font-bold">{title}</div>
          {sub ? <div className="text-[11px] capitalize text-muted-foreground">{sub}</div> : null}
        </div>
        {worn ? <Badge variant="success"><Check className="size-3" />{t("Worn")}</Badge> : null}
      </div>
    </button>
  );
}

/** The Modern wardrobe: a large stage on the left, everything you own as cards on the right. */
function ModernWardrobe({
  t, busy, guard, skins, capes, selectedSkin, activeSkin, activeCape, selectedCape,
  skinTexture, setSelectedSkinId, setSelectedCapeId, profile
}) {
  const [tab, setTab] = useState("skins");
  const upload = () => guard(() => api()?.chooseSkin("classic"));
  const capeWorn = (cape) => Boolean(cape && (cape.active || cape.state === "ACTIVE"));
  const activeTexture = activeSkin ? activeSkin.previewDataUrl || activeSkin.previewFileUrl : "";

  return (
    <Page
      title={t("Wardrobe")}
      description={profile?.name ? t("Signed in as {name}", { name: profile.name }) : t("Skins and capes")}
      actions={
        <Button disabled={busy} onClick={upload}>
          {busy ? <Loader2 className="size-4 animate-spin" /> : <Upload className="size-4" />}
          {t("Upload skin")}
        </Button>
      }
    >
      <Tabs value={tab} onValueChange={setTab}>
        <TabsList>
          <TabsTrigger value="skins">{t("Skins")}</TabsTrigger>
          <TabsTrigger value="capes">{t("Capes")}</TabsTrigger>
        </TabsList>

        <TabsContent value="skins" className="mt-6">
          {skins.length ? (
            <div className="grid gap-8 lg:grid-cols-[340px_1fr]">
              <div className="space-y-3">
                <Stage label={selectedSkin?.active ? t("Wearing") : t("Preview")}>
                  <SkinViewer
                    className="absolute inset-0"
                    texture={skinTexture}
                    slim={selectedSkin?.variant === "slim"}
                    cape={activeCape?.url || ""}
                    spin={false}
                    scale={10}
                  />
                </Stage>
                <div className="flex items-center gap-2">
                  <VariantToggle
                    value={selectedSkin?.variant === "slim" ? "slim" : "classic"}
                    disabled={busy || !selectedSkin}
                    onChange={(variant) => {
                      if (!selectedSkin || variant === selectedSkin.variant) return;
                      guard(() => api()?.updateSkinEntry({ skinId: selectedSkin.id, variant }));
                    }}
                  />
                  <div className="ml-auto flex items-center gap-1">
                    <Button size="icon" variant="ghost" title={t("Export PNG")} disabled={busy || !selectedSkin} onClick={() => guard(() => api()?.exportSkin(selectedSkin.id))}>
                      <Download className="size-4" />
                    </Button>
                    <Button size="icon" variant="ghost" title={t("Remove")} disabled={busy || !selectedSkin} onClick={() => guard(() => api()?.removeSkin(selectedSkin.id))}>
                      <Trash2 className="size-4 text-destructive" />
                    </Button>
                  </div>
                </div>
                {selectedSkin && !selectedSkin.active ? (
                  <PlayBlock size="md" icon={Shirt} className="w-full" busy={busy} disabled={busy} onClick={() => guard(() => api()?.equipSkin(selectedSkin.id))}>
                    {t("Wear")}
                  </PlayBlock>
                ) : (
                  <p className="flex h-10 items-center justify-center gap-1.5 rounded-lg border border-border/60 text-xs text-muted-foreground">
                    <Check className="size-3.5 text-success" />{t("You are wearing this skin")}
                  </p>
                )}
                <p className="text-center text-[11px] text-muted-foreground">{t("Drag the model to rotate.")}</p>
              </div>

              <div>
                <h2 className="text-[17px] font-extrabold tracking-[-0.01em]">{t("Your skins")}</h2>
                <div className="mt-4 grid grid-cols-[repeat(auto-fill,minmax(140px,1fr))] gap-3">
                  {skins.map((skin) => (
                    <WardrobeCard
                      key={skin.id}
                      picture={<SkinFront src={skin.previewDataUrl || skin.previewFileUrl} slim={skin.variant === "slim"} scale={4} />}
                      title={skin.name}
                      sub={skin.variant}
                      worn={skin.active}
                      selected={selectedSkin?.id === skin.id}
                      onClick={() => setSelectedSkinId(skin.id)}
                    />
                  ))}
                  <button
                    type="button"
                    onClick={upload}
                    disabled={busy}
                    className="flex min-h-[200px] flex-col items-center justify-center gap-2 rounded-xl border border-dashed border-border/70 text-muted-foreground transition-colors hover:border-primary/60 hover:text-foreground"
                  >
                    <Plus className="size-6" />
                    <span className="text-[13px] font-semibold">{t("Upload skin")}</span>
                  </button>
                </div>
              </div>
            </div>
          ) : (
            <EmptyState
              icon={Shirt}
              title={t("No skins yet")}
              description={t("Upload a skin PNG.")}
              action={<Button size="sm" onClick={upload}>{t("Upload skin")}</Button>}
            />
          )}
        </TabsContent>

        <TabsContent value="capes" className="mt-6">
          {capes.length ? (
            <div className="grid gap-8 lg:grid-cols-[340px_1fr]">
              <div className="space-y-3">
                {/* Turned round, since a cape is worn on the back. */}
                <Stage label={capeWorn(selectedCape) ? t("Wearing") : t("Preview")}>
                  <SkinViewer
                    className="absolute inset-0"
                    texture={activeTexture}
                    slim={activeSkin?.variant === "slim"}
                    cape={selectedCape?.url || ""}
                    spin={false}
                    angle={158}
                    scale={10}
                  />
                </Stage>
                {selectedCape && !capeWorn(selectedCape) ? (
                  <PlayBlock size="md" icon={Shirt} className="w-full" busy={busy} disabled={busy} onClick={() => guard(() => api()?.equipCape(selectedCape.id))}>
                    {t("Wear")}
                  </PlayBlock>
                ) : selectedCape ? (
                  <p className="flex h-10 items-center justify-center gap-1.5 rounded-lg border border-border/60 text-xs text-muted-foreground">
                    <Check className="size-3.5 text-success" />{t("You are wearing this cape")}
                  </p>
                ) : null}
                <p className="text-center text-[11px] text-muted-foreground">{t("Drag the model to rotate.")}</p>
              </div>

              <div>
                <h2 className="text-[17px] font-extrabold tracking-[-0.01em]">{t("Your capes")}</h2>
                <div className="mt-4 grid grid-cols-[repeat(auto-fill,minmax(140px,1fr))] gap-3">
                  <WardrobeCard
                    picture={<Ban className="size-8 text-muted-foreground/60" />}
                    title={t("No cape")}
                    worn={!activeCape}
                    selected={false}
                    onClick={() => { setSelectedCapeId(""); guard(() => api()?.clearCape()); }}
                  />
                  {capes.map((cape) => (
                    <WardrobeCard
                      key={cape.id}
                      picture={<CapeFront src={cape.url} scale={7} />}
                      title={cape.alias || cape.name || t("Cape")}
                      worn={capeWorn(cape)}
                      selected={selectedCape?.id === cape.id}
                      onClick={() => setSelectedCapeId(cape.id)}
                    />
                  ))}
                </div>
              </div>
            </div>
          ) : (
            <EmptyState
              icon={Shirt}
              title={t("No capes on this account")}
              description={t("Unlocked capes show up here.")}
            />
          )}
        </TabsContent>
      </Tabs>
    </Page>
  );
}

export function WardrobeView({ status, refresh, notify }) {
  const t = useT();
  const modern = useModern();
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
      if (res && res.ok === false) notify(res.message || t("That did not work."), "error");
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
      <Page title={t("Wardrobe")} description={t("Skins and capes.")}>
        <EmptyState
          icon={LogIn}
          title={t("Sign in to use the wardrobe")}
          description={t("Sign in to your Microsoft account.")}
          action={<Button size="sm" onClick={() => guard(() => api()?.microsoftLogin())}>{t("Sign in")}</Button>}
        />
      </Page>
    );
  }

  if (modern) {
    return (
      <ModernWardrobe
        t={t}
        busy={busy}
        guard={guard}
        skins={skins}
        capes={capes}
        selectedSkin={selectedSkin}
        activeSkin={activeSkin}
        activeCape={activeCape}
        selectedCape={selectedCape}
        skinTexture={skinTexture}
        setSelectedSkinId={setSelectedSkinId}
        setSelectedCapeId={setSelectedCapeId}
        profile={profile}
      />
    );
  }

  return (
    <Page
      title={t("Wardrobe")}
      description={profile?.name ? t("Signed in as {name}", { name: profile.name }) : t("Skins and capes")}
      actions={
        <Button size="sm" disabled={busy} onClick={() => guard(() => api()?.chooseSkin("classic"))}>
          {busy ? <Loader2 className="size-3.5 animate-spin" /> : <Upload className="size-3.5" />}
          {t("Upload skin")}
        </Button>
      }
    >
      <Tabs defaultValue="skins">
        <TabsList>
          <TabsTrigger value="skins">{t("Skins")}</TabsTrigger>
          <TabsTrigger value="capes">{t("Capes")}</TabsTrigger>
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
                        {t("Wear")}
                      </Button>
                    ) : null}
                    {selectedSkin ? (
                      <>
                        <Button size="icon" variant="ghost" title={t("Export PNG")} disabled={busy} onClick={() => guard(() => api()?.exportSkin(selectedSkin.id))}>
                          <Download className="size-3.5" />
                        </Button>
                        <Button size="icon" variant="ghost" title={t("Remove")} disabled={busy} onClick={() => guard(() => api()?.removeSkin(selectedSkin.id))}>
                          <Trash2 className="size-3.5 text-destructive" />
                        </Button>
                      </>
                    ) : null}
                  </div>
                </div>
                <p className="text-center text-[11px] text-muted-foreground">{t("Drag the model to rotate.")}</p>
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
              title={t("No skins yet")}
              description={t("Upload a skin PNG.")}
              action={<Button size="sm" onClick={() => guard(() => api()?.chooseSkin("classic"))}>{t("Upload skin")}</Button>}
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
                    {selectedCape?.alias || selectedCape?.name || t("No cape")}
                  </span>
                  <div className="flex items-center gap-1.5">
                    {selectedCape && !(selectedCape.active || selectedCape.state === "ACTIVE") ? (
                      <Button size="sm" variant="secondary" disabled={busy} onClick={() => guard(() => api()?.equipCape(selectedCape.id))}>
                        {t("Wear")}
                      </Button>
                    ) : null}
                    <Button size="sm" variant="ghost" disabled={busy} onClick={() => { setSelectedCapeId(""); guard(() => api()?.clearCape()); }}>
                      {t("No cape")}
                    </Button>
                  </div>
                </div>
                <p className="text-center text-[11px] text-muted-foreground">{t("Drag the model to rotate.")}</p>
              </div>

              {/* Cape grid. */}
              <div className="grid grid-cols-[repeat(auto-fill,minmax(120px,1fr))] content-start gap-3">
                {capes.map((cape) => (
                  <Thumb
                    key={cape.id}
                    src={cape.url}
                    label={cape.alias || cape.name || t("Cape")}
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
              title={t("No capes on this account")}
              description={t("Unlocked capes show up here.")}
            />
          )}
        </TabsContent>
      </Tabs>
    </Page>
  );
}

import React, { useEffect, useMemo, useRef } from "react";
import { Shirt } from "lucide-react";
import { cn } from "../lib/utils";

/*
 * A dependency-free 3D Minecraft skin viewer.
 *
 * The launcher runs under a strict offline/CSP setup, so instead of pulling in a
 * WebGL library we assemble the player model out of CSS 3D transforms: every body
 * part is a cuboid of six <div> faces, and each face crops the right region of the
 * skin texture with background-position (image-rendering: pixelated keeps it crisp).
 * The texture is a data URL, so nothing leaves the machine. Drag to rotate; it idles
 * with a slow spin. Supports the slim (3px) arm model and an optional cape.
 */

// UV regions for a 64x64 skin. Each entry is [u, v, w, h] in texture pixels.
const SKIN = { w: 64, h: 64 };
const HEAD = {
  top: [8, 0, 8, 8], bottom: [16, 0, 8, 8], right: [0, 8, 8, 8],
  front: [8, 8, 8, 8], left: [16, 8, 8, 8], back: [24, 8, 8, 8]
};
const HAT = {
  top: [40, 0, 8, 8], bottom: [48, 0, 8, 8], right: [32, 8, 8, 8],
  front: [40, 8, 8, 8], left: [48, 8, 8, 8], back: [56, 8, 8, 8]
};
const BODY = {
  top: [20, 16, 8, 4], bottom: [28, 16, 8, 4], right: [16, 20, 4, 12],
  front: [20, 20, 8, 12], left: [28, 20, 4, 12], back: [32, 20, 8, 12]
};
const JACKET = {
  top: [20, 32, 8, 4], bottom: [28, 32, 8, 4], right: [16, 36, 4, 12],
  front: [20, 36, 8, 12], left: [28, 36, 4, 12], back: [32, 36, 8, 12]
};
const LEG_R = {
  top: [4, 16, 4, 4], bottom: [8, 16, 4, 4], right: [0, 20, 4, 12],
  front: [4, 20, 4, 12], left: [8, 20, 4, 12], back: [12, 20, 4, 12]
};
const PANTS_R = {
  top: [4, 32, 4, 4], bottom: [8, 32, 4, 4], right: [0, 36, 4, 12],
  front: [4, 36, 4, 12], left: [8, 36, 4, 12], back: [12, 36, 4, 12]
};
const LEG_L = {
  top: [20, 48, 4, 4], bottom: [24, 48, 4, 4], right: [16, 52, 4, 12],
  front: [20, 52, 4, 12], left: [24, 52, 4, 12], back: [28, 52, 4, 12]
};
const PANTS_L = {
  top: [4, 48, 4, 4], bottom: [8, 48, 4, 4], right: [0, 52, 4, 12],
  front: [4, 52, 4, 12], left: [8, 52, 4, 12], back: [12, 52, 4, 12]
};
// Arms: width is 4 (classic) or 3 (slim). The narrower regions shift the u origin.
function armR(slim) {
  const aw = slim ? 3 : 4;
  return {
    top: [44, 16, aw, 4], bottom: [44 + aw, 16, aw, 4], right: [40, 20, 4, 12],
    front: [44, 20, aw, 12], left: [44 + aw, 20, 4, 12], back: [44 + aw + 4, 20, aw, 12]
  };
}
function sleeveR(slim) {
  const aw = slim ? 3 : 4;
  return {
    top: [44, 32, aw, 4], bottom: [44 + aw, 32, aw, 4], right: [40, 36, 4, 12],
    front: [44, 36, aw, 12], left: [44 + aw, 36, 4, 12], back: [44 + aw + 4, 36, aw, 12]
  };
}
function armL(slim) {
  const aw = slim ? 3 : 4;
  return {
    top: [36, 48, aw, 4], bottom: [36 + aw, 48, aw, 4], right: [32, 52, 4, 12],
    front: [36, 52, aw, 12], left: [36 + aw, 52, 4, 12], back: [36 + aw + 4, 52, aw, 12]
  };
}
function sleeveL(slim) {
  const aw = slim ? 3 : 4;
  return {
    top: [52, 48, aw, 4], bottom: [52 + aw, 48, aw, 4], right: [48, 52, 4, 12],
    front: [52, 52, aw, 12], left: [52 + aw, 52, 4, 12], back: [52 + aw + 4, 52, aw, 12]
  };
}

// Cape texture is 64x32. Outer (decorated) side sits on the "back" face.
const CAPE_TEX = { w: 64, h: 32 };
const CAPE = {
  top: [1, 0, 10, 1], bottom: [11, 0, 10, 1], right: [11, 1, 1, 16],
  front: [12, 1, 10, 16], left: [0, 1, 1, 16], back: [1, 1, 10, 16]
};

// Fixed directional shading so the model reads as solid 3D instead of a flat
// papercraft cut-out: front lit, sides and bottom darker, top brightest.
const SHADE = { front: 1.0, back: 0.8, right: 0.78, left: 0.78, top: 0.96, bottom: 0.6 };

/** Build the six face descriptors for one cuboid. */
function faces(dims, uv, inflate, scale, texture, tex) {
  const [wu, hu, du] = dims;
  const W = (wu + inflate) * scale;
  const H = (hu + inflate) * scale;
  const D = (du + inflate) * scale;
  const list = [
    ["front", W, H, `translateZ(${D / 2}px)`, uv.front],
    ["back", W, H, `rotateY(180deg) translateZ(${D / 2}px)`, uv.back],
    // Character's right is world -x (they face the camera), so "right" is rotateY(-90).
    // Having these swapped put each side's texture on the wrong face, which read as
    // reversed outer layers on patterned skins.
    ["right", D, H, `rotateY(-90deg) translateZ(${W / 2}px)`, uv.right],
    ["left", D, H, `rotateY(90deg) translateZ(${W / 2}px)`, uv.left],
    ["top", W, D, `rotateX(90deg) translateZ(${H / 2}px)`, uv.top],
    // Minecraft's down-face UV runs front-to-back opposite the naive mapping, so the
    // bottom needs a 180 spin - without it the underside (neck/jaw) faced backwards.
    ["bottom", W, D, `rotateX(-90deg) translateZ(${H / 2}px) rotateZ(180deg)`, uv.bottom]
  ];
  return list.map(([name, fw, fh, transform, region]) => {
    const [u, v, rw, rh] = region;
    const sx = fw / rw;
    const sy = fh / rh;
    return {
      name, fw, fh, transform,
      filter: `brightness(${SHADE[name]})`,
      bg: {
        backgroundImage: `url("${texture}")`,
        backgroundSize: `${tex.w * sx}px ${tex.h * sy}px`,
        backgroundPosition: `${-u * sx}px ${-v * sy}px`
      }
    };
  });
}

function Box({ dims, uv, inflate = 0, pos, scale, texture, tex, extra = "" }) {
  const [x, y, z] = pos;
  const built = faces(dims, uv, inflate, scale, texture, tex);
  return (
    <div
      style={{
        position: "absolute",
        left: "50%",
        top: "50%",
        transformStyle: "preserve-3d",
        transform: `translate3d(${x * scale}px, ${y * scale}px, ${z * scale}px) ${extra}`
      }}
    >
      {built.map((f) => (
        <div
          key={f.name}
          style={{
            position: "absolute",
            left: "50%",
            top: "50%",
            width: `${f.fw}px`,
            height: `${f.fh}px`,
            marginLeft: `${-f.fw / 2}px`,
            marginTop: `${-f.fh / 2}px`,
            transform: f.transform,
            filter: f.filter,
            backfaceVisibility: "hidden",
            imageRendering: "pixelated",
            ...f.bg
          }}
        />
      ))}
    </div>
  );
}

export function SkinViewer({ texture, slim = false, cape = "", scale = 9, spin = true, className }) {
  const pivotRef = useRef(null);
  const state = useRef({ rx: -6, ry: -22, dragging: false, sx: 0, sy: 0, startRx: 0, startRy: 0 });

  useEffect(() => {
    let raf = 0;
    const apply = () => {
      const el = pivotRef.current;
      if (el) el.style.transform = `rotateX(${state.current.rx}deg) rotateY(${state.current.ry}deg)`;
    };
    const tick = () => {
      if (spin && !state.current.dragging) {
        state.current.ry += 0.25;
        apply();
      }
      raf = requestAnimationFrame(tick);
    };
    apply();
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [spin]);

  const onDown = (e) => {
    const s = state.current;
    s.dragging = true;
    s.sx = e.clientX;
    s.sy = e.clientY;
    s.startRx = s.rx;
    s.startRy = s.ry;
    e.currentTarget.setPointerCapture?.(e.pointerId);
  };
  const onMove = (e) => {
    const s = state.current;
    if (!s.dragging) return;
    s.ry = s.startRy + (e.clientX - s.sx) * 0.5;
    s.rx = Math.max(-40, Math.min(40, s.startRx + (e.clientY - s.sy) * 0.3));
    const el = pivotRef.current;
    if (el) el.style.transform = `rotateX(${s.rx}deg) rotateY(${s.ry}deg)`;
  };
  const onUp = (e) => {
    state.current.dragging = false;
    e.currentTarget.releasePointerCapture?.(e.pointerId);
  };

  const boxes = useMemo(() => {
    if (!texture) return [];
    const armW = slim ? 3 : 4;
    const armX = 4 + armW / 2;
    const t = texture;
    const list = [
      { dims: [8, 8, 8], uv: HEAD, pos: [0, -12, 0] },
      { dims: [8, 8, 8], uv: HAT, inflate: 1.0, pos: [0, -12, 0] },
      { dims: [8, 12, 4], uv: BODY, pos: [0, -2, 0] },
      { dims: [8, 12, 4], uv: JACKET, inflate: 0.5, pos: [0, -2, 0] },
      { dims: [armW, 12, 4], uv: armR(slim), pos: [-armX, -2, 0] },
      { dims: [armW, 12, 4], uv: sleeveR(slim), inflate: 0.5, pos: [-armX, -2, 0] },
      { dims: [armW, 12, 4], uv: armL(slim), pos: [armX, -2, 0] },
      { dims: [armW, 12, 4], uv: sleeveL(slim), inflate: 0.5, pos: [armX, -2, 0] },
      { dims: [4, 12, 4], uv: LEG_R, pos: [-2, 10, 0] },
      { dims: [4, 12, 4], uv: PANTS_R, inflate: 0.5, pos: [-2, 10, 0] },
      { dims: [4, 12, 4], uv: LEG_L, pos: [2, 10, 0] },
      { dims: [4, 12, 4], uv: PANTS_L, inflate: 0.5, pos: [2, 10, 0] }
    ].map((b) => ({ ...b, texture: t, tex: SKIN }));

    if (cape) {
      list.push({
        dims: [10, 16, 1], uv: CAPE, pos: [0, -1, -2.6], texture: cape, tex: CAPE_TEX,
        // Hangs from the shoulders and swings its bottom back, away from the body.
        extra: "rotateX(-10deg)"
      });
    }
    return list;
  }, [texture, slim, cape]);

  if (!texture) {
    return (
      <div className={cn("flex items-center justify-center text-muted-foreground", className)}>
        <Shirt className="size-8" />
      </div>
    );
  }

  return (
    <div
      className={cn("relative select-none overflow-hidden", className)}
      style={{ perspective: "700px", touchAction: "none", cursor: "grab" }}
      onPointerDown={onDown}
      onPointerMove={onMove}
      onPointerUp={onUp}
      onPointerLeave={onUp}
    >
      <div className="absolute left-1/2 top-1/2" style={{ transformStyle: "preserve-3d" }}>
        <div ref={pivotRef} style={{ transformStyle: "preserve-3d" }}>
          {boxes.map((b, i) => (
            <Box key={i} {...b} scale={scale} />
          ))}
        </div>
      </div>
    </div>
  );
}

"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { Box, RotateCcw } from "lucide-react";

/**
 * Interactive 3D model of the enclosure, loaded from the real STL files in /public/models
 * (exported from the OpenSCAD design). Three.js is imported lazily when the viewer nears the
 * viewport, and the scene renders on demand (no continuous animation loop).
 *
 * Coordinates follow the design file: Z = depth, Z=0 is the front face of the bezel.
 * The housing STL is stored print-oriented (shifted by -3 mm) and the rear cover is stored
 * flipped, so both are placed back into assembly position below.
 */
const D = 25; // total depth (rev 4)
const HOUSING_Z = 3; // housing STL is stored print-oriented, shifted by -3 mm (bezel plate thickness)
const OFFSETS = { bezel: -30, tft: -15, housing: 0, esp: 10, cover: 30 }; // exploded travel (mm)
const VIEWS = {
  Angle: [60, 45, -120],
  Front: [0, 0, -180],
  Side: [180, 0, 14],
  Back: [0, 0, 180],
} as const;
type ViewName = keyof typeof VIEWS;

/** Real firmware output: rendered by firmware/host-tests/render_site.cpp using the sketch's own drawing code, with sample data. */
const SCREENS = [
  { id: "clock", label: "Clock" },
  { id: "steps", label: "Steps" },
  { id: "music", label: "Music" },
  { id: "screentime", label: "Screen time" },
  { id: "notifications", label: "Notifications" },
  { id: "pairing", label: "Pairing" },
] as const;
type ScreenId = (typeof SCREENS)[number]["id"];

type Api = {
  setExplode: (v: number) => void;
  setBoards: (v: boolean) => void;
  setView: (n: ViewName) => void;
  setScreen: (id: ScreenId) => void;
};

export default function DeviceViewer() {
  const hostRef = useRef<HTMLDivElement>(null);
  const canvasHostRef = useRef<HTMLDivElement>(null);
  const api = useRef<Api | null>(null);
  const [started, setStarted] = useState(false);
  const [status, setStatus] = useState<"idle" | "loading" | "ready" | "error">("idle");
  const [explode, setExplode] = useState(0);
  const [boards, setBoards] = useState(true);
  const [screen, setScreen] = useState<ScreenId>("clock");

  // Start loading only when the viewer is close to the viewport.
  useEffect(() => {
    const el = hostRef.current;
    if (!el) return;
    if (typeof IntersectionObserver === "undefined") { setStarted(true); return; }
    const io = new IntersectionObserver(([e]) => { if (e.isIntersecting) { setStarted(true); io.disconnect(); } }, { rootMargin: "300px" });
    io.observe(el);
    return () => io.disconnect();
  }, []);

  useEffect(() => {
    if (!started) return;
    const host = canvasHostRef.current;
    if (!host) return;
    let cancelled = false;
    let cleanup = () => {};
    setStatus("loading");

    (async () => {
      try {
        const [THREE, { OrbitControls }, { STLLoader }, { RoomEnvironment }, { toCreasedNormals }] = await Promise.all([
          import("three"),
          import("three/examples/jsm/controls/OrbitControls.js"),
          import("three/examples/jsm/loaders/STLLoader.js"),
          import("three/examples/jsm/environments/RoomEnvironment.js"),
          import("three/examples/jsm/utils/BufferGeometryUtils.js"),
        ]);
        const loader = new STLLoader();
        const [bezelG, housingG, coverG] = await Promise.all(
          ["front-bezel", "main-housing", "rear-cover"].map((n) => loader.loadAsync(`/models/${n}.stl`)),
        );
        if (cancelled) return;

        const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true, powerPreference: "low-power" });
        renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
        renderer.toneMapping = THREE.ACESFilmicToneMapping;
        const canvas = renderer.domElement;
        canvas.tabIndex = 0;
        canvas.setAttribute("role", "img");
        canvas.setAttribute("aria-label", "Interactive 3D model of the MEOW Island enclosure. Drag to rotate, scroll or pinch to zoom, use the buttons for preset views.");
        canvas.style.cssText = "display:block;width:100%;height:100%;touch-action:none;cursor:grab;outline-offset:-2px";
        host.appendChild(canvas);

        const scene = new THREE.Scene();
        const pmrem = new THREE.PMREMGenerator(renderer);
        const envTex = pmrem.fromScene(new RoomEnvironment(), 0.04).texture;
        scene.environment = envTex;
        scene.environmentIntensity = 0.9;
        const key = new THREE.DirectionalLight(0xffffff, 1.4);
        key.position.set(80, 120, -120);
        scene.add(key);

        const camera = new THREE.PerspectiveCamera(32, 1, 5, 2000);
        const target = new THREE.Vector3(0, 0, 12.5);
        const controls = new OrbitControls(camera, canvas);
        controls.target.copy(target);
        controls.minDistance = 140;
        controls.maxDistance = 650;
        controls.enablePan = false;
        controls.listenToKeyEvents(canvas);

        const render = () => renderer.render(scene, camera);
        controls.addEventListener("change", render);

        const mat = (c: number, r = 0.55, m = 0.15) => new THREE.MeshStandardMaterial({ color: c, roughness: r, metalness: m });
        const disposables: { dispose(): void }[] = [pmrem, envTex, renderer, controls];
        const mk = (g: import("three").BufferGeometry, m: import("three").Material) => {
          const geo = toCreasedNormals(g, 0.6);
          disposables.push(geo, m);
          return new THREE.Mesh(geo, m);
        };

        const bezel = mk(bezelG, mat(0x2b2b30));
        const housing = mk(housingG, mat(0x3a3a40));
        const cover = mk(coverG, mat(0x2b2b30));
        cover.rotation.x = Math.PI; // stored flipped for printing

        // Simplified, NOT-printed reference blocks for the two boards (sizes from the design file).
        const box = (w: number, h: number, d: number, c: number, r = 0.5, m = 0.1) => {
          const geo = new THREE.BoxGeometry(w, h, d);
          const mt = mat(c, r, m);
          disposables.push(geo, mt);
          return new THREE.Mesh(geo, mt);
        };
        const tft = new THREE.Group();
        const tftPcb = box(51, 82, 1.6, 0x2457c5); tftPcb.position.set(0, 0, 5.8 + 0.8);
        const tftGlass = box(47, 78, 2.8, 0x07070b, 0.15, 0.4); tftGlass.position.set(0, 0, 3 + 1.4);
        tft.add(tftPcb, tftGlass);

        // Live display: 240x320 active area, 45 x 60 mm, centred in the 47 x 60 bezel window (window centre sits 3 mm below the PCB centre).
        const texLoader = new THREE.TextureLoader();
        const screenTex = {} as Record<ScreenId, import("three").Texture>;
        await Promise.all(SCREENS.map(async ({ id }) => {
          const t = await texLoader.loadAsync(`/models/screens/${id}.png`);
          t.colorSpace = THREE.SRGBColorSpace;
          t.anisotropy = Math.min(8, renderer.capabilities.getMaxAnisotropy());
          screenTex[id] = t;
          disposables.push(t);
        }));
        if (cancelled) { disposables.forEach((d) => d.dispose()); return; }
        const screenMat = new THREE.MeshBasicMaterial({ map: screenTex.clock, toneMapped: false });
        const screenGeo = new THREE.PlaneGeometry(45, 60);
        disposables.push(screenMat, screenGeo);
        const screenMesh = new THREE.Mesh(screenGeo, screenMat);
        screenMesh.rotation.y = Math.PI; // face the front (-Z)
        screenMesh.position.set(0, -3, 3 - 0.05);
        tft.add(screenMesh);

        const esp = new THREE.Group();
        const espPcb = box(52, 29, 1.6, 0x14281c); espPcb.position.set(0, 0, 13.4 + 0.8);
        const espMod = box(18, 25.5, 3.2, 0xb9bcc4, 0.35, 0.8); espMod.position.set(-14, 0, 13.4 - 1.6);
        const espUsb = box(8, 9, 3.3, 0xb9bcc4, 0.3, 0.9); espUsb.position.set(22, 0, 13.4 - 1.65); // USB connector, lines up with the +X wall cut-out
        esp.add(espPcb, espMod, espUsb);

        const group = new THREE.Group();
        group.add(bezel, housing, cover, tft, esp);
        scene.add(group);

        const place = (t: number) => {
          bezel.position.z = OFFSETS.bezel * t;
          tft.position.z = OFFSETS.tft * t;
          housing.position.z = HOUSING_Z + OFFSETS.housing * t;
          esp.position.z = OFFSETS.esp * t;
          cover.position.z = D + OFFSETS.cover * t;
        };
        place(0);

        const setView = (n: ViewName) => {
          const dist = camera.position.distanceTo(controls.target) || 300;
          const v = new THREE.Vector3(...VIEWS[n]).normalize().multiplyScalar(Math.min(Math.max(dist, 260), 650));
          camera.position.copy(target).add(v);
          controls.update();
          render();
        };
        camera.position.copy(target).add(new THREE.Vector3(...VIEWS.Angle).normalize().multiplyScalar(300));
        camera.lookAt(target);

        const resize = () => {
          const { clientWidth: w, clientHeight: h } = host;
          if (!w || !h) return;
          renderer.setSize(w, h, false);
          camera.aspect = w / h;
          camera.updateProjectionMatrix();
          render();
        };
        const ro = new ResizeObserver(resize);
        ro.observe(host);
        resize();

        api.current = {
          setExplode: (v) => { place(v); render(); },
          setBoards: (v) => { tft.visible = v; esp.visible = v; render(); },
          setView,
          setScreen: (id) => { screenMat.map = screenTex[id]; screenMat.needsUpdate = true; render(); },
        };
        setStatus("ready");

        cleanup = () => {
          ro.disconnect();
          controls.removeEventListener("change", render);
          disposables.forEach((d) => d.dispose());
          canvas.remove();
          api.current = null;
        };
        if (cancelled) cleanup();
      } catch {
        if (!cancelled) setStatus("error");
      }
    })();

    return () => { cancelled = true; cleanup(); };
  }, [started]);

  useEffect(() => { api.current?.setExplode(explode); }, [explode, status]);
  useEffect(() => { api.current?.setBoards(boards); }, [boards, status]);
  useEffect(() => { api.current?.setScreen(screen); }, [screen, status]);

  const view = useCallback((n: ViewName) => api.current?.setView(n), []);
  const ready = status === "ready";

  return (
    <div ref={hostRef} className="glass overflow-hidden rounded-[28px]">
      <div className="relative h-[340px] bg-gradient-to-b from-[#1a1330] to-[#0b0813] sm:h-[460px]">
        <div ref={canvasHostRef} className="absolute inset-0" />
        {status !== "ready" && (
          <div className="absolute inset-0 grid place-items-center px-6 text-center text-sm text-mist" role="status">
            {status === "error" ? (
              <p>Your browser could not start the 3D viewer (WebGL may be off). The design files are in the GitHub repository.</p>
            ) : (
              <p className="flex items-center gap-2"><Box size={18} className="text-lilac" aria-hidden /> Loading 3D model…</p>
            )}
          </div>
        )}
        {ready && <p className="pointer-events-none absolute left-4 top-3 text-xs text-mist">Drag to rotate · scroll or pinch to zoom</p>}
      </div>

      <div className="space-y-4 border-t border-lilac/10 p-4 sm:p-5">
        <div role="group" aria-label="Screen shown on the display" className="flex flex-wrap items-center gap-2">
          <span className="mr-1 text-xs font-semibold uppercase tracking-wider text-mist">Screen</span>
          {SCREENS.map(({ id, label }) => (
            <button key={id} type="button" disabled={!ready} aria-pressed={screen === id} onClick={() => setScreen(id)}
              className={`rounded-full border px-3.5 py-1.5 text-sm font-medium transition disabled:opacity-40 ${screen === id ? "border-lilac bg-lilac text-ink" : "border-lilac/30 text-[#e4def8] hover:border-lilac hover:bg-white/5"}`}>
              {label}
            </button>
          ))}
        </div>
        <div className="flex flex-wrap items-center gap-2" role="group" aria-label="Preset views">
          {(Object.keys(VIEWS) as ViewName[]).map((n) => (
            <button key={n} type="button" disabled={!ready} onClick={() => view(n)} className="rounded-full border border-lilac/30 px-3.5 py-1.5 text-sm font-medium text-[#e4def8] transition hover:border-lilac hover:bg-white/5 disabled:opacity-40">
              {n === "Side" ? "USB side" : n}
            </button>
          ))}
          <button type="button" disabled={!ready} onClick={() => { setExplode(0); view("Angle"); }} className="ml-auto inline-flex items-center gap-1.5 rounded-full px-3 py-1.5 text-sm text-mist transition hover:text-white disabled:opacity-40">
            <RotateCcw size={14} aria-hidden /> Reset
          </button>
        </div>
        <div className="flex flex-wrap items-center gap-x-6 gap-y-3">
          <label className="flex min-w-[200px] flex-1 items-center gap-3 text-sm text-[#e4def8]">
            <span className="shrink-0">Explode</span>
            <input type="range" min={0} max={1} step={0.01} value={explode} disabled={!ready} onChange={(e) => setExplode(Number(e.target.value))} className="h-2 w-full accent-[#b79cff]" aria-label="Explode the assembly" />
          </label>
          <label className="flex items-center gap-2 text-sm text-[#e4def8]">
            <input type="checkbox" checked={boards} disabled={!ready} onChange={(e) => setBoards(e.target.checked)} className="h-4 w-4 accent-[#b79cff]" />
            Show display + ESP32 boards
          </label>
        </div>
        <p className="text-xs leading-relaxed text-mist">
          The display shows your firmware&apos;s own screen drawing code, rendered on a computer with sample data (the Custom screen is omitted because it shows designs you send from the app). The case comes from the enclosure design files (57 × 87 × 25 mm, rev 4) and is not yet 3D-printed. The two boards are simplified blocks sized to the 5.1 × 8.2 cm display and 2.9 × 5.2 cm ESP32 from the design drawings.
        </p>
      </div>
    </div>
  );
}

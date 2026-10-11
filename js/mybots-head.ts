/* MyBots head wrapper — no React. Drives RobotSim/RobotRenderer directly
   (same pattern as RobotHead.tsx), exposes window.MB for the Kotlin side. */
import { RobotSim, restPose } from '../third_party/robot-heads/src/tv/sim';
import { RobotRenderer, frameFor, type Palette } from '../third_party/robot-heads/src/tv/render';
import { getShape } from '../third_party/robot-heads/src/tv/geometry';
import { linear } from '../third_party/robot-heads/src/tv/light';
import { parseColor } from '../third_party/robot-heads/src/color';
import type { RobotHeadState } from '../third_party/robot-heads/src/types';

const STATES: RobotHeadState[] = ['idle', 'thinking', 'searching', 'listening', 'speaking', 'working', 'happy', 'error', 'sleeping'];

let size = 240;
let state: RobotHeadState = 'idle';
const sim = new RobotSim(0.5, state);
const renderer = new RobotRenderer();
let shape = getShape('rectangle');
let pal: Palette = {
  shell: { albedo: linear([48, 80, 172]), f0: 0.05, metal: 0.15, spec: 0.55, shininess: 70, rim: 0.9 },
  trim: { albedo: linear([150, 168, 200]), f0: 0.2, metal: 0.65, spec: 0.8, shininess: 45, rim: 0.7 },
  led: [232, 242, 255],
};

const box = document.getElementById('box') as HTMLDivElement;
const canvas = document.getElementById('head') as HTMLCanvasElement;

function frame() { return frameFor(shape, true, size); }

function applySize() {
  box.style.width = size + 'px';
  box.style.height = size + 'px';
  const f = frame();
  canvas.style.left = -f.padSide + 'px';
  canvas.style.top = -f.padTop + 'px';
  canvas.style.width = (f.box + 2 * f.padSide) + 'px';
  canvas.style.height = (f.box + f.padTop + f.padBottom) + 'px';
}

function paint() {
  const f = frame();
  const dpr = Math.min(1.5, window.devicePixelRatio || 1);
  const w = Math.round((f.box + 2 * f.padSide) * dpr);
  const h = Math.round((f.box + f.padTop + f.padBottom) * dpr);
  if (canvas.width !== w || canvas.height !== h) { canvas.width = w; canvas.height = h; }
  const ctx = canvas.getContext('2d');
  if (!ctx) return;
  renderer.draw(ctx, f, dpr, sim.pose, sim, state, shape, pal, { floorShadow: true, antenna: true });
}

let raf = 0, last = 0, lastPaint = 0;
let lastSig = '';

/* Idle-only dirty gate: pose springs, blinks and glances can go perfectly
   still; only then skip redraws (the LED breath is sampled at ~10 Hz so it
   keeps pulsing). Busy states (anything with an active animation) always
   paint at ~30 fps — the playground feel stays intact where it matters. */
const ACTIVE: RobotHeadState[] = ['thinking', 'searching', 'listening', 'speaking', 'working', 'happy'];

function sig(): string {
  const p = sim.pose, f = sim.face;
  const q = (v: number) => Math.round(v * 1000);
  return [q(p.yaw), q(p.pitch), q(p.roll), q(p.x), q(p.lift), q(f.lookX), q(f.lookY),
          q(f.blink), Math.round(sim.time * 10)].join(',');
}

/* Dual-speed loop. Idle: a slow timer tick — no vsync registration, so
   Chromium's compositor goes quiet between ticks (~10 fps, plenty for
   blinks/glances, and the LED breath keeps pulsing). Anything moving:
   switch to rAF at full 30 fps, exactly like the playground. */
let mode = 'raf';
let intervalId = 0;

function wakeLoop() {
  if (mode === 'raf') return;
  mode = 'raf';
  if (intervalId) { clearInterval(intervalId); intervalId = 0; }
  last = 0;
  if (!raf && !document.hidden) raf = requestAnimationFrame(loop);
}

function slowLoop() {
  if (mode === 'idle') return;
  mode = 'idle';
  if (raf) { cancelAnimationFrame(raf); raf = 0; }
  last = 0;
  intervalId = setInterval(tick, 111);
}

function tick() {
  if (document.hidden) return;
  const now = performance.now();
  const dt = last ? Math.min(0.5, (now - last) / 1000) : 0.111;
  last = now;
  sim.update(dt);
  if (performance.now() < busyUntil || ACTIVE.indexOf(state) >= 0 || sim.shown !== state) {
    wakeLoop();
    return;
  }
  const s = sig();
  if (s !== lastSig) { paint(); lastSig = s; lastPaint = performance.now(); }
}

function loop(now: number) {
  raf = 0;
  const dt = last ? Math.min(0.1, (now - last) / 1000) : 0;
  last = now;
  if (!document.hidden) {
    sim.update(dt);
    const active = ACTIVE.indexOf(state) >= 0 || sim.shown !== state ||
                   performance.now() < busyUntil;
    const s = sig();
    const dirty = s !== lastSig;
    if (active) {
      if (now - lastPaint >= 31) { paint(); lastPaint = now; lastSig = s; }
    } else {
      if (dirty && now - lastPaint >= 90) { paint(); lastPaint = now; lastSig = s; }
      slowLoop(); /* settled down — drop the vsync loop */
    }
  }
  if (mode === 'raf') raf = requestAnimationFrame(loop);
}
let busyUntil = 0;

(window as any).MB = {
  state(s: string) {
    const key = (s && STATES.indexOf(s as RobotHeadState) >= 0) ? s as RobotHeadState : 'idle';
    if (key === state) return;
    state = key;
    sim.setState(state);
    wakeLoop();
    console.log('state=' + state);
  },
  size(px: number) { if (px > 0 && px !== size) { size = Math.round(px); applySize(); } },
  color(hex: string) {
    const shell = parseColor(hex);
    if (shell) pal = { ...pal, shell: { ...pal.shell, albedo: linear(shell) } };
  },
  poke() { sim.poke(); busyUntil = performance.now() + 1500; wakeLoop(); },
  pointer(x: number | null, y?: number | null) {
    if (x === null || y === null || x === undefined || y === undefined ||
        !Number.isFinite(x) || !Number.isFinite(y)) {
      sim.pointer = null;
      busyUntil = performance.now() + 800; /* springs settle back */
    } else {
      const d = Math.hypot(x, y);
      sim.pointer = d < 700 ? { x: x, y: y } : null;
      busyUntil = performance.now() + 160;
      wakeLoop();
    }
  },
  hidden(h: boolean) {
    if (h) {
      if (raf) { cancelAnimationFrame(raf); raf = 0; }
      if (intervalId) { clearInterval(intervalId); intervalId = 0; }
      mode = 'idle';
    }
    else { wakeLoop(); }
  },
};

applySize();
sim.update(1 / 60);
paint();
raf = requestAnimationFrame(loop);

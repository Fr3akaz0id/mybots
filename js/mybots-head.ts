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
  const dpr = Math.min(2, window.devicePixelRatio || 1);
  const w = Math.round((f.box + 2 * f.padSide) * dpr);
  const h = Math.round((f.box + f.padTop + f.padBottom) * dpr);
  if (canvas.width !== w || canvas.height !== h) { canvas.width = w; canvas.height = h; }
  const ctx = canvas.getContext('2d');
  if (!ctx) return;
  renderer.draw(ctx, f, dpr, sim.pose, sim, state, shape, pal, { floorShadow: true, antenna: true });
}

let raf = 0, last = 0;
function loop(now: number) {
  raf = 0;
  const dt = last ? Math.min(0.1, (now - last) / 1000) : 0;
  last = now;
  if (!document.hidden) { sim.update(dt); paint(); }
  raf = requestAnimationFrame(loop);
}

(window as any).MB = {
  state(s: string) {
    const key = (s && STATES.indexOf(s as RobotHeadState) >= 0) ? s as RobotHeadState : 'idle';
    if (key === state) return;
    state = key;
    sim.setState(state);
    console.log('state=' + state);
  },
  size(px: number) { if (px > 0 && px !== size) { size = Math.round(px); applySize(); } },
  color(hex: string) {
    const shell = parseColor(hex);
    if (shell) pal = { ...pal, shell: { ...pal.shell, albedo: linear(shell) } };
  },
  poke() { sim.poke(); },
  hidden(h: boolean) {
    if (h) { if (raf) { cancelAnimationFrame(raf); raf = 0; } }
    else if (!raf) { last = 0; raf = requestAnimationFrame(loop); }
  },
};

applySize();
sim.update(1 / 60);
paint();
raf = requestAnimationFrame(loop);

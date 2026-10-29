// Bundles the thinking-orbs engine for Genie's overlay WebView.
// Rebuild: npx esbuild scripts/orb/entry.js --bundle --minify --format=iife --outfile=app/src/main/assets/orb/thinking-orbs.js
import { MODE_FRAMES, resolvePreset, paintFrame } from 'thinking-orbs/engine';

// Mirrors the ThinkingOrb component's draw loop, minus React.
const canvas = document.getElementById('orb');
const ctx = canvas.getContext('2d');
const size = 64;
const { mode, speed, opts } = resolvePreset('composing', size);
const scale = Math.min(window.innerWidth, window.innerHeight) / size;
const dpr = window.devicePixelRatio || 1;
canvas.width = Math.round(size * scale * dpr);
canvas.height = Math.round(size * scale * dpr);

const draw = () => {
  ctx.setTransform(scale * dpr, 0, 0, scale * dpr, 0, 0);
  ctx.clearRect(0, 0, size, size);
  paintFrame(ctx, MODE_FRAMES[mode](size, performance.now() / 1e3 * speed, opts), true);
  requestAnimationFrame(draw);
};
draw();

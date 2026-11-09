// Animated custom screens: everything that can be done without Android. Pure TypeScript, unit-tested with Node (tests/animscreen.test.js).
//
//   native (Kotlin) : renders the user's HTML/CSS/JS in an isolated WebView, steps a *virtual clock* frame by frame (so CSS animations, Web Animations,
//                     setTimeout/setInterval/requestAnimationFrame and Date all advance deterministically) and returns the frames as RGB565
//   this module     : composites the frames, builds ONE colour palette (<= 256) for the whole animation (exact when the design is flat, median-cut + ordered
//                     dithering when it has gradients or photos), stores only the changed rectangle of every frame, run-length encodes it (MANI, see
//                     firmware/MeowIsland/mani.h), decodes it again for the preview, and writes an animated GIF so the preview shows exactly what the display plays
//   firmware        : stores the file in flash and plays it frame by frame, straight to the TFT
//
// The ESP32 itself cannot run a web engine (no GPU, ~300 KB of RAM) and Bluetooth LE moves only a few KB per second, so live HTML cannot be streamed to
// it. The phone does the web rendering; the display plays what the phone recorded. The byte format is defined once, in firmware/MeowIsland/mani.h.

import { crc32, b64decode, EncodeError, Bytes, DEVICE_W, DEVICE_H, rgb565ToRgb, pngFromRgb, b64encode } from './customscreen';

export const MANI_HEADER = 16;
export const MANI_FRAME_HEADER = 14;
export const MAX_SLOTS = 8;                    // MANI_SLOTS in protocol.h: screens the display can hold
export const DEVICE_MAX_FILE = 786432;         // the firmware refuses larger files (MANI_MAX_FILE)
export const DEFAULT_MAX_FILE = 786432;        // what the app builds by default: the display's own limit (768 KB); the screen shows the size and the send time before anything is sent
export const MAX_FRAMES = 240;                 // MANI_MAX_FRAMES
export const MIN_DELAY_MS = 20, MAX_DELAY_MS = 60000;
export const MAX_CAPTURE_FRAMES = 120;         // frames the phone records per screen
export const MAX_FPS = 15;                     // the TFT plus flash decode manage roughly 15 full-screen frames per second

// ---------------------------------------------------------------------------------------------------------------- settings of one screen
export type LoopMode = 'auto' | 'on' | 'off';
export type ScreenSettings = { scripts: boolean; online: boolean; animate: boolean; fps: number; durationMs: number; loop: LoopMode };
export const DEFAULT_SETTINGS: ScreenSettings = { scripts: true, online: true, animate: true, fps: 10, durationMs: 0, loop: 'auto' };
export function normalizeSettings(s: any): ScreenSettings {
  const d = DEFAULT_SETTINGS, o: any = s && typeof s === 'object' ? s : {};
  const num = (v: any, lo: number, hi: number, dflt: number) => { const n = Math.round(Number(v)); return isFinite(n) ? Math.min(hi, Math.max(lo, n)) : dflt; };
  return {
    scripts: o.scripts === undefined ? d.scripts : !!o.scripts, online: o.online === undefined ? d.online : !!o.online, animate: o.animate === undefined ? d.animate : !!o.animate,
    fps: num(o.fps, 1, MAX_FPS, d.fps), durationMs: Number(o.durationMs) > 0 ? num(o.durationMs, 500, 20000, 0) : 0,
    loop: o.loop === 'on' || o.loop === 'off' ? o.loop : 'auto',
  };
}

export type Frame = { x: number; y: number; w: number; h: number; delay: number; idx: Uint8Array };   // idx = palette indices of the rectangle, row-major
export type Animation = { w: number; h: number; loop: boolean; colors: number; palette: Uint16Array; frames: Frame[] };
export type Built = { bytes: Uint8Array; crc: number; w: number; h: number; colors: number; frames: number; durationMs: number; reduced: boolean; loop: boolean; animated: boolean };

// ---------------------------------------------------------------------------------------------------------------- colour quantiser
const BAYER4 = [0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5];
const r8 = (c: number) => { const r = c >> 11; return (r << 3) | (r >> 2); };
const g8 = (c: number) => { const g = (c >> 5) & 63; return (g << 2) | (g >> 4); };
const b8 = (c: number) => { const b = c & 31; return (b << 3) | (b >> 2); };
const to565 = (r: number, g: number, b: number) => ((r >> 3) << 11) | ((g >> 2) << 5) | (b >> 3);

export type Quantized = { palette: number[]; indexed: Uint8Array[]; reduced: boolean };

/**
 * One palette for every frame. <= 256 distinct colours: exact (flat designs, text, QR codes are untouched). Otherwise: the flat colours that cover
 * big areas (backgrounds, panels, pure black and white) keep exact palette entries, the rest (gradients, photos, shadows) is median-cut into the
 * remaining entries and mapped with a fixed 4x4 ordered dither. The dither depends only on (x, y, colour), so a pixel that does not change between
 * two frames gets the same index in both and costs nothing in the delta.
 */
export function quantizeFrames(frames: Uint16Array[], w = DEVICE_W): Quantized {
  const hist = new Uint32Array(65536); let unique = 0, total = 0;
  for (const f of frames) { total += f.length; for (let i = 0; i < f.length; i++) { if (hist[f[i]]++ === 0) unique++; } }
  if (unique <= 256) {
    const at = new Int16Array(65536).fill(-1), palette: number[] = [];
    const indexed = frames.map((f) => { const o = new Uint8Array(f.length); for (let i = 0; i < f.length; i++) { const c = f[i]; let k = at[c]; if (k < 0) { k = palette.length; at[c] = k; palette.push(c); } o[i] = k; } return o; });
    return { palette, indexed, reduced: false };
  }
  // 1. exact entries: black, white, and every colour that covers at least 0.25 % of all pixels (max 64 entries)
  const pinned: number[] = [], isPinned = new Uint8Array(65536);
  const pin = (c: number) => { if (!isPinned[c] && hist[c] > 0 && pinned.length < 64) { isPinned[c] = 1; pinned.push(c); } };
  pin(0x0000); pin(0xffff);
  const common: number[] = []; const minCover = Math.max(48, Math.floor(total * 0.0025));
  for (let c = 0; c < 65536; c++) if (hist[c] >= minCover && !isPinned[c]) common.push(c);
  common.sort((a, b) => hist[b] - hist[a] || a - b); for (const c of common) pin(c);
  // 2. median cut over the remaining colours
  const cols: number[] = []; for (let c = 0; c < 65536; c++) if (hist[c] > 0 && !isPinned[c]) cols.push(c);
  const target = 256 - pinned.length;
  type Box = { lo: number; hi: number; pop: number; rng: number; ch: number };
  const order = Int32Array.from(cols); const R = new Uint8Array(65536), G = new Uint8Array(65536), B = new Uint8Array(65536);
  for (const c of cols) { R[c] = r8(c); G[c] = g8(c); B[c] = b8(c); }
  const mk = (lo: number, hi: number): Box => {
    let rl = 255, rh = 0, gl = 255, gh = 0, bl = 255, bh = 0, pop = 0;
    for (let i = lo; i < hi; i++) { const c = order[i]; pop += hist[c]; if (R[c] < rl) rl = R[c]; if (R[c] > rh) rh = R[c]; if (G[c] < gl) gl = G[c]; if (G[c] > gh) gh = G[c]; if (B[c] < bl) bl = B[c]; if (B[c] > bh) bh = B[c]; }
    const rr = (rh - rl) * 1.0, gr = (gh - gl) * 1.0, br = (bh - bl) * 0.8;
    const ch = gr >= rr && gr >= br ? 1 : rr >= br ? 0 : 2; return { lo, hi, pop, rng: Math.max(rr, gr, br), ch };
  };
  const boxes: Box[] = []; if (cols.length) boxes.push(mk(0, cols.length));
  while (boxes.length < target) {
    let bi = -1, best = -1;
    for (let i = 0; i < boxes.length; i++) { const b = boxes[i]; if (b.hi - b.lo < 2) continue; const score = b.rng * Math.sqrt(b.pop); if (score > best) { best = score; bi = i; } }
    if (bi < 0 || best <= 0) break;
    const b = boxes[bi], ch = b.ch, arr = ch === 0 ? R : ch === 1 ? G : B;
    const part = Array.from(order.subarray(b.lo, b.hi)).sort((p, q) => arr[p] - arr[q] || p - q); for (let i = 0; i < part.length; i++) order[b.lo + i] = part[i];
    let acc = 0, cut = b.lo + 1; const half = b.pop / 2;
    for (let i = b.lo; i < b.hi - 1; i++) { acc += hist[order[i]]; cut = i + 1; if (acc >= half) break; }
    boxes.splice(bi, 1, mk(b.lo, cut), mk(cut, b.hi));
  }
  const palette: number[] = pinned.slice();
  for (const b of boxes) { let rs = 0, gs = 0, bs = 0, pop = 0; for (let i = b.lo; i < b.hi; i++) { const c = order[i], n = hist[c]; rs += R[c] * n; gs += G[c] * n; bs += B[c] * n; pop += n; } if (pop) palette.push(to565(Math.round(rs / pop), Math.round(gs / pop), Math.round(bs / pop))); }
  // 3. nearest-colour table on a 32x32x32 grid (fast lookups), pinned colours map to themselves
  const pr = palette.map(r8), pg = palette.map(g8), pb = palette.map(b8), n = palette.length;
  const lut = new Uint8Array(32768);
  for (let ri = 0; ri < 32; ri++) for (let gi = 0; gi < 32; gi++) for (let bi2 = 0; bi2 < 32; bi2++) {
    const r = ri * 8 + 4, g = gi * 8 + 4, b = bi2 * 8 + 4; let bk = 0, bd = 1e9;
    for (let k = 0; k < n; k++) { const dr = r - pr[k], dg = g - pg[k], db = b - pb[k]; const d = dr * dr * 2 + dg * dg * 4 + db * db * 3; if (d < bd) { bd = d; bk = k; } }
    lut[(ri << 10) | (gi << 5) | bi2] = bk;
  }
  const exact = new Int16Array(65536).fill(-1); for (let k = 0; k < pinned.length; k++) exact[pinned[k]] = k;
  const clamp = (v: number) => (v < 0 ? 0 : v > 255 ? 255 : v | 0);
  const indexed = frames.map((f) => {
    const o = new Uint8Array(f.length);
    for (let i = 0; i < f.length; i++) {
      const c = f[i], e = exact[c]; if (e >= 0) { o[i] = e; continue; }
      const r = R[c] || r8(c), g = G[c] || g8(c), b = B[c] || b8(c);
      let k = lut[((r >> 3) << 10) | ((g >> 3) << 5) | (b >> 3)];
      const dr = r - pr[k], dg = g - pg[k], db = b - pb[k];
      if (dr * dr * 2 + dg * dg * 4 + db * db * 3 > 300) {                  // a poor match: dither (a close match is left alone, so text edges stay clean)
        const t = (BAYER4[((((i / w) | 0) & 3) << 2) | ((i % w) & 3)] - 7.5) * 2.2;
        k = lut[((clamp(r + t) >> 3) << 10) | ((clamp(g + t) >> 3) << 5) | (clamp(b + t) >> 3)];
      }
      o[i] = k;
    }
    return o;
  });
  return { palette, indexed, reduced: true };
}

// ---------------------------------------------------------------------------------------------------------------- run-length coding (same tokens as MIMG)
export function rleEncode(idx: Uint8Array): number[] {
  const data: number[] = [], lit: number[] = [];
  const flush = () => { for (let p = 0; p < lit.length; p += 128) { const n = Math.min(128, lit.length - p); data.push(0x80 | (n - 1)); for (let k = 0; k < n; k++) data.push(lit[p + k]); } lit.length = 0; };
  for (let i = 0; i < idx.length;) {
    let r = 1; while (i + r < idx.length && idx[i + r] === idx[i] && r < 65535) r++;
    if (r >= 3) { flush(); if (r > 127) data.push(0, r & 255, r >> 8, idx[i]); else data.push(r, idx[i]); i += r; } else { lit.push(idx[i]); i++; }
  }
  flush(); return data;
}

// ---------------------------------------------------------------------------------------------------------------- build
function le16(o: number[], v: number) { o.push(v & 255, (v >> 8) & 255); }
function le32(o: number[], v: number) { o.push(v & 255, (v >>> 8) & 255, (v >>> 16) & 255, (v >>> 24) & 255); }

/**
 * Turns full-canvas RGB565 frames into a MANI file. Frame 0 is stored whole, every later frame only as the bounding rectangle of what changed;
 * frames that look identical to the previous one are merged into it (their time is added to its delay).
 * Throws EncodeError('TOO_COMPLEX') when the file would not fit maxBytes, EncodeError('BAD_SIZE') for malformed input.
 */
export function buildAnimation(frames: Uint16Array[], delays: number[], opts: { loop?: boolean; maxBytes?: number; w?: number; h?: number } = {}): Built {
  const w = opts.w || DEVICE_W, h = opts.h || DEVICE_H, maxBytes = Math.min(opts.maxBytes || DEFAULT_MAX_FILE, DEVICE_MAX_FILE);
  if (!frames.length || frames.length > MAX_CAPTURE_FRAMES + 1 || frames.length !== delays.length) throw new EncodeError('BAD_SIZE', 'The rendered animation has the wrong number of frames.');
  for (const f of frames) if (f.length !== w * h) throw new EncodeError('BAD_SIZE', 'The rendered picture has the wrong size.');
  const q = quantizeFrames(frames, w);
  const kept: { idx: Uint8Array; delay: number }[] = [];
  for (let i = 0; i < frames.length; i++) {
    const d = Math.min(MAX_DELAY_MS, Math.max(MIN_DELAY_MS, Math.round(Number(delays[i]) || 100)));
    const prev = kept[kept.length - 1], cur = q.indexed[i];
    if (prev && sameArr(prev.idx, cur)) prev.delay = Math.min(MAX_DELAY_MS, prev.delay + d); else kept.push({ idx: cur, delay: d });
  }
  const body: number[] = []; let prev: Uint8Array | null = null;
  for (const k of kept) {
    let x = 0, y = 0, rw = w, rh = h;
    if (prev) {
      let minX = w, minY = h, maxX = -1, maxY = -1;
      for (let j = 0; j < h; j++) { const row = j * w; for (let i = 0; i < w; i++) if (k.idx[row + i] !== prev[row + i]) { if (i < minX) minX = i; if (i > maxX) maxX = i; if (j < minY) minY = j; if (j > maxY) maxY = j; } }
      x = minX; y = minY; rw = maxX - minX + 1; rh = maxY - minY + 1;
    }
    const rect = new Uint8Array(rw * rh); for (let j = 0; j < rh; j++) rect.set(k.idx.subarray((y + j) * w + x, (y + j) * w + x + rw), j * rw);
    const data = rleEncode(rect);
    le16(body, x); le16(body, y); le16(body, rw); le16(body, rh); le16(body, k.delay); le32(body, data.length);
    for (let i = 0; i < data.length; i++) body.push(data[i]);
    prev = k.idx;
    if (MANI_HEADER + 2 * q.palette.length + body.length > maxBytes) break;
  }
  const total = MANI_HEADER + 2 * q.palette.length + body.length;
  if (total > maxBytes) throw new EncodeError('TOO_COMPLEX', `This screen is too detailed for the display (${Math.round(total / 1024)} KB after compression, the limit is ${Math.round(maxBytes / 1024)} KB). Lower the frame rate or the duration, or use flat colours, fewer gradients and smaller moving areas.`);
  const loop = kept.length > 1 && opts.loop !== false;
  const out: number[] = [0x4d, 0x41, 1, loop ? 1 : 0]; le16(out, w); le16(out, h); le16(out, q.palette.length); le16(out, kept.length); le32(out, body.length);
  for (const c of q.palette) le16(out, c);
  const bytes = new Uint8Array(total); bytes.set(out, 0); bytes.set(body, out.length);
  const durationMs = kept.reduce((s, k) => s + k.delay, 0);
  return { bytes, crc: crc32(bytes), w, h, colors: q.palette.length, frames: kept.length, durationMs, reduced: q.reduced, loop, animated: kept.length > 1 };
}
function sameArr(a: Uint8Array, b: Uint8Array) { if (a.length !== b.length) return false; for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) return false; return true; }

// ---------------------------------------------------------------------------------------------------------------- decode (mirror of the firmware validator)
export function isMani(b: Bytes) { return b.length >= 4 && b[0] === 0x4d && b[1] === 0x41; }
export function decodeAnimation(b: Bytes): Animation {
  const bad = (m: string): never => { throw new EncodeError('BAD_IMAGE', m); };
  if (b.length < MANI_HEADER + 2 + MANI_FRAME_HEADER + 2 || b.length > DEVICE_MAX_FILE) bad('wrong length');
  if (b[0] !== 0x4d || b[1] !== 0x41 || b[2] !== 1 || (b[3] & 0xfe) !== 0) bad('bad header');
  const w = b[4] | (b[5] << 8), h = b[6] | (b[7] << 8), colors = b[8] | (b[9] << 8), nf = b[10] | (b[11] << 8), bodyLen = (b[12] | (b[13] << 8) | (b[14] << 16) | (b[15] << 24)) >>> 0;
  if (w < 1 || h < 1 || w > 320 || h > 320 || colors < 1 || colors > 256 || nf < 1 || nf > MAX_FRAMES) bad('bad header fields');
  if (MANI_HEADER + 2 * colors + bodyLen !== b.length) bad('length does not match header');
  const pal = new Uint16Array(colors); for (let i = 0; i < colors; i++) pal[i] = b[MANI_HEADER + 2 * i] | (b[MANI_HEADER + 2 * i + 1] << 8);
  let at = MANI_HEADER + 2 * colors; const frames: Frame[] = [];
  for (let f = 0; f < nf; f++) {
    if (at + MANI_FRAME_HEADER > b.length) bad('truncated frame header');
    const x = b[at] | (b[at + 1] << 8), y = b[at + 2] | (b[at + 3] << 8), fw = b[at + 4] | (b[at + 5] << 8), fh = b[at + 6] | (b[at + 7] << 8), delay = b[at + 8] | (b[at + 9] << 8);
    const dl = (b[at + 10] | (b[at + 11] << 8) | (b[at + 12] << 16) | (b[at + 13] << 24)) >>> 0; at += MANI_FRAME_HEADER;
    if (fw < 1 || fh < 1 || x + fw > w || y + fh > h) bad('frame outside the screen');
    if (f === 0 && (x !== 0 || y !== 0 || fw !== w || fh !== h)) bad('the first frame must cover the screen');
    if (dl < 2 || at + dl > b.length) bad('truncated frame data');
    const idx = new Uint8Array(fw * fh); let o = 0; const end = at + dl;
    const put = (i: number, n: number) => { if (i >= colors) bad('palette index out of range'); if (o + n > idx.length) bad('too many pixels'); idx.fill(i, o, o + n); o += n; };
    while (at < end) {
      const c = b[at++];
      if (c === 0) { if (at + 3 > end) bad('truncated'); const n = b[at] | (b[at + 1] << 8); if (n === 0) bad('empty run'); put(b[at + 2], n); at += 3; }
      else if (c < 0x80) { if (at + 1 > end) bad('truncated'); put(b[at++], c); }
      else { const n = (c & 0x7f) + 1; if (at + n > end) bad('truncated'); for (let k = 0; k < n; k++) put(b[at++], 1); }
    }
    if (o !== idx.length) bad('too few pixels');
    frames.push({ x, y, w: fw, h: fh, delay, idx });
  }
  if (at !== b.length) bad('trailing data');
  return { w, h, loop: (b[3] & 1) !== 0, colors, palette: pal, frames };
}
/** Full-canvas RGB565 pictures of every frame (what the TFT shows after each frame). */
export function composeFrames(a: Animation): Uint16Array[] {
  const out: Uint16Array[] = []; let cur = new Uint16Array(a.w * a.h);
  for (const f of a.frames) {
    cur = Uint16Array.from(cur);
    for (let j = 0; j < f.h; j++) for (let i = 0; i < f.w; i++) cur[(f.y + j) * a.w + f.x + i] = a.palette[f.idx[j * f.w + i]];
    out.push(cur);
  }
  return out;
}

/** The frames as the native renderer reports them (frame 0 whole, later frames only the changed rectangle) -> full canvases + delays. */
export type NativeFrame = { x: number; y: number; w: number; h: number; delay: number; px: string };
export function framesFromNative(frames: NativeFrame[], w = DEVICE_W, h = DEVICE_H): { frames: Uint16Array[]; delays: number[] } {
  const out: Uint16Array[] = [], delays: number[] = []; let cur = new Uint16Array(w * h);
  for (let n = 0; n < frames.length; n++) {
    const f = frames[n], bytes = b64decode(f.px);
    if (f.w < 1 || f.h < 1 || f.x < 0 || f.y < 0 || f.x + f.w > w || f.y + f.h > h || bytes.length !== f.w * f.h * 2) throw new EncodeError('BAD_SIZE', 'The rendered picture has the wrong size.');
    if (n === 0 && (f.x !== 0 || f.y !== 0 || f.w !== w || f.h !== h)) throw new EncodeError('BAD_SIZE', 'The rendered picture has the wrong size.');
    cur = Uint16Array.from(cur);
    for (let j = 0; j < f.h; j++) for (let i = 0; i < f.w; i++) { const p = (j * f.w + i) * 2; cur[(f.y + j) * w + f.x + i] = bytes[p] | (bytes[p + 1] << 8); }
    out.push(cur); delays.push(f.delay);
  }
  return { frames: out, delays };
}

// ---------------------------------------------------------------------------------------------------------------- preview: animated GIF (the frames are already palette indexed)
function lzw(idx: Uint8Array, minCode: number): number[] {
  const clear = 1 << minCode, eoi = clear + 1, out: number[] = []; let size = minCode + 1, next = eoi + 1, acc = 0, bits = 0, table = new Map<number, number>();
  const emit = (c: number) => { acc |= c << bits; bits += size; while (bits >= 8) { out.push(acc & 255); acc >>>= 8; bits -= 8; } };
  emit(clear); let cur = idx[0];
  for (let i = 1; i < idx.length; i++) {
    const k = idx[i], key = (cur << 8) | k, hit = table.get(key);
    if (hit !== undefined) { cur = hit; continue; }
    emit(cur);
    if (next === 4096) { emit(clear); next = eoi + 1; size = minCode + 1; table = new Map(); }
    else { if (next >= (1 << size)) size++; table.set(key, next++); }
    cur = k;
  }
  emit(cur); if (next < 4096 && next >= (1 << size) && size < 12) size++;   // the decoder adds one more entry after the last data code
  emit(eoi); if (bits > 0) out.push(acc & 255);
  return out;
}
export function gifFromAnimation(a: Animation): Uint8Array {
  const o: number[] = []; const push = (...v: number[]) => { for (const x of v) o.push(x); };
  push(0x47, 0x49, 0x46, 0x38, 0x39, 0x61); le16(o, a.w); le16(o, a.h); push(0xf7, 0, 0);
  for (let i = 0; i < 256; i++) { const c = i < a.colors ? a.palette[i] : 0; push(r8(c), g8(c), b8(c)); }
  if (a.frames.length > 1 && a.loop) push(0x21, 0xff, 0x0b, 0x4e, 0x45, 0x54, 0x53, 0x43, 0x41, 0x50, 0x45, 0x32, 0x2e, 0x30, 0x03, 0x01, 0, 0, 0);
  for (const f of a.frames) {
    push(0x21, 0xf9, 0x04, 0x04); le16(o, Math.max(2, Math.round(f.delay / 10))); push(0, 0);
    push(0x2c); le16(o, f.x); le16(o, f.y); le16(o, f.w); le16(o, f.h); push(0);
    push(8); const data = lzw(f.idx, 8);
    for (let p = 0; p < data.length; p += 255) { const n = Math.min(255, data.length - p); push(n); for (let k = 0; k < n; k++) o.push(data[p + k]); }
    push(0);
  }
  push(0x3b); return Uint8Array.from(o);
}
export function gifUri(a: Animation): string { return 'data:image/gif;base64,' + b64encode(gifFromAnimation(a)); }
/** Small PNG of one full-canvas picture, nearest-neighbour scaled (list thumbnails). */
export function thumbUri(px: Uint16Array, w = DEVICE_W, h = DEVICE_H, tw = 60, th = 80): string {
  const small = new Uint16Array(tw * th); for (let y = 0; y < th; y++) for (let x = 0; x < tw; x++) small[y * tw + x] = px[Math.min(h - 1, Math.floor(((y + 0.5) * h) / th)) * w + Math.min(w - 1, Math.floor(((x + 0.5) * w) / tw))];
  return 'data:image/png;base64,' + b64encode(pngFromRgb(rgb565ToRgb(small), tw, th));
}

// ---------------------------------------------------------------------------------------------------------------- budget, labels
export type DeviceStorage = { totalKb: number; freeKb: number };
/** null when the file fits on the display, otherwise a readable reason. The display keeps the old copy until the new one is complete, so both must fit. */
export function storageProblem(bytes: number, st: DeviceStorage | null): string | null {
  if (bytes > DEVICE_MAX_FILE) return `This screen is ${Math.round(bytes / 1024)} KB; the display accepts at most ${DEVICE_MAX_FILE / 1024} KB per screen.`;
  if (!st || st.totalKb <= 0) return null;
  const freeBytes = st.freeKb * 1024 - 16384;       // room for file-system overhead
  if (bytes > freeBytes) return `The display has about ${Math.max(0, Math.round(freeBytes / 1024))} KB free but this screen needs ${Math.round(bytes / 1024)} KB (an updated screen needs room for the old copy until it is complete). Delete another screen, simplify this one, or flash the firmware with the "Default 4MB with spiffs" partition scheme (1.5 MB of storage).`;
  return null;
}
export function durationLabel(ms: number) { return ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(ms % 1000 === 0 ? 0 : 1)} s`; }

/** Expected digest of "slot -> file CRC" pairs, identical to the firmware's (protocol.h slotDigest). */
export function slotDigest(slots: { slot: number; crc: number }[]): number {
  const s = slots.slice().sort((a, b) => a.slot - b.slot), bytes: number[] = [];
  for (const e of s) { bytes.push(e.slot & 255); le32(bytes, e.crc >>> 0); }
  return bytes.length ? crc32(bytes) : 0;
}

// ---------------------------------------------------------------------------------------------------------------- HTML preparation
/**
 * The virtual clock and animation controller, injected as the first script of the page (scripts on) or installed after load (scripts off).
 * Nothing in the page can reach the app through it: it only replaces time sources inside the sealed renderer.
 */
export const HARNESS_JS = String.raw`(function(){
if (window.__meow) return;
var W = window, RD = Date, vt = 0, base = RD.now(), timers = [], rafs = [], ids = 1;
function vnow() { return base + vt; }
function FD() {
  if (!(this instanceof FD)) return new RD(vnow()).toString();
  var a = arguments;
  if (a.length === 0) return new RD(vnow());
  if (a.length === 1) return new RD(a[0]);
  return new RD(a[0], a[1], a[2] === undefined ? 1 : a[2], a[3] || 0, a[4] || 0, a[5] || 0, a[6] || 0);
}
FD.prototype = RD.prototype; FD.now = vnow; FD.UTC = RD.UTC; FD.parse = RD.parse; try { W.Date = FD; } catch (e) {}
try { W.performance.now = function () { return vt; }; } catch (e) {}
function add(fn, ms, every, args) {
  if (typeof fn === 'string') { var src = fn; fn = function () { (0, eval)(src); }; }
  if (typeof fn !== 'function') return 0;
  var id = ids++; ms = +ms; if (!(ms > 0)) ms = 0;
  timers.push({ id: id, at: vt + ms, fn: fn, args: args, every: every ? Math.max(4, ms) : 0 }); return id;
}
W.setTimeout = function (fn, ms) { return add(fn, ms, false, Array.prototype.slice.call(arguments, 2)); };
W.setInterval = function (fn, ms) { return add(fn, ms, true, Array.prototype.slice.call(arguments, 2)); };
W.clearTimeout = W.clearInterval = function (id) { for (var i = 0; i < timers.length; i++) if (timers[i].id === id) { timers.splice(i, 1); return; } };
W.requestAnimationFrame = function (cb) { if (typeof cb !== 'function') return 0; var id = ids++; rafs.push({ id: id, cb: cb }); return id; };
W.cancelAnimationFrame = function (id) { for (var i = 0; i < rafs.length; i++) if (rafs[i].id === id) { rafs.splice(i, 1); return; } };
W.requestIdleCallback = function (cb) { return add(function () { cb({ didTimeout: false, timeRemaining: function () { return 10; } }); }, 1, false, []); };
W.cancelIdleCallback = W.clearTimeout;
function runTimers(limit) {
  var guard = 0;
  for (;;) {
    var bi = -1;
    for (var i = 0; i < timers.length; i++) { var t = timers[i]; if (t.at <= limit && (bi < 0 || t.at < timers[bi].at || (t.at === timers[bi].at && t.id < timers[bi].id))) bi = i; }
    if (bi < 0 || ++guard > 4000) break;
    var tm = timers[bi]; if (tm.at > vt) vt = tm.at;
    if (tm.every) tm.at += tm.every; else timers.splice(bi, 1);
    try { tm.fn.apply(W, tm.args); } catch (e) {}
  }
}
function anims() { try { return document.getAnimations ? document.getAnimations() : []; } catch (e) { return []; } }
function sync() {
  var l = anims();
  for (var i = 0; i < l.length; i++) {
    var a = l[i], m = a.__m;
    if (!m) { m = a.__m = { v0: vt }; try { a.pause(); } catch (e) {} }
    try { a.currentTime = vt - m.v0; } catch (e) {}
  }
}
W.__meow = {
  step: function (dt) {
    var target = vt + (+dt || 0);
    runTimers(target); vt = target; sync();
    var cbs = rafs; rafs = [];
    for (var i = 0; i < cbs.length; i++) { try { cbs[i].cb(vt); } catch (e) {} }
    runTimers(vt); sync();
    return rafs.length + timers.length;
  },
  ready: function () {
    if (document.readyState !== 'complete') return false;
    var im = document.images;
    for (var i = 0; i < im.length; i++) { try { if (im[i].loading === 'lazy') im[i].loading = 'eager'; } catch (e) {} if (!im[i].complete) return false; }
    try { if (document.fonts && document.fonts.status === 'loading') return false; } catch (e) {}
    return true;
  },
  measure: function () {
    var fin = 0, inf = 0, n = 0, l = anims();
    for (var i = 0; i < l.length; i++) {
      var t; try { t = l[i].effect && l[i].effect.getComputedTiming(); } catch (e) { continue; }
      if (!t || typeof t.duration !== 'number') continue; n++;
      var d = (t.delay || 0) + t.duration;
      if (t.iterations === Infinity || t.endTime === Infinity) { if (d > inf) inf = d; } else { var e2 = typeof t.endTime === 'number' ? t.endTime : d * (t.iterations || 1); if (e2 > fin) fin = e2; }
    }
    var iv = 0; for (var j = 0; j < timers.length; j++) if (timers[j].every) iv++;
    return { anims: n, finiteEnd: fin, infiniteCycle: inf, raf: rafs.length, timers: timers.length, intervals: iv };
  },
  now: function () { return vt; }
};
})();`;

const FREEZE_STYLE = '<style id="__meow_freeze">*,*::before,*::after{animation-play-state:paused!important}</style>';
const NO_SCRIPT_CSP = '<meta http-equiv="Content-Security-Policy" content="script-src \'none\'">';
/**
 * Adds, right at the start of the document: a style that holds CSS animations at time zero until the capture steps them, and either the harness
 * script (scripts on) or a Content-Security-Policy that stops the page's own scripts (scripts off; the harness is then installed from outside).
 */
export function prepareHtml(html: string, scripts: boolean): string {
  const inject = (scripts ? '' : NO_SCRIPT_CSP) + FREEZE_STYLE + (scripts ? '<script>' + HARNESS_JS + '</script>' : '');
  let m = /<head(\s[^>]*)?>/i.exec(html);
  if (!m) m = /<html(\s[^>]*)?>/i.exec(html);
  if (m) return html.slice(0, m.index + m[0].length) + inject + html.slice(m.index + m[0].length);
  const d = /^\s*<!doctype[^>]*>/i.exec(html);
  if (d) return html.slice(0, d[0].length) + '<head>' + inject + '</head>' + html.slice(d[0].length);
  return '<head>' + inject + '</head>' + html;
}

// ---------------------------------------------------------------------------------------------------------------- upload checks
/** Readable reason why a picked set of files cannot be used, or null. */
export function bundleProblem(name: string, htmlBytes: number, html: string): string | null {
  if (!html || html.length === 0) return 'No HTML file was found. Choose an .html file (optionally with its images), or a .zip that contains one.';
  if (html.indexOf('\u0000') >= 0) return 'That file looks like a binary file, not HTML.';
  if (html.indexOf('<') < 0) return 'That file does not contain any HTML tags.';
  if (htmlBytes > 1024 * 1024) return `The HTML file is ${Math.round(htmlBytes / 1024)} KB. The limit is 1024 KB; put big images in separate files or a .zip.`;
  return null;
}
export function screenNameFromFile(file: string): string {
  const base = String(file || '').replace(/\.(html?|zip)$/i, '').replace(/[_-]+/g, ' ').trim();
  return (base || 'Custom screen').slice(0, 40);
}

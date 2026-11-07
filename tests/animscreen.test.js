const A = src('core/animscreen.ts');
const C = src('core/customscreen.ts');
const fs = require('fs'), path = require('path');
const W = 240, H = 320;
const rgb = (r, g, b) => ((r >> 3) << 11) | ((g >> 2) << 5) | (b >> 3);
let seed = 4242; const rnd = () => ((seed = (seed * 1103515245 + 12345) & 0x7fffffff) / 0x7fffffff);
const blank = (c) => new Uint16Array(W * H).fill(c);
function fillRect(px, x, y, w, h, c) { for (let j = y; j < y + h; j++) for (let i = x; i < x + w; i++) if (i >= 0 && j >= 0 && i < W && j < H) px[j * W + i] = c; }
const same = (a, b) => a.length === b.length && a.every((v, i) => v === b[i]);
const ch = (c) => [((c >> 11) << 3) | (c >> 13), (((c >> 5) & 63) << 2) | (((c >> 5) & 63) >> 4), ((c & 31) << 3) | ((c & 31) >> 2)];
function mae(a, b) { let s = 0; for (let i = 0; i < a.length; i++) { const p = ch(a[i]), q = ch(b[i]); s += Math.abs(p[0] - q[0]) + Math.abs(p[1] - q[1]) + Math.abs(p[2] - q[2]); } return s / (a.length * 3); }

// a flat animation: a coloured square sliding over a dark card, with a pulsing bar (8 colours)
function flatFrames(n = 12) {
  const out = [];
  for (let f = 0; f < n; f++) { const p = blank(rgb(14, 11, 22)); fillRect(p, 20, 20, 200, 60, rgb(124, 77, 255)); fillRect(p, 10 + f * 16, 150, 40, 40, rgb(255, 209, 102)); fillRect(p, 20, 260, 20 + (f % 6) * 30, 14, rgb(239, 71, 111)); out.push(p); }
  return out;
}
// smooth colour field + a moving highlight: far more than 256 colours
function gradientFrames(n = 6) {
  const out = [];
  for (let f = 0; f < n; f++) { const p = new Uint16Array(W * H); for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) { const d = Math.hypot(x - (40 + f * 30), y - 160); const glow = Math.max(0, 1 - d / 60) * 120; p[y * W + x] = rgb(Math.min(255, 30 + (x * 120) / W + glow), Math.min(255, 20 + (y * 160) / H + glow), Math.min(255, 110 + ((x + y) * 80) / (W + H) + glow)); } out.push(p); }
  return out;
}
// photo-like: smooth low-frequency colour plus fine texture
function photo() { const p = new Uint16Array(W * H); for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) { const t = (rnd() - 0.5) * 60; p[y * W + x] = rgb(Math.max(0, Math.min(255, 128 + 90 * Math.sin(x / 31) * Math.cos(y / 47) + t)), Math.max(0, Math.min(255, 110 + 80 * Math.sin((x + y) / 53) + t)), Math.max(0, Math.min(255, 140 + 70 * Math.cos(x / 29 - y / 61) + t))); } return p; }
const delays = (n, ms = 100) => new Array(n).fill(ms);

test('flat animation: exact palette, pixel-exact round trip, delta frames are small', () => {
  const fr = flatFrames(); const b = A.buildAnimation(fr, delays(fr.length)); const a = A.decodeAnimation(b.bytes);
  assert.strictEqual(b.reduced, false); assert.strictEqual(b.colors, 4); assert.strictEqual(a.frames.length, 12); assert.ok(b.animated); assert.ok(b.loop);
  const back = A.composeFrames(a); for (let i = 0; i < fr.length; i++) assert.ok(same(back[i], fr[i]), 'frame ' + i + ' identical');
  assert.deepStrictEqual([a.frames[0].x, a.frames[0].y, a.frames[0].w, a.frames[0].h], [0, 0, W, H]);
  for (let i = 1; i < a.frames.length; i++) assert.ok(a.frames[i].w * a.frames[i].h < W * H / 2, 'frame ' + i + ' only stores what changed');
  assert.ok(b.bytes.length < 6000, 'a 12 frame flat animation is tiny: ' + b.bytes.length); assert.strictEqual(b.crc, C.crc32(b.bytes)); assert.strictEqual(b.durationMs, 1200);
});
test('identical frames are merged and a still becomes one frame', () => {
  const p = flatFrames(1)[0]; const b = A.buildAnimation([p, p, p], [100, 200, 300]); const a = A.decodeAnimation(b.bytes);
  assert.strictEqual(a.frames.length, 1); assert.strictEqual(a.frames[0].delay, 600); assert.strictEqual(b.animated, false); assert.strictEqual(b.loop, false);
  const solid = A.buildAnimation([blank(0)], [100]); assert.ok(solid.bytes.length < 80);
});
test('a loop flag of off is kept', () => { const fr = flatFrames(3); const b = A.buildAnimation(fr, delays(3), { loop: false }); assert.strictEqual(A.decodeAnimation(b.bytes).loop, false); });
test('delays are clamped to what the display can honour', () => {
  const fr = flatFrames(3); const a = A.decodeAnimation(A.buildAnimation(fr, [1, 99999, 50]).bytes);
  assert.deepStrictEqual(Array.from(a.frames.map((f) => f.delay)), [A.MIN_DELAY_MS, A.MAX_DELAY_MS, 50]);
});
test('gradients and photos are reduced to 256 colours with a small error, flat colours stay exact', () => {
  const g = gradientFrames(); const b = A.buildAnimation(g, delays(g.length)); const back = A.composeFrames(A.decodeAnimation(b.bytes));
  assert.strictEqual(b.reduced, true); assert.ok(b.colors <= 256);
  for (let i = 0; i < g.length; i++) assert.ok(mae(g[i], back[i]) < 9, 'gradient frame ' + i + ' mean error ' + mae(g[i], back[i]).toFixed(2));
  const mixed = blank(rgb(14, 11, 22)); fillRect(mixed, 10, 10, 220, 40, rgb(124, 77, 255)); const ph = photo(); for (let y = 100; y < 300; y++) for (let x = 20; x < 220; x++) mixed[y * W + x] = ph[y * W + x];
  const bm = A.buildAnimation([mixed], [100]); const dm = A.composeFrames(A.decodeAnimation(bm.bytes))[0];
  assert.strictEqual(dm[5 * W + 5], rgb(14, 11, 22), 'background stays exact'); assert.strictEqual(dm[20 * W + 20], rgb(124, 77, 255), 'panel stays exact'); assert.ok(mae(mixed, dm) < 9, 'photo mean error ' + mae(mixed, dm).toFixed(2));
});
test('a full-screen photo now fits (the old single-image encoder refused it)', () => {
  const p = photo(); assert.throws(() => C.encodeImage(p), (e) => e.code === 'TOO_COMPLEX');
  const b = A.buildAnimation([p], [100]); assert.ok(b.bytes.length > C.MAX_IMAGE_BYTES && b.bytes.length < A.DEFAULT_MAX_FILE, 'size ' + b.bytes.length);
  assert.ok(mae(p, A.composeFrames(A.decodeAnimation(b.bytes))[0]) < 10);
});
test('black and white stay exactly black and white after reduction', () => {
  const g = gradientFrames(1)[0]; g[0] = 0xffff; g[1] = 0; const d = A.composeFrames(A.decodeAnimation(A.buildAnimation([g], [100]).bytes))[0]; assert.strictEqual(d[0], 0xffff); assert.strictEqual(d[1], 0);
});
test('unchanged pixels stay unchanged after dithering (so deltas stay small on gradients)', () => {
  const base = gradientFrames(1)[0]; const f2 = Uint16Array.from(base); fillRect(f2, 100, 100, 20, 20, 0xffff);
  const b = A.buildAnimation([base, f2], [100, 100]); const a = A.decodeAnimation(b.bytes);
  assert.strictEqual(a.frames.length, 2); assert.ok(a.frames[1].w <= 20 && a.frames[1].h <= 20, 'only the changed square is stored: ' + a.frames[1].w + 'x' + a.frames[1].h);
});
test('too detailed or too many frames are refused with advice, never sent', () => {
  const noise = new Uint16Array(W * H); for (let i = 0; i < noise.length; i++) noise[i] = (rnd() * 65536) | 0;
  assert.throws(() => A.buildAnimation([noise], [100], { maxBytes: 20000 }), (e) => e.code === 'TOO_COMPLEX' && /Lower the frame rate/.test(e.message));
  assert.throws(() => A.buildAnimation([new Uint16Array(10)], [100]), (e) => e.code === 'BAD_SIZE');
  assert.throws(() => A.buildAnimation([], []), (e) => e.code === 'BAD_SIZE');
  assert.throws(() => A.buildAnimation(flatFrames(2), [100]), (e) => e.code === 'BAD_SIZE');
});
test('decoder rejects anything the device would reject and never crashes', () => {
  const good = A.buildAnimation(flatFrames(5), delays(5)).bytes; assert.doesNotThrow(() => A.decodeAnimation(good));
  const bad = (b, why) => assert.throws(() => A.decodeAnimation(b), (e) => e.code === 'BAD_IMAGE', why);
  bad(good.subarray(0, good.length - 1), 'truncated'); bad(Uint8Array.from([...good, 0]), 'trailing byte');
  const m = (i, v) => { const c = Uint8Array.from(good); c[i] = v; return c; };
  bad(m(0, 0x58), 'magic'); bad(m(2, 2), 'version'); bad(m(3, 2), 'unknown flag'); bad(m(8, 0), 'no colours'); bad(m(10, 0), 'no frames'); bad(m(5, 0xff), 'width');
  let rejected = 0; for (let t = 0; t < 600; t++) { const c = Uint8Array.from(good); for (let k = 0; k < 1 + (t % 4); k++) c[(rnd() * c.length) | 0] ^= 1 << ((rnd() * 8) | 0); try { A.decodeAnimation(c); } catch (e) { assert.ok(e.code === 'BAD_IMAGE', 'only controlled errors: ' + e.message); rejected++; } }
  assert.ok(rejected > 250, 'rejected ' + rejected);
});
test('native frames (first whole, rest rectangles) composite to the full pictures', () => {
  const fr = flatFrames(4); const nat = fr.map((f, i) => { let x = 0, y = 0, w = W, h = H; if (i) { let a = W, b2 = H, c = -1, d = -1; for (let j = 0; j < H; j++) for (let k = 0; k < W; k++) if (f[j * W + k] !== fr[i - 1][j * W + k]) { a = Math.min(a, k); c = Math.max(c, k); b2 = Math.min(b2, j); d = Math.max(d, j); } x = a; y = b2; w = c - a + 1; h = d - b2 + 1; }
    const bytes = new Uint8Array(w * h * 2); for (let j = 0; j < h; j++) for (let k = 0; k < w; k++) { const v = f[(y + j) * W + x + k]; bytes[(j * w + k) * 2] = v & 255; bytes[(j * w + k) * 2 + 1] = v >> 8; } return { x, y, w, h, delay: 100, px: C.b64encode(bytes) }; });
  const out = A.framesFromNative(nat); for (let i = 0; i < fr.length; i++) assert.ok(same(out.frames[i], fr[i]), 'frame ' + i);
  assert.throws(() => A.framesFromNative([{ x: 5, y: 0, w: 10, h: 10, delay: 100, px: '' }]), (e) => e.code === 'BAD_SIZE');
});

// ---- a small GIF decoder, so the preview is verified by decoding it, not by trusting the encoder
function gifDecode(buf) {
  let p = 6; const w = buf[p] | (buf[p + 1] << 8), h = buf[p + 2] | (buf[p + 3] << 8), flags = buf[p + 4]; p += 7; const gct = []; if (flags & 0x80) { const n = 2 << (flags & 7); for (let i = 0; i < n; i++) { gct.push([buf[p], buf[p + 1], buf[p + 2]]); p += 3; } }
  const frames = []; let canvas = new Array(w * h).fill(null), delay = 0, loops = null;
  while (buf[p] !== 0x3b) {
    if (buf[p] === 0x21) { const lab = buf[p + 1]; p += 2; if (lab === 0xf9) delay = buf[p + 2] | (buf[p + 3] << 8); if (lab === 0xff && buf[p + 1] === 0x4e) loops = true; while (buf[p] !== 0) p += buf[p] + 1; p++; continue; }
    assert.strictEqual(buf[p], 0x2c); const x = buf[p + 1] | (buf[p + 2] << 8), y = buf[p + 3] | (buf[p + 4] << 8), fw = buf[p + 5] | (buf[p + 6] << 8), fh = buf[p + 7] | (buf[p + 8] << 8); p += 10;
    const min = buf[p++]; const data = []; while (buf[p] !== 0) { for (let i = 0; i < buf[p]; i++) data.push(buf[p + 1 + i]); p += buf[p] + 1; } p++;
    const clear = 1 << min, eoi = clear + 1; let size = min + 1, next = eoi + 1, dict = [], acc = 0, bits = 0, di = 0, prev = null; const out = [];
    const reset = () => { dict = []; for (let i = 0; i < clear; i++) dict[i] = [i]; dict[clear] = null; dict[eoi] = null; size = min + 1; next = eoi + 1; prev = null; }; reset();
    for (;;) {
      while (bits < size && di < data.length) { acc |= data[di++] << bits; bits += 8; } if (bits < size) break;
      const code = acc & ((1 << size) - 1); acc >>>= size; bits -= size;
      if (code === clear) { reset(); continue; } if (code === eoi) break;
      let entry; if (dict[code]) entry = dict[code]; else { assert.ok(prev && code === next, 'bad lzw code'); entry = prev.concat(prev[0]); }
      for (const v of entry) out.push(v);
      if (prev && next < 4096) { dict[next++] = prev.concat(entry[0]); if (next === (1 << size) && size < 12) size++; }
      prev = entry;
    }
    assert.strictEqual(out.length, fw * fh, 'gif frame pixel count');
    canvas = canvas.slice(); for (let j = 0; j < fh; j++) for (let i = 0; i < fw; i++) canvas[(y + j) * w + x + i] = gct[out[j * fw + i]];
    frames.push({ delay: delay * 10, canvas }); 
  }
  return { w, h, frames, loops };
}
test('the animated GIF preview decodes to exactly the frames the display will show', () => {
  for (const [name, fr] of [['flat', flatFrames(7)], ['gradient', gradientFrames(3)]]) {
    const b = A.buildAnimation(fr, delays(fr.length, 80)); const a = A.decodeAnimation(b.bytes); const gif = A.gifFromAnimation(a); const g = gifDecode(gif);
    assert.strictEqual(String.fromCharCode(...gif.subarray(0, 6)), 'GIF89a'); assert.strictEqual(g.w, W); assert.strictEqual(g.h, H); assert.strictEqual(g.frames.length, a.frames.length); assert.ok(g.loops, 'loops');
    const want = A.composeFrames(a);
    for (let i = 0; i < want.length; i++) { assert.strictEqual(g.frames[i].delay, 80); for (const px of [0, 1000, 20000, 40000, 76799]) { const c = ch(want[i][px]); assert.deepStrictEqual(g.frames[i].canvas[px], c.map((v, k) => v), name + ' frame ' + i + ' px ' + px); } let bad = 0; for (let k = 0; k < want[i].length; k++) { const c = ch(want[i][k]), q = g.frames[i].canvas[k]; if (c[0] !== q[0] || c[1] !== q[1] || c[2] !== q[2]) bad++; } assert.strictEqual(bad, 0, name + ' frame ' + i + ' pixels differ: ' + bad); }
  }
  assert.ok(A.gifUri(A.decodeAnimation(A.buildAnimation(flatFrames(2), delays(2)).bytes)).startsWith('data:image/gif;base64,'));
  const still = A.gifFromAnimation(A.decodeAnimation(A.buildAnimation([blank(0)], [100]).bytes)); assert.ok(!gifDecode(still).loops, 'a still does not loop');
});
test('thumbnail PNG is a PNG', () => { const u = A.thumbUri(flatFrames(1)[0]); assert.ok(u.startsWith('data:image/png;base64,')); assert.strictEqual(Buffer.from(u.slice(22), 'base64').subarray(0, 8).toString('hex'), '89504e470d0a1a0a'); });

test('settings are clamped and defaulted', () => {
  assert.deepStrictEqual(JSON.parse(JSON.stringify(A.normalizeSettings(undefined))), { scripts: true, online: true, animate: true, fps: 10, durationMs: 0, loop: 'auto' });
  const s = A.normalizeSettings({ fps: 99, durationMs: 100, loop: 'sideways', scripts: false, online: 0 }); assert.strictEqual(s.fps, A.MAX_FPS); assert.strictEqual(s.durationMs, 500); assert.strictEqual(s.loop, 'auto'); assert.strictEqual(s.scripts, false); assert.strictEqual(s.online, false);
  assert.strictEqual(A.normalizeSettings({ fps: 'x' }).fps, 10); assert.strictEqual(A.normalizeSettings({ durationMs: 999999 }).durationMs, 20000); assert.strictEqual(A.normalizeSettings({ fps: 0 }).fps, 1);
});
test('storage check explains what to do', () => {
  assert.strictEqual(A.storageProblem(10000, { totalKb: 1400, freeKb: 1200 }), null); assert.strictEqual(A.storageProblem(10000, null), null);
  assert.ok(/Delete another screen/.test(A.storageProblem(120000, { totalKb: 190, freeKb: 100 }))); assert.strictEqual(A.storageProblem(60000, { totalKb: 190, freeKb: 100 }), null);
  assert.ok(/at most/.test(A.storageProblem(A.DEVICE_MAX_FILE + 1, null)));
});
test('slot digest matches the firmware definition (sorted slot byte + CRC little-endian)', () => {
  assert.strictEqual(A.slotDigest([]), 0); const d = A.slotDigest([{ slot: 3, crc: 0xdeadbeef }, { slot: 0, crc: 0x01020304 }]);
  assert.strictEqual(d, C.crc32([0, 4, 3, 2, 1, 3, 0xef, 0xbe, 0xad, 0xde])); assert.strictEqual(A.slotDigest([{ slot: 0, crc: 1 }]), C.crc32([0, 1, 0, 0, 0]));
});
test('HTML preparation puts the harness first without breaking the doctype', () => {
  const doc = '<!doctype html><html lang="en"><head><meta charset="utf-8"><title>x</title></head><body>hi</body></html>';
  const on = A.prepareHtml(doc, true); assert.ok(on.startsWith('<!doctype html><html lang="en"><head>')); assert.ok(on.indexOf('<head><style id="__meow_freeze">') > 0 && on.indexOf('<script>') < on.indexOf('<meta charset'));
  assert.ok(!/Content-Security-Policy/.test(on)); const off = A.prepareHtml(doc, false); assert.ok(/script-src 'none'/.test(off) && off.indexOf('<script>') < 0);
  assert.ok(/^<head><style/.test(A.prepareHtml('<p>bare</p>', true)) && A.prepareHtml('<p>bare</p>', true).endsWith('<p>bare</p>'));
  const noHead = A.prepareHtml('<!DOCTYPE html><body>x</body>', true); assert.ok(noHead.startsWith('<!DOCTYPE html><head>') && noHead.endsWith('<body>x</body>'));
  assert.ok(A.prepareHtml('<html><body>x</body></html>', true).indexOf('<html><style') === 0);
});
test('upload checks and names', () => {
  assert.strictEqual(A.bundleProblem('a.html', 20, '<p>x</p>'), null); assert.ok(/No HTML/.test(A.bundleProblem('', 0, ''))); assert.ok(/binary/.test(A.bundleProblem('a.html', 5, '<p>\u0000'))); assert.ok(/tags/.test(A.bundleProblem('a.html', 5, 'words'))); assert.ok(/1024 KB/.test(A.bundleProblem('a.html', 2e6, '<p>')));
  assert.strictEqual(A.screenNameFromFile('my_cool-card.HTML'), 'my cool card'); assert.strictEqual(A.screenNameFromFile(''), 'Custom screen'); assert.strictEqual(A.screenNameFromFile('bundle.zip'), 'bundle');
});

// ---- virtual clock: the parts that need no DOM can be checked in plain Node (the browser run is tests/browser/harness.test.js)
test('virtual clock: timers, intervals, rAF, Date and performance.now advance only when stepped', () => {
  const vm = require('vm'); const calls = []; const sandbox = { document: { readyState: 'complete', images: [], getAnimations: () => [] }, performance: { now: () => 1e9 }, console };
  sandbox.window = sandbox; vm.createContext(sandbox); vm.runInContext(A.HARNESS_JS, sandbox);
  const w = sandbox, m = w.__meow; const t0 = w.Date.now();
  w.setTimeout(() => calls.push('t100@' + (w.Date.now() - t0)), 100); const iv = w.setInterval(() => calls.push('i50'), 50); w.requestAnimationFrame((ts) => { calls.push('raf@' + ts); w.requestAnimationFrame((ts2) => calls.push('raf2@' + ts2)); });
  w.setTimeout((a, b) => calls.push('args' + a + b), 10, 'x', 'y');
  assert.deepStrictEqual(calls, [], 'nothing runs before the first step');
  m.step(0); assert.deepStrictEqual(calls, ['raf@0']);
  m.step(10); assert.deepStrictEqual(calls, ['raf@0', 'argsxy', 'raf2@10']);
  m.step(90); assert.ok(calls.includes('t100@100') && calls.filter((c) => c === 'i50').length === 2, calls.join());
  assert.strictEqual(w.performance.now(), 100); assert.strictEqual(w.Date.now() - t0, 100); assert.ok(new w.Date() instanceof w.Date); assert.strictEqual(new w.Date().getTime(), w.Date.now()); assert.strictEqual(new w.Date(2020, 0, 5).getDate(), 5);
  w.clearInterval(iv); m.step(500); assert.strictEqual(calls.filter((c) => c === 'i50').length, 2, 'a cleared interval stops'); assert.strictEqual(m.measure().intervals, 0);
  assert.strictEqual(m.ready(), true);
});

test('write cross-check vectors for the firmware player (tests/mani_vectors.txt)', () => {
  const lines = []; const hex = (b) => Buffer.from(b).toString('hex');
  const add = (name, fr, dl, opts) => { const b = A.buildAnimation(fr, dl, opts), a = A.decodeAnimation(b.bytes), full = A.composeFrames(a); lines.push([name, hex(b.bytes), a.w + 'x' + a.h, a.frames.length, a.loop ? 1 : 0, full.map((p) => C.pixelsCrc(p).toString(16)).join(','), a.frames.map((f) => f.delay).join(','), a.frames.map((f) => [f.x, f.y, f.w, f.h].join(':')).join(',')].join('|')); };
  seed = 99; add('flat', flatFrames(9), delays(9, 90)); add('still', [flatFrames(1)[0]], [100]); add('once', flatFrames(4), delays(4, 40), { loop: false }); add('gradient', gradientFrames(3), delays(3, 120));
  const mixed = blank(rgb(14, 11, 22)); const ph = photo(); for (let y = 80; y < 300; y++) for (let x = 10; x < 230; x++) mixed[y * W + x] = ph[y * W + x]; const mixed2 = Uint16Array.from(mixed); fillRect(mixed2, 100, 10, 60, 30, 0xffff); add('photo', [mixed, mixed2], [200, 300]);
  const one = blank(0); fillRect(one, 239, 319, 1, 1, 0xffff); const two = blank(0); fillRect(two, 0, 0, 1, 1, 0xffff); add('corners', [one, two, blank(0xffff)], [30, 30, 30]);
  fs.writeFileSync(path.join(__dirname, 'mani_vectors.txt'), lines.join('\n') + '\n'); assert.strictEqual(lines.length, 6);
});

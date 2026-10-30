const C = src('core/customscreen.ts');
const fs = require('fs'), path = require('path'), zlib = require('zlib');
const W = 240, H = 320;
const rgb = (r, g, b) => ((r >> 3) << 11) | ((g >> 2) << 5) | (b >> 3);
let seed = 12345; const rnd = () => ((seed = (seed * 1103515245 + 12345) & 0x7fffffff) / 0x7fffffff);
const blank = (c) => new Uint16Array(W * H).fill(c);
function fillRect(px, x, y, w, h, c) { for (let j = y; j < y + h; j++) for (let i = x; i < x + w; i++) if (i >= 0 && j >= 0 && i < W && j < H) px[j * W + i] = c; }
// designs a user could plausibly make
function textLike() { const p = blank(rgb(10, 10, 20)); for (let line = 0; line < 12; line++) for (let g = 0; g < 18; g++) { const grey = 60 + ((g * 37 + line * 11) % 16) * 12; fillRect(p, 10 + g * 12, 20 + line * 24, 3 + (g % 5), 12, rgb(grey, grey, 255)); fillRect(p, 10 + g * 12, 20 + line * 24, 1, 12, rgb(255, 255, 255)); } return p; }
function qrLike() { const p = blank(0xffff); const n = 29, s = 8, ox = (W - n * s) / 2, oy = 40; for (let y = 0; y < n; y++) for (let x = 0; x < n; x++) if (rnd() < 0.5) fillRect(p, ox + x * s, oy + y * s, s, s, 0); return p; }
function gradient() { const p = new Uint16Array(W * H); for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) p[y * W + x] = rgb(40 + (x * 150) / W, 10 + (y * 200) / H, 120 + ((x + y) * 100) / (W + H)); return p; }
function noise() { const p = new Uint16Array(W * H); for (let i = 0; i < p.length; i++) p[i] = (rnd() * 65536) | 0; return p; }
const same = (a, b) => a.length === b.length && a.every((v, i) => v === b[i]);

test('crc32 and base64 are standard', () => {
  assert.strictEqual(C.crc32(Buffer.from('123456789')), 0xcbf43926); assert.strictEqual(C.crc32([]), 0);
  for (let n = 0; n <= 40; n++) { const b = Buffer.alloc(n); for (let i = 0; i < n; i++) b[i] = (i * 73 + n) & 255; assert.strictEqual(C.b64encode(b), b.toString('base64'), 'len ' + n); assert.deepStrictEqual(Array.from(C.b64decode(b.toString('base64'))), Array.from(b)); }
  assert.deepStrictEqual(Array.from(C.b64decode('QUJD\n')), [65, 66, 67], 'whitespace is ignored');
});
test('uploaded file checks give readable reasons', () => {
  assert.strictEqual(C.htmlProblem('card.html', 100, '<h1>Hi</h1>'), null); assert.strictEqual(C.htmlProblem('CARD.HTM', 100, '<p>x'), null);
  assert.ok(/\.html/.test(C.htmlProblem('pic.png', 100, '<p>'))); assert.ok(/empty/.test(C.htmlProblem('a.html', 0, ''))); assert.ok(/limit/.test(C.htmlProblem('a.html', 300 * 1024, '<p>')));
  assert.ok(/binary/.test(C.htmlProblem('a.html', 10, '<p>\u0000'))); assert.ok(/tags/.test(C.htmlProblem('a.html', 10, 'just words')));
  assert.ok(C.htmlProblem(undefined, 1, '<p>'), 'a missing name is rejected, not a crash');
});
test('flat designs encode exactly and small', () => {
  for (const [name, px] of [['text', textLike()], ['qr', qrLike()], ['solid', blank(rgb(124, 77, 255))]]) {
    const e = C.encodeImage(px); const d = C.decodeImage(e.bytes);
    assert.strictEqual(e.reduced, false, name); assert.ok(same(d.pixels, px), name + ' round trip'); assert.strictEqual(d.w, W); assert.strictEqual(d.h, H);
    assert.ok(e.bytes.length < C.MAX_IMAGE_BYTES / 2, name + ' size ' + e.bytes.length); assert.ok(e.colors <= 256); assert.strictEqual(e.crc, C.crc32(e.bytes));
  }
  assert.strictEqual(C.encodeImage(blank(0)).colors, 1); assert.ok(C.encodeImage(blank(0)).bytes.length < 40, 'a single colour is a handful of bytes');
});
test('gradients are reduced to 256 colours with a small error', () => {
  const px = gradient(); const e = C.encodeImage(px); const d = C.decodeImage(e.bytes);
  assert.strictEqual(e.reduced, true); assert.ok(e.colors <= 256); assert.ok(e.bytes.length < C.MAX_IMAGE_BYTES);
  let worst = 0; for (let i = 0; i < px.length; i++) { const a = px[i], b = d.pixels[i]; worst = Math.max(worst, Math.abs((a >> 11) - (b >> 11)) * 2, Math.abs(((a >> 5) & 63) - ((b >> 5) & 63)), Math.abs((a & 31) - (b & 31)) * 2); }
  assert.ok(worst <= 12, 'worst channel error (in 6-bit units) ' + worst);
});
test('photos and noise are refused with advice, never sent', () => {
  assert.throws(() => C.encodeImage(noise()), (e) => e.code === 'TOO_COMPLEX' && /too detailed/.test(e.message));
  assert.throws(() => C.encodeImage(new Uint16Array(10)), (e) => e.code === 'BAD_SIZE');
});
test('decoder rejects anything the device would reject, and never loops or crashes', () => {
  const good = C.encodeImage(textLike()).bytes; assert.doesNotThrow(() => C.decodeImage(good));
  const expectBad = (b, why) => assert.throws(() => C.decodeImage(b), (e) => e.code === 'BAD_IMAGE', why);
  expectBad(good.subarray(0, good.length - 1), 'truncated'); expectBad(Uint8Array.from([...good, 0]), 'extra byte');
  const m = (i, v) => { const c = Uint8Array.from(good); c[i] = v; return c; };
  expectBad(m(0, 0x58), 'magic'); expectBad(m(2, 2), 'version'); expectBad(m(3, 1), 'flags'); expectBad(m(8, 0), 'no colours'); expectBad(m(9, 1), '>256 colours'); expectBad(m(5, 0xff), 'width');
  expectBad(m(16 + 2 * C.decodeImage(good).pixels.length * 0 + 2 * (good[8] | (good[9] << 8)) + 1, 255), 'palette index out of range');
  let rejected = 0; for (let t = 0; t < 400; t++) { const c = Uint8Array.from(good); for (let k = 0; k < 1 + (t % 5); k++) c[(rnd() * c.length) | 0] ^= 1 << ((rnd() * 8) | 0); try { C.decodeImage(c); } catch (e) { assert.ok(e.code === 'BAD_IMAGE', 'only controlled errors'); rejected++; } }
  assert.ok(rejected > 200);
});
test('preview PNG is a valid PNG whose pixels equal the device image', () => {
  const px = C.decodeImage(C.encodeImage(textLike()).bytes).pixels; const png = Buffer.from(C.pngFromRgb(C.rgb565ToRgb(px), W, H));
  assert.strictEqual(png.subarray(0, 8).toString('hex'), '89504e470d0a1a0a'); let at = 8; const chunks = {};
  while (at < png.length) { const len = png.readUInt32BE(at), type = png.toString('ascii', at + 4, at + 8); const body = png.subarray(at + 8, at + 8 + len); assert.strictEqual(png.readUInt32BE(at + 8 + len), C.crc32(png.subarray(at + 4, at + 8 + len)), type + ' crc'); chunks[type] = body; at += 12 + len; }
  assert.strictEqual(chunks.IHDR.readUInt32BE(0), W); assert.strictEqual(chunks.IHDR.readUInt32BE(4), H); assert.ok('IEND' in chunks);
  const raw = zlib.inflateSync(chunks.IDAT), rgbPx = C.rgb565ToRgb(px); assert.strictEqual(raw.length, (W * 3 + 1) * H);
  for (const y of [0, 1, 100, 319]) { assert.strictEqual(raw[y * (W * 3 + 1)], 0); assert.deepStrictEqual(Array.from(raw.subarray(y * (W * 3 + 1) + 1, (y + 1) * (W * 3 + 1))), Array.from(rgbPx.subarray(y * W * 3, (y + 1) * W * 3))); }
  assert.ok(C.previewUri(px).startsWith('data:image/png;base64,'));
});
test('white and black stay exactly white and black after colour reduction', () => {
  const p = gradient(); p[0] = 0xffff; p[1] = 0; const d = C.decodeImage(C.encodeImage(p).bytes).pixels; assert.strictEqual(d[0], 0xffff); assert.strictEqual(d[1], 0);
});
test('transfer error messages are readable', () => { for (let c = 1; c <= 8; c++) assert.ok(C.transferReasonText(c).length > 10); assert.ok(/SPIFFS/.test(C.transferReasonText(3))); assert.strictEqual(C.sizeLabel(2048), '2.0 KB'); assert.ok(C.transferEstimateSeconds(65536) >= 10); });

test('write cross-check vectors for the firmware decoder (tests/mimg_vectors.txt)', () => {
  const lines = []; const hex = (b) => Buffer.from(b).toString('hex');
  const add = (name, px) => { const e = C.encodeImage(px), d = C.decodeImage(e.bytes); if (!e.reduced) assert.ok(same(d.pixels, px), name); lines.push(`${name}|${hex(e.bytes)}|${C.pixelsCrc(d.pixels).toString(16)}|${d.w}x${d.h}`); };
  seed = 777; add('text', textLike()); add('qr', qrLike()); add('solid', blank(rgb(124, 77, 255))); add('gradient_reduced', gradient()); add('black', blank(0));
  const stripes = blank(0); for (let y = 0; y < H; y += 2) fillRect(stripes, 0, y, W, 1, 0xffff); add('stripes', stripes);   // long literal-free runs of 1 row
  const checker = blank(0); for (let y = 0; y < 150; y++) for (let x = 0; x < W; x++) checker[y * W + x] = (x + y) & 1 ? 0xffff : 0; add('checker', checker);    // literal-heavy (a full-screen checkerboard would be refused as too detailed: see the noise test)
  fs.writeFileSync(path.join(__dirname, 'mimg_vectors.txt'), lines.join('\n') + '\n'); assert.ok(lines.length >= 5);
});

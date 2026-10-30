// Custom HTML screen: everything that can be done without Android. Pure TypeScript so it is unit-tested with Node (tests/customscreen.test.js).
//   native (Kotlin)  : pick the .html file, render it in an isolated WebView, hand over the 240x320 bitmap as RGB565
//   this module      : check the file, reduce the bitmap to <= 256 colours, run-length encode it (MIMG, see firmware/MeowIsland/mimg.h),
//                      decode it back for the preview (so the preview shows exactly what the display will show), PNG for <Image>
//   native (Kotlin)  : store the encoded file and send it to the device in CRC-checked chunks
// The byte format is defined once, in firmware/MeowIsland/mimg.h; tests/mimg_vectors.txt is produced here and decoded by the firmware tests.

export const DEVICE_W = 240;                 // TFT is used in portrait (TFT_ROTATION 0 in firmware/MeowIsland/config.h)
export const DEVICE_H = 320;
export const MAX_HTML_BYTES = 256 * 1024;
export const MAX_IMAGE_BYTES = 65536;        // MIMG_MAX_FILE in mimg.h
export const HEADER_BYTES = 16;

export type Bytes = Uint8Array | number[];

// ---------------------------------------------------------------------------------------------------------------- CRC-32 / base64
let CRC_TABLE: Int32Array | null = null;
export function crc32(data: Bytes): number {
  if (!CRC_TABLE) {
    CRC_TABLE = new Int32Array(256);
    for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? (c >>> 1) ^ 0xedb88320 : c >>> 1; CRC_TABLE[n] = c; }
  }
  let c = -1;
  for (let i = 0; i < data.length; i++) c = CRC_TABLE[(c ^ data[i]) & 255] ^ (c >>> 8);
  return (c ^ -1) >>> 0;
}
const B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
export function b64encode(b: Bytes): string {
  const parts: string[] = []; let chunk = '';
  for (let i = 0; i < b.length; i += 3) {
    const n = (b[i] << 16) | ((i + 1 < b.length ? b[i + 1] : 0) << 8) | (i + 2 < b.length ? b[i + 2] : 0);
    chunk += B64[(n >> 18) & 63] + B64[(n >> 12) & 63] + (i + 1 < b.length ? B64[(n >> 6) & 63] : '=') + (i + 2 < b.length ? B64[n & 63] : '=');
    if (chunk.length >= 4096) { parts.push(chunk); chunk = ''; }
  }
  parts.push(chunk); return parts.join('');
}
export function b64decode(s: string): Uint8Array {
  const clean = s.replace(/[^A-Za-z0-9+/]/g, '');
  const out = new Uint8Array(Math.floor((clean.length * 3) / 4));
  const lut = new Int8Array(128).fill(-1); for (let i = 0; i < 64; i++) lut[B64.charCodeAt(i)] = i;
  let o = 0;
  for (let i = 0; i < clean.length; i += 4) {
    const a = lut[clean.charCodeAt(i)], b = lut[clean.charCodeAt(i + 1)], c = i + 2 < clean.length ? lut[clean.charCodeAt(i + 2)] : -1, d = i + 3 < clean.length ? lut[clean.charCodeAt(i + 3)] : -1;
    out[o++] = (a << 2) | (b >> 4); if (c >= 0) out[o++] = ((b & 15) << 4) | (c >> 2); if (d >= 0) out[o++] = ((c & 3) << 6) | d;
  }
  return out.subarray(0, o);
}

// ---------------------------------------------------------------------------------------------------------------- the uploaded file
/** A readable reason why this file cannot be used, or null when it is fine. Checked before anything is rendered. */
export function htmlProblem(name: string, sizeBytes: number, text: string): string | null {
  if (!/\.html?$/i.test(name || '')) return 'Please choose an .html or .htm file.';
  if (!(sizeBytes > 0)) return 'That file is empty.';
  if (sizeBytes > MAX_HTML_BYTES) return `That file is ${Math.round(sizeBytes / 1024)} KB. The limit is ${MAX_HTML_BYTES / 1024} KB. Simplify the design or shrink embedded images.`;
  if (text.indexOf('\u0000') >= 0) return 'That file looks like a binary file, not HTML.';
  if (text.indexOf('<') < 0) return 'That file does not contain any HTML tags.';
  return null;
}

// ---------------------------------------------------------------------------------------------------------------- pixels
/** RGB565, little-endian byte pairs (what the native renderer returns) -> Uint16Array */
export function rgb565FromBytes(b: Bytes): Uint16Array {
  const out = new Uint16Array(b.length >> 1);
  for (let i = 0; i < out.length; i++) out[i] = b[2 * i] | (b[2 * i + 1] << 8);
  return out;
}
export function rgb565ToBytes(p: Uint16Array): Uint8Array {
  const out = new Uint8Array(p.length * 2);
  for (let i = 0; i < p.length; i++) { out[2 * i] = p[i] & 255; out[2 * i + 1] = p[i] >> 8; }
  return out;
}
export function pixelsCrc(p: Uint16Array): number { return crc32(rgb565ToBytes(p)); }

// ---------------------------------------------------------------------------------------------------------------- quantise + encode
const LEVELS: [number, number, number][] = [[5, 6, 5], [4, 5, 4], [4, 4, 4], [3, 4, 3], [3, 3, 3], [2, 3, 2], [2, 2, 2]];
function reduceColor(c: number, rb: number, gb: number, bb: number): number {
  const r = c >> 11, g = (c >> 5) & 63, b = c & 31;
  const r2 = Math.round(((r >> (5 - rb)) * 31) / ((1 << rb) - 1)), g2 = Math.round(((g >> (6 - gb)) * 63) / ((1 << gb) - 1)), b2 = Math.round(((b >> (5 - bb)) * 31) / ((1 << bb) - 1));
  return (r2 << 11) | (g2 << 5) | b2;
}
export interface Encoded { bytes: Uint8Array; colors: number; reduced: boolean; crc: number }
export class EncodeError extends Error { code: string; constructor(code: string, msg: string) { super(msg); this.code = code; } }

/**
 * Reduces to <= 256 colours (exact when the design already has that few, otherwise by lowering the colour depth step by step: no dithering,
 * flat designs, text and QR codes are unaffected) and run-length encodes. Throws EncodeError('TOO_COMPLEX') when the result would not fit the device.
 */
export function encodeImage(pixels: Uint16Array, w = DEVICE_W, h = DEVICE_H): Encoded {
  if (pixels.length !== w * h || w < 1 || h < 1 || w > 320 || h > 320) throw new EncodeError('BAD_SIZE', 'The rendered picture has the wrong size.');
  const seen = new Uint8Array(65536); let unique = 0;
  for (let i = 0; i < pixels.length; i++) { const c = pixels[i]; if (!seen[c]) { seen[c] = 1; unique++; } }
  let q: Uint16Array = pixels, reduced = false;
  if (unique > 256) {
    reduced = true;
    for (const [rb, gb, bb] of LEVELS) {
      const map = new Uint16Array(65536), mark = new Uint8Array(65536); let n = 0;
      for (let c = 0; c < 65536; c++) if (seen[c]) { const r = reduceColor(c, rb, gb, bb); map[c] = r; if (!mark[r]) { mark[r] = 1; n++; } }
      q = new Uint16Array(pixels.length); for (let i = 0; i < pixels.length; i++) q[i] = map[pixels[i]];
      if (n <= 256) break;
    }
  }
  const idxOf = new Int16Array(65536).fill(-1), palette: number[] = [], idx = new Uint8Array(q.length);
  for (let i = 0; i < q.length; i++) { const c = q[i]; let k = idxOf[c]; if (k < 0) { k = palette.length; idxOf[c] = k; palette.push(c); } idx[i] = k; }
  const data: number[] = []; const lit: number[] = [];
  const flush = () => { for (let p = 0; p < lit.length; p += 128) { const n = Math.min(128, lit.length - p); data.push(0x80 | (n - 1)); for (let k = 0; k < n; k++) data.push(lit[p + k]); } lit.length = 0; };
  for (let i = 0; i < idx.length;) {
    let r = 1; while (i + r < idx.length && idx[i + r] === idx[i] && r < 65535) r++;
    if (r >= 3) { flush(); if (r > 127) data.push(0, r & 255, r >> 8, idx[i]); else data.push(r, idx[i]); i += r; }
    else { lit.push(idx[i]); i++; }
  }
  flush();
  const total = HEADER_BYTES + 2 * palette.length + data.length;
  if (total > MAX_IMAGE_BYTES) throw new EncodeError('TOO_COMPLEX', `This design is too detailed for the display (${Math.round(total / 1024)} KB after compression, the limit is ${MAX_IMAGE_BYTES / 1024} KB). Use flat colours, fewer gradients and no photos.`);
  const out = new Uint8Array(total);
  out.set([0x4d, 0x49, 1, 0, w & 255, w >> 8, h & 255, h >> 8, palette.length & 255, palette.length >> 8, 0, 0, data.length & 255, (data.length >> 8) & 255, (data.length >> 16) & 255, (data.length >>> 24) & 255], 0);
  for (let i = 0; i < palette.length; i++) { out[HEADER_BYTES + 2 * i] = palette[i] & 255; out[HEADER_BYTES + 2 * i + 1] = palette[i] >> 8; }
  out.set(data, HEADER_BYTES + 2 * palette.length);
  return { bytes: out, colors: palette.length, reduced, crc: crc32(out) };
}

/** Mirror of the firmware validator + decoder (mimg.h). Throws on anything the device would reject. */
export function decodeImage(b: Bytes): { w: number; h: number; pixels: Uint16Array } {
  const bad = (m: string) => { throw new EncodeError('BAD_IMAGE', m); };
  if (b.length < HEADER_BYTES + 3 || b.length > MAX_IMAGE_BYTES) bad('wrong length');
  if (b[0] !== 0x4d || b[1] !== 0x49 || b[2] !== 1 || b[3] !== 0) bad('bad header');
  const w = b[4] | (b[5] << 8), h = b[6] | (b[7] << 8), colors = b[8] | (b[9] << 8), dataLen = (b[12] | (b[13] << 8) | (b[14] << 16) | (b[15] << 24)) >>> 0;
  if ((b[10] | (b[11] << 8)) !== 0 || w < 1 || h < 1 || w > 320 || h > 320 || colors < 1 || colors > 256 || dataLen < 2) bad('bad header fields');
  if (HEADER_BYTES + 2 * colors + dataLen !== b.length) bad('length does not match header');
  const pal = new Uint16Array(colors); for (let i = 0; i < colors; i++) pal[i] = b[HEADER_BYTES + 2 * i] | (b[HEADER_BYTES + 2 * i + 1] << 8);
  const px = new Uint16Array(w * h); let at = HEADER_BYTES + 2 * colors; const end = b.length; let o = 0;
  const put = (idx: number, n: number) => { if (idx >= colors) bad('palette index out of range'); if (o + n > px.length) bad('too many pixels'); px.fill(pal[idx], o, o + n); o += n; };
  while (at < end) {
    const c = b[at++];
    if (c === 0) { if (at + 3 > end) bad('truncated'); const n = b[at] | (b[at + 1] << 8); if (n === 0) bad('empty run'); put(b[at + 2], n); at += 3; }
    else if (c < 0x80) { if (at + 1 > end) bad('truncated'); put(b[at++], c); }
    else { const n = (c & 0x7f) + 1; if (at + n > end) bad('truncated'); for (let k = 0; k < n; k++) put(b[at++], 1); }
  }
  if (o !== px.length) bad('too few pixels');
  return { w, h, pixels: px };
}

// ---------------------------------------------------------------------------------------------------------------- preview picture
export function rgb565ToRgb(p: Uint16Array): Uint8Array {
  const out = new Uint8Array(p.length * 3);
  for (let i = 0; i < p.length; i++) { const c = p[i], r = c >> 11, g = (c >> 5) & 63, b = c & 31; out[3 * i] = (r << 3) | (r >> 2); out[3 * i + 1] = (g << 2) | (g >> 4); out[3 * i + 2] = (b << 3) | (b >> 2); }
  return out;
}
/** Minimal PNG (stored deflate blocks, no compression): enough for <Image source={{ uri }}> without any native image library. */
export function pngFromRgb(rgb: Uint8Array, w: number, h: number): Uint8Array {
  const raw = new Uint8Array((w * 3 + 1) * h);
  for (let y = 0; y < h; y++) { raw[y * (w * 3 + 1)] = 0; raw.set(rgb.subarray(y * w * 3, (y + 1) * w * 3), y * (w * 3 + 1) + 1); }
  let a = 1, bsum = 0; for (let i = 0; i < raw.length; i++) { a = (a + raw[i]) % 65521; bsum = (bsum + a) % 65521; }
  const blocks = Math.max(1, Math.ceil(raw.length / 65535)), z = new Uint8Array(2 + raw.length + blocks * 5 + 4);
  let o = 0; z[o++] = 0x78; z[o++] = 0x01;
  for (let p = 0; p < raw.length || p === 0; p += 65535) { const n = Math.min(65535, raw.length - p), fin = p + n >= raw.length ? 1 : 0; z[o++] = fin; z[o++] = n & 255; z[o++] = n >> 8; z[o++] = ~n & 255; z[o++] = (~n >> 8) & 255; z.set(raw.subarray(p, p + n), o); o += n; if (fin) break; }
  z[o++] = (bsum >> 8) & 255; z[o++] = bsum & 255; z[o++] = (a >> 8) & 255; z[o++] = a & 255;
  const chunk = (type: string, data: Uint8Array): Uint8Array => {
    const c = new Uint8Array(12 + data.length), t = [type.charCodeAt(0), type.charCodeAt(1), type.charCodeAt(2), type.charCodeAt(3)];
    c[0] = (data.length >>> 24) & 255; c[1] = (data.length >> 16) & 255; c[2] = (data.length >> 8) & 255; c[3] = data.length & 255; c.set(t, 4); c.set(data, 8);
    const crc = crc32(c.subarray(4, 8 + data.length)); c[8 + data.length] = crc >>> 24; c[9 + data.length] = (crc >> 16) & 255; c[10 + data.length] = (crc >> 8) & 255; c[11 + data.length] = crc & 255; return c;
  };
  const ihdr = new Uint8Array([w >>> 24, (w >> 16) & 255, (w >> 8) & 255, w & 255, h >>> 24, (h >> 16) & 255, (h >> 8) & 255, h & 255, 8, 2, 0, 0, 0]);
  const parts = [new Uint8Array([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ihdr), chunk('IDAT', z.subarray(0, o)), chunk('IEND', new Uint8Array(0))];
  const out = new Uint8Array(parts.reduce((s, p) => s + p.length, 0)); let at = 0; for (const p of parts) { out.set(p, at); at += p.length; }
  return out;
}
export function previewUri(pixels: Uint16Array, w = DEVICE_W, h = DEVICE_H): string { return 'data:image/png;base64,' + b64encode(pngFromRgb(rgb565ToRgb(pixels), w, h)); }

// ---------------------------------------------------------------------------------------------------------------- messages
/** Reason codes reported by the device (protocol.h XferReason) and by the native sender. */
export function transferReasonText(code: number): string {
  switch (code) {
    case 1: return 'The device did not accept the picture header. Update the device firmware.';
    case 2: return 'The design is too large for the device.';
    case 3: return 'The device is out of storage space. Choose the “Minimal SPIFFS” or “Default” partition scheme when flashing (see the firmware README).';
    case 4: return 'The device could not write to its flash memory.';
    case 5: return 'The data arrived damaged (checksum mismatch). Try again, closer to the device.';
    case 6: return 'The device stopped responding during the transfer.';
    case 7: return 'The device rejected the picture as invalid.';
    case 8: return 'The device is low on memory.';
    default: return 'The transfer failed.';
  }
}
/** Rough upload time over Bluetooth LE (about 4 KB/s is a safe planning number). */
export function transferEstimateSeconds(bytes: number): number { return Math.max(1, Math.round(bytes / 4096)); }
export function sizeLabel(bytes: number): string { return bytes < 1024 ? `${bytes} B` : `${(bytes / 1024).toFixed(1)} KB`; }

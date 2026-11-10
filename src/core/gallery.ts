// Photo gallery logic that needs no Android: labels, setting limits, storage estimates, and the digest that tells the phone whether the display holds exactly
// its photos. Unit-tested with Node (tests/gallery.test.js); the Kotlin side (plugins/kotlin/Gallery.kt) does the picture work.
//
//   phone  : picks pictures, makes them upright, crops/fits them to 240 x 320 and compresses them heavily to baseline JPEG (a few KB to ~30 KB each)
//   link   : the existing Bluetooth transfer (CRC-checked, resumable), one photo at a time, only what the display does not have yet
//   display: keeps the JPEG files in flash (up to 24), decodes one at a time to the full screen and moves on after the chosen number of seconds

export const MAX_PHOTOS = 24;                       // PHOTO_SLOTS in firmware protocol.h
export const MIN_INTERVAL_SEC = 2, MAX_INTERVAL_SEC = 3600, DEFAULT_INTERVAL_SEC = 10;
export const INTERVALS: { sec: number; label: string }[] = [
  { sec: 3, label: '3 s' }, { sec: 5, label: '5 s' }, { sec: 10, label: '10 s' }, { sec: 30, label: '30 s' },
  { sec: 60, label: '1 min' }, { sec: 300, label: '5 min' }, { sec: 900, label: '15 min' }, { sec: 3600, label: '1 hour' },
];
export type Fit = 'fill' | 'fit';
export type Quality = 'small' | 'balanced' | 'best';
export const FITS: { id: Fit; label: string; hint: string }[] = [
  { id: 'fill', label: 'Fill screen', hint: 'Crops the centre so the picture covers the whole display.' },
  { id: 'fit', label: 'Whole picture', hint: 'Shows the whole picture with black bars where it does not fill the screen.' },
];
export const QUALITIES: { id: Quality; label: string; kb: number; hint: string }[] = [
  { id: 'small', label: 'Smaller', kb: 12, hint: 'About 12 KB per photo: the most photos and the fastest transfer.' },
  { id: 'balanced', label: 'Balanced', kb: 20, hint: 'About 20 KB per photo.' },
  { id: 'best', label: 'Best', kb: 32, hint: 'Up to 32 KB per photo: the sharpest, slower to send.' },
];
export type GallerySettings = { intervalSec: number; fit: Fit; quality: Quality };
export const DEFAULT_SETTINGS: GallerySettings = { intervalSec: DEFAULT_INTERVAL_SEC, fit: 'fill', quality: 'balanced' };

export function clampInterval(sec: any): number {
  const n = Math.round(Number(sec)); return isFinite(n) ? Math.min(MAX_INTERVAL_SEC, Math.max(MIN_INTERVAL_SEC, n)) : DEFAULT_INTERVAL_SEC;
}
export function normalizeSettings(s: any): GallerySettings {
  const o: any = s && typeof s === 'object' ? s : {};
  return { intervalSec: o.intervalSec === undefined ? DEFAULT_INTERVAL_SEC : clampInterval(o.intervalSec), fit: o.fit === 'fit' ? 'fit' : 'fill', quality: o.quality === 'small' || o.quality === 'best' ? o.quality : 'balanced' };
}
export function intervalLabel(sec: number): string {
  const s = clampInterval(sec);
  if (s < 60) return `${s} s`;
  if (s < 3600) return s % 60 === 0 ? `${s / 60} min` : `${Math.floor(s / 60)} min ${s % 60} s`;
  return s % 3600 === 0 ? `${s / 3600} h` : `${Math.floor(s / 3600)} h ${Math.round((s % 3600) / 60)} min`;
}
export function sizeLabel(bytes: number): string { return bytes < 1024 ? `${bytes} B` : bytes < 1048576 ? `${(bytes / 1024).toFixed(bytes < 10240 ? 1 : 0)} KB` : `${(bytes / 1048576).toFixed(1)} MB`; }
/** "6 photos, 18 s for the whole loop" */
export function loopLabel(count: number, sec: number): string {
  if (count <= 0) return 'No photos yet';
  if (count === 1) return '1 photo';
  return `${count} photos, ${intervalLabel(count * clampInterval(sec))} for the whole loop`;
}
/** Seconds to send `bytes` over Bluetooth (planning figure: about 4 KB per second). */
export function sendSeconds(bytes: number): number { return Math.max(1, Math.round(bytes / 4096)); }

export type DeviceStorage = { hasGallery: boolean; freeKb: number; totalKb: number };
/** How many more photos of the average size fit on the display (null when unknown). */
export function roomFor(st: DeviceStorage | null | undefined, avgBytes: number): number | null {
  if (!st || !st.hasGallery || st.totalKb <= 0 || avgBytes <= 0) return null;
  return Math.max(0, Math.floor((st.freeKb * 1024 - 16384) / (avgBytes + 1024)));
}
export function storageLine(st: DeviceStorage | null | undefined, avgBytes: number): string {
  if (!st || !st.hasGallery || st.totalKb <= 0) return '';
  const room = roomFor(st, avgBytes || 20 * 1024);
  return `Display storage: ${st.freeKb} of ${st.totalKb} KB free` + (room !== null ? ` (room for about ${room} more photos)` : '');
}

// ---- digest: identical to firmware crc32.h photoDigest and Kotlin DeviceProtocol.photoDigest -------------------------------------------------------------
let table: number[] | null = null;
export function crc32(bytes: ArrayLike<number>): number {
  if (!table) { table = []; for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; table[n] = c >>> 0; } }
  let c = 0xffffffff; for (let i = 0; i < bytes.length; i++) c = table[(c ^ bytes[i]) & 255] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0;
}
/** CRC-32 over (slot u8, file CRC-32 u32 little-endian) of every photo in slot order; 0 when there is none. */
export function photoDigest(photos: { slot: number; crc: number }[]): number {
  const bytes: number[] = [];
  for (const p of photos.slice().sort((a, b) => a.slot - b.slot)) { if (!p.crc) continue; const c = p.crc >>> 0; bytes.push(p.slot & 255, c & 255, (c >>> 8) & 255, (c >>> 16) & 255, (c >>> 24) & 255); }
  return bytes.length ? crc32(bytes) : 0;
}

// ---- library as the native side reports it ---------------------------------------------------------------------------------------------------------------
export type PhotoEntry = { id: string; slot: number; size: number; uri: string; onDevice: boolean; sending: boolean; queued: boolean; shown: boolean };
export type Library = {
  photos: PhotoEntry[]; settings: GallerySettings; auto: boolean; connected: boolean; max: number; totalBytes: number;
  device: { hasGallery: boolean; count: number; freeKb: number; totalKb: number; intervalSec: number; fw: string; deviceOnly: number[]; inSync: boolean };
  transfer: { state: 'idle' | 'sending' | 'done' | 'error'; id: string; sent: number; total: number; message: string; queued: number };
};
export type AddResult = { added: number; skipped: { name: string; reason: string }[]; library: Library };
/** One readable line about a finished "add photos" run. */
export function addSummary(r: { added: number; skipped: { name: string; reason: string }[] }): string {
  const parts: string[] = [];
  if (r.added > 0) parts.push(`${r.added} photo${r.added === 1 ? '' : 's'} added.`);
  if (r.skipped.length > 0) parts.push(`${r.skipped.length} skipped: ` + r.skipped.slice(0, 3).map((s) => `${s.name} (${s.reason})`).join('; ') + (r.skipped.length > 3 ? '…' : ''));
  return parts.join(' ') || 'Nothing was added.';
}
/** What the display shows for a photo, for the small badge on its thumbnail. */
export function photoBadge(p: PhotoEntry, transfer: Library['transfer']): { text: string; ok: boolean } {
  if (p.sending) { const pct = transfer.total > 0 ? Math.min(100, Math.round((transfer.sent / transfer.total) * 100)) : 0; return { text: `Sending ${pct}%`, ok: false }; }
  if (p.queued) return { text: 'Waiting', ok: false };
  if (p.shown) return { text: 'Showing', ok: true };
  return p.onDevice ? { text: 'On display', ok: true } : { text: 'Not sent', ok: false };
}

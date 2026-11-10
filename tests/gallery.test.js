const G = src('core/gallery.ts');
const A = (a) => Array.from(a);

test('interval limits, labels and defaults', () => {
  assert.strictEqual(G.clampInterval(1), 2); assert.strictEqual(G.clampInterval(99999), 3600); assert.strictEqual(G.clampInterval('x'), 10); assert.strictEqual(G.clampInterval(7.6), 8);
  assert.deepStrictEqual(A(G.INTERVALS.map((i) => i.sec)), [3, 5, 10, 30, 60, 300, 900, 3600]);
  assert.strictEqual(G.intervalLabel(3), '3 s'); assert.strictEqual(G.intervalLabel(60), '1 min'); assert.strictEqual(G.intervalLabel(90), '1 min 30 s'); assert.strictEqual(G.intervalLabel(3600), '1 h'); assert.strictEqual(G.intervalLabel(3000), '50 min'); assert.strictEqual(G.intervalLabel(5400), '1 h', 'longer than the maximum is shown as the maximum');
  for (const i of G.INTERVALS) assert.ok(i.sec >= G.MIN_INTERVAL_SEC && i.sec <= G.MAX_INTERVAL_SEC);
});
test('settings are normalised', () => {
  assert.deepStrictEqual(JSON.parse(JSON.stringify(G.normalizeSettings(undefined))), { intervalSec: 10, fit: 'fill', quality: 'balanced' });
  assert.deepStrictEqual(JSON.parse(JSON.stringify(G.normalizeSettings({ intervalSec: 1, fit: 'fit', quality: 'best' }))), { intervalSec: 2, fit: 'fit', quality: 'best' });
  assert.deepStrictEqual(JSON.parse(JSON.stringify(G.normalizeSettings({ fit: 'weird', quality: 'huge', intervalSec: 'soon' }))), { intervalSec: 10, fit: 'fill', quality: 'balanced' });
  assert.strictEqual(G.QUALITIES.length, 3); assert.ok(G.QUALITIES[0].kb < G.QUALITIES[1].kb && G.QUALITIES[1].kb < G.QUALITIES[2].kb); assert.ok(G.QUALITIES[2].kb * 1024 <= 49152, 'every quality target fits the display file limit');
});
test('size, loop and send-time labels', () => {
  assert.strictEqual(G.sizeLabel(500), '500 B'); assert.strictEqual(G.sizeLabel(2048), '2.0 KB'); assert.strictEqual(G.sizeLabel(20480), '20 KB'); assert.strictEqual(G.sizeLabel(2 * 1048576), '2.0 MB');
  assert.strictEqual(G.loopLabel(0, 5), 'No photos yet'); assert.strictEqual(G.loopLabel(1, 5), '1 photo'); assert.strictEqual(G.loopLabel(6, 3), '6 photos, 18 s for the whole loop'); assert.strictEqual(G.loopLabel(12, 300), '12 photos, 1 h for the whole loop');
  assert.strictEqual(G.sendSeconds(100), 1); assert.strictEqual(G.sendSeconds(20480), 5);
});
test('storage room estimate', () => {
  assert.strictEqual(G.roomFor(null, 20000), null); assert.strictEqual(G.roomFor({ hasGallery: false, freeKb: 1000, totalKb: 1900 }, 20000), null);
  assert.strictEqual(G.roomFor({ hasGallery: true, freeKb: 1900, totalKb: 1900 }, 20000), Math.floor((1900 * 1024 - 16384) / 21024));
  assert.strictEqual(G.roomFor({ hasGallery: true, freeKb: 10, totalKb: 190 }, 20000), 0);
  assert.ok(/room for about \d+ more photos/.test(G.storageLine({ hasGallery: true, freeKb: 1800, totalKb: 1900 }, 20000))); assert.strictEqual(G.storageLine(null, 1), '');
});
test('crc32 and the photo digest match the firmware / Kotlin definition', () => {
  assert.strictEqual(G.crc32(Buffer.from('123456789')), 0xcbf43926);
  assert.strictEqual(G.photoDigest([]), 0); assert.strictEqual(G.photoDigest([{ slot: 0, crc: 0 }]), 0);
  assert.strictEqual(G.photoDigest([{ slot: 3, crc: 0xdeadbeef }, { slot: 0, crc: 0x01020304 }]), 0x109439c6, 'same constant as firmware photo_store test and Kotlin test');
  assert.strictEqual(G.photoDigest([{ slot: 3, crc: 0xdeadbeef }, { slot: 0, crc: 0x01020304 }]), G.photoDigest([{ slot: 0, crc: 0x01020304 }, { slot: 3, crc: 0xdeadbeef }]), 'order of the list does not matter');
});
test('add summary and badges', () => {
  assert.strictEqual(G.addSummary({ added: 1, skipped: [] }), '1 photo added.'); assert.strictEqual(G.addSummary({ added: 3, skipped: [] }), '3 photos added.'); assert.strictEqual(G.addSummary({ added: 0, skipped: [] }), 'Nothing was added.');
  assert.ok(/2 skipped: a.jpg \(bad\); b.jpg \(worse\)/.test(G.addSummary({ added: 2, skipped: [{ name: 'a.jpg', reason: 'bad' }, { name: 'b.jpg', reason: 'worse' }] })));
  const t = { state: 'sending', id: 'x', sent: 500, total: 2000, message: '', queued: 0 }, p = { id: 'x', slot: 0, size: 1, uri: '', onDevice: false, sending: true, queued: false, shown: false };
  assert.strictEqual(G.photoBadge(p, t).text, 'Sending 25%'); assert.strictEqual(G.photoBadge({ ...p, sending: false, queued: true }, t).text, 'Waiting');
  assert.strictEqual(G.photoBadge({ ...p, sending: false, onDevice: true }, t).text, 'On display'); assert.strictEqual(G.photoBadge({ ...p, sending: false, onDevice: true, shown: true }, t).text, 'Showing'); assert.strictEqual(G.photoBadge({ ...p, sending: false }, t).ok, false);
});

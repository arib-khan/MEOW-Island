const W = src('core/wellbeing.ts');
const eq = (a, b) => assert.strictEqual(JSON.stringify(a), JSON.stringify(b));   // the transpiler sandbox has its own Array prototype, so compare as JSON

test('defaults match the spec milestone table and are configurable', () => {
  const c = W.mergeShorts(undefined);
  eq(c.counter.tiers.map((t) => [t.min, t.emoji]), [[1, '😺'], [10, '😼'], [25, '😿'], [50, '🙀'], [100, '😾']]);
  assert.strictEqual(c.block.ig, false); assert.strictEqual(c.block.yt, false); assert.strictEqual(c.block.strict, false);
  const mine = W.mergeShorts({ counter: { tiers: [{ min: 5, emoji: 'x', text: 'five' }, { min: 3, emoji: 'y', text: 'three' }] } });
  eq(mine.counter.tiers.map((t) => t.min), [3, 5]);
});
test('milestone boundaries', () => {
  const t = W.DEFAULT_TIERS;
  assert.strictEqual(W.pickTier(0, t), null);
  for (const [n, e] of [[1, '😺'], [9, '😺'], [10, '😼'], [24, '😼'], [25, '😿'], [49, '😿'], [50, '🙀'], [99, '🙀'], [100, '😾'], [4000, '😾']]) assert.strictEqual(W.pickTier(n, t).emoji, e, 'count ' + n);
});
test('settings persistence: JSON round trip is stable', () => {
  const c = W.mergeShorts({ counter: { mode: 'total', y: 80, scale: 1.3, text: 'always' }, block: { ig: true, yt: true, strict: true, pauseMin: 15 }, goal: { daily: 60, remindEvery: 5 } });
  eq(W.mergeShorts(JSON.parse(JSON.stringify(c))), c);
  assert.strictEqual(c.block.strict, true); assert.strictEqual(c.goal.daily, 60);
});
test('hostile / corrupt stored data is clamped, not trusted', () => {
  const c = W.mergeShorts({ counter: { fx: 9, y: -4, scale: 99, opacity: 0, bg: 'red', mode: 'x', text: 7, tiers: 'nope' }, block: { ig: 'yes', pauseMin: 1e9 }, goal: { daily: -1, remindEvery: NaN } });
  assert.strictEqual(c.counter.fx, 1); assert.strictEqual(c.counter.y, 0); assert.strictEqual(c.counter.scale, 1.6); assert.strictEqual(c.counter.opacity, 0.3);
  assert.strictEqual(c.counter.bg, '#000000'); assert.strictEqual(c.counter.mode, 'app'); assert.strictEqual(c.counter.text, 'milestone');
  assert.strictEqual(c.counter.tiers.length, 5); assert.strictEqual(c.block.ig, false); assert.strictEqual(c.block.pauseMin, 60);
  assert.strictEqual(c.goal.daily, 0); assert.strictEqual(c.goal.remindEvery, 10);
  assert.doesNotThrow(() => W.mergeShorts(null)); assert.doesNotThrow(() => W.mergeShorts('x')); assert.doesNotThrow(() => W.mergeShorts([]));
});
test('tiers: duplicates and bad thresholds dropped, strings length-limited', () => {
  const t = W.sanitizeTiers([{ min: 5, emoji: 'a', text: 'x' }, { min: 5, emoji: 'b', text: 'dup' }, { min: 0, emoji: 'z', text: 'zero' }, { min: -3 }, null, { min: 7, emoji: '', text: 'y'.repeat(100) }]);
  eq(t.map((x) => x.min), [5, 7]); assert.strictEqual(t[1].emoji, '🐱'); assert.strictEqual(t[1].text.length, 40);
  assert.strictEqual(W.sanitizeTiers([]).length, 5);
});
test('history: missing days are zeros, order is oldest first, across a month boundary', () => {
  const now = new Date(2026, 9, 2, 15, 0).getTime();            // 2 Oct 2026
  const rows = W.lastDays({ '2026-10-02': { ig: 4, yt: 6 }, '2026-09-30': { yt: 3, ytA: 2 } }, 4, now);
  eq(rows.map((r) => r.day), ['2026-09-29', '2026-09-30', '2026-10-01', '2026-10-02']);
  eq(rows.map((r) => r.total), [0, 3, 0, 10]);
  assert.strictEqual(rows[1].approx, true); assert.strictEqual(rows[3].approx, false);
  const s = W.summarize(rows); assert.strictEqual(s.sum, 13); assert.strictEqual(s.best, 10); assert.strictEqual(s.activeDays, 2);
});
test('history parsing survives garbage and DST-length days', () => {
  eq(W.parseDays('not json'), {}); eq(W.parseDays('[1]'), {}); eq(W.parseDays(null), {});
  const rows = W.lastDays({}, 400, new Date(2026, 10, 2, 0, 30).getTime());
  assert.strictEqual(new Set(rows.map((r) => r.day)).size, 400);
  assert.strictEqual(W.lastDays({ '2026-10-02': { ig: -5, yt: 'x' } }, 1, new Date(2026, 9, 2).getTime())[0].total, 0);
});
test('dayKey is the local calendar day', () => {
  assert.strictEqual(W.dayKey(new Date(2026, 0, 5, 23, 59).getTime()), '2026-01-05'); assert.strictEqual(W.dayKey(new Date(2026, 0, 6, 0, 0).getTime()), '2026-01-06');
});
test('goal states', () => {
  assert.strictEqual(W.goalState(50, 0), 'off'); assert.strictEqual(W.goalState(10, 50), 'under'); assert.strictEqual(W.goalState(40, 50), 'near');
  assert.strictEqual(W.goalState(50, 50), 'reached'); assert.strictEqual(W.goalState(51, 50), 'over');
});
test('adult filter: domain normalisation and validation', () => {
  assert.strictEqual(W.normalizeDomain('HTTPS://WWW.Example.com/path?q=1'), 'www.example.com'); assert.strictEqual(W.normalizeDomain('*.bad.example.'), 'bad.example');
  for (const bad of ['', 'localhost', '1.2.3.4', 'has space.com', 'a..b', '-x.com', 5, null, 'x'.repeat(300) + '.com']) assert.strictEqual(W.normalizeDomain(bad), null, String(bad));
  eq(W.cleanDomains(['a.com', 'A.COM', 'b.org', 'zzz']), ['a.com', 'b.org']);
});
test('adult filter settings: off by default, only https list sources, only real IPs', () => {
  const a = W.mergeAdult(undefined);
  assert.strictEqual(a.enabled, false); assert.strictEqual(a.upstream, 'system'); assert.strictEqual(a.blocklistUrl, W.DEFAULT_BLOCKLIST_URL);
  const b = W.mergeAdult({ enabled: true, upstream: 'custom', customDns: '9.9.9.9', blocklistUrl: 'http://evil.example/list', allow: ['ok.com'] });
  assert.strictEqual(b.enabled, true); assert.strictEqual(b.customDns, '9.9.9.9'); assert.strictEqual(b.blocklistUrl, W.DEFAULT_BLOCKLIST_URL); eq(b.allow, ['ok.com']);
  assert.strictEqual(W.mergeAdult({ customDns: 'rm -rf' }).customDns, ''); assert.strictEqual(W.mergeAdult({ customDns: '999.1.1.1' }).customDns, '');
  assert.strictEqual(W.mergeAdult({ customDns: '2606:4700::1111' }).customDns, '2606:4700::1111');
  eq(W.mergeAdult(JSON.parse(JSON.stringify(b))), b);
});

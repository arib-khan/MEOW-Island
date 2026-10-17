const H = src('core/health.ts');
const M = src('core/migrate.ts');

test('everything health-related is OFF by default', () => {
  const c = H.mergeHealth(undefined);
  assert.strictEqual(c.stepCounter, false); assert.strictEqual(c.walkingIsland, false); assert.strictEqual(c.goalEnabled, false);
  assert.strictEqual(c.heatmap, false); assert.strictEqual(c.water.enabled, false); assert.strictEqual(H.stepsNeeded(c), false);
});
test('settings persistence: JSON round trip keeps every value', () => {
  const c = H.mergeHealth({ stepCounter: true, goal: 9500, walkSensitivity: 'high', water: { enabled: true, intervalMin: 90, days: [true, false, true, false, true, false, true], toNotification: false } });
  const back = H.mergeHealth(JSON.parse(JSON.stringify(c)));
  assert.deepStrictEqual(back, c); assert.strictEqual(back.goal, 9500); assert.strictEqual(back.water.intervalMin, 90); assert.strictEqual(back.water.toNotification, false);
});
test('corrupt / hostile stored data is clamped, not trusted', () => {
  const c = H.mergeHealth({ goal: -5, walkReturnSec: 9999, walkSensitivity: 'x', water: { intervalMin: 0, startMin: 2000, endMin: -3, days: 'nope', snoozeMin: 1e9 } });
  assert.strictEqual(c.goal, 500); assert.strictEqual(c.walkReturnSec, 60); assert.strictEqual(c.walkSensitivity, 'normal');
  assert.strictEqual(c.water.intervalMin, 5); assert.ok(c.water.startMin <= c.water.endMin); assert.strictEqual(c.water.days.length, 7); assert.strictEqual(c.water.snoozeMin, 120);
});
test('step features are independent switches sharing one sensor', () => {
  assert.strictEqual(H.stepsNeeded(H.mergeHealth({ heatmap: true })), true);
  const c = H.mergeHealth({ heatmap: true, goalEnabled: true }); c.heatmap = false;
  assert.strictEqual(c.goalEnabled, true); assert.strictEqual(H.stepsNeeded(c), true);
});
test('goal completion and day status', () => {
  assert.strictEqual(H.dayStatus({ s: 8000, g: 8000, p: false }), 'complete');
  assert.strictEqual(H.dayStatus({ s: 9000, g: 8000, p: true }), 'complete');
  assert.strictEqual(H.dayStatus({ s: 100, g: 8000, p: false }), 'missed');
  assert.strictEqual(H.dayStatus({ s: 100, g: 8000, p: true }), 'unknown');
  assert.strictEqual(H.dayStatus(undefined), 'nodata');
});
test('levels: missing data is never a miss; goal reached is unmistakable', () => {
  assert.strictEqual(H.level(undefined), 0); assert.strictEqual(H.level({ s: 1, g: 8000, p: true }), 1);
  assert.strictEqual(H.level({ s: 3999, g: 8000, p: false }), 2); assert.strictEqual(H.level({ s: 4000, g: 8000, p: false }), 3);
  assert.strictEqual(H.level({ s: 7999, g: 8000, p: false }), 3); assert.strictEqual(H.level({ s: 8000, g: 8000, p: false }), 4);
});
test('each day keeps its own goal when the goal changes later', () => {
  const days = { '2026-10-15': { s: 8100, g: 8000, p: false }, '2026-10-16': { s: 8100, g: 10000, p: false } };
  assert.strictEqual(H.level(days['2026-10-15']), 4); assert.strictEqual(H.level(days['2026-10-16']), 3);
});
test('formatting', () => {
  assert.strictEqual(H.fmt(6420), '6,420'); assert.strictEqual(H.fmt(0), '0'); assert.strictEqual(H.fmt(1234567), '1,234,567'); assert.strictEqual(H.fmt(-3), '0');
  assert.strictEqual(H.pct(6420, 8000), 80); assert.strictEqual(H.pct(9999, 8000), 100); assert.strictEqual(H.pct(5, 0), 0); assert.strictEqual(H.hhmm(545), '09:05');
});
test('grid: today is in the last column on the right row, future cells are flagged', () => {
  const today = new Date(2026, 9, 16); // Friday
  const g = H.buildGrid(today, { '2026-10-16': { s: 8000, g: 8000, p: false } }, 5);
  assert.strictEqual(g.length, 5); assert.ok(g.every((c) => c.length === 7));
  const cell = g[4][4]; assert.strictEqual(cell.key, '2026-10-16'); assert.strictEqual(cell.level, 4); assert.strictEqual(cell.future, false);
  assert.strictEqual(g[4][5].future, true); assert.strictEqual(g[4][6].future, true); assert.strictEqual(g[4][3].key, '2026-10-15');
});
test('grid: no gaps or repeats across month and year boundaries and DST dates', () => {
  for (const t of [new Date(2026, 0, 3), new Date(2026, 2, 8), new Date(2026, 2, 9), new Date(2026, 10, 1), new Date(2026, 9, 25)]) {
    const flat = [].concat(...H.buildGrid(t, {}, 8)).filter((c) => !c.future);
    const keys = flat.map((c) => c.key);
    assert.strictEqual(new Set(keys).size, keys.length, 'repeat near ' + H.ymd(t));
    for (let i = 1; i < flat.length; i++) { const d = new Date(flat[i - 1].date.getFullYear(), flat[i - 1].date.getMonth(), flat[i - 1].date.getDate() + 1); assert.strictEqual(H.ymd(d), flat[i].key, 'gap near ' + H.ymd(t)); }
    assert.strictEqual(keys[keys.length - 1], H.ymd(t));
  }
});
test('grid: untracked days stay level 0 (never fabricated)', () => {
  const g = H.buildGrid(new Date(2026, 9, 16), {}, 5);
  assert.ok([].concat(...g).filter((c) => !c.future).every((c) => c.level === 0));
});
test('config migration: new default position, drag setting removed, runs once', () => {
  const m = M.migrateConfig({ posArea: 'usable', dragEnabled: true, fx: 0.3, fy: 0.2, w: 150 });
  assert.strictEqual(m.posArea, 'statusbar'); assert.strictEqual(m.cfgVersion, 2); assert.strictEqual('dragEnabled' in m, false); assert.strictEqual(m.fx, 0.3); assert.strictEqual(m.w, 150);
  const again = M.migrateConfig({ ...m, posArea: 'screen' }); assert.strictEqual(again.posArea, 'screen');
  assert.strictEqual(M.migrateConfig(undefined).posArea, 'statusbar');
});

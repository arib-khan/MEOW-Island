const S = src('core/screentime.ts');
test('durations read like the device shows them', () => {
  assert.strictEqual(S.fmtDuration(222), '3h 42m'); assert.strictEqual(S.fmtDuration(42), '42m'); assert.strictEqual(S.fmtDuration(120), '2h 00m'); assert.strictEqual(S.fmtDuration(0), '0m'); assert.strictEqual(S.fmtDuration(-5), '0m');
});
test('goal rules: off, 15-minute steps, bounded', () => {
  assert.strictEqual(S.clampGoal(0), 0); assert.strictEqual(S.clampGoal(7), 15); assert.strictEqual(S.clampGoal(100), 105); assert.strictEqual(S.clampGoal(99999), 960); assert.strictEqual(S.clampGoal('x'), 0); assert.strictEqual(S.clampGoal(NaN), 0);
  assert.strictEqual(S.stepGoal(0, 1), 240); assert.strictEqual(S.stepGoal(0, -1), 0); assert.strictEqual(S.stepGoal(240, 1), 255); assert.strictEqual(S.stepGoal(15, -1), 0); assert.strictEqual(S.stepGoal(960, 1), 960);
});
test('progress text, including over the goal and no goal', () => {
  assert.strictEqual(S.goalText(120, 240), '50% of your 4h 00m goal'); assert.strictEqual(S.goalText(300, 240), 'Over your 4h 00m goal by 1h 00m'); assert.strictEqual(S.goalText(50, 0), 'No daily goal set');
  assert.strictEqual(S.goalPercent(60, 0), 0);
});
test('values from the bridge are validated', () => {
  const s = S.mergeScreenTime({ access: true, ok: true, totalMin: 99999, goalMin: 100, day: '2026-10-30T00:00', apps: [{ name: 'B', min: 5 }, { name: 'A', min: 50 }, { name: '', min: 3 }, null, { name: 'C', min: -4 }, 'x'] });
  assert.strictEqual(s.totalMin, 1440); assert.strictEqual(s.goalMin, 105); assert.strictEqual(s.day, '2026-10-30'); assert.deepStrictEqual(s.apps.map((a) => a.name), ['A', 'B', 'C']); assert.strictEqual(s.apps[2].min, 0);
  assert.doesNotThrow(() => S.mergeScreenTime(null)); assert.doesNotThrow(() => S.mergeScreenTime('x')); assert.strictEqual(S.mergeScreenTime(undefined).access, false);
  assert.strictEqual(S.mergeScreenTime({ apps: new Array(30).fill({ name: 'x', min: 1 }) }).apps.length, 10);
});
test('status messages cover permission denied, no data, error and empty', () => {
  assert.strictEqual(S.screenTimeStatus(S.mergeScreenTime({ access: false })).tone, 'warn'); assert.ok(/Usage Access/.test(S.screenTimeStatus(S.EMPTY_SCREEN_TIME).text));
  assert.strictEqual(S.screenTimeStatus(S.mergeScreenTime({ access: true, ok: false })).tone, 'warn'); assert.strictEqual(S.screenTimeStatus(S.mergeScreenTime({ access: true, error: 'boom' })).tone, 'bad');
  assert.ok(/No phone usage/.test(S.screenTimeStatus(S.mergeScreenTime({ access: true, ok: true })).text)); assert.strictEqual(S.screenTimeStatus(S.mergeScreenTime({ access: true, ok: true, totalMin: 5 })).tone, 'ok');
});
test('the local calendar day decides the reset', () => {
  const a = new Date(2026, 9, 30, 23, 59).getTime(), b = new Date(2026, 9, 31, 0, 1).getTime();
  assert.strictEqual(S.localDayKey(a), '2026-10-30'); assert.strictEqual(S.localDayKey(b), '2026-10-31'); assert.notStrictEqual(S.localDayKey(a), S.localDayKey(b));
});

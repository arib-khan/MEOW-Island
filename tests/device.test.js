const D = src('core/device.ts');

test('device state from the native side is clamped to known values', () => {
  const d = D.mergeDeviceState({ state: 'exploding', paired: 'yes', screen: 99, wifiState: -4, name: 'x'.repeat(200), error: 5 });
  assert.strictEqual(d.state, 'unpaired'); assert.strictEqual(d.paired, false); assert.strictEqual(d.screen, 2); assert.strictEqual(d.wifiState, 0);
  assert.strictEqual(d.name.length, 40); assert.strictEqual(d.error, '');
  assert.doesNotThrow(() => D.mergeDeviceState(null)); assert.doesNotThrow(() => D.mergeDeviceState('x'));
  assert.strictEqual(D.mergeDeviceState({ state: 'connected', ready: true }).ready, true);
});
test('status labels cover every state', () => {
  for (const [st, tone] of [['connected', 'ok'], ['connecting', 'warn'], ['reconnecting', 'warn'], ['pairing', 'warn'], ['bluetooth_off', 'bad'], ['no_permission', 'bad'], ['unpaired', 'idle'], ['disconnected', 'idle']])
    assert.strictEqual(D.statusLabel(D.mergeDeviceState({ state: st, paired: true })).tone, tone, st);
  assert.strictEqual(D.statusLabel(D.mergeDeviceState({ state: 'disconnected', paired: true })).text, 'Paired · disconnected');
});
test('scan results are validated', () => {
  const j = JSON.stringify([{ address: 'AA:BB:CC:DD:EE:FF', name: 'MeowIsland-1234', rssi: -55, pairingOpen: true, bonded: false }, { address: 'not an address' }, null, { address: 'AA:BB:CC:DD:EE:00', rssi: 50 }]);
  const f = D.parseFound(j);
  assert.strictEqual(f.length, 2); assert.strictEqual(f[0].pairingOpen, true); assert.strictEqual(f[1].name, 'Meow Island'); assert.strictEqual(f[1].rssi, 0);
  assert.strictEqual(D.parseFound('garbage').length, 0); assert.strictEqual(D.parseFound(undefined).length, 0); assert.strictEqual(D.parseFound('{"a":1}').length, 0);
});
test('Wi-Fi input rules match the device (protocol.h)', () => {
  assert.strictEqual(D.wifiProblem('Home', 'password1'), null); assert.strictEqual(D.wifiProblem('Cafe', ''), null);
  assert.ok(D.wifiProblem('', 'password1')); assert.ok(D.wifiProblem('x'.repeat(33), 'password1')); assert.ok(D.wifiProblem('ok', 'short')); assert.ok(D.wifiProblem('ok', 'p'.repeat(64)));
  assert.strictEqual(D.wifiProblem('x'.repeat(32), 'p'.repeat(63)), null);
  assert.strictEqual(D.utf8Len('é'), 2); assert.strictEqual(D.utf8Len('日本'), 6); assert.strictEqual(D.utf8Len('😀'), 4);
  assert.ok(D.wifiProblem('日'.repeat(11), 'password1'), '33 bytes of CJK is too long even though it is only 11 characters');
});
test('Wi-Fi status text never contains a password and reads sensibly', () => {
  const base = { ready: true, ssid: 'HomeNet' };
  assert.strictEqual(D.wifiText(D.mergeDeviceState({ ...base, wifiState: 2 })), 'Connected to Wi-Fi “HomeNet”');
  assert.strictEqual(D.wifiText(D.mergeDeviceState({ ...base, wifiState: 1 })), 'Connecting to Wi-Fi “HomeNet”…');
  assert.strictEqual(D.wifiText(D.mergeDeviceState({ ...base, wifiState: 3, wifiReason: 'The Wi-Fi password was not accepted' })), 'The Wi-Fi password was not accepted');
  assert.strictEqual(D.wifiText(D.mergeDeviceState({ ready: true, wifiState: 0 })), 'No Wi-Fi set up');
  assert.strictEqual(D.wifiText(D.mergeDeviceState({ ready: false })), 'Connect to the device to see its Wi-Fi');
});
test('signal bars and screen list', () => {
  assert.strictEqual(D.signalBars(-50), 3); assert.strictEqual(D.signalBars(-70), 2); assert.strictEqual(D.signalBars(-85), 1); assert.strictEqual(D.signalBars(-100), 0);
  assert.strictEqual(D.SCREENS.length, 3); assert.strictEqual(D.SCREENS[0].name, 'Clock');
});

# Pairing and reconnecting: how it works and what was fixed

## Flow (unchanged protocol, UUIDs and security model)
1. **Scan** for the service UUID `4d656f77-1000-…`; the advertisement says whether the display's pairing window is open and how many phones it has.
2. **Pair** (`DeviceLink.pair`): Android bonds with the display (LE Secure Connections, passkey). The display shows a random 6-digit code on the TFT only while its pairing window is open (no phone paired yet, or BOOT held 3 s). The user types it into Android's dialog.
3. **Connect** after the bond: GATT connect, MTU, service discovery, enable notifications on the status characteristic, then **one encrypted read of the status characteristic**. Only when that read succeeds is the link "ready" and data is sent.
4. **Reconnect** (after Disconnect, app restart, display restart): the same connect sequence with the stored bond; no code is needed.

## Root cause of "Pairing… forever" / "connected in Bluetooth settings but not in the app"
Found by tracing the code (no device logs were available, so these are the defects the code proves, not a captured trace):
1. **No exit from the pairing state.** The app left "Pairing…" only when Android sent a bond-state broadcast for exactly the address it expected. A missed broadcast, one carrying a different address, or a bonding that stalled left the state (and the disabled Pair button) in place forever. There was no timeout, no cancel, no fallback.
2. **"Ready" was declared without proof of encryption.** Enabling notifications needs no encryption on this firmware, so a phone and display that disagreed about their keys looked connected (Bluetooth shows the link up) while every real read/write failed.
3. **A stale phone-side bond was never removed.** After the display forgot the phone (Unpair, BOOT 10 s, a reflash that erased its storage), Android still held the old key. "Add device" then took the "already bonded" shortcut, skipped the code exchange and could never succeed. Before Android 16 the Bluetooth stack silently re-paired in that situation; Android 16 stopped doing that (bond-loss handling now reports `KEY_MISSING` to the app instead), which is why it surfaced on that version.
4. **`disconnect()` was immediately followed by `close()`**, which can leave the old link (and the display's single connection slot) occupied, and **stale callbacks** of an old connection could touch the new one.
5. **Firmware:** a pairing attempt while the pairing window was closed produced a code nobody could see and then waited for Android's 30 s timeout; a link that never completed security was never dropped.

## What changed
Phone (`plugins/kotlin/DeviceLink.kt`, `DeviceModule.kt`, `src/screens/Device.tsx`, `src/native.ts`, `src/core/device.ts`):
* Pairing is a state machine with a **90 s bonding timeout**, a **40 s first-connection timeout**, a **Cancel** button and cleanup (`cancelPair`, `failPairing`). The UI can no longer stay on "Pairing…".
* While bonding, the app **polls the real bond state every second** (and also accepts a bond recorded under another address), so a missed broadcast is harmless.
* Every connect step (connect, MTU, discovery, notifications, secure read) has a **watchdog**; some Android versions never call back after their own MTU exchange, which now just moves on.
* The link is declared ready only after a **successful encrypted read**.
* A refused encrypted request, repeated drops while pairing, or Android 16's `KEY_MISSING` trigger **one automatic repair** during pairing (forget the stale phone-side bond, pair afresh) and otherwise show **Repair pairing**. Unpair also removes the phone-side bond. Removal uses the hidden `BluetoothDevice.removeBond()` through reflection; if Android refuses, the app says so and points to Bluetooth settings.
* `disconnect()` first, `close()` after Android confirms or after 1.5 s; a new connection waits 0.7 s after a teardown; stale callbacks are ignored; scheduled connects are cancelled by generation counters.
* A **connection log** (Device screen) shows the last steps and status codes; it is also in logcat under `MeowLink`.

Display (`firmware/MeowIsland/device_link.h`):
* A pairing attempt while the window is closed is **refused immediately** (no unseeable code, no 30 s wait).
* A link that is not encrypted + authenticated within 20 s is dropped (120 s while a code is being typed), retried once a second until the stack confirms.

## If it still does not connect
1. Device screen > **Repair pairing** (hold the display's BOOT button for 3 s first if it already has a phone paired), type the 6-digit code.
2. If Android refuses the automatic removal: Android Settings > Bluetooth > forget "MeowIsland-…", then Add Device.
3. Open **Show connection log** on the Device screen and send it; it shows exactly which step stopped and with which status code.

## Tests
`tests/device.test.js` (phone/firmware UUID + framing agreement, regression guard for the new code paths); `firmware/host-tests/sketch_smoke_link.cpp` (25 reconnect cycles, closed-window refusal, unauthenticated and half-open links, passkey display lifecycle). The Android logic needs a device or emulator to exercise; it has not been run here.

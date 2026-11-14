# Radrower web dashboard

A static Vue 3 dashboard for the ProForm Sport RL (`I_RW`), using Web Bluetooth.
Vue **3.5.13** is loaded from jsDelivr. No npm installation, Node.js project,
bundler, backend, or BLE proxy is needed.

## Open the page

Serve this directory over **HTTPS** or **http://localhost**. Opening `index.html`
directly with `file://` is not supported because the page uses JavaScript modules.
For example, with BusyBox already installed, run from the repository root:

```sh
busybox httpd -f -p 127.0.0.1:8080 -h web
```

Open <http://localhost:8080> and stop the server with Ctrl+C when finished.
Any static HTTP server is suitable; this command does not install dependencies.
Internet access is needed to load Vue from the CDN.

An unencrypted LAN URL such as `http://192.168.x.x:8080` is **not** a secure
context, including when accessed from a phone. To use a phone, host the files on
HTTPS with a certificate trusted by that phone, or use a local development
forwarding setup exposing the server as localhost.

## Connect

1. Use a browser/platform that supports Web Bluetooth: typically Chrome or
   Edge on desktop, or Chrome on Android. Firefox and Safari do not support
   this API. Linux support may require enabling the browser's experimental
   Web Bluetooth features; the API being present does not guarantee that the
   system Bluetooth adapter is available.
2. Turn on the rower. Disconnect iFit, Bleak scripts and other BLE clients.
3. Click **Connecter le rameur** and select **I_RW** in the browser's device
   chooser. Bluetooth permission requires this user gesture.
4. Wait for initialization, then row and compare the readings with the console.
5. Click **Déconnecter** to stop polling and close the GATT connection.

Keep the tab visible and the screen awake. Browser timer throttling or system
sleep can break the rower's approximately ten-second keep-alive watchdog.
Reconnection is manual; the page never reconnects or selects a device silently.

## Metrics and protocol

The page displays power (W), distance (m), resistance (1–24), cadence
(strokes/min), stroke count, pace (time/500 m), and speed (km/h, calculated as
`1800 / pace_seconds`). No elapsed time or calorie interpretation is invented.

- [`protocol.js`](protocol.js) ports the exact initialization, poll messages,
  reassembly, resistance mapping, and decoding from
  [`rower.py`](../bleak/rower.py) and
  [`03-telemetry.py`](../bleak/03-telemetry.py).
- [`rower.js`](rower.js) subscribes to notifications, performs the 25 setup
  writes with ATT response, waits for notification silence (150 ms, up to
  1.5 s after each setup message), and alternates **complete** `17`/`19`
  messages every 600 ms. Writes never overlap.
- No resistance, start, stop, pause, or session-control commands are included.
  Initialization/polling do write to the BLE characteristic; this is
  telemetry-only operation, not a GATT connection without writes.
- A zero resistance byte means **unknown**, not level zero. Unknown nonzero
  encodings are shown as hexadecimal rather than guessed. A zero pace is
  unavailable; the derived speed is zero.
- Messages with the all-`ff` no-data sentinel clear the corresponding values.
  Reassembled messages with a bad checksum are **discarded with a warning**,
  unlike the Python diagnostic script which prints them with `checksum=BAD`.
  Standalone `ff 11` frames use the same recognition rules as the Python
  reference; their provisional checksum formula is not enforced.
- After five seconds without an update, the affected metric is marked stale.
  Disconnection preserves but dims the last readings; a new connection clears
  them. These are rower counters, not browser-session totals; rollover is not
  corrected.
- The connection journal is capped at 40 entries, kept in memory, and does
  not log device identifiers or send metrics to a server. Vue is the only
  CDN script; loading it contacts jsDelivr.

See the [protocol notes](../docs/protocole-ble.md) for observed offsets,
unknown fields, and hardware validation history.

## Tests

With an existing Node.js **22.7+** installation (optional, tests only):

```sh
node --experimental-default-type=module --test web/protocol.test.js web/rower.test.js
```

Run this command from the repository root. Tests use Node's built-in runner,
with no package manifest or dependencies. They compare every setup/poll byte
against the Python reference, exercise framing and metric edge cases, and use
a fake GATT connection to check ordering, polling, cancellation, errors,
disconnect and reconnect. They never access Bluetooth hardware.

Browser UI checks and automated mocks cannot certify the real BLE link.
**The web implementation still needs a hardware trial**: confirm connection
stability, metric agreement, manual disconnect and reconnect on the target
browser/platform. Only the original Bleak telemetry has been hardware-validated.

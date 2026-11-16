# Radrower web dashboard

A static Vue 3 progressive web app (PWA) dashboard for the ProForm Sport RL
(`I_RW`), using Web Bluetooth. Vue **3.5.13** is loaded from jsDelivr. No npm
installation, Node.js project, bundler, backend, or BLE proxy is needed.

## Open the page

Serve this directory over **HTTPS** or **http://localhost**. Opening `index.html`
directly with `file://` is not supported because the page uses JavaScript modules.
For example, with BusyBox already installed, run from the repository root:

```sh
busybox httpd -f -p 127.0.0.1:8080 -h web
```

Open <http://localhost:8080> and stop the server with Ctrl+C when finished.
Any static HTTP server is suitable; this command does not install dependencies.
Internet access is needed on the first visit to load Vue from the CDN. The
service worker caches the app shell and makes a best-effort cache of Vue, so a
previously loaded app can reopen offline. Browser storage may be cleared, and
the first visit cannot work offline. The service worker requires HTTPS or
localhost, just like Web Bluetooth.

When the browser supports PWA installation, use its install control or the
**Install app** button in the page header. The installed app still needs a
compatible browser with Web Bluetooth and an available Bluetooth adapter to
connect to the rower; offline mode only makes the interface assets available.

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
3. Click **Connect rower** and select **I_RW** in the browser's device
   chooser. Bluetooth permission requires this user gesture.
4. Wait for initialization and a fresh session state. **Start** starts a
  session; **Pause** pauses it, and **Resume** resumes it. **Stop** ends
  a running or paused session. Compare the readings with the console.
5. Click **Disconnect** to stop polling and close the GATT connection. This
  does not send a stop command.

During a running session, the app requests a screen wake lock to keep the
display on. It releases the lock on pause, session end or disconnection and
requests it again on resume or when the page becomes visible. The browser or
operating system can refuse or release the lock; a notice advises keeping the
screen awake manually when this happens. Keep the tab visible: a wake lock
cannot prevent background timer throttling or guarantee uninterrupted BLE.
Browser timer throttling or system sleep can break the rower's approximately
ten-second keep-alive watchdog.
Reconnection is manual; the page never reconnects or selects a device silently.

## Metrics and protocol

The page displays power, distance, resistance, cadence, stroke count, pace,
speed, elapsed time, calories, estimated calories per hour, average power,
paused time and average pace. Speed is `1800 / pace_seconds`; calories per
hour is an empirical estimate from power, unavailable at zero power. Paused
time, average power and average pace are probable interpretations, not yet
validated live.
Some values may temporarily be unavailable during a pause; the page does not
reconstruct cumulative distance or calories from earlier readings.

- [`protocol.js`](protocol.js) ports the exact initialization, poll messages,
  reassembly, resistance mapping, and decoding from
  [`rower.py`](../bleak/rower.py) and
  [`03-telemetry.py`](../bleak/03-telemetry.py).
- [`rower.js`](rower.js) subscribes to notifications, performs the 25 setup
  writes with ATT response, waits for notification silence (150 ms, up to
  1.5 s after each setup message), and alternates **complete** `17`/`19`
  messages every 600 ms. Writes never overlap.
- Session commands use the frames validated by `05-start-pause-stop.py` and
  are serialized with polling. A fresh recognized rower state is required.
  Start, pause and return to idle must be confirmed within five seconds;
  the intermediate end state is optional, as in the Python script. A failed
  required confirmation stops the sequence; check the console before retrying.
  No resistance command is included.
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
- The connection journal is capped at 40 entries and kept in memory without
  device identifiers. Workouts are stored locally. They are sent to Google
  only after optional Google Health authorization. Vue loads from jsDelivr;
  the Google Identity Services library loads from accounts.google.com.

See the [protocol notes](../docs/protocole-ble.md) for observed offsets,
unknown fields, and hardware validation history.

## Local workout storage

Workouts are stored in IndexedDB (`radrower`, version 1), scoped to this
browser and origin. The `sessions` store holds UUID, start/end timestamps,
status (`recording`, `completed`, `interrupted`), last available metrics,
sample count, a `partial` flag for workouts joined in progress, and `synced`
(initially `false`). The separate `samples` store holds every received metric
delta with a timestamp and sequence number, including unavailable (`null`)
values and pause/resume states. It is indexed by session UUID for future charts.
No Bluetooth identifiers are stored. Idle counters do not overwrite final totals.

Each notification is saved asynchronously in an atomic transaction. Stop or a
reported end/idle state completes a workout; disconnection marks it interrupted.
On reopening, unfinished recordings are recovered as interrupted at their last
saved sample. Closing a page can lose pending writes, but not earlier committed
samples. Use only one Radrower tab at a time. Storage errors are shown in the UI;
browser eviction, private browsing or clearing site data can delete history.
Storage is not a backup and is not shared across devices or hosting origins.

## Optional Google Health sync

**Connect Google Health** opens Google's consent popup and syncs completed,
unsynced workouts. While authorization remains valid, new completed workouts
are synced automatically. **Sync workouts** retries failed uploads.
Interrupted workouts are never uploaded. Bluetooth recording works without
Google, and a Google error never deletes a local workout.

The browser uses Google Identity Services' official token model, requesting
only `https://www.googleapis.com/auth/googlehealth.activity_and_fitness.writeonly`.
Access tokens stay in tab memory and are discarded on reload or disconnect.
When authorization expires, the button obtains a new token through a user
gesture. This static application does not need or store refresh tokens or a
client secret. Unattended synchronization across browser sessions would require
a separate backend with secure OAuth authorization-code/refresh-token handling.
Disconnect stops future uploads and clears the token, but does not delete Google
workouts or revoke Google's saved consent; manage consent in your Google account.
An upload already sent before disconnect may still complete remotely.

### Google Cloud setup

1. Enable the Google Health API and configure its developer access and OAuth
  consent screen for the Google Cloud project. Add test users while the OAuth
  application is in testing; public access can require Google's verification.
2. Use a **Web application** OAuth client. `google-health.js` contains only the
  public client ID supplied for this project, never the client secret.
3. Add exact **Authorized JavaScript origins** in Google Cloud, for example
  `https://fmairesse.github.io` and `http://127.0.0.1:8082` (or your development
  origin). Origins have no path. Authorized redirect URIs alone are not enough
  for the popup token model. Allow popups and access to accounts.google.com.
4. Test consent and an upload with your own Google Health account. No real
  authenticated upload has been validated yet; tests use simulated responses.

**Security:** revoke/rotate any OAuth secrets or authorization codes exposed in
local codelab files. These local files are ignored by Git and are not cached by
the service worker. Do not deploy them with a manual directory upload: a static
HTTP server can still serve untracked files from its document root.

### Exported data and confirmation

The verified endpoint is
`POST https://health.googleapis.com/v4/users/me/dataTypes/exercise/dataPoints`.
The payload identifies `ROWING_MACHINE` and the ProForm Sport RL, with start/end
times, timezone offsets, active duration, pause/resume events, distance (converted
from meters to millimeters), calories, and calculated average speed/pace for
complete recordings. Stroke counts are not exported as walking steps. Additional
rower metrics (power, cadence, strokes, resistance and others) and sampled
min/max/means are included in the exercise's free-form `notes`, with units and
provisional interpretation warnings. Sample means are not time-weighted.
Recordings joined in progress are marked partial in notes; their counters can
include activity before the first recorded sample and averages are omitted.

Google's intraday telemetry streams are read-only: the REST write API cannot
import the raw power/cadence/distance time series. All samples remain in IndexedDB
for future local charts. No heart rate, GPS, steps or elevation are fabricated.

Before uploading, the app lists exercises for that civil date and checks for a
matching start/end interval. This uses the documented collection `GET`; it does
not try to read a resource using the local session UUID. Creation uses the
documented `POST` body without a client-supplied resource name, so Google assigns
the workout's ID. A successful HTTP response alone is not enough: `synced`
becomes true only after a completed operation or collection readback confirms
matching start/end times. The server-assigned resource name and sync timestamp
are retained. Network failures and create conflicts trigger another collection
readback before retrying. Pending asynchronous operations remain unsynced until a
later readback finds the exercise. Duplicate protection depends on the real API's
listing consistency and still needs an authenticated trial.

References: [Google Health workouts](https://developers.google.com/health/data-types/workouts),
[create data point](https://developers.google.com/health/reference/rest/v4/users.dataTypes.dataPoints/create),
[data point schema](https://developers.google.com/health/reference/rest/v4/users.dataTypes.dataPoints),
[Google browser token model](https://developers.google.com/identity/oauth2/web/guides/use-token-model).

## Tests

With an existing Node.js **22.7+** installation (optional, tests only):

```sh
node --experimental-default-type=module --test web/*.test.js
```

Run this command from the repository root. Tests use Node's built-in runner,
with no package manifest or dependencies. They compare every setup/poll byte
  against the Python reference, exercise framing and metric edge cases, and use
  a fake GATT connection to check ordering, polling, session transitions,
  confirmation failures, cancellation, disconnect and reconnect. Wake lock
  tests simulate session, visibility and browser lock events. They never access
  Bluetooth hardware or control the physical display.
  Storage and Google Health tests cover metric recording, final totals, recovery,
  payload units, consent failures, expiration, uncertain writes and conflict
  readback. The current suite contains 30 passing tests.

Browser UI checks and automated mocks cannot certify the real BLE link.
**The web implementation still needs a hardware trial**: confirm connection
stability, metric agreement, session controls, manual disconnect and reconnect
on the target browser/platform. The Python session-control sequence has been
validated on the rower; the web port has not.

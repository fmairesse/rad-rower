# Radrower Android

The Android app displays ProForm Sport RL metrics and controls a session. It
requires Android 8.0 (API 26) or newer and targets a Pixel 3a running Android 12.
The app scans for the first device advertising as `I_RW` when opened and
Bluetooth permission is granted; no Bluetooth identifier is stored. Turn the
rower on and disconnect other apps before opening Radrower. The scan stops
after 12 seconds; use **Retry
connection** if the rower was not found or the connection was lost.

On Android 12 and newer, grant the nearby-devices permissions. On Android 11
and earlier, Bluetooth scanning requires location permission and may require
location services to be enabled. Bluetooth itself must also be enabled.

Keep the app in the foreground: the screen stays awake while the connected
rower reports an active session. It can turn off normally when the session is
paused or stopped, or when the rower disconnects. The last reported session
state is used until a new state arrives or the connection is lost, even if the
reading is out of date. Leaving the app disconnects the rower, and the app
attempts to reconnect when returned to the foreground. Session buttons are
disabled until a recent rower state is available. Start, pause, resume and stop
send commands only when pressed; they wait for the corresponding state
reported by the rower. If a command fails or is not confirmed, check the
rower's console before retrying. Metrics older than five seconds are marked as
out of date.

The protocol is based on the verified Linux experiments documented in
[`docs/protocole-ble.md`](../docs/protocole-ble.md) and the web implementation.
When a telemetry message reports resistance byte `0` (not yet configured),
Android assumes level 1. Fully unavailable telemetry and unknown nonzero
encodings are still reported as unavailable or unknown.
On 2026-11-16 the user reported a successful test of the Android app; the phone
and exact test conditions were not specified. The first matching `I_RW` is
selected, so avoid running the app near multiple machines with that name.

Workouts are saved on this device in a Room/SQLite database. Recording begins
when the rower reports an active or paused session. Metric updates and session
states are saved as timestamped samples; the summary keeps the highest
cumulative counters when the rower resets them at the end. Open **View workout
history** on the dashboard to see all workouts, newest first, with their start
date and time, duration, distance and calories. Duration is the elapsed time
reported by the rower; unavailable values are shown as a dash. Recording,
interrupted and partial workouts remain visible with their status.
Average speed and pace are calculated from the rower's cumulative distance and
active elapsed time for complete workouts when both measurements are available.
Each workout also stores contiguous rowing and pause segments based on the
observed session-state transitions. A stop from active state first requests a
pause, so a short final pause may be recorded. An interrupted segment ends at
disconnection, or at the last saved sample after an unexpected shutdown.
Transition timestamps reflect when the app received the state, not the exact
instant the rower changed state.

The pre-release database schema was reset without migration when segments were
added. Existing app databases must be cleared or the app reinstalled; this
deletes locally saved workouts.

An active workout is marked **interrupted** when the connection is lost or the
app leaves the foreground. After an unexpected process termination, unfinished
workouts are marked interrupted on the next launch using their last saved
sample time. A workout first observed in progress without a preceding idle
state is marked **partial**; a session started after the app observed idle is
not partial even if its first active reading arrives a few seconds later.
No measurements are inferred for missing intervals; a sudden shutdown may lose
pending writes. Storage errors are displayed on the dashboard. Android's
configured app backup behavior also applies to the database.

Completed, non-partial workouts can be exported from **Workout history** to
Health Connect. Export is always initiated by the user. Radrower writes an
indoor rowing exercise session, one distance record using the stored cumulative
distance, active calories, and separate timestamped power and speed records.
The session includes the stored rowing and pause segments; zero-duration
segments are omitted because Health Connect requires a positive duration.
It also includes 500 m laps and, when needed, a shorter final lap. Lap
boundaries are estimated by linear interpolation of saved cumulative distance
readings, assuming distance starts at zero and progresses uniformly between
readings during non-pause time. Known pause segments are excluded from that
interpolation but remain part of the lap's elapsed duration. The estimates
may be inaccurate when readings are sparse or pauses are missing.
Counter decreases are ignored, as in the stored summary. If a 500 m boundary
cannot be placed with the available samples, the workout is exported without
laps and the export message says so.
The stored distance is the highest valid rower counter value, and may differ
from the integral of the speed series.
Speed is derived from the rower's reported pace and converted from km/h to m/s;
it is not reconstructed from the distance counter. Only active-session power and
speed samples are exported; pause, missing and invalid samples are omitted.
Each series spans the whole session, so readers may interpolate across pauses.
Other raw telemetry is not exported. Radrower does not read Health Connect data. Health Connect
permissions are requested only when needed; export errors are shown in the
history. Repeating an export uses stable client record IDs to avoid creating
another copy of the same workout.

Before re-exporting a workout exported with an older version, remove its old
Radrower data in Health Connect. The new distance record replaces the first
old record, but additional distance windows from previous exports are not
removed and would otherwise duplicate distance.

Health Connect must be installed and available on the device. A synthetic
export was written to Health Connect on an emulator, and the I1 synthetic
layout was viewed in Intervals.icu. Export and downstream behavior still need
validation with a real workout; local recording remains independent.

To force a new export of existing workouts (for example to make Health Sync
import them again), give them new IDs on a phone connected with `adb`. The
script backs up the database first and needs a debug build:

```sh
android/tools/regenerate-workout-ids.py --dry-run   # preview
android/tools/regenerate-workout-ids.py
```

Delete the old records from Health Connect (and the activity from Strava)
beforehand, otherwise they will be duplicated.

Build and run unit tests from the repository root:

```sh
android/gradlew -p android :app:assembleDebug :app:testDebugUnitTest
```

The local unit tests include the conversion of a saved workout and its
samples into Health Connect records. They do not write to Health Connect.

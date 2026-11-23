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
At launch, the app requests the applicable Bluetooth permissions, followed by
all four Health Connect write permissions when Health Connect is available.
If Health Connect is unavailable, install or update it before exporting. A
denied Health Connect permission can be requested again when reopening the app,
or changed in Health Connect settings; local recording does not depend on it.
Debug builds declare permission to write exercise routes for the synthetic
Health Connect experiment, although the current experiment does not write a
route. Production exports do not contain routes.

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

The version 1 database is migrated without deleting workouts. Existing workouts
are marked exported by the migration, even if they were never actually sent to
Health Connect; they are not automatically exported.

An active workout is marked **interrupted** when the connection is lost or the
app leaves the foreground. After an unexpected process termination, unfinished
workouts are marked interrupted on the next launch using their last saved
sample time. A workout first observed in progress without a preceding idle
state is marked **partial**; a session started after the app observed idle is
not partial even if its first active reading arrives a few seconds later.
No measurements are inferred for missing intervals; a sudden shutdown may lose
pending writes. Storage errors are displayed on the dashboard. Android's
configured app backup behavior also applies to the database.

Completed, non-partial workouts with a valid time range are automatically
queued for export to Health Connect after recording ends. **Workout history**
shows whether each eligible workout is exported or pending; other workouts
are not eligible. Radrower writes an
indoor rowing exercise session, active calories when available,
one-second distance records derived from the rower's cumulative counter, and a
timestamped power record when measured samples are available. The session
includes the stored rowing and pause segments; zero-duration segments are
omitted because Health Connect requires a positive duration.
Distance is linearly interpolated between saved counter readings at one-second
boundaries during active stretches. Counter decreases and pause readings are
ignored; the final interval in a stretch can be shorter than one second.
Only positive distance intervals are written, so sparse readings or counter
plateaus can leave some seconds without a record. The exported distance series
may differ from the stored summary total. Speed records and exercise laps are
not exported. Only active-session power samples are exported; pause, missing
and invalid samples are omitted. Other raw telemetry is not exported.
Radrower does not read Health Connect data.
WorkManager persists each export independently of the app's UI, so it can run
after the app is closed and retry failures with increasing delays. Android
controls the exact execution time; force-stopping the app prevents background
execution until it is opened again. Pending eligible workouts are also queued
when the app starts. If permissions are missing, install or update Health Connect
and grant the required permissions; pending exports will be retried. A toast
reports success or a pending export while the app is visible. Failures are
logged, and the workout remains pending until all writes succeed. Stable client
record IDs prevent duplicate records when a partially completed export is retried.
Ordinary WorkManager tasks have a limited execution window; unusually long
exports may be stopped and retried.

Before re-exporting a workout exported with an older version, remove its old
Radrower data in Health Connect. Old distance records are not removed
automatically and would otherwise duplicate distance.

Health Connect must be installed and available on the device. A synthetic
export was written to Health Connect on an emulator, and the I1 synthetic
layout was viewed in Intervals.icu. Export and downstream behavior still need
validation with a real workout; local recording remains independent.

Run the opt-in synthetic Health Connect insertion test on a connected phone
from the repository root:

```sh
android/tools/run-health-sync-experiment.sh
# If multiple devices are connected:
android/tools/run-health-sync-experiment.sh --serial DEVICE
```

The test inserts a synthetic rowing session with pause segments, power samples
and individual distance records. It no longer asserts the record structure and
does not write speed samples, laps or a route. The script installs a debug
build and writes or updates the synthetic I1 records in Health Connect. On
Android 14 and newer it grants the four permissions required by the test with
`adb`; on older versions, grant them in the Health Connect app when prompted.

To force a new export of existing eligible workouts (for example to make Health
Sync import them again), give them new IDs on a phone connected with `adb`.
The script resets their export flags, backs up the database first and needs a
debug build. Reopen the app afterwards to queue the exports:

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

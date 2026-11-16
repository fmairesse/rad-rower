# Radrower Android

The Android app displays ProForm Sport RL metrics and controls a session. It
targets a Pixel 3a running Android 12. The app scans for the first device
advertising as `I_RW` when opened and Bluetooth permission is granted; no
Bluetooth identifier is stored. Turn the rower on and disconnect other apps
before opening Radrower. The scan stops after 12 seconds; use **Retry
connection** if the rower was not found or the connection was lost.

On Android 12 and newer, grant the nearby-devices permissions. On Android 11
and earlier, Bluetooth scanning requires location permission and may require
location services to be enabled. Bluetooth itself must also be enabled.

Keep the app in the foreground: the screen stays awake while connected, and
leaving the app disconnects the rower. The app attempts to reconnect when
returned to the foreground. Session buttons are disabled until a recent rower
state is available. Start, pause, resume and stop send commands only when
pressed; they wait for the corresponding state reported by the rower. If a
command fails or is not confirmed, check the rower's console before retrying.
Metrics older than five seconds are marked as out of date.

The protocol is based on the verified Linux experiments documented in
[`docs/protocole-ble.md`](../docs/protocole-ble.md) and the web implementation.
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

An active workout is marked **interrupted** when the connection is lost or the
app leaves the foreground. After an unexpected process termination, unfinished
workouts are marked interrupted on the next launch using their last saved
sample time. A workout first observed after it started is marked **partial**.
No measurements are inferred for missing intervals; a sudden shutdown may lose
pending writes. Storage errors are displayed on the dashboard. Android's
configured app backup behavior also applies to the database.

Health Connect export is not implemented yet.

Build and run unit tests from the repository root:

```sh
android/gradlew -p android :app:assembleDebug :app:testDebugUnitTest
```

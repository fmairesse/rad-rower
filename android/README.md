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

Workout recording and Health Connect export are not implemented yet; closing
the app does not save a session.

Build and run unit tests from the repository root:

```sh
android/gradlew -p android :app:assembleDebug :app:testDebugUnitTest
```

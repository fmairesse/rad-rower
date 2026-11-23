# Décisions du projet

Consigner ici les choix durables et leurs raisons, pas le détail de chaque
conversation. Si un choix change, indiquer quelle décision le remplace.

## D001 - Expérimenter sous Linux avant le developpement BLE Android

- Date : 2026-11-01.
- Statut : adoptée.
- Décision : utiliser Python et Bleak dans `bleak/` pour apprendre BLE et
  explorer le rameur, puis utiliser ces connaissances dans `android/`.
- Raison : isoler l'apprentissage du protocole de celui de son intégration
  dans l'application Android.
- Conséquence : consigner les observations reproductibles pour faciliter
  leur utilisation ulterieure sur Android.

## D002 - Conserver une memoire de projet en Markdown dans Git

- Date : 2026-11-01.
- Statut : adoptée.
- Décision : séparer les consignes Copilot, le contexte courant, les notes
  de protocole et les décisions importantes.
- Raison : retrouver les connaissances utiles entre les sessions sans
  conserver toute la conversation dans le contexte de l'IA.
- Conséquence : tenir une synthese courte, consulter les details à la demande
  et relire les modifications avant de les versionner.
- Limite : les documents chargés consomment aussi des tokens ; cette
  organisation ne garantit pas une réduction de leur consommation.

## D003 - Prendre QZ comme référence pour l’init et le poll

- Date : 2026-11-14.
- Statut : adoptée.
- Décision : `bleak/02-connection.py` reprend l’initialisation et le poll de
  QZ (qdomyos-zwift, branche Sport RL) plutôt que les rejeux de captures iFit
  de `bleak/01-connection.py`, conservé comme historique.
- Raison : séquence plus courte, éprouvée par un projet existant, et
  confirmée par un essai sur notre rameur (connexion maintenue).
- Conséquence : toute trame ajoutée doit être comparée à QZ ou à une capture ;
  aucune commande de pilotage sans accord explicite.

## D004 - Store web workouts in IndexedDB

- Date: 2026-11-15.
- Status: adopted.
- Decision: store session metadata and timestamped metric deltas in separate
  IndexedDB stores, rather than serializing growing histories in localStorage.
- Reason: asynchronous writes and indexed sample queries suit future charts.
- Consequence: recordings survive reloads; interrupted recordings are kept but
  not treated as completed. Sample keys use the same unit-bearing names as the
  Android protocol. Provisional `averageWatts`, `paused`, `caloriesPerHour`, and
  `averagePace` values remain available in flexible sample metrics. `synced`
  stays false until remote storage succeeds.
- Limits: one active tab, origin-local storage, possible browser eviction and
  pending-write loss on abrupt closure. No device identifiers are retained.

## D005 - Use optional Google browser authorization without a backend

- Date: 2026-11-15.
- Status: adopted; real consent and upload still require validation.
- Decision: use Google Identity Services' token model and the Google Health v4
  `exercise` endpoint. Request only the activity-and-fitness write scope.
- Reason: the app is statically hosted; a web client secret or long-lived refresh
  token must not be embedded in public JavaScript. Access tokens remain in memory,
  with user-triggered renewal after expiry. No backend is needed for this model.
- Export: workout summaries and pause/resume events, extra rowing metrics in
  notes. Raw telemetry is kept locally because Google's intraday write endpoints
  are not supported. No walking steps or unmeasured health metrics are invented.
- Confirmation: query the exercise collection by civil date before upload and
  match exact interval timestamps. Let Google assign the resource ID; use
  collection readback to reconcile ambiguous writes and conflicts. Mark `synced`
  only after remote completion/readback. A real authenticated trial is required
  to verify API consistency and duplicate behavior.
- Sources: [workouts](https://developers.google.com/health/data-types/workouts),
  [browser token model](https://developers.google.com/identity/oauth2/web/guides/use-token-model).

## D006 - Offer optional Intervals.icu activity export

- Date: 2026-11-16.
- Status: adopted; real authorization and upload still require validation.
- Decision: export completed rowing sessions as manual `Rowing` activities via
  the Intervals.icu API. Ask for the API key only when connecting, keep it in
  tab memory and never persist it. Keep this sync state independent from Google.
- Reason: the web app is static and already records complete sessions locally;
  the documented API accepts manual activities and exposes external IDs for
  reconciling retries.
- Export: summary fields and additional rower metrics in the activity
  description. Raw telemetry stays local because its stream format has not been
  validated for this integration. Interrupted sessions are not exported.
- Limits: an API key is sensitive and broader than a scoped OAuth grant; use
  only on a trusted device. API authorization, browser CORS and real upload need
  an authenticated trial. Idempotency relies on Intervals.icu activity listing
  returning the external ID consistently.
- Source: [Intervals.icu API documentation](https://intervals.icu/api-docs.html).

## D007 - Store Android workouts locally with Room

- Date: 2026-11-16.
- Status: implemented; real-rower recording not yet tested.
- Decision: store workout summaries and timestamped metric updates in separate
  SQLite tables using Room. Save each update and its summary transactionally.
- Reason: preserve recordings independently of Health Connect availability or
  permissions, and retain rowing metrics for later analysis and export. Since
  the sample table carries most rows, persisted metrics use typed columns with
  unit-bearing names instead of a per-sample JSON object that repeats every key.
- Consequence: Kotlin properties and Room columns use camel-case unit suffixes,
  such as `powerW`, `distanceM`, and `elapsedS`; metric uncertainty is not hidden
  in an untyped payload. The protocol continues to expose `averageWatts`, `paused`,
  `caloriesPerHour`, and `averagePace`, but their insufficiently validated
  interpretations are retained only in `unvalidatedMetricsJson`. Mark disconnected or recovered
  unfinished recordings as interrupted; mark recordings first observed in
  progress without a preceding idle state as partial. Calculate average speed
  and pace from rower-reported active elapsed time and cumulative distance, not
  local wall-clock timing.
- Limits: pending writes may be lost on abrupt termination; Android app backup
  settings apply. The app does not store Bluetooth device identifiers.
- Schema reset: before release, schema version 1 was replaced on 2026-11-19
  with no data migration; existing debug databases must be cleared or the app
  reinstalled. Room exports the replacement schema to `android/app/schemas/`.
  It was reset again on 2026-11-19 to add workout segments.
- Tests: `android/app/src/androidTest/java/io/radrower/WorkoutPersistenceTest.kt`.

## D008 - Export completed Android workouts to Health Connect

- Date: 2026-11-16.
- Status: implemented; the current one-second distance layout still needs
  validation with a real workout and downstream apps.
- Decision: automatically queue an export after a completed, non-partial
  workout with a valid time range. Write an indoor rowing `ExerciseSessionRecord`
  with rowing and pause segments, an active-calorie record when available,
  one-second distance records, and timestamped power samples. Do not write
  exercise laps or `SpeedRecord`s.
- Reason: make the already-persisted local summary available to fitness apps
  without making Health Connect a prerequisite for recording.
- Privacy and retries: request all four Health Connect write
  permissions at app launch when available. Never read Health Connect records. Use stable
  client record IDs and versions so retrying the same immutable workout does
  not create a second copy. Distance readings are processed only during active
  stretches; counter decreases and pause readings are ignored.
  Interpolate cumulative distance at one-second boundaries between valid
  readings, with a possibly shorter final interval per stretch; omit intervals
  with no positive distance. Distance client IDs are stable by interval and
  the record version is 7. The exercise session version is 4. Old records from
  earlier exports are not deleted automatically, so remove old Radrower data
  before re-exporting to avoid duplicate distance.
- Background export (2026-11-23): WorkManager queues one unique task per
  eligible workout, retries failures with backoff and continues independently
  of the UI. Reopening the app also queues any pending eligible workouts.
  Health Connect permissions cannot be requested in the background. The version
  1 to 2 Room migration marks all existing workouts exported, regardless of
  whether they were actually written to Health Connect. The flag is set for new
  workouts only after every write succeeds. Android controls task timing, and a
  force-stopped app cannot run the task until reopened. The UI shows export
  status and displays a toast when an export finishes while it is visible.
- Write limits: Health Connect guidance recommends at most 1000 records per
  write request, so `insertRecords` is called in chunks of 1000. The SDK
  (androidx.health.connect:connect-client 1.1.0) caps each power sample at
  0-100000 W. One-second distance windows can produce many records; whether
  Health Connect and downstream apps handle long workouts as expected is
  unverified.
- Segments (2026-11-19): persist rowing and pause intervals at observed state
  transitions and export them as exercise segments. Close interrupted intervals
  at disconnect or the last saved sample on recovery. Omit zero-duration
  intervals because Health Connect requires positive duration; increment the
  exercise record version so re-export updates it. Exact transition timing and
  downstream pause handling remain unverified on a real workout.
- Limits: availability, consent UI, permissions and remote write behavior
  require a real-workout test. Partial and interrupted workouts are not
  exported; zero or unavailable calorie measurements are omitted. Earlier export
  layouts were inserted synthetically on an emulator, but the current export
  layout and downstream behavior remain unverified.
- Compatibility: raise the app minimum API from 24 to 26 because the official
  Health Connect client declares API 26 as its minimum. Health Connect itself
  may still be unavailable on some devices running API 26 or 27.
- Source: [Health Connect write data](https://developer.android.com/health-and-fitness/health-connect/write-data),
  [exercise sessions](https://developer.android.com/reference/kotlin/androidx/health/connect/client/records/ExerciseSessionRecord),
  [distance records](https://developer.android.com/reference/kotlin/androidx/health/connect/client/records/DistanceRecord),
  [power records](https://developer.android.com/reference/kotlin/androidx/health/connect/client/records/PowerRecord),
  [AndroidX release notes](https://developer.android.com/jetpack/androidx/releases/health-connect).

## D009 - Assume resistance level 1 when unset

- Date: 2026-11-18.
- Status: adopted.
- Decision: interpret resistance byte `0` in Android and web telemetry as
  resistance level 1. Keep fully unavailable telemetry and unknown nonzero encodings
  distinct.
- Reason: the user requested a usable default when the rower has not reported
  a resistance level; level 1 is the chosen assumption, not a measured value.
- Scope: Android and web. Linux experimentation tools continue to report the raw
  observation.

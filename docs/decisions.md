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
  not treated as completed. `synced` stays false until remote storage succeeds.
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
  permissions, and retain rowing metrics for later analysis and export.
- Consequence: mark disconnected or recovered unfinished recordings as
  interrupted; mark recordings first observed in progress without a preceding
  idle state as partial. Calculate average speed and pace from rower-reported
  active elapsed time and cumulative distance, not local wall-clock timing.
- Limits: pending writes may be lost on abrupt termination; Android app backup
  settings apply. The app does not store Bluetooth device identifiers.
- Tests: `android/app/src/androidTest/java/io/radrower/WorkoutPersistenceTest.kt`.

## D008 - Export completed Android workouts to Health Connect

- Date: 2026-11-16.
- Status: implemented, including measured power and distance intervals on
  2026-11-17; a real Health Connect provider has not yet been tested.
- Decision: offer a user-triggered export from workout history for completed,
  non-partial workouts only. Write an indoor rowing `ExerciseSessionRecord`
  and, when available, active calories, distance intervals derived from the
  rower's cumulative readings, plus the timestamped measured power series
  while actively rowing.
- Reason: make the already-persisted local summary available to fitness apps
  without making Health Connect a prerequisite for recording.
- Privacy and retries: request only the write permissions needed for a
  workout's available metrics; never read Health Connect records. Use stable
  client record IDs and versions so retrying the same immutable workout does
  not create a second copy. Derive distance deltas only
  between monotonic readings in active session state, and split records around
  pauses; disregard counter decreases and pause values. Preserve the previous
  distance as the summary total. Consecutive deltas within one active
  stretch are merged into windows of at most 60 s (2026-11-18) to avoid
  writing one record per sample. The first window reuses the earlier
  whole-session distance record ID; all distance records use version 3 so a
  re-export overwrites previous layouts (extra per-sample records from an
  earlier export, if any, would not be removed). Export power measurements from
  locally stored samples only when their session state is active; pause,
  missing, invalid and out-of-session samples are excluded. Other raw samples
  remain local.
- Write limits: Health Connect guidance recommends at most 1000 records per
  write request, so `insertRecords` is called in chunks of 1000. The SDK
  (androidx.health.connect:connect-client 1.1.0) caps each power sample at
  0-100000 W but sets no limit on samples per `PowerRecord`; whether the
  Health Connect service enforces one is unverified, so the power series is
  exported without downsampling until a real-provider test shows otherwise.
- Limits: availability, consent UI, permissions and remote write behavior
  require a real-provider test. Partial and interrupted workouts are not
  exported; zero or unavailable metric totals are omitted.
- Compatibility: raise the app minimum API from 24 to 26 because the official
  Health Connect client declares API 26 as its minimum. Health Connect itself
  may still be unavailable on some devices running API 26 or 27.
- Source: [Health Connect write data](https://developer.android.com/health-and-fitness/health-connect/write-data),
  [exercise sessions](https://developer.android.com/reference/kotlin/androidx/health/connect/client/records/ExerciseSessionRecord),
  [distance records](https://developer.android.com/reference/kotlin/androidx/health/connect/client/records/DistanceRecord),
  [power records](https://developer.android.com/reference/kotlin/androidx/health/connect/client/records/PowerRecord),
  [AndroidX release notes](https://developer.android.com/jetpack/androidx/releases/health-connect).

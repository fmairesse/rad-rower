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

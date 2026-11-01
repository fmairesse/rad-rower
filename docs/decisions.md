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

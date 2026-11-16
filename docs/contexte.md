# Contexte du projet

Dernière mise à jour : 2026-11-15.

## Objectif

Créer une application Android pour piloter un rameur d’intérieur ProForm
Sport RL via Bluetooth LE. On explore d’abord le protocole sous Linux avec
Python et Bleak (`bleak/`), avant de l’utiliser dans `android/`.

## Étape actuelle

`bleak/02-connection.py` maintient la connexion avec le rameur (validé par
l’utilisateur le 2026-11-14) : initialisation de 25 écritures puis poll
alternant des messages complets `17`/`19`, sans confirmation ni commande de
résistance. Les trames viennent de QZ (qdomyos-zwift, branche Sport RL) et ont
été comparées octet par octet. Sans messages complets, le rameur coupe la
liaison environ 10 s après le dernier. Détails, historique des essais et
limites : [notes de protocole BLE](protocole-ble.md).

`bleak/03-telemetry.py` (réutilise `bleak/rower.py`) réassemble les
notifications en messages et décode les métriques. Validés par l’utilisateur
le 2026-11-14 après un essai réel : puissance (W), distance (m), résistance
(`unset` tant qu’elle n’a pas été réglée), cadence
(coups/min), nombre de coups, allure (s/500 m) et vitesse (trame `ff 11`).

`web/` propose un tableau de bord Vue 3 (CDN, sans projet Node.js), installable
comme PWA avec cache du shell hors ligne après une première visite en ligne.
Le service worker met en cache Vue depuis le CDN au mieux ; Bluetooth exige
toujours un navigateur compatible et HTTPS/localhost. L'application utilise
Web Bluetooth : même init et poll, toutes les métriques décodées par
`03-telemetry.py`, commandes start/pause/reprise/arrêt, déconnexion manuelle
et signalement des valeurs non actualisées. Les tests JavaScript comparent les
écritures à `bleak/rower.py` et simulent les transitions de séance en GATT.
Un verrou d'écran est demandé pendant une séance active, avec libération en
pause ou à la déconnexion ; les transitions sont testées avec des simulations.
L’affichage a été vérifié dans un navigateur avec des notifications simulées,
y compris l’absence de Web Bluetooth, l’échec du CDN et les valeurs périmées.
**La connexion web au vrai rameur n’a pas encore été testée.**
Conditions de lancement et limites : [guide web](../web/README.md).

`bleak/04-start-session.py` rejoue le démarrage de session d’iFit (message `17`
puis `0d` état 2) d’après l’analyse de `btsnoop_hci-start.log`. Par défaut c’est
un essai à blanc (lit l’état du rameur, octet 25 du message de 50 octets) ; il
n’envoie rien sans `--send`, et refuse de démarrer si le rameur n’est pas au
repos. **Essai réel du 2026-11-14 : validé** (bip, chrono démarré, état 1 → 2
dans le message de 50 octets, octet 25 avec notre poll ; depuis la pause, le
chrono reprend sans remise à zéro). Après correction de l’octet d’état,
`--send` sans `--force` depuis le repos démarre le chrono (confirmé le soir
même) ; `--force` n’est plus nécessaire.

`bleak/05-start-pause-stop.py` enchaîne start, pause 10 s plus tard, reprise
10 s plus tard, puis arrêt (pause, `0d` état 4, retour au repos) 10 s plus
tard, en confirmant chaque état avant de continuer. **Cycle complet validé par
l’utilisateur le 2026-11-14 sur le rameur** : chrono démarré, mis en pause à
11 s, repris, arrêté avec 3 bips de fin de session ; états 2, 3, 2, 3, 4, 1 lus
à l’octet 25 du message de 50 octets. Détails : notes de protocole.
`03-telemetry.py` et `04` partagent désormais
l’assembleur de messages de `bleak/rower.py`.

`bleak/08-set-resistance.py` reprend la table QZ Sport RL des niveaux 1 à 24,
avec dry-run par défaut, garde-fou sur l’état repos et confirmation par
télémétrie. **Niveau 12 validé sur le rameur le 2026-11-15** : la résistance
était initialement non réglée (`raw=0x00`); après l’envoi, la télémétrie a
rapporté `raw=0x13`, soit le niveau 12. Le premier essai avait échoué au scan
car le rameur était éteint; aucune écriture n’avait alors été faite. Détails :
notes de protocole.

## Acquis vérifiés

- `web/sessions.js` enregistre les séances et les échantillons horodatés dans
  IndexedDB, avec `synced: false`, pauses/reprises, fin et interruption.
  Le résumé conserve les maxima des compteurs cumulatifs, la puissance moyenne
  rapportée, la cadence moyenne échantillonnée et l’allure/vitesse calculées;
  les trames de fin remettant les compteurs à zéro ne les écrasent pas. Les
  échantillons temps réel restent stockés, sauf le taux calorique dérivé et le
  champ `averagePace` non vérifié. Les moyennes calculées sont omises pour les
  séances partielles.
  `web/sessions.test.js` couvre le cycle et les erreurs de stockage. IndexedDB,
  la persistance après rechargement et `synced` ont été vérifiés dans le navigateur
  avec des données et réponses Google simulées.
- `web/google-health.js` exporte les séances achevées vers Google Health v4 :
  totaux, durée active, pauses/reprises et statistiques supplémentaires en notes.
  Connexion optionnelle Google Identity Services ; jeton en mémoire, renouvelé
  par clic après expiration, sans secret ni refresh token côté navigateur.
  Les séries brutes restent locales (écriture non prise en charge par Google).
  **Consentement et envoi réel non validés** ; configurer les origines JavaScript
  OAuth et révoquer les secrets exposés dans les fichiers locaux. Sources,
  prérequis et limites : `web/README.md`, tests : `web/google-health.test.js`.
- `web/intervals-icu.js` exporte les séances terminées comme activités manuelles
  `Rowing` : horodatage local, durée écoulée/active, distance, calories,
  puissance/cadence moyennes et autres métriques en description. L’identifiant
  externe Radrower sert à retrouver une activité après un envoi ambigu ; les
  échecs distants ne suppriment pas la séance locale. Clé API saisie à la demande
  et gardée en mémoire seulement; Google et intervals.icu ont des états de
  synchronisation séparés. Les échantillons bruts restent locaux. Tests simulés :
  `web/intervals-icu.test.js`. **Clé réelle, CORS et envoi réel non validés**;
  la clé est sensible et doit être utilisée uniquement sur un navigateur/appareil
  de confiance. Voir `web/README.md` et `docs/decisions.md`.
  Les 36 tests JavaScript web passent.

- Environnement : adaptateur Bluetooth et `bluetoothctl` 5.72 disponibles ;
  Bleak fonctionne (`poetry` dans `bleak/`) ; le rameur s’annonce `I_RW`.
- Services GATT, UUID, cadrage des messages (`fe 02 …` / `00 12` / `ff …`) et
  poll d’iFit : voir les notes de protocole.
- Sans session démarrée, les polls renvoient déjà les métriques validées
  ci-dessus (offsets dans les notes de protocole).

## Hypothèses et inconnues

- Le sens des échanges d’init (`08`, `0a`, `2c`, `17`) est inconnu.
- Champs non décodés : octets 15–16 de la trame `ff 11`, compteurs et valeur
  24 bits du message de 50 octets. Les offsets 46–48 de ce message (coups,
  cadence du 750R chez QZ) restent à 0 avec le poll `17`/`19`.
- L’état du rameur suit les écritures `ff 0d …` d’iFit : 1 repos, 2 en cours,
  3 pause, 4 terminé (octet 21 du message de 50 octets avec le poll d’iFit,
  octet 25 avec le nôtre). Start, pause, reprise, fin et retour au repos ont été envoyés par `05-start-pause-stop.py` et validés.
- Cadence minimale de poll tolérée, utilité de chaque échange d’init et état
  du voyant iFit pendant la connexion : à vérifier.

## Blocages

Aucun.

## Prochaine étape

Distance : décodage confirmé en direct (`07-distance.py`) ; l’écart de 1,6 des relevés de
`session-4` correspond à 1,609 (km → miles) : console en miles à ce moment (confirmé). Les autres
champs sont décodés (voir les notes de protocole) ; `03-telemetry.py` les affiche
et reste à vérifier en direct sur le rameur (temps, calories, cals/hr calculées,
puissance moyenne, durée de pause, allure moyenne). Restent inconnus : octets 16,
38-39, 42 (constants) ; 19 et 20 sont des drapeaux de l’historique depuis le branchement, messages `0c`, durée d’inactivité avant l’état 10 (probable fin automatique de session). Ensuite, chercher à simplifier l’init
et les messages par essais, puis intégrer dans l’application Android et tester
la connexion, les métriques et les commandes de `web/` sur le vrai rameur.
Ne pas envoyer de commande modifiant le rameur
(résistance, start/stop) sans accord explicite.

## Documents associés

- [Guide de scan avec bluetoothctl](../bluetoothctl/README.md) : procédure
  documentée en anglais, pas encore testée avec le rameur.
- [Notes de protocole BLE](protocole-ble.md)
- [Décisions du projet](decisions.md)
- [Tableau de bord Web Bluetooth](../web/README.md)

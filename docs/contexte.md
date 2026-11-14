# Contexte du projet

Dernière mise à jour : 2026-11-14.

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
(`unset` tant qu’elle n’a pas été changée sur le rameur), cadence
(coups/min), nombre de coups, allure (s/500 m) et vitesse (trame `ff 11`).

`web/` propose maintenant un tableau de bord Vue 3 (CDN, sans projet Node.js)
avec Web Bluetooth : même init et poll, sept métriques, déconnexion manuelle
et signalement des valeurs non actualisées. Les 11 tests JavaScript passent
(dont comparaison de toutes les écritures à `bleak/rower.py` et GATT simulé).
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

## Acquis vérifiés

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
19, 20, 38-39, 42, état 10, messages `0c`. Ensuite, chercher à simplifier l’init
et les messages par essais, puis intégrer dans `web/` ou l’application Android
(et tester `web/` sur le vrai rameur).
Ne pas envoyer de commande modifiant le rameur
(résistance, start/stop) sans accord explicite.

## Documents associés

- [Guide de scan avec bluetoothctl](../bluetoothctl/README.md) : procédure
  documentée en anglais, pas encore testée avec le rameur.
- [Notes de protocole BLE](protocole-ble.md)
- [Décisions du projet](decisions.md)
- [Tableau de bord Web Bluetooth](../web/README.md)

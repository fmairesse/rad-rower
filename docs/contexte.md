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

## Acquis vérifiés

- Environnement : adaptateur Bluetooth et `bluetoothctl` 5.72 disponibles ;
  Bleak fonctionne (`poetry` dans `bleak/`) ; le rameur s’annonce `I_RW`.
- Services GATT, UUID, cadrage des messages (`fe 02 …` / `00 12` / `ff …`) et
  poll d’iFit : voir les notes de protocole.
- Sans session démarrée, les notifications des polls portent déjà des valeurs
  qui évoluent avec les coups de rame ; le décodage reste à faire.

## Hypothèses et inconnues

- Le sens des échanges d’init (`08`, `0a`, `2c`, `17`) et des champs des
  notifications est inconnu ; les formules de QZ pour le Sport RL ne sont pas
  vérifiées sur notre rameur.
- Les écritures `fe 02 0d 02` / `ff 0d …` sont corrélées aux changements
  d’état d’iFit (start, stop, résistance) mais n’ont jamais été envoyées.
- Cadence minimale de poll tolérée, utilité de chaque échange d’init et état
  du voyant iFit pendant la connexion : à vérifier.

## Blocages

Aucun.

## Prochaine étape

Enregistrer les notifications de `02-connection.py` pendant des coups de rame
dont le nombre et l’instant sont notés, puis les comparer aux champs observés
et aux formules de QZ pour décoder cadence, puissance, distance et résistance.
Ne pas envoyer de commande modifiant le rameur (résistance, start/stop) sans
accord explicite.

## Documents associés

- [Guide de scan avec bluetoothctl](../bluetoothctl/README.md) : procédure
  documentée en anglais, pas encore testée avec le rameur.
- [Notes de protocole BLE](protocole-ble.md)
- [Décisions du projet](decisions.md)

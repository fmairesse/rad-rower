# Contexte du projet

Dernière mise a jour : 2026-11-08.

## Objectif

Créer une application Android pour piloter un rameur d’interieur Proform
Sport RL via Bluetooth LE.

## Etape actuelle

Analyser une capture BTSnoop d’une session iFit sur Android, puis corréler les
échanges observés aux actions de l’utilisateur avant toute expérience active.

## Acquis verifies

- Le workspace contient les dossiers `android/` et `bleak/`.
- L’utilisateur indique débuter en BLE et souhaite progresser dans son
  utilisation de l’IA.
- Une documentation de contexte a été initialisée pour être versionnée avec
  le projet.
- La CLI BlueZ `bluetoothctl` est disponible dans `/usr/bin/bluetoothctl`.
  Vérification du 2026-11-01 : `command -v bluetoothctl` et
  `bluetoothctl --version` (version 5.72). Aucun scan ni connexion sous Linux
  n’a encore été effectué.
- Une capture `bugreport/btsnoop_hci.log` d’une session iFit a été fournie
  depuis un Google Pixel 3a sous Android 12. Elle contient une connexion BLE
  réussie et des échanges ATT/GATT ; voir les observations dans
  [les notes de protocole BLE](protocole-ble.md).
- Une deuxième capture horodatée, `bugreport/btsnoop_hci-202611081036.log`,
  a été rapprochée des actions iFit. Une écriture candidate se corrèle à la
  pause, la reprise, aux résistances 2/3 et à l’arrêt ; son sens reste à
  vérifier dans les notes de protocole.
- Une capture ciblée `bugreport/btsnoop_hci-start.log` associe plus
  précisément le même couple d’écritures aux actions start/stop/end d’iFit.
  Les payloads sont candidats seulement ; leur sémantique n’est pas décodée.
- `bleak/main.py` s’abonne maintenant aux notifications de la caractéristique
  `00001535-1412-efde-1523-785feabcd123` et affiche leur heure locale et les
  octets bruts en hexadécimal. Après confirmation `y`, il envoie aussi la
  première paire d’écritures d’initialisation capturée sur `0x000e`. Ce code
  compile ; un essai a allumé brièvement le voyant iFit et reçu trois
  notifications, puis le voyant s’est éteint alors que la liaison BLE est
  restée active quelques secondes. La cause n’est pas établie.
- La capture montre des écritures répétées vers la caractéristique `0x000e`
  et des notifications sur `0x000b`. La signification des payloads et leur
  correspondance avec les actions de la session restent inconnues.

## Hypotheses et inconnues

- Les services et caractéristiques observés sont consignés dans les notes de
  protocole ; le sens des commandes et le format des métriques restent
  inconnus. Ne pas déduire une commande de pilotage de la seule forme des
  payloads.
- La disponibilité de l’adaptateur Bluetooth, le fonctionnement du service
  BlueZ, ainsi que la disponibilité de Python et de Bleak restent à vérifier.
- Les besoins d’appairage et les possibilités réelles de pilotage restent à
  déterminer expérimentalement.

## Blocages

Aucun blocage constaté à ce stade ; l’environnement BLE reste à vérifier.

## Prochaine étape

Déterminer si la connexion BLE reste active après l’extinction du voyant et
analyser la suite des échanges d’initialisation iFit. Ne pas ajouter ni envoyer
d’autres payloads sans accord explicite.

## Documents associés

- [Guide de scan avec bluetoothctl](../bluetoothctl/README.md) : procédure
  documentée en anglais, pas encore testée avec le rameur.
- [Notes de protocole BLE](protocole-ble.md)
- [Decisions du projet](decisions.md)

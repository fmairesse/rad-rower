# Contexte du projet

Dernière mise a jour : 2026-11-08.

## Objectif

Créer une application Android pour piloter un rameur d’interieur Proform
Sport RL via Bluetooth LE.

## Etape actuelle

La capture iFit sans entraînement confirme l’émission des échanges `18`/`19`
en mode connecté : 190/193 occurrences, avec des intervalles médians de
0,677/0,676 s. Cela ne prouve pas que ces trames maintiennent la connexion.
L’essai du 23:38 a exécuté les 14 étapes de la continuation idle ; son btmon
est tronqué. Les essais périodiques du 09:00 et du 22:30 ont tous deux fini
par une déconnexion distante HCI `0x13`. Dans le 22:30, la boucle seule a
envoyé 14 `19` et 13 `18`; le rameur coupe la liaison 9,99 s après le dernier
burst du setup, juste avant le prochain `18`. La cause applicative reste
inconnue.

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
  octets bruts en hexadécimal. Après confirmation `y`, il envoie les paires
  d’initialisation `08`, puis la paire `0b` seulement si les deux notifications
  du troisième échange `08` arrivent et que la liaison reste active. Lors de
  l’essai du 2026-11-08, la paire `0b` a déclenché trois notifications, puis
  une quatrième environ 45 ms plus tard. Le rameur a néanmoins coupé la
  liaison (raison HCI `0x13`) environ 10 s après cette dernière notification.
  Le script propose une continuation idle optionnelle jusqu’aux échanges
  `17`/`11`/`10`, avec une confirmation distincte avant l’envoi. Le test du
  23:38 a confirmé les 37 écritures et les bursts des 14 étapes, mais son
  btmon était tronqué. Les tests de 09:00 et 22:30 ont tous deux obtenu un
  disconnect distant (`0x13`) avant 15 s. Le 22:30 a transmis 14 `19` et 13
  `18` dans la boucle seule, puis le rameur a coupé la liaison ~5 ms avant le
  prochain `18`, 9,99 s après le dernier burst du setup.
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

`18`/`19`, puis faire correspondre l’écriture `18` affichée avec le dernier
événement HCI. Ne pas répéter le test avant d’avoir décidé comment poursuivre ;
Analyser pourquoi le disconnect distant survient environ 10 s après le dernier
burst du setup malgré les échanges périodiques `18`/`19`. Ne pas répéter le
test avant d’avoir une nouvelle hypothèse vérifiable ; la cause applicative du
motif HCI `0x13` reste inconnue.

## Documents associés

- [Guide de scan avec bluetoothctl](../bluetoothctl/README.md) : procédure
  documentée en anglais, pas encore testée avec le rameur.
- [Notes de protocole BLE](protocole-ble.md)
- [Decisions du projet](decisions.md)

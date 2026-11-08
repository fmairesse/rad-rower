# Contexte du projet

Dernière mise a jour : 2026-11-08.

## Objectif

Créer une application Android pour piloter un rameur d’interieur Proform
Sport RL via Bluetooth LE.

## Etape actuelle

La capture iFit sans entraînement confirme l’émission des échanges `18`/`19`
en mode connecté : 190/193 occurrences, avec des intervalles médians de
0,677/0,676 s. La liaison est restée active jusqu’à une déconnexion locale
après environ 135 s de trafic observé. Cela ne prouve pas que ces trames
maintiennent la connexion. Le script propose aussi le handshake idle capturé
`0a`/`08`/`2c`, derrière une confirmation distincte. Un essai réel a reçu les
bursts attendus (3/2/2). Un second essai a envoyé 18 échanges `18`/`19` à
~0,37 s d’intervalle, puis le rameur a terminé la liaison (raison HCI `0x13`)
environ 0,18 s après la dernière notification, avant les 15 s prévues. La
cause applicative reste inconnue.

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
  Le script propose maintenant, avec une confirmation distincte, un essai de
  15 s des échanges périodiques candidats `19`/`18`. Lors du premier essai,
  les étapes `0a`/`08`/`2c` ont produit les bursts attendus ; le test périodique
  s’est arrêté après 12 échanges et le btmon était tronqué. Le second essai
  confirme la déconnexion distante (`0x13`) après 18 échanges, avant les 15 s.
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

Comparer les écritures intercalées du flux iFit sans entraînement à celles du
test limité à `18`/`19` pour identifier ce qui manque avant toute nouvelle
transmission. Ne pas attribuer un rôle de heartbeat ni une cause applicative
au disconnect `0x13` sans preuve supplémentaire.

## Documents associés

- [Guide de scan avec bluetoothctl](../bluetoothctl/README.md) : procédure
  documentée en anglais, pas encore testée avec le rameur.
- [Notes de protocole BLE](protocole-ble.md)
- [Decisions du projet](decisions.md)

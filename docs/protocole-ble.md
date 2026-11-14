# Notes de protocole BLE

Dernière mise à jour : 2026-11-14.

Abréviation : « QZ » = projet qdomyos-zwift (dossier `qdomyos-zwift/`, code du
rameur dans `src/devices/proformrower/proformrower.cpp`).

## Sources

- Captures HCI BTSnoop d’iFit (Google Pixel 3a, Android 12), dans `bugreport/`,
  non versionnées (elles contiennent des identifiants Bluetooth) :
  `btsnoop_hci.log`, `btsnoop_hci-202611081036.log`, `btsnoop_hci-start.log`
  (start/stop/end), `btsnoop_hci-only-connect.log` (connexion seule, ~134 s),
  `btsnoop_hci-only-connect2.log` (7 s), `btsnoop_hci-row-nostart.log` (coups
  de rame sans session). Décodées avec `btmon`/`tshark` par des scripts
  ponctuels non conservés.
- Code de QZ (source externe, branche « Sport RL »).
- Essais Bleak sous Linux : `bleak/01-connection.py` (essais successifs,
  historique) et `bleak/02-connection.py` (script de référence ; le code
  partagé de connexion, d’init et de poll est dans `bleak/rower.py`) et
  `bleak/03-telemetry.py` (lecture des métriques).

## Services GATT (capture iFit)

Pour les UUID 128 bits, la représentation canonique est l’inversion des
16 octets de l’UUID transporté dans les PDU ATT (même résultat que Bleak ;
`uuid.UUID(bytes_le=...)` ne convient pas).

| Plage de handles du service | UUID du service | Handle valeur | UUID caractéristique | Propriétés observées |
| --- | --- | --- | --- | --- |
| `0x0001-0x0007` | `0x1800` | `0x0003` | `0x2a00` | Read, Write |
| `0x0001-0x0007` | `0x1800` | `0x0005` | `0x2a01` | Read |
| `0x0001-0x0007` | `0x1800` | `0x0007` | `0x2a04` | Read |
| `0x0008-0x0008` | `0x1801` | — | — | — |
| `0x0009-0x000e` | `00001533-1412-efde-1523-785feabcd123` | `0x000b` | `00001535-1412-efde-1523-785feabcd123` | Read, Notify |
| `0x0009-0x000e` | `00001533-1412-efde-1523-785feabcd123` | `0x000e` | `00001534-1412-efde-1523-785feabcd123` | Read, Write |
| `0x000f-0x0016` | `00001530-1212-efde-1523-785feabcd123` | `0x0011` | `00001532-1212-efde-1523-785feabcd123` | Write Without Response |
| `0x000f-0x0016` | `00001530-1212-efde-1523-785feabcd123` | `0x0013` | `00001531-1212-efde-1523-785feabcd123` | Write, Notify |
| `0x000f-0x0016` | `00001530-1212-efde-1523-785feabcd123` | `0x0016` | `00001534-1212-efde-1523-785feabcd123` | Read |
| `0x0017-0xffff` | `00001400-555e-e99c-e511-f9f4f8daeb24` | `0x0019` | `00001401-555e-e99c-e511-f9f4f8daeb24` | Read, Write |
| `0x0017-0xffff` | `00001400-555e-e99c-e511-f9f4f8daeb24` | `0x001b` | `00001402-555e-e99c-e511-f9f4f8daeb24` | Read, Notify |
| `0x0017-0xffff` | `00001400-555e-e99c-e511-f9f4f8daeb24` | `0x001e` | `00001403-555e-e99c-e511-f9f4f8daeb24` | Read, Write |
| `0x0017-0xffff` | `00001400-555e-e99c-e511-f9f4f8daeb24` | `0x0020` | `00001404-555e-e99c-e511-f9f4f8daeb24` | Read, Notify |

Bleak affiche `00001400-555e-e99c-e511-f9f4f8daeb24` comme `HDP` à partir de ses
16 premiers bits ; les autres champs ne suivent pas l’UUID de base Bluetooth,
donc ce libellé ne prouve pas un vrai Health Device Profile. Aucun Fitness
Machine Service (`0x1826`) n’a été observé.

Le rameur n’est utilisé que par le service `00001533-…` : commandes écrites sur
`…1534-…` (handle `0x000e`), notifications sur `…1535-…` (handle `0x000b`,
CCCD `0x000c`, écrire `01 00`). Les autres services n’ont pas servi.

## Format des messages

Observé sur toutes les écritures et notifications (captures iFit, essais
Bleak) :

- trame d’en-tête : `fe 02 <longueur totale> <nombre de trames, en-tête inclus>` ;
- trames de données : `00 12 <18 octets>`, `01 12 <18 octets>`, … ;
- dernière trame : `ff <longueur restante> <octets>`.

Exemple : `fe 02 19 03` annonce 25 octets = 18 (`00 12`) + 7 (`ff 07`). La
longueur annoncée correspond toujours au `ff <n>` final. Les trames font
20 octets (4 pour l’en-tête). QZ réassemble les notifications avec le même
cadrage (et vérifie une somme de contrôle, non vérifiée ici).

Les trames d’un message sont écrites dans l’ordre, avec réponse ATT
(`response=True`). Un message sans sa trame finale `ff` ne réarme pas le
watchdog du rameur (voir ci-dessous).

## Connexion maintenue (validé le 2026-11-14)

**Fait observé** : `bleak/02-connection.py` maintient la connexion avec le
rameur ; l’utilisateur a validé l’essai (durée et paramètres exacts non
consignés). Le script n’envoie ni confirmation, ni commande de résistance, ni
échanges `0b`, `18`, `10`, `11`.

Séquence (même contenu et même ordre que QZ pour le Sport RL ; chaque trame a
été comparée octet par octet au code de QZ) :

1. activer les notifications (CCCD `0x000c`) ;
2. initialisation, 25 écritures : `08` ×3 (`ff08…8187`, `…8098`, `…88a0`),
   `0a` ×2 (`…8200008a`, `…8400008c`), `08` (`…959b`), `2c` (4 trames), `17`,
   `19` ×2. Le script attend après chaque écriture un silence du rameur de
   150 ms (max 1,5 s) ; QZ attend 400 ms fixes ;
3. poll : messages complets (3 trames) en alternance `17` puis `19`, un message
   toutes les 0,6 s par défaut (chaque type toutes les 1,2 s). QZ envoie le
   même cycle, une trame toutes les 200 ms (≈ 1,2 s par cycle).

QZ fait donc lui aussi une longue initialisation (≈ 10 s) avant le poll. Elle
n’a pas été raccourcie : le rôle de chaque échange est inconnu.

**Watchdog d’environ 10 s** : chaque fois que le client cesse d’écrire, le
rameur coupe la liaison (raison HCI `0x13`, côté distant) 9,99 à 10,03 s après
la dernière notification. Des messages complets réarment ce délai ; des
messages incomplets ne le font pas.

| Date | Écritures envoyées | Résultat |
| --- | --- | --- |
| 2026-11-08 | `08` seul, puis `08` ×2, ×3, puis `0b` | bursts de notifications attendus (3, 3, 2, puis 3 + 1 pour `0b`), coupure ~10 s après la dernière notification |
| 2026-11-08 à 11-10 | init complète + `18`/`19` **incomplets** (sans trame `ff`), 0,37 s | coupure `0x13` avant 15 s (≈ 10 s après la fin de l’init) |
| 2026-11-11 | init partielle + `18`/`19` **complets**, 0,37 s, 15 s | boucle terminée ; coupure 10,03 s après l’arrêt des échanges |
| 2026-11-11 | idem, 0,75 s entre messages (chaque type toutes les 1,5 s), 60 s | liaison maintenue 60 s ; coupure 9,99 s après l’arrêt |
| 2026-11-14 | `02-connection.py` : init QZ + `17`/`19` complets | connexion maintenue (validé par l’utilisateur) |

Les ATT Write Responses prouvent la réception, pas l’acceptation applicative.
Les `17`/`19` (QZ) comme les `18`/`19` (iFit) suffisent comme poll.

À mesurer : cadence minimale tolérée (0,6 s par message testé ; l’écart entre
deux messages complets doit rester < 10 s), nécessité réelle de chaque échange
d’init, et état du voyant iFit pendant la connexion.

### Poll d’iFit (capture connexion seule)

1 175 écritures vers `0x000e` et 1 364 notifications en ~134 s. Après la
même init, iFit envoie en boucle `19` (193 fois) et `18` (190 fois), médiane
0,676 s par type, chaque trame longue suivant son en-tête de 0,08–0,09 s. C’est
iFit qui termine la liaison (raison `0x16`), souvent après des minutes de poll.
`fe 02 0c 02` (toutes les 0,86–1,1 s) apparaît aussi dans les captures avec
session ; sens inconnu.

## Rôle des échanges (hypothèses)

| En-tête | Usage observé | Sens |
| --- | --- | --- |
| `08`, `0a`, `0b`, `2c` | initialisation, une fois | inconnu (`2c` = 44 octets) |
| `17`, `18`, `19` | poll périodique ; la réponse est une série de notifications | métriques (voir ci-dessous) ; correspondance poll → réponse non établie |
| `10`, `11` | fin de l’initialisation d’iFit | inconnu |
| `0d` | changements d’état d’iFit (start, stop, pause, reprise, résistance) | voir plus bas |

## Notifications et métriques (2026-11-12 à 2026-11-14)

Les polls produisent des notifications ; il n’y a pas de notification
spontanée entre deux polls. Aucune session n’est nécessaire : sans rame les
valeurs restent à 0, avec des coups de rame elles évoluent.

**Messages de réponse** (réassemblés comme décrit plus haut). Avec le poll
`17`/`19` de `02-connection.py`/`03-telemetry.py`, on reçoit en alternance :

- un message de 50 octets (`fe 02 32 04`, premier octet de données
  `01 04 02 2e 14 2e 02 02`) ;
- une trame isolée `ff 11 00 00 00 00 00 00 00 00 …` (message de 17 octets).

Un message de 33 octets (`fe 02 21 03`, `01 04 02 1d 14 1d 02 02`) apparaît
dans les captures iFit et dans les essais avec poll `18`/`19`, mais pas avec
`17`/`19`. La correspondance exacte poll → réponse n’est pas établie. Les octets
4 à 19 d’une trame d’en-tête de notification répètent des données périmées et
sont ignorés. Dans les messages de 50 octets, le dernier octet est la somme des
octets 4 à avant-dernier, modulo 256.

**Valeurs validées par l’utilisateur (2026-11-14)** (essai réel avec
`03-telemetry.py` ; méthode de comparaison non détaillée) :

| Grandeur | Source | Offset | Remarque |
| --- | --- | --- | --- |
| puissance (W) | message de 50 octets, corps | 10–11 LE | |
| distance (m) | message de 50 octets, corps | 12–13 LE | |
| résistance (niveau 1–24) | message de 50 octets, corps | 9, table de QZ | l’octet vaut 0 tant que la résistance n’a pas été changée sur le rameur (niveau inconnu) ; 1 après réglage |
| cadence (coups/min) | trame `ff 11` | 12 | |
| nombre de coups | trame `ff 11` | 10–11 LE | trouvé sur notre rameur, absent de QZ ; 36 → 37 sur l’essai suivant |
| allure (s pour 500 m) | trame `ff 11` | 13–14 LE | 275 s = 4:35 ; 330 s = 5:30 |
| vitesse (km/h) | calculée | 1800 / allure | formule de QZ, 6,55 et 5,45 km/h |

Résultat inattendu : avec le poll `17`/`19`, les offsets 46–48 du message de
50 octets (coups et cadence du 750R chez QZ) valent toujours 0, alors que le
décodage hors ligne de la capture iFit `row-nostart` (poll `18`/`19`) y trouvait
des valeurs non nulles (coups 2 à 4, cadence 18 puis 12). Hypothèse : le contenu
de la réponse dépend de la requête de poll (les écritures `17` et `18` diffèrent).
Avec `17`/`19`, la cadence vient de la trame `ff 11`.

**Trame `ff 11`** : `ff 11`, 8 octets à 0, puis nombre de coups (octets 10–11),
cadence (12), allure en s/500 m (13–14), deux octets non décodés (15, 16 : vus
`bd 00`, `be 00`, `07 01`, `09 01`), un octet à 0 (17), une somme de contrôle (18 :
somme des octets 10 à 17 plus 0x2f, modulo 256 ; vérifiée sur 4 trames) et un
octet à `ff` (19).

Autres champs du message de 50 octets, non décodés : trois compteurs égaux (corps
20, 25, 29–30) qui augmentent d’environ 1 par seconde (temps ?), une valeur
24 bits LE croissante (corps 34–36), un octet qui décroît lentement (corps 40).

Prochaine expérience : décoder les champs restants (octets 15–16 de `ff 11`,
compteurs et valeur 24 bits du message de 50 octets) en les comparant à
l’affichage du rameur (temps, calories…).

## Écritures d’état `fe 02 0d 02` (non rejouées)

Les captures iFit `…202611081036.log` et `…start.log` associent le couple
`fe 02 0d 02` puis une écriture de 20 octets `ff 0d …` (moins de 300 ms plus
tard) aux actions start, stop, end, pause, reprise et résistance 2 et 3 (0,3 à
0,6 s après l’action ; une occurrence 2,2 s avant le start). Aucune occurrence
pour un réglage manuel à 24 sur le rameur ni pour les coups de rame.

Payloads de la capture `start` : start
`ff0d020402091409020200100200330000000000`, stop `…100300340000000000`, end
`…100400350000000000`, puis `…100100320000000000` après la fin. Le dernier
octet non nul semble être une somme de contrôle (hypothèse).

**Information externe (QZ)** : pour régler la résistance 1–24, QZ envoie
`fe 02 0d 02` puis un `ff 0d 02 04 02 09 14 09 02 01 04 …` dont quelques octets
varient selon le niveau (niveau 1 : `76 01 00 9b`, niveau 24 : `e6 26 00 30`).
Ce format diffère de celui d’iFit ci-dessus. Ne jamais envoyer ces écritures
sans accord explicite et protocole d’essai convenu.

## Règles de consignation

- Séparer les observations brutes de leur interprétation.
- Indiquer la date, les conditions de l’essai et le script utilisé.
- Conserver les valeurs et UUID exacts lorsqu’ils ont été observés.
- Étayer une interprétation par une expérience reproductible ou une source.
- Identifier les informations externes par leur source et leur modèle cible.
- Masquer les identifiants personnels du matériel dans les traces versionnées.
- Obtenir un accord explicite avant une commande modifiant le comportement
  du rameur ; documenter alors les précautions prises.

## Modèle d’observation

```markdown
### Date - Nom de l’expérience

- Objectif :
- Environnement et conditions :
- Script et révision Git, si disponible :
- Manipulations effectuées :
- Observations et données brutes :
- Interprétation ou hypothèse :
- Vérification réalisée et limites :
- Source externe, si applicable :
- Prochaine expérience :
```

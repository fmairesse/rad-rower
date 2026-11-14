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
  `bleak/03-telemetry.py` (lecture des métriques) et `bleak/04-start-session.py`
  (démarrage de session ; essai réel du 2026-11-14, voir plus bas) et
  `bleak/05-start-pause-stop.py` (start, pause, reprise, arrêt ; essai réel
  validé le 2026-11-14).
- Portage Web Bluetooth : `web/protocol.js` (trames et décodage),
  `web/rower.js` (connexion et poll). Tests sans matériel dans
  `web/protocol.test.js` et `web/rower.test.js` ; toutes les trames écrites
  sont comparées à `bleak/rower.py`. **Pas encore validé sur le vrai rameur.**

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

Le tableau de bord `web/` reprend ces offsets et la table de résistance du
script Python. Il rejette avec avertissement les messages réassemblés dont
la somme de contrôle est incorrecte (le script Python les affiche avec
`checksum=BAD`). La formule provisoire de somme de contrôle de `ff 11`
n’est pas imposée : même reconnaissance que dans le script Python. Les tests
GATT et les essais d’interface utilisent des notifications synthétiques ;
ils ne constituent pas une nouvelle validation matérielle du protocole.

Autres champs du message de 50 octets, non décodés : trois compteurs égaux (corps
20, 25, 29–30) qui augmentent d’environ 1 par seconde (temps ?), une valeur
24 bits LE croissante (corps 34–36), un octet qui décroît lentement (corps 40).

Prochaine expérience : décoder les champs restants (octets 15–16 de `ff 11`,
compteurs et valeur 24 bits du message de 50 octets) en les comparant à
l’affichage du rameur (temps, calories…).

## État du rameur et commande de démarrage (analyse du 2026-11-14)

Analyse de `btsnoop_hci-start.log` (50 min, 13 connexions iFit, 6 démarrages ;
script d’analyse ponctuel non conservé, rejeu partiel via `04-start-session.py`).

**Fait observé (capture iFit, poll `18`/`19`)** : l’octet 21 du corps du
message de 50 octets (`01 04 02 2e …`, offset de `03-telemetry.py`) donne
l’état du rameur : 1 = repos, 2 = en cours, 3 = en pause, 4 = terminé.
**Avec notre poll `17`/`19`, la disposition est différente** (voir « Essai réel »
plus bas) : ne pas appliquer ces offsets à `03-telemetry.py`/`04-start-session.py`. Il change 0,3 à 1,1 s après
chaque écriture `0d` d’iFit, selon son dernier octet de données : `02` →
2 (start), `03` → 3 (stop), `04` → 4 (end), `01` → 1 (retour au repos), `0d`
(vu une fois, après un stop) → 2. L’octet 22 est un compteur qui monte
d’environ 1 par seconde en état 2 et reste figé en état 3 et 4 ; il revient à 0
en état 1 (hypothèse : temps écoulé en s). Un start depuis l’état 1 repart de
0 ; depuis l’état 3, le compteur continue (vu : 17 → 17, puis 37 → 38 après
une pause). Quand la liaison tombe pendant une session, la session reparaît en
pause à la connexion suivante.

**Séquence d’iFit pour démarrer** (6 occurrences, toujours identiques, depuis
l’état 1 ou 3) :

1. message `17` avec l’octet d’indicateur à 0 : `fe 02 17 03`,
   `00 12 02 04 02 13 14 13 02 0c 00…`, `ff 05 00 80 00 00 b5 00…`
   (l’init d’iFit et de QZ envoie la même trame avec `80 01 00 b6`) ;
2. 0,1 à 0,4 s plus tard : `fe 02 0d 02`, puis
   `ff 0d 02 04 02 09 14 09 02 02 00 10 02 00 33 00…` ;
3. environ 0,4 s plus tard, iFit envoie en boucle (≈ 1 Hz) `fe 02 0c 02` +
   `ff 0c 02 04 02 08 14 08 02 00 02 00 10 30 00…` (une variante `…00 02 22` une
   fois, 1 à 3 s après le début de la boucle). Ce message est aussi envoyé sans start dans certaines
   connexions ; son rôle est inconnu et `04-start-session.py` ne l’envoie pas.

À l’arrêt (iFit) : `0d` états `03` (stop), `04` (end), puis `17` avec
`80 01 00 b6` et `0d` état `01`. Octet de contrôle de ces `ff 0d` : somme des
5 octets de données (`02 00 10 <état> 00`) + `0x1f`, vérifiée sur 5 valeurs
d’état ; même constante pour la résistance de QZ (`01 04 76 01 00` → `9b`).
Dans une seule connexion (la première, avant le premier start), iFit envoie en
plus des messages `11` (×3) et `15` (`ff 03 02 00 0a`), jamais revus ensuite :
probablement sans lien avec le start.

**Validé par l’utilisateur le 2026-11-14** : `04-start-session.py --send --force`
(init et poll `17`/`19` de QZ, puis la séquence ci-dessus) fait biper le rameur
et démarrer son chrono. Donc le start est accepté avec le poll `17`/`19` et
sans les échanges `0b`, `10`, `11` d’iFit.

### Essai réel du 2026-11-14 (21:00-21:09, `04-start-session.py`, poll `17`/`19`)

**Corrige l’hypothèse précédente** : `--force` n’était nécessaire que parce que
le script lisait l’état au mauvais octet (21, valable pour la capture iFit).
Avec le poll `17`/`19`, le message de 50 octets (checksum correcte sur les 4
exemples) contient :

| Octet du corps | Valeur observée |
| --- | --- |
| 25 | **état** : 1 avant le start (rameur branché, chrono arrêté), 2 après le start (chrono en marche), 3 en pause (chrono arrêté après quelques coups de rame) |
| 21, 26, 30 | compteur de secondes (identique aux trois octets en marche : 1, 2, 3… ; 0x96 = 150 en pause) ; 30 reste à 0 en pause |
| 19 | 1 juste après le branchement, 0 ensuite (rôle inconnu) |
| 20 | 1 dans tous les exemples (rôle inconnu) |

Résultats (utilisateur : bip et chrono constatés sur le rameur) :

- sans `--send` : rien n’est envoyé, le chrono ne démarre pas (essais avec et
  sans `--force`) ;
- `--send --force`, rameur au repos : le message `17` puis `0d` état 2 fait
  passer l’état de 1 à 2 en 1,5 s ; le compteur monte d’environ 1 par seconde et le
  chrono du rameur continue après la fin du script (aucune commande d’arrêt
  n’est envoyée) ; le rameur passe en pause (3) quand on cesse de ramer ;
- même commande depuis l’état 3 : l’état repasse à 2 et le chrono **reprend sans
  se remettre à zéro** (compteur 150 → 151…), comme dans la capture iFit.

Les valeurs 4 (terminé) et 5, 7, 8… affichées par la première version du
script étaient le compteur de secondes, pas un état.

**Validation de la correction (2026-11-14, 21:19-21:20)** : sans option, le script
lit « idle (1), counter 0 » et n’envoie rien ; avec `--send` (sans `--force`), il
envoie le start, lit « running (2), counter 1 » 1,5 s plus tard, et le chrono du
rameur démarre (confirmé par l’utilisateur).

### Cycle complet validé (2026-11-14, 21:28-21:29, `05-start-pause-stop.py`)

Le script envoie, à 10 s d’intervalle, start, pause, reprise, puis pause + `0d`
état 4 + `17` (`80 01 00 b6`) + `0d` état 1, chaque envoi attendant l’état
attendu (octet 25). Trames : voir `START`, `PAUSE`, `END`, `IDLE` dans
`bleak/rower.py` (copiées de la capture iFit). Résultat confirmé par
l’utilisateur : le chrono démarre, se met en pause à 11 s, reprend, puis s’arrête
complètement à l’envoi du stop avec 3 bips (fin de session).

| Envoi | État lu (octet 25) | Compteur (octet 21) | Délai de confirmation |
| --- | --- | --- | --- |
| start | 2 | 0 | 1,6 s |
| pause | 3 | 11 | 0,7 s |
| reprise | 2 | 11 (continue) | 1,2 s |
| pause (stop) | 3 | 22 | 1,2 s |
| `0d` état 4 | **4 (terminé)**, compteur figé | 22 | 1,1 s |
| `17` + `0d` état 1 | 1 (repos) | revient à 0 | 1,6 s |

L’état 4 est donc visible avec notre poll `17`/`19`, au même octet 25 que les
autres états. Le retour au repos remet le compteur à 0. Octets 19 et 20 : voir le
tableau des champs plus bas (drapeaux liés à l’historique depuis le branchement).

### Décodage des champs restants (2026-11-14, `06-read-session.py`, poll `17`/`19`)

Sources (non versionnées, `bugreport/`) : `session-1.log` (60 s, 17 coups ; notes
de l’utilisateur : 4 cals à 22:02:00, 7 cals et 600 cals/hr à la fin),
`session-2.log` (cals relevées à 7 MARK, toutes les 15 s), `session-3.log`
(cals/hr, 7 MARK), `session-4.log` (temps et distance, 5 MARK à 20 s). Le script
joue un bip à chaque MARK et l’utilisateur note la valeur de la console. Les
relevés ont un décalage de lecture d’environ ±1 s par rapport aux messages.

Offsets du corps du message de 50 octets (`01 04 02 2e …`) :

| Offset | Contenu | Validation |
| --- | --- | --- |
| 21 (copies en 26 et 30) | temps écoulé en s, hors pauses ; 30 passe à 0 en pause | **validé** : console 22, 42, 56, 70, 90 s pour 21-22, 40-41, 54-56, 69-70, 88-89 |
| 34-37 (u32 LE) | calories : valeur / 100 000 = kcal ; nulle en pause | **validé** : les 5 relevés de `session-2` hors pause (console 2, 6, 9, 11, 15 pour 1,8-2,0 ; 5,1-5,5 ; 8,9-9,1 ; 11,1-11,6 ; 14,4-14,6) sont reproduits à ±1 près (arrondi et décalage de lecture), les 2 autres tombent en pause ; l’intégrale de cals/hr sur le temps de marche égale à la valeur dans `session-3` (rapport 1,00) |
| 10-11 (puissance) | cals/hr de la console = **3,2438 × watts + 174,66** | **validé** : 6 relevés de `session-3` (765, 820, 1378, 1326, 1229, 1106) reproduits à ±0,4 près, avec la puissance de l’octet 10 ; non calculé quand la puissance est 0 (console non relevée) |
| 8-9 | niveau de résistance × 416 (1248, 2496, 4992 pour 3, 6, 12) ; cohérent avec l’octet 9 de la table de QZ | cohérent avec le décodage existant, relevés de niveau non faits |
| 40 | puissance moyenne en W, sur le temps de marche | **fortement probable** : égale à la moyenne temporelle des octets 10 à ±3 W sur les sessions 2, 3 et 4 |
| 45 | durée cumulée des pauses en s (le rameur se met en pause seul quand on arrête de ramer) | **probable** : 12 puis 24 s et 6 puis 12 s, pauses relevées dans les logs 2 et 4 |
| 25 | état : 1, 2, 3, 4 comme avant ; vu **10** (0x0a) avant le start de `session-4`, juste au branchement, avec les restes d’une session précédente (temps 298 s sur 16 bits aux octets 30-31, calories ≈ 0,1) | **10 : hypothèse** : session arrêtée par le rameur après inactivité (voir ci-dessous) |
| 19 | 1 au premier message de `session-1` (rameur juste branché, jamais démarré), 0 dans tous les autres messages (459 analysés) | **drapeau** : probablement « pas encore démarré depuis le branchement », effacé au premier start |
| 20 | 1 depuis le branchement jusqu’au premier retour au repos (`0d` état 1), 0 ensuite, y compris pendant les sessions suivantes | **drapeau** : `session-1` (1 jusqu’à l’état 1 final), `session-2` (rameur déjà en marche à la connexion, 1 jusqu’à l’état 1 final), `session-3` et `session-4` (toujours 0) ; hypothèse : « aucune session terminée depuis le branchement » |
| 16, 38-39, 42 | constants sur 459 messages, 3 résistances différentes : `46` (70), `2c 01` (300), `b4` (180) | inconnu ; peut-être la configuration du rameur ou de l’utilisateur (hypothèse sans preuve) |
| 14-15, 17-18, 22-24, 27-29, 32-33, 37, 41, 43-44, 46-48 | toujours 0 sur 459 messages (les offsets 46-48 contiennent les coups et la cadence du 750R chez QZ) | inutilisés avec ce poll, ou jamais activés |
| 31 | octet haut du temps de l’octet 30 (1 une fois, avec 298 s) ; 22 et 27 (octets hauts de 21 et 26) restent à 0 | le temps passe de 255 s : à décoder en u16 aux octets 30-31 (et probablement 21-22, 26-27) |

`ff 11`, octets 15-16 (u16 LE) : allure moyenne en s/500 m, **fortement probable**.
Elle reste figée en pause alors que l’allure instantanée tombe à 0 et vaut
500 × temps / distance de l’octet 12 à quelques secondes près (113 contre 113,
144 contre 142 dans `session-4`).

**Distance : décodage confirmé.** Octets 12-13 (u16 LE), en mètres. Comparaison en
direct avec la console le 2026-11-14 (`07-distance.py`, valeurs « cohérentes » selon
l’utilisateur ; paires de valeurs non consignées). Les relevés de `session-4`
(console 60, 125, 139, 154, 200 m pour environ 100, 195, 214, 248, 325 dans le
message) sont à environ 0,62 × le message, soit 1 / 1,609 (facteur km → miles).
Confirmé par l’utilisateur : la console était alors en miles (unités
impériales). L’explication d’un relevé décalé ne tient pas (une lecture tardive
donnerait une valeur plus grande, pas plus petite).

**État 10** (récit de l’utilisateur, non journalisé) : un premier lancement du
script avait démarré le chrono, puis avait été tué avant d’envoyer la fin de
session ; l’utilisateur n’a plus touché au rameur, qui a arrêté son chrono seul ;
le lancement suivant (`session-4`) a lu l’état 10. Hypothèse : 10 = session
terminée automatiquement par le rameur après inactivité (différent de 3, pause
quand on arrête de ramer, et de 4, fin demandée). Le start envoyé ensuite a
quand même été accepté (running en 1,6 s, temps et calories repartis de 0). À
confirmer en laissant volontairement une session inactive en notant la durée
avant le changement d’état.

**En pause**, les octets de calories, distance, puissance et temps de marche
(30) passent à 0 ou restent périmés dans le message, alors que la console les
garde : seuls le temps (21, 26), la puissance moyenne (40), la durée de pause
(45) et l’allure moyenne restent lisibles. Il faut donc intégrer les valeurs
pendant la marche pour retrouver les calories ou la distance après une pause.

**Autres observations** : le rameur passe seul en pause (état 3) quelques
secondes après l’arrêt des coups et revient seul en marche (2) quand on rame
de nouveau, même après notre pause ; il démarre aussi seul une session quand
on rame. Le message de 35 octets (`01 04 02 1f`) recopie le même `ff 11`. Deux
messages de 35 octets (`session-1`, 22:01:32 et 22:03:20) contiennent
`72 00 … 0d 00 aa 00` et `72 00 … 3e 00 6e 00` (le premier champ vaut le temps
écoulé), rôle inconnu. Les messages de l’init (33, 22, 17, 37, 32, 28, 10, 9 et
36 octets) contiennent des identifiants du matériel (numéro de série, version) :
ne pas les versionner.

**Non établi** : le rôle des octets 16, 38-39, 42 et des messages `0c`, la confirmation du sens des drapeaux 19 et 20,
la valeur 10 de l’état (hypothèse ci-dessous), l’effet de chaque trame prise
isolément (par exemple si le `17` avant `0d` est nécessaire) et le comportement
d’un arrêt depuis l’état 2 sans pause préalable.

## Écritures d’état `fe 02 0d 02` (captures)

Les captures iFit `…202611081036.log` et `…start.log` associent le couple
`fe 02 0d 02` puis une écriture de 20 octets `ff 0d …` (moins de 300 ms plus
tard) aux actions start, stop, end, pause, reprise et résistance 2 et 3 (0,3 à
0,6 s après l’action ; une occurrence 2,2 s avant le start). Aucune occurrence
pour un réglage manuel à 24 sur le rameur ni pour les coups de rame.

Payloads de la capture `start` (décodés plus haut) : start
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

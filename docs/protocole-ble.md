# Notes de protocole BLE

Dernière mise a jour : 2026-11-08.

## Etat des connaissances

Une capture BTSnoop d’une session iFit sur un Google Pixel 3a (Android 12) a
été analysée. Le fichier local est `bugreport/btsnoop_hci.log` ; il reste
non versionné et contient des identifiants Bluetooth. Ne pas le publier tel
quel.

Une connexion LE a réussi sur le handle HCI `0x0001`. Les offsets ci-dessous
sont relatifs au début de la capture : connexion réussie vers `126.6 s`,
déconnexion vers `291.4 s` (raison HCI `0x16`). La conversion des horodatages
absolus n’est pas validée.
L’analyse a utilisé un script Python ponctuel pour lire BTSnoop/H4 et extraire
les PDU ATT ; ce script n’a pas été conservé. Aucune commande n’a été envoyée
au rameur pendant l’analyse.

La découverte GATT a observé les services et caractéristiques suivants.
Pour les UUID 128 bits, la représentation canonique correspond à l’inversion
des 16 octets de l’UUID transporté dans les PDU ATT. Cette conversion donne les
mêmes identifiants que Bleak ; `uuid.UUID(bytes_le=...)` ne convient pas ici,
car cette fonction ne renverse que les premiers champs de l’UUID.

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

Bleak labels `00001400-555e-e99c-e511-f9f4f8daeb24` as `HDP` by resolving
its leading 16-bit value `0x1400`. Since the remaining UUID fields do not match
the Bluetooth Base UUID, this label alone does not establish that the service
implements the standard Health Device Profile. The full UUID and observed
characteristics are the reliable identifiers.

CCCD descriptors (`0x2902`) were observed at handles `0x000c`, `0x0014`,
`0x001c` and `0x0021`. The client wrote `01 00` to `0x000c` before receiving
notifications on characteristic `0x000b`.

During the capture, ATT traffic included 1,461 Write Requests to value handle
`0x000e` and 1,677 notifications on value handle `0x000b`. The observed
payloads are proprietary byte sequences, commonly 20 bytes and sometimes 4
bytes. The short write `fe 02 18 03` (218 occurrences) appears from `3.6 s` to
`163.1 s` after connection; `fe 02 19 03` (221) appears from `2.8 s` to
`163.4 s`. The short writes `fe 02 0c 02` (46) and `fe 02 0d 02` (8) appear
between about `12.6 s` and `56.5 s`. Their meanings have not been established.
No Fitness Machine Service (`0x1826`) was observed in the discovery responses.

The capture covers the user's reported start, resistance changes, rowing,
pause/resume and stop sequence. The `fe 02 0d 02` exchange is correlated with
several iFit state changes, but its meaning is unknown; the occurrence nearest
the initial session start precedes the user's timestamp by about 2.2 s, so it
does not identify a confirmed start-session command. Do not send captured or
inferred writes to the rower.

## Protocole de capture horodatée

Refaire la même séquence avec une capture HCI BTSnoop démarrée avant la
connexion et un chronomètre indépendant. Déclencher le chronomètre au moment
où iFit indique que le rameur est connecté ; noter ce repère comme `t=0`.
L’indication de l’application peut survenir légèrement après l’événement HCI
de connexion, qui sera aussi repérable dans le log.

Garder le rameur immobile, sauf pendant les deux séries de deux coups. Utiliser
uniquement les commandes normales d’iFit et les niveaux déjà testés. Attendre
environ 10 secondes sans autre action entre chaque étape :

1. Connecter iFit et démarrer le chronomètre indépendant à `t=0`, puis
  attendre 10 secondes.
2. Démarrer la session ; noter le temps
  et attendre 10 secondes.
3. Régler la résistance à 2 ; appuyer sur `Tour/Lap`, noter le temps écoulé et attendre 10 secondes.
4. Régler la résistance à 3 ; appuyer sur `Tour/Lap`, noter le temps écoulé  etattendre 10 secondes.
5. Noter le début des deux coups de rame et le moment où les métriques
   apparaissent.
6. Mettre en pause ; noter le temps et attendre 10 secondes.
7. Reprendre ; noter le temps et
  attendre 10 secondes.
8. Faire deux coups de rame, puis noter le temps à l’apparition des métriques.
9. Arrêter la session ; noter le temps
  et repérer le moment où le chrono du rameur s’arrête.
10. Ne plus toucher à l’application pendant au moins 15 secondes ; laisser la
  capture enregistrer la déconnexion éventuelle.

Ne pas manipuler le téléphone ou le chronomètre pendant les coups de rame. Une
seconde personne peut appuyer sur `Tour/Lap` et noter le temps affiché ; sinon,
noter l’intervalle juste avant et après chaque série. Fournir les temps écoulés
depuis `t=0` et les valeurs affichées par le chrono/résistance. Extraire et
partager seulement `btsnoop_hci.log`, pas le bugreport complet ; vérifier et
masquer les adresses Bluetooth avant toute publication.

## Analyse de la capture du 8 novembre

Fichier : `bugreport/btsnoop_hci-202611081036.log` (483,928 octets, non
versionné). La capture couvre `10:35:12.310` à `10:41:41.794` heure locale
(Europe/Paris). Connexion LE réussie à `10:36:18.061` sur le handle HCI
`0x0001`. La session iFit a été démarrée selon les notes à `10:36:33`, soit
environ 15 secondes après la connexion. L’estimation de l’origine du chrono
indépendant à partir des colonnes `Durée` et `Heure` varie sur environ une
seconde ; les écarts ci-dessous sont donc approximatifs (environ ±0.5 s).

Neuf Write Requests vers le handle `0x000e` contiennent le payload court
`fe 02 0d 02`. Chacune est suivie, en moins de 300 ms, d’une autre écriture
de 20 octets commençant par `ff 0d`. Les occurrences les plus proches des
actions annotées sont :

| Action notée | Heure de l’écriture `fe 02 0d 02` | Ecart estimé à l’action |
| --- | --- | --- |
| Début session | `10:36:31.221` | environ 2.2 s avant |
| Rame au repos | `10:37:39.156` | environ 2.7 s avant |
| Pause iFit | `10:38:00.506` | environ 0.6 s après |
| Reprise iFit | `10:38:11.674` | environ 0.6 s après |
| Résistance 2 iFit | `10:38:29.663` | environ 0.5 s après |
| Résistance 3 iFit | `10:38:39.203` | environ 0.3 s après |
| Résistance 24 depuis le rameur | aucune dans la fenêtre proche | — |
| Arrêt session iFit | `10:39:20.963` | environ 0.4 s après |
| Fin session iFit | `10:39:31.734`, puis `10:39:32.663` | environ 0.4 s après la première |

Les deux coups de rame n’ont pas d’occurrence de ce payload à proximité ; les
notifications continuent toutefois sur `0x000b` et leurs valeurs changent.
Des écritures courtes `fe 02 18 03`, `fe 02 19 03` et `fe 02 0c 02` sont
beaucoup plus fréquentes et périodiques dans la capture.

Observation : le motif `fe 02 0d 02` suivi de `ff 0d ...` est fortement corrélé
à plusieurs changements d’état demandés depuis iFit, mais sa fonction et le
contenu des champs ne sont pas décodés. L’absence de ce motif autour du réglage
manuel à 24 est compatible avec un changement qui n’a pas été demandé par
iFit ; elle ne prouve pas l’absence de tout autre message lié à ce réglage.
Ne pas rejouer ces écritures sur le rameur.

Un événement de déconnexion HCI a lieu à `10:41:07.429`, environ 1 min 36 s
après l’arrêt de la session iFit. La capture se termine à `10:41:41.794` ; la
dernière ligne du CSV (`10:42:57`) est postérieure à la capture et ne peut pas
y être corrélée.

## Capture ciblée start/stop du 8 novembre

Fichier : `bugreport/btsnoop_hci-start.log` (1,784,150 octets, non versionné).
Le log s’étend de `14:57:44.902` à `15:47:55.293` heure locale. Il contient
de nombreux autres événements de connexion BLE ; la session étudiée utilise
le handle ACL `0x0010`, dont l’adresse distante correspond au rameur repéré
par Bleak. La connexion réussit à `15:45:27.097`.

Les quatre couples `Durée`/`Heure` fournis donnent une origine de chronomètre
estimée autour de `15:45:25.592`, avec une dispersion de `0.277 s`. Le début
de session est donc estimé à `15:45:53.821`.

Sur le handle ACL `0x0010`, les trois actions sont associées à des écritures
sortantes vers le handle ATT `0x000e` :

| Action | Ecriture courte `fe 02 0d 02` | Ecriture suivante `ff 0d ...` | Décalage estimé |
| --- | --- | --- | --- |
| Start, estimé `15:45:53.821` | `15:45:53.206` | `15:45:53.296` : `ff0d020402091409020200100200330000000000` | environ `0.6 s` avant |
| Stop, estimé `15:46:02.069` | `15:46:02.345` | `15:46:02.429` : `ff0d020402091409020200100300340000000000` | environ `0.3 s` après |
| End, estimé `15:46:08.097` | `15:46:08.335` | `15:46:08.414` : `ff0d020402091409020200100400350000000000` | environ `0.2 s` après |

Une quatrième paire apparaît à `15:46:09.272` / `15:46:09.360`, après la fin
notée, avec le payload `ff0d020402091409020200100100320000000000`.

Cette capture renforce nettement la corrélation du couple d’écritures
`fe 02 0d 02` puis `ff 0d ...` avec start, stop et end dans iFit. Elle ne
révèle pas encore la sémantique du couple ni celle des champs qui changent
entre les payloads. Ne pas rejouer ces écritures sans accord explicite et
protocole de test convenu.

## Analyse hors ligne des écritures périodiques

Les captures BTSnoop ont été relues avec `btmon` et les Write Requests ont été
filtrées par connexion ACL du rameur, afin d’exclure les autres périphériques
présents dans la capture ciblée : handle `0x0001` pour
`btsnoop_hci-202611081036.log` et `0x0010` pour `btsnoop_hci-start.log`.
Les intervalles ci-dessous sont des écarts entre occurrences du même payload,
calculés à partir des horodatages btmon.

| Payload court | Occurrences (capture 1 / start) | Intervalle médian (capture 1 / start) | Payload long suivant le plus fréquent |
| --- | ---: | ---: | --- |
| `fe021803` | 366 / 181 | 0.720 s / 0.677 s | `001202040214141402000e1c9631001040400080` |
| `fe021903` | 369 / 183 | 0.720 s / 0.676 s | `001202040215141502000f800040000000000000` |
| `fe020c02` | 149 / 19 | 1.124 s / 0.855 s | `ff0c020402081408020002001030000000000000` |

Les écritures longues correspondantes suivent généralement leur écriture
courte en environ 0.08–0.09 s. Les payloads longs ci-dessus sont stables dans
la grande majorité des occurrences ; quelques variantes existent, notamment
pour `fe021903` et `fe020c02`.

Dans la capture start, les écritures `fe021803` et `fe021903` sont déjà
présentes avant le start annoté. Elles continuent jusqu’à environ 90 s après
la dernière paire `fe 02 0d 02` associée à la fin de session. Dans la capture
du 8 novembre à 10:36, les dernières occurrences de `fe021803` et `fe021903`
sont vers `353.5 s` et `353.9 s` ; le HCI Disconnect Complete arrive à
`355.429 s` avec la raison `0x16` (connexion terminée par l’hôte local). Dans
la capture start, les dernières occurrences sont vers `2995.5 s` ; le
Disconnect Complete survient à `2997.427 s`, également avec la raison `0x16`.
Ces fins de connexion iFit ne démontrent donc pas que le rameur aurait
déconnecté un client dépourvu de ces écritures.

Interprétation limitée : `fe021803` et `fe021903`, avec leurs écritures longues
associées, sont de bons candidats à un échange périodique de polling ou de
maintien de flux. Leur périodicité et leur présence autour de la session ne
permettent pas de les qualifier de heartbeat ni d’établir qu’elles maintiennent
la connexion. `fe020c02` est moins fréquent et sa cadence varie davantage.
Les couples `fe020d02` / `ff0d...` sont associés à des changements d’état,
pas à un rythme régulier. Ne pas rejouer ces écritures sans accord explicite.

`bleak/main.py` contient maintenant un test optionnel, après la séquence `08`
/ `0b`, pour envoyer les échanges capturés `19` puis `18` en alternance. Chaque
paire est envoyée avec `response=True`, les débuts de paire sont espacés de
0.37 s et le test s’arrête après 15 s ou à la déconnexion. Une seconde
confirmation `y` est requise. Cette cadence reprend la médiane observée entre
les écritures courtes en régime établi ; le motif initial de la capture
comporte parfois deux `19` successifs. Le test n’a pas encore été exécuté sur
le rameur. Une capture iFit sans démarrage de session décrite ci-dessous
confirme que ces échanges existent aussi hors entraînement.

`bleak/main.py` propose également, après l’échange `0b`, un handshake idle
capturé, avec une confirmation distincte : `0a`, un échange `08` supplémentaire
et l’échange `2c` suivi des trois écritures longues observées. Les réponses
attendues sont respectivement trois, deux et deux notifications. Les
notifications déjà en attente sont vidées avant chaque étape pour ne pas
compter la notification supplémentaire du précédent échange `0b`. Les
payloads `2c` restent opaques. Le handshake idle puis le test `18`/`19` ne sont
pas exécutés automatiquement et n’ont pas encore été testés sur le rameur.

## iFit connection without a workout

The user reports that `bugreport/btsnoop_hci-only-connect.log` was captured
after opening iFit without starting a workout, while the rower's iFit light
remained on. On the rower's ACL handle `0x0001`, the capture contains 1,175
Write Requests to `0x000e` and 1,364 notifications on `0x000b`. The captured
traffic runs from `28.111 s` to `161.974 s`; HCI Disconnect Complete occurs at
`163.233 s` with reason `0x16` (the local host terminated the connection).

The startup writes include three `08` exchanges, then the captured `0b` and
`0a` exchanges, followed by another `08` exchange with
`ff08020402040204959b00000000000000000000`, then `fe022c04` and three writes
whose payloads begin `0012`, `0112` and `ff08`. The periodic `19`/`18` writes
follow this setup. Their short payload counts are 193 (`19`) and 190 (`18`);
their median per-payload intervals are `0.676 s` and `0.677 s`. Each is
typically followed by its associated 20-byte write after about `0.08–0.09 s`.
Neither `fe020d02` nor `fe020c02` occurs in this capture.

This is direct evidence that the `18`/`19` exchanges are used by iFit while
connected without a workout. It strengthens the polling/connection-maintenance
hypothesis, but does not prove that these writes are what keep the BLE link or
the rower light active. The observed `0a` and `2c` setup writes are now
available in `bleak/main.py` only behind a separate interactive confirmation;
their semantics remain unknown.

The larger file `bugreport/btsnoop_hci-only-connect.log.last` contains 13
separate ACL connections to the proprietary service, with mixed occurrences
of `fe020d02`; it is not a single clean connection-only trace and cannot be
used to attribute those state writes to one user action without more timing
context.

## Notifications avant le démarrage de session

Dans les deux captures iFit, le client active les notifications en écrivant
`01 00` au CCCD du handle `0x000c`, puis reçoit déjà des notifications sur le
handle valeur `0x000b` avant l’action start notée :

| Capture | Ecriture CCCD | Première notification | Notifications avant start |
| --- | --- | --- | ---: |
| `btsnoop_hci-202611081036.log` | `10:36:19.064` | `10:36:19.518` | 139 |
| `btsnoop_hci-start.log` | `15:45:28.103` | `15:45:28.497` | 259 |

Cela montre que la commande start n’est pas nécessaire pour recevoir au moins
les notifications de base de `0x000b`. Cela ne permet pas de conclure que les
métriques de rame sont émises avant start : leur décodage reste à faire.

Le script `bleak/main.py` appelle `BleakClient.start_notify()` sur la
caractéristique `00001535-1412-efde-1523-785feabcd123`, ce qui configure le
CCCD. Dans les deux captures, après la réponse à l’écriture CCCD, iFit envoie une écriture
`fe 02 08 02` au handle `0x000e`, puis une écriture de 20 octets commençant
par `ff 08`. La première notification suit cette séquence :

| Capture | Ecriture CCCD | `fe 02 08 02` | Ecriture `ff 08 ...` | Première notification |
| --- | --- | --- | --- | --- |
| `btsnoop_hci-202611081036.log` | `10:36:19.064` | `10:36:19.292` | `10:36:19.436` | `10:36:19.518` |
| `btsnoop_hci-start.log` | `15:45:28.103` | `15:45:28.334` | `15:45:28.411` | `15:45:28.497` |

Cette séquence est un candidat d’initialisation du flux, et non une preuve
qu’il faut démarrer l’entraînement pour recevoir des notifications. Le script
envoie maintenant la première paire capturée (`fe020802` puis
`ff08020402040204818700000000000000000000`) après `start_notify()`, et
seulement après une confirmation `y` à l’exécution. Ces ATT Write Requests
utilisent `response=True`. L’essai réel est consigné ci-dessous. Le sens de la
paire reste inconnu, et les écritures `ff 08 ...` suivantes d’iFit contiennent
des valeurs différentes qui ne sont pas toutes incluses dans le script.

Essai réel du `bleak/main.py` le 2026-11-08 : la connexion BLE réussit et le
script s’abonne à `00001535-1412-efde-1523-785feabcd123`. Après confirmation,
il envoie les deux payloads ci-dessus ; le voyant iFit du rameur s’allume et
trois notifications arrivent, puis le voyant s’éteint. Les notifications 2
et 3 sont identiques à des payloads vus dans les captures iFit précédentes ;
la première partage le préfixe `fe 02 21 03` avec une notification capturée,
mais son reste diffère. L’utilisateur rapporte que le script s’est arrêté
après l’extinction du voyant. La capture `bugreport/btmon-bleak-202611082205.log`
confirme une déconnexion BLE initiée par le rameur : événement HCI `Disconnect
Complete`, statut réussi, raison `0x13` (`Remote User Terminated Connection`)
à `19.982618` dans le temps btmon. Il survient `10.031 s` après la troisième
notification (`9.952032`). Aucune autre écriture ATT n’est observée entre les
trois notifications et la déconnexion.

Observation : la paire déclenche au moins une réponse du rameur et un état
indiqué par son voyant iFit. Elle ne suffit pas à maintenir cet état dans cet
essai. L’écart d’environ 10 secondes sans écriture avant la déconnexion est
compatible avec un délai d’inactivité ou un délai de protocole ; la raison HCI
ne révèle pas la cause applicative.

Dans les deux captures iFit, la suite immédiate après le premier burst est
identique :

| Etape | Payload court | Payload suivant |
| --- | --- | --- |
| 2e échange | `fe020802` | `ff08020402041404809800000000000000000000` |
| 3e échange | `fe020802` | `ff0802040204140488a000000000000000000000` |
| 4e échange | `fe020b02` | `ff0b020402070207820000008b00000000000000` |
| 5e échange | `fe020a02` | `ff0a0204020602068400008c00000000000000` |

Les échanges `08` sont suivis d’un burst de notifications sur `0x000b`. Ces
payloads sont des candidats capturés chez iFit, pas des commandes décodées.
Toutes les écritures du script utilisent `response=True`.
Après confirmation `y`, `bleak/main.py` envoie la paire initiale, attend trois
notifications en 3 secondes, puis envoie les deuxième et troisième paires
`08`, chacune seulement après le burst de trois notifications précédent.
Le troisième échange produit deux notifications ; le script attend donc deux
notifications à cette étape, puis envoie la paire `0b` si la liaison reste
active. Il attend ensuite trois notifications pendant au plus 3 secondes.
La paire `0a` reste exclue.

Essai réel de cette séquence : les deux paires `08` ont chacune été suivies
d’un burst de trois notifications. La seconde paire est envoyée à
`14.540976` dans le temps btmon ; la dernière notification du second burst
arrive à `14.719022`. Le rameur termine la liaison à `24.751410` avec la
raison HCI `0x13`, soit `10.032 s` après cette notification. Aucun autre
Write Request n’apparaît après la seconde paire et avant la déconnexion.
Cette paire produit donc le burst attendu, mais ne suffit pas à maintenir la
liaison. Le troisième échange `08` est maintenant implémenté dans le script,
et a été testé : il produit deux notifications, `fe02110241e8141280020c4c471445434d4e5440`
et `ff110104020d140d880209829083718984955c40`, et non trois. Dans les deux
captures iFit, ces deux notifications sont suivies par la paire `0b` du
tableau. Pendant l’essai, aucune écriture `0b` n’a été envoyée ; le rameur
termine la liaison avec la raison HCI `0x13` environ 10 s après les deux
notifications. Le script attend désormais deux notifications pour le
troisième échange afin de refléter l’observation.

Essai réel de la séquence complète avec `0b` le 2026-11-08 : les trois
échanges `08` ont reproduit les bursts déjà observés (3, 3 et 2 notifications).
Le script a ensuite envoyé la paire `0b` à `32.640119` dans le temps btmon ;
les deux Write Requests ont reçu une réponse ATT. Trois notifications arrivent
sur `0x000b` à `32.817054`, `32.817055` et `32.819308` :

| Payload de notification | Temps btmon |
| --- | ---: |
| `fe022504020d140d880209829083718984955c40` | `32.817054` |
| `00120104022114218202640001f9bb00005cad06` | `32.817055` |
| `01120053002de8030024f400f401010001020000` | `32.819308` |
| `ff015d53002de8030024f400f401010001020000` | `32.862029` |

Le script rapporte les trois premières comme le burst `0b`. Une quatrième
notification, listée ci-dessus, arrive environ 45 ms après le troisième
élément ; aucune nouvelle écriture ATT ne la précède. Le rameur termine la
liaison à `42.851363` avec la raison HCI `0x13` (`Remote User Terminated
Connection`), soit environ 9.99 s après cette quatrième notification. Le
dernier paquet ATT observé avant la déconnexion est cette notification ; aucun
autre Write Request n’est envoyé après la paire `0b`.

Observation : la paire `0b` est suivie des trois notifications attendues, mais
ne maintient pas la connexion au-delà du délai d’environ 10 s déjà observé
après les échanges `08`. La notification supplémentaire ne change pas cette
conclusion. La raison HCI établit que le pair distant termine la liaison, mais
ne révèle pas la cause applicative. La signification des payloads reste
inconnue ; ne pas ajouter ni envoyer la paire `0a` sans nouvelle capture et
accord explicite.

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

Utiliser ce modèle pour chaque experience significative :

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

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
Les UUID 128 bits sont affichés après décodage de leur ordre little-endian BLE.

| Plage de handles du service | UUID du service | Handle valeur | UUID caractéristique | Propriétés observées |
| --- | --- | --- | --- | --- |
| `0x0001-0x0007` | `0x1800` | `0x0003` | `0x2a00` | Read, Write |
| `0x0001-0x0007` | `0x1800` | `0x0005` | `0x2a01` | Read |
| `0x0001-0x0007` | `0x1800` | `0x0007` | `0x2a04` | Read |
| `0x0008-0x0008` | `0x1801` | — | — | — |
| `0x0009-0x000e` | `eabcd123-785f-1523-deef-121433150000` | `0x000b` | `eabcd123-785f-1523-deef-121435150000` | Read, Notify |
| `0x0009-0x000e` | `eabcd123-785f-1523-deef-121433150000` | `0x000e` | `eabcd123-785f-1523-deef-121434150000` | Read, Write |
| `0x000f-0x0016` | `eabcd123-785f-1523-deef-121230150000` | `0x0011` | `eabcd123-785f-1523-deef-121232150000` | Write Without Response |
| `0x000f-0x0016` | `eabcd123-785f-1523-deef-121230150000` | `0x0013` | `eabcd123-785f-1523-deef-121231150000` | Write, Notify |
| `0x000f-0x0016` | `eabcd123-785f-1523-deef-121230150000` | `0x0016` | `eabcd123-785f-1523-deef-121234150000` | Read |
| `0x0017-0xffff` | `f8daeb24-f9f4-e511-9ce9-5e5500140000` | `0x0019` | `f8daeb24-f9f4-e511-9ce9-5e5501140000` | Read, Write |
| `0x0017-0xffff` | `f8daeb24-f9f4-e511-9ce9-5e5500140000` | `0x001b` | `f8daeb24-f9f4-e511-9ce9-5e5502140000` | Read, Notify |
| `0x0017-0xffff` | `f8daeb24-f9f4-e511-9ce9-5e5500140000` | `0x001e` | `f8daeb24-f9f4-e511-9ce9-5e5503140000` | Read, Write |
| `0x0017-0xffff` | `f8daeb24-f9f4-e511-9ce9-5e5500140000` | `0x0020` | `f8daeb24-f9f4-e511-9ce9-5e5504140000` | Read, Notify |

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

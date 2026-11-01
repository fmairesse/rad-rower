# Contexte du projet

Dernière mise a jour : 2026-11-01.

## Objectif

Créer une application Android pour piloter un rameur d’interieur Proform
Sport RL via Bluetooth LE.

## Etape actuelle

Préparer l’apprentissage et les expériences BLE sous Linux avec Python et
Bleak, avant de porter les connaissances acquises dans l’application Android.

## Acquis verifies

- Le workspace contient les dossiers `android/` et `bleak/`.
- L’utilisateur indique débuter en BLE et souhaite progresser dans son
  utilisation de l’IA.
- Une documentation de contexte a été initialisée pour être versionnée avec
  le projet. Le statut du dépôt Git n’a pas été vérifié.
- Aucun résultat d’expérience BLE n’a encore été consigné.

## Hypotheses et inconnues

- Les services, caractéristiques et commandes exposés par le rameur ne
  sont pas encore connus.
- La disponibilité de l’adaptateur Bluetooth, de BlueZ, de Python et de
  Bleak sur le poste reste à vérifier.
- Les besoins d’appairage et les possibilités réelles de pilotage restent à déterminer expérimentalement.

## Blocages

Aucun blocage constaté à ce stade ; l’environnement BLE reste à vérifier.

## Prochaine étape

Vérifier l’environnement Bluetooth et Python sous Linux, puis préparer une
première experience de scan BLE sans commande de pilotage.

## Documents associés

- [Notes de protocole BLE](protocole-ble.md)
- [Decisions du projet](decisions.md)

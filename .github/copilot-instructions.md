# Projet Radrower

## Objectif et organisation

Créer une application Android pour piloter un rameur d'intérieur Proform
Sport RL via Bluetooth LE. Commencer par apprendre et expérimenter sous
Linux avec Python et Bleak.

- `android/` : projet de l'application Android.
- `bleak/` : scripts Python d'expérimentation BLE.
- `docs/contexte.md` : état actuel, hypothèses, blocages et prochaine étape.
- `docs/protocole-ble.md` : observations et connaissances du protocole.
- `docs/decisions.md` : décisions importantes et leurs raisons.

## Methode de travail

- Echanger en français (en utilisant les accents). L'utilisateur apprend BLE et l'utilisation de l'IA :
  expliquer les notions nouvelles et avancer par petites étapes vérifiables.
- Code et commentaires de code écrits en anglais.
- Documents non destinés à l’IA écrits en anglais.
- Consulter `docs/contexte.md` au début d'une nouvelle tache.
- Consulter les notes de protocole pour les travaux BLE, et les décisions
  lorsque la tache concerne un choix deja consigné.
- Distinguer faits observes, hypotheses et informations externes. Ne pas
  inventer de services, UUID, formats de données ou commandes du rameur.
- Ne pas envoyer de commande modifiant le comportement du rameur sans
  accord explicite de l'utilisateur.
- Apres une avancée significative, mettre a jour les documents concernés
  avec les résultats vérifiés, les incertitudes et la prochaine étape.
- Garder le contexte court et actuel ; ne pas y recopier les conversations.
- Referencer les scripts, tests ou sources qui justifient les conclusions.
- Ne pas versionner de secrets ou d'identifiants personnels du materiel.
- Ne pas créer de commit ou de branche sans demande explicite.
- Quand demandé de commit, écrire le message en anglais, à la façon "conventional commit", en incluant le co-auteur copilot. Respecte la règle 50-72 caractères.

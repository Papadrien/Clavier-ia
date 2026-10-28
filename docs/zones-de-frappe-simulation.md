# Zones de frappe adaptatives — étape 3 : simulation chiffrée

Question : agrandir la zone de toucher des lettres probables (voir `NextLetterModel` et `TouchTargets`) réduit-il les erreurs
de touche, sans pénaliser les mots rares ? Script : `tools/simulate_touch_targets.py` (miroir Python du code Kotlin).

## Méthode
- AZERTY français, 3 rangées de lettres (largeurs réelles : Maj 1,4, Effacer 1,6, apostrophe).
- Modèle construit comme dans l'app : candidats `fr.txt` de fréquence ≥ 100 (≈ 49 900 mots).
- Frappe : centre de la touche visée + bruit gaussien horizontal (écart-type σ en largeurs de touche). Le préfixe donné au
  modèle est ce qui a **réellement été tapé** (une erreur fausse la prédiction suivante).
- Comparaison appariée : mêmes tirages pour les zones nominales et adaptatives.
- Trois scénarios : **texte courant** (20 000 mots tirés selon leur fréquence), **mot rare** (10 000 mots valides de fréquence
  5 à 99, donc hors modèle), **hors dictionnaire** (8 000 mots anglais absents du français, proxy des noms et mots étrangers).
- « Usage mixte » = 70 % texte courant + 30 % mots hors modèle (estimation pessimiste).

## Résultats (réglages par défaut : cœur 0,6 ; régularisateur 0,05 ; exposant 1,0)
Taux d'erreur de touche, nominal → adaptatif :

| σ | Texte courant | Mot rare | Hors dictionnaire | Usage mixte |
|---|---|---|---|---|
| 0,20 | 1,13 → 0,42 % | 1,17 → 0,99 % | 1,17 → 0,96 % | 1,14 → 0,58 % |
| **0,25** | **4,13 → 1,75 %** | **4,14 → 3,43 %** | **4,19 → 3,39 %** | **4,14 → 2,24 %** |
| 0,30 | 8,75 → 4,69 % | 8,68 → 7,17 % | 9,07 → 7,50 % | 8,79 → 5,48 % |
| 0,35 | 14,02 → 8,72 % | 13,86 → 11,98 % | 14,41 → 12,44 % | 14,05 → 9,77 % |

À σ = 0,25 : erreurs corrigées 4 109, introduites 958 (rapport 4,3). Mots avec au moins une erreur : 16,3 → 6,8 % (texte courant).
Résultat stable sur 4 graines (usage mixte 4,14–4,22 % → 2,24–2,33 %).

Par rang de la lettre (texte courant, σ = 0,25) : 1re 3,77 → 2,79 % ; 2e 3,95 → 1,71 % ; 3e 4,25 → 1,64 % ; 4e 4,64 → 1,37 % ; 5e 4,28 → 0,91 %.

## Réglages
Balayage cœur × régularisateur × exposant (moyenne sur σ = 0,20 / 0,25 / 0,30, gain d'erreurs en usage mixte) :
- Plateau à 44–46 % de gain pour cœur 0,5–0,6 et régularisateur 0,02–0,1. Les valeurs par défaut (0,6 ; 0,05) donnent 44,2 %.
- Cœur 0,5 : +1,7 point de gain mais +36 % d'erreurs introduites, et un clavier moins prévisible. Cœur ≥ 0,7 : gain 32–40 %.
- Cœur 0,4 puis 0,3 : le texte courant ne progresse presque plus (1,54 puis 1,56 %), les mots rares se dégradent (3,60 puis 3,81 %).
- Exposant 1,0 meilleur que 0,5 (≈ −1,5 point).
- Seuil de préfixe (ne rien faire avant N lettres) ou nombre minimal de mots : **tous dégradent** le résultat (ex. dès la
  2e lettre : 2,41 % au lieu de 2,24 %). Pas de seuil.

## Limites (à lire avant de conclure)
1. Bruit gaussien horizontal sans biais : le vrai toucher a des décalages systématiques (pouce) et des erreurs verticales,
   que ce lot ne corrige pas. σ réel sur Pixel 9 inconnu ; 0,20–0,30 est plausible, l'effet reste net de 0,20 à 0,40.
2. Aucune autocorrection simulée : le gain réel perçu est plus faible, car l'autocorrection rattrape déjà une partie des erreurs.
3. Le « texte courant » vient du même corpus que le dictionnaire (sous-titres) : estimation optimiste pour un vocabulaire de
   messagerie. Les deux scénarios hors modèle donnent la borne basse (+10 à +19 %).
4. La simulation ne mesure pas la prévisibilité perçue ni l'adaptation de l'utilisateur.
5. Français / AZERTY seulement ; les autres langues et dispositions sont à vérifier.
6. À σ = 0,15 (très peu d'erreurs), les mots rares sont légèrement moins bons (0,08 → 0,11 %) : effet négligeable en valeur absolue.

## Conclusion
Critère de l'étape (gain net sans régression sur les mots rares) : **atteint en simulation** pour σ ≥ 0,20. Réglages retenus pour
l'étape 4 : cœur 0,6, régularisateur 0,05, exposant 1,0, sans seuil de préfixe ni de nombre de mots. Validation sur appareil
obligatoire (étape 5, interrupteur A/B) avant d'activer par défaut.

Relancer : `python3 tools/simulate_touch_targets.py --sweep sigma|grid|extras` ou `--sigma 0.25 --core 0.6 --reg 0.05`.

## Intégration (étape 4)
- `NextLetterContext` : du texte avant le curseur au mot en cours (vide après une espace, élision et trait d'union repartent du mot
  qui suit, rien après un chiffre, `@`, `/`, `:` ou `_`).
- `SuggestionController.refresh` pousse la prédiction au clavier à chaque changement de texte, avant la frappe suivante (thread
  principal, quelques recherches dichotomiques). Le modèle est construit hors du thread principal avec le préchargement du dictionnaire ;
  tant qu'il n'est pas prêt, zones nominales.
- `KeyboardView.keyAt` juge le toucher sur les bords adaptatifs de la rangée, figés à l'appui (glissement et relâchement du même
  toucher). Touches dessinées, accessibilité (TalkBack), bulles et geste vers le bas des chiffres : inchangés.
- Jamais de prédiction dans les champs de mot de passe, d'adresse e-mail, d'adresse web ou sans suggestions.
- Réglage « Zones de frappe adaptatives » (Paramètres du clavier > Précision de frappe) : actif par défaut dans le build debug,
  désactivé en release tant que l'étape 5 (validation sur appareil) n'est pas faite. Réglage debug « Afficher les zones de frappe » :
  traits magenta aux bords des zones.

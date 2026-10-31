# Banc de mesure de l'autocorrection (phase 0 de la refonte)

Avant de refondre l'autocorrecteur (score unifié, décision de déclenchement, contexte français : voir le plan du
30/10/2026), on mesure le moteur actuel. Chaque phase suivante est jugée sur ces chiffres : elle doit améliorer sa
cible **sans faire monter les fausses corrections**.

## Ce qui est mesuré

Le banc (`core/src/test/kotlin/fr/junade/taipo/bench/AutocorrectBench.kt`) charge le vrai `dictionaries/fr.txt` avec les
mêmes seuils que `DictionaryLoader` (mots vus 5 fois, candidats à partir de 100, clavier AZERTY, prénoms). Rien n'est
appris : ni habitudes, ni dictionnaire personnel. Tirages reproductibles (graine fixe).

| Mesure | Contenu | Sens |
|---|---|---|
| `typos.<jeu>.<type>.ok` | 1 000 fautes de frappe par type : touche voisine, lettre oubliée, doublée, ajoutée, inversion, accent oublié, apostrophe oubliée | plus haut = mieux |
| `typos.<jeu>.ok / missed / wrong` | bien corrigées / non corrigées / remplacées par un autre mot que l'attendu | `wrong` plus bas = mieux |
| `typos.<jeu>.surMotValide` | fautes qui tombent sur un mot valide (le dictionnaire ne peut pas les voir ; non comptées ci-dessus) | information |
| `valides.<jeu>.faux` | mots justes remplacés par un AUTRE mot : la fausse correction | plus bas = mieux |
| `valides.<jeu>.restaure` | mots justes dont on rétablit l'accent ou la majuscule d'un prénom (« ca » → « ça », « kevin » → « Kévin ») : voulu | information |
| `phrases.justes.changees` | phrases justes (`sentences-ok.txt`) que le moteur modifie | doit rester à 0 |
| `contexte.<catégorie>.ok` | phrases fautives de `context-cases.tsv` frappées mot par mot, comparées à la phrase attendue | plus haut = mieux |

Deux jeux de mots : **courants** (tirés au prorata de la fréquence : ce que l'on tape vraiment) et **varies** (tirés
uniformément parmi les mots proposables : plus difficile). Le temps de `correctionFor` est affiché (p50, p95, p99, max) mais
sur cette machine seulement : il ne remplace pas une mesure sur le Pixel 9.

La frappe est simulée comme au clavier : à chaque espace ou ponctuation, correction du dictionnaire puis correction de
contexte français, dans l'ordre de `SuggestionController.autocorrectionFor` (sans la tournure habituelle ni les
habitudes).

## Lancer le banc

Hors Gradle, depuis la racine du projet (même méthode que `tools/PruneDictionaryTypos.kt`) :

```
kotlinc core/src/main/kotlin core/src/test/kotlin/fr/junade/taipo/bench/AutocorrectBench.kt -include-runtime -d bench.jar
java -cp bench.jar fr.junade.taipo.bench.AutocorrectBenchKt
```

`--write-baseline` enregistre une nouvelle référence dans `tools/autocorrect-bench/baseline.txt`. Environ 10 secondes.

Dans Gradle : `AutocorrectBaselineTest` (module `core`) relance le banc et échoue si l'une des mesures se dégrade par
rapport à la référence (tolérance 0,05 point), si un cas de contexte qui passait échoue, ou si une phrase juste est
changée. Une amélioration ne le fait pas échouer. Il est ignoré si `dictionaries/fr.txt` ou la référence sont
introuvables. Il ajoute une dizaine de secondes aux tests du module `core`.

**Changer la référence** : seulement après une phase voulue, en relançant avec `--write-baseline` et en relisant la
différence de `baseline.txt` ; jamais pour faire passer une régression.

## Jeux de test

- `tools/autocorrect-bench/sentences-ok.txt` : 136 phrases françaises justes, dont les pièges (a/à, ou/où, la/là,
  homophones corrects, élisions, traits d'union, ligatures).
- `tools/autocorrect-bench/context-cases.tsv` : 128 cas « phrase fautive → phrase attendue » répartis en catégories :
  a/à, ou/où, la/là, participe, ce/se, on/ont, son/sont, c'est/s'est, leur/leurs, ça/sa, et/est, peu/peut, accents,
  ligatures, trait d'union, noms, fautes de frappe. Y figurent les cas signalés le 10/10/2026. Ajouter un cas = ajouter une
  ligne (`catégorie<TAB>tapé<TAB>attendu`), puis régénérer la référence.

## Référence au 30/10/2026 (moteur avant refonte, sur les 117 phrases et 101 cas de l'époque ; les jeux ont grandi depuis)

| Mesure | courants | varies |
|---|---|---|
| Fautes bien corrigées | 90,84 % | 92,57 % |
| Fautes non corrigées | 3,54 % | 1,47 % |
| Fautes remplacées par un mauvais mot | 5,61 % | 5,96 % |
| Mots justes remplacés par un autre mot | 0,00 % | 0,02 % |
| Phrases justes changées | 0 / 117 | |
| Cas de contexte réussis | 51 / 101 (50,5 %) | |

Par type de faute (courants) : voisine 86,3 %, oubli 75,1 %, doublon 88,4 %, inversion 94,2 %, ajout 95,1 %, accent 96,8 %,
apostrophe 100 %. Le point le plus faible est la **lettre oubliée** (75 %).

Contexte : réussis à 100 % : accents, noms ; a/à 57 %, ou/où 71 %, la/là 40 %, participe 90 % ; à **0 %** : ce/se, on/ont,
son/sont, leur/leurs, et/est, peu/peut, ligatures œ.

## Ce que le banc a déjà montré

1. **Plantage corrigé** : `Dictionary.elidedWord` plantait (`NoSuchElementException`) quand le rétablissement des accents
   d'un mot élidé donnait une forme fléchie absente des listes (« loles » → « olés »). Corrigé (la forme fléchie n'est plus
   retenue pour une élision) et couvert par `DictionaryElisionInflectedTest`.
2. **Élisions absurdes** parmi les mauvaises corrections : « mour » → « m'our », « jous » → « j'ous », « mensais » → « m'ensais »,
   « cait » → « c'ait ». Cible de la phase 3 (décision de déclenchement : une élision doit mener à un vrai mot et à une vraie
   forme élidable).
3. Une partie des « mauvaises corrections » est un vrai choix ambigu (« fien » → « fine » ou « rien » ?) : la baisse de
   `wrong` visée est limitée, c'est surtout `missed` et `valides.faux` qu'il faut surveiller.

## Journal des phases

### Phase 1 : canoniseur français (30/10/2026)

Nouveaux fichiers : `FrenchCanonicalizer` (apostrophes typographiques, ligatures), `FrenchHyphenation` (traits d'union),
branchés dans `Dictionary.correctionFor` et `FrenchContextCorrector.correct`.

- **Ligature œ** : « coeur » → « cœur », « oeuvre » → « œuvre », « soeur », « voeux », « noeud », « boeuf », « oeil »,
  « manoeuvre », « oedème »... Règle portée par les données : la forme avec « œ » doit exister dans les listes et peser au
  moins 1/3 de la forme « oe » (« phoenix », « moelleux », « moelle », « poète », « coefficient » ne changent jamais).
  Marche aussi après une élision (« l'oeil » → « l'œil ») et avec un accent oublié (« oedeme » → « œdème »).
- **Traits d'union** : « est ce que », « qu'est ce que », « n'est ce pas » (jamais après un sujet : « il est ce que » reste
  intact) ; « va t il » → « va-t-il » ; « dis moi », « dépêche toi », « rends lui » (impératif en début de proposition,
  seulement avec moi / toi / lui) ; une trentaine de locutions figées (« rendez vous », « au dessus », « grand père »,
  « c'est a dire », « aujourd hui », « celui ci », « là bas »...).
- **Volontairement non couvert** : « dit il » (« je lui ai dit il y a longtemps »), « est il » sans -t-, « peut être » en
  deux mots, « fais le » / « écoutez vous » (autre sens possible sans le mot suivant).
- **Majuscules accentuées** : déjà gérées par le moteur (« Apres » → « Après », « A demain » → « À demain ») ; rien à ajouter.
- **Espace fine insécable avant ; : ! ?** : non faite (aucune application de messagerie n'en veut par défaut) ; à reprendre
  seulement derrière un réglage, si vous le souhaitez.

Résultat : cas de contexte 50,5 % → 68,0 %, `ligatures` 0 → 100 %, `trait-union` 20 → 100 %, aucune phrase juste changée
(19 phrases de pièges ajoutées), aucune fausse correction de plus (`valides.*.faux` inchangé), fautes de frappe
inchangées. Un test existant a changé de résultat attendu : « vis a vis » donne maintenant « vis-à-vis ».

### Phase 2 : score unique des candidats (31/10/2026)

Nouveau fichier : `CandidateScore` ; `Dictionary.correctionFor` ne compare plus ses candidats par une cascade de critères
mais par un score additif (fréquence, coût de saisie, inversion, habitudes, mot personnel). La distance d'édition reste le
critère dominant, et deux scores identiques restent une ambiguïté (aucune correction).

- **Mesure avant de choisir** : le banc a été relancé sur une copie du moteur avec des poids réglables. Avec un poids de coût
  de saisie élevé, le score reproduit **exactement** l'ancienne cascade (mêmes chiffres au centième). Baisser ce poids (laisser
  la fréquence passer devant la proximité des touches) dégrade les résultats : la proximité doit rester prioritaire. Le seul
  réglage qui améliore est le poids de l'inversion : elle n'est plus un critère absolu mais un bonus (e^4, environ 55 fois la
  fréquence), de sorte qu'un mot beaucoup plus fréquent peut passer devant une inversion à coût de saisie égal.
- **Hiérarchie conservée** : mot personnel > paire acceptée > contexte > usage > coût de saisie > fréquence (`CandidateScoreTest`).
  Les 440 tests du module `core` existants passent, dont ceux des habitudes.
- **Résultat sur le banc** (avant -> après) : fautes courantes bien corrigées 90,84 -> 91,67 % (mauvaises corrections
  5,61 -> 4,79 %) ; touche voisine 86,3 -> 87,3 %, lettre oubliée 75,1 -> 78,5 %, doublon 88,4 -> 89,5 %, ajout 95,1 -> 95,6 % ;
  inversion 94,2 -> 94,0 % (le prix du réglage). Fautes variées (mots rares) : 92,57 -> 92,50 % (-0,07, mauvaises corrections
  5,96 -> 6,03 %). Fausses corrections de mots justes, phrases justes et cas de contexte : inchangés. Temps par correction
  inchangé (p50 environ 0,3 ms sur cette machine).
- **Test modifié** : « une inversion l'emporte sur un mot plus fréquent » ne vaut plus pour un mot 100 000 fois plus fréquent
  (dictionnaire de test sans proximité des touches) ; avec le vrai clavier, « teh » reste « the » (une inversion coûte 2, une
  lettre en trop 4).
- **Pour la phase 3** : le score est maintenant une valeur numérique, donc on peut mesurer la marge entre le meilleur
  candidat et le suivant (confiance) et la comparer à un seuil par type de faute.

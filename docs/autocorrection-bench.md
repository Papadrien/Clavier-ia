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
| `longues.<jeu>.deux/trois.ok / wrong` | mots de 8 lettres ou plus abîmés par 2 ou 3 erreurs aléatoires : bien rétablis / remplacés par un autre mot (hors totaux `typos.*`) | `ok` plus haut, `wrong` plus bas |
| `inconnus.<jeu>.faux` | mots courants retirés des listes puis tapés (imitent prénoms, argot, termes techniques) et remplacés par un autre mot | plus bas = mieux ; borne pessimiste, à lire d'une phase à l'autre |

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

### Phase 3 : décision de déclenchement (31/10/2026)

Nouveaux fichiers : `CorrectionConfidence` (type de faute, seuils, règles), `CandidateContest` (meilleur candidat, suivant,
marge). `Dictionary.correctionFor` ne retient plus un drapeau « ambigu » mais la **marge** entre le meilleur candidat et le
suivant (même distance d'édition, en unités de ln(fréquence)), comparée à un seuil par type de faute. Le bandeau de
suggestions et l'autocorrection passent par la même fonction : le mot en gras reste celui qui sera appliqué.

- **Ce que le plan prévoyait et ce que les mesures ont montré.** Le plan parlait d'un seuil « plus bas qu'aujourd'hui ». Or
  aujourd'hui il n'y en a pas : dès que le meilleur candidat bat le suivant d'un cheveu, il est appliqué. Un seuil de marge ne
  peut donc que rendre le moteur **plus prudent**. Le levier qui permet de corriger plus quand la confiance est forte est
  ailleurs : la distance 3 sur les mots longs (ci-dessous). Une règle sur les « mots valides mais rares » a été mesurée et
  écartée : elle ne touche que 0,8 % des fautes et se trompe une fois sur cinq.
- **Seuils (français, `CorrectionConfidence.STANDARD`)** : inversion 0,75 ; touche voisine 0,25 ; lettre oubliée 0,25 ; lettre en
  trop 0,5 ; autre substitution 0,5 ; deux erreurs 0,5 ; trois erreurs 6,0. Une marge de 0,7 équivaut à un mot deux fois plus
  fréquent à coût de saisie égal. Les habitudes et le mot personnel pèsent des millions dans le score : un candidat qu'ils
  soutiennent passe toujours. Une égalité stricte (marge nulle) ne corrige jamais.
- **Trois erreurs sur les mots longs** : à partir de 8 lettres, si rien n'est trouvé à distance 2 ou moins, la recherche passe à 3,
  avec une marge d'au moins 6 et un coût de saisie d'au plus 12 (seulement si l'appelant n'a pas limité la distance).
- **Élisions absurdes** : après une lettre élidée, il faut au moins 4 lettres (« mour » ne devient plus « m'our », « jous » plus
  « j'ous »), et « c' » ne s'élide que devant « e » (« c'est », « c'était » ; « cait » n'est plus « c'ait »).
- **Autres langues** : `CorrectionConfidence.NONE` (comportement d'avant). Le calibrage n'a été fait que sur le français ;
  `Dictionary.withFrequencies` garde `NONE` par défaut, donc les tests existants sont inchangés.

**Méthode de calibrage.** Ni `kotlinc` ni Gradle n'étaient disponibles : le banc Kotlin n'a **pas été lancé**. Les seuils ont été
calés sur un portage Python de la recherche de candidats (mêmes distances, mêmes coûts de saisie AZERTY, même score, même
`fr.txt`, 300 fautes par type et par jeu). Ce portage n'a pas les étapes qui précèdent la recherche (accents, élisions,
contractions) : ses chiffres absolus diffèrent du banc Kotlin (95,1 % contre 91,67 % avant), seules les **différences** comptent.

| Estimation (portage Python) | avant | après |
|---|---|---|
| Fautes courantes : bien corrigées / non corrigées / mal corrigées | 95,1 / 0,0 / 4,9 | 94,4 / 1,6 / 4,0 |
| Fautes variées : bien corrigées / non corrigées / mal corrigées | 91,4 / 0,0 / 8,6 | 90,9 / 1,5 / 7,6 |
| Mots courants retirés des listes et remplacés (courants / variés) | 98,9 / 92,9 | 90,2 / 85,0 |
| Mots longs à 3 erreurs bien rétablis (courants / variés) | 0 / 0 | 77 / 75 |
| Mots longs à 3 erreurs remplacés par un autre mot | 12 / 9 | 15 / 11 |

Par type (courants) : touche voisine 95,0 -> 94,0 ; lettre oubliée 83,7 -> 82,0 ; doublon 98,0 -> 97,3 ; inversion 100 -> 100 ;
ajout 98,7 -> 98,7. Les seuils sont volontairement bas : à 0,5 pour tous les types, les bonnes corrections perdent environ 1 point
pour environ 1,2 de mauvaises corrections en moins, à 1,0 environ 3 points pour 2,3. La table est dans `CorrectionConfidence`,
un seul endroit à régler.

**À faire au premier build** (rien n'est compilé) :
1. lancer les tests du module `core` ;
2. lancer le banc (`kotlinc ... AutocorrectBench`) et relire le rapport ;
3. `AutocorrectBaselineTest` peut échouer : quelques mesures `.ok` baissent volontairement de moins de 1 point (voir ci-dessus).
   Si les baisses sont de cet ordre, régénérer la référence avec `--write-baseline` et relire la différence de `baseline.txt` ;
   si elles sont nettement plus grandes, relever ou baisser les seuils de `STANDARD` plutôt que d'accepter la référence ;
4. vérifier la latence sur le Pixel 9 : la recherche à trois erreurs ne tourne que pour un mot d'au moins 8 lettres sans
   aucun candidat plus proche (noms, adresses, termes techniques), mais à chaque frappe.

### Phase 4 : contexte français déclaratif, homophones (31/10/2026)

Nouveau fichier : `FrenchConfusions` (le moteur et **la table de règles**), branché dans `FrenchContextCorrector.correct` après
a / à, ou / où et la / là. Chaque règle dit *quel mot, remplacé par quoi, dans quel contexte* : mot précédent (en sautant « ne »
si besoin), mot d'avant, mot suivant, début de phrase, ponctuation juste après. Une règle sans contexte est refusée au
chargement (sauf « d'ou », qui n'existe pas). Les règles qui regardent le mot suivant attendent qu'il soit tapé (correction à
rebours, comme a / à), donc ne s'appliquent pas juste après une annulation. Une ponctuation entre deux mots coupe le contexte ;
un mot en capitales n'est jamais touché ; la majuscule initiale est conservée.

| Confusion | Corrigée quand... |
|---|---|
| ce / se | « ce » après il, elle, on, ils, elles (« il ce lève ») ; « se » en début de phrase ou après une préposition devant « livre », « film », « soir »... |
| on / ont | « on » après ils, elles, ou après un nom pluriel et devant un participe ; « ont » devant un verbe au singulier (« ont y va ») |
| son / sont | « son » après ils, elles (même avec « ne ») ; « sont » devant un objet ou un parent (« sont portable », sauf après « ce », « ils », un pluriel) |
| ces / c'est / s'est | « ces » devant un, une, mon, à... ; « c'est » après il, elle, on devant un mot en -é ; « s'est » en début de phrase devant un, une, à |
| leur / leurs | « leur » devant un nom pluriel (singulier reconnu dans les listes, sauf après un pronom : « je leur dis ») ; « leurs » après un sujet |
| ça / sa | « sa » devant va, fait, suffit, me, te... ; « ça » devant mère, maison, famille... |
| et / est | « et » après il, elle, on devant un adjectif ou un mot en -é ; après « ce livre » devant un adjectif ; « est » entre deux adjectifs après « fait » ou « très » |
| peu / peut | « peu » après il, elle, on, ça ; « peut » après très, trop, assez, si, un, ou « a peut de » |
| la / là, d'où, a / à | « la » suivi d'une virgule après reste, suis, par... ; « n'est pas la aujourd'hui » ; « d'ou » ; « a » devant midi, après facile, rien, du travail, « dit »... |

Ajouts de listes : « attends », « rentre » et « joue » (et leurs formes) appellent « à » (« je t'attends a la gare »).

**Ce qui n'est volontairement pas fait.** Les règles a / à, ou / où, la / là et participes d'avant ne sont **pas** migrées dans la
table : elles fonctionnent et je ne peux pas relancer les tests ici ; la table accueille les nouvelles règles et les cas
qu'elles manquaient. Pas de ces / ses (le sens seul décide), pas de « Marie est Paul » (il faudrait reconnaître deux prénoms),
pas de « Passe par la. » (« par la fenêtre » est juste et la suite n'est pas encore connue), pas de participe après « de »
(« fini de travaillé »). Les traits d'union des inversions (« a-t-il », « est-ce ») sont ceux de la phase 1.

**Méthode.** Comme pour la phase 3, ni `kotlinc` ni Gradle : la table a d'abord été écrite et réglée dans un prototype Python du
même moteur, puis portée en Kotlin. Le prototype donne : les 12 familles d'homophones du banc corrigées, plus 53 phrases fautives
supplémentaires ; **0 phrase juste touchée** sur les 138 phrases du banc et sur 130 phrases difficiles écrites pour l'occasion
(« ce sont père et fils », « le grand et beau château », « ce qui est beau est cher », « je leur dis », « nous on a décidé »...).
Un faux positif trouvé et corrigé en chemin : « leur avis » devenait « leurs avis » (« avi » n'est pas un mot courant : le
singulier doit exister et ne pas être bien plus rare que le pluriel). Ces phrases sont dans `FrenchConfusionsTest` et dans
`sentences-ok.txt`.

**À faire au premier build :** lancer les tests de `core` ; relancer le banc ; comparer `contexte.*.ok` (doit monter) et
`phrases.changees` (ne doit pas monter ; si une des nouvelles phrases justes est modifiée, c'est une règle plus ancienne qui
la touche, à regarder dans le rapport) ; régénérer la référence avec `--write-baseline` quand le résultat est celui voulu.

### Phase 5 : cohérence bande de suggestions, annulation et habitudes (31/10/2026)

Nouveaux fichiers : `Autocorrection` (l'autocorrection d'un mot, en une fonction pure : dictionnaire, puis contexte français, puis
tournure habituelle, puis refus), `ContextualCorrection` (les corrections de contexte soumises aux habitudes, et la lecture qu'en fait
la bande). `SuggestionController.autocorrectionFor` n'est plus qu'un appel à `Autocorrection.correctedText` ; la bande de suggestions
lit les mêmes éléments.

- **Le mot en gras est celui qui sera appliqué.** Deux écarts existaient. (1) La bande ne montrait que la correction du dictionnaire et
  le participe : « ils on » n'affichait rien, puis l'espace changeait le mot en « ont ». Elle montre maintenant toute correction de
  contexte du mot en cours (a / à, homophones, participes) via `ContextualCorrection.lastWordFix`. (2) Les refus (une correction annulée
  deux fois) n'étaient lus que par l'autocorrection : la bande continuait d'afficher en gras une correction qui ne serait pas appliquée.
  `Dictionary.correctionFor` écarte maintenant une paire refusée quand on lui passe les habitudes, donc partout. La révision du mot
  précédent (« ces un » -> « c'est un ») change un autre mot que celui qu'on tape : elle n'est pas montrée dans la bande, qui ne
  montre alors rien. Test : `AutocorrectionCoherenceTest` tape des phrases mot à mot et compare, à chaque fin de mot, la bande et
  l'autocorrection.
- **Même mécanisme d'annulation.** Les corrections de contexte étaient déjà comptées par `HabitSignals` (le mot réellement changé, quel
  que soit son auteur) ; ce qui manquait est la lecture : la règle des deux annulations vaut maintenant pour le dictionnaire, le
  contexte et les tournures habituelles (`ContextualCorrectionTest`). Une correction qui change plusieurs mots à la fois (« est ce » ->
  « est-ce ») ne peut pas être comparée à une paire : elle reste appliquée telle quelle, comme avant.
- **Départage par les habitudes.** Une correction de contexte n'a qu'un candidat ; le départage compare donc « garder » et « changer » :
  si le mot tapé a suivi le mot précédent au moins 2 fois (paires et triplets appris), plus souvent que la correction, et que la
  correction n'est pas une paire acceptée, il est gardé (`HabitContext.keepsTyped`). L'usage seul ne compte pas : « on » est plus
  tapé que « ont » partout, cela ne prouve rien. Une paire acceptée passe toujours devant le contexte, comme pour le dictionnaire.
- **Ce qui ne change pas** : la bande continue d'afficher le dictionnaire avant le contexte quand les deux s'appliqueraient au même mot
  (rare : une faute de frappe qui est aussi un homophone) ; l'autocorrection, elle, applique le contexte sur le mot déjà corrigé.

**Changement visible à vérifier à la main** : la bande affiche désormais en gras des corrections qu'elle ne montrait pas, y compris
pendant la frappe (« il va a » -> « à » dès le « a »). C'était le but, mais c'est un changement d'allure de la bande à regarder sur
le Pixel 9.

**À faire au premier build** : lancer les tests de `core` (trois nouveaux fichiers) ; vérifier la latence de la bande (une analyse de
contexte de plus à chaque frappe, sur le fil de calcul, sur une fenêtre de texte courte) ; régénérer la référence du banc avec les phases
3 à 5 ensemble, car la phase 5 ne change pas les mesures du banc (il tape sans habitudes) et doit les laisser inchangées.


### Phase 6 : compteurs de debug et latence (01/11/2026)

Nouveaux fichiers (module `core`, logique pure) : `DecisionTrace` (ce que `Dictionary.correctionFor` a décidé pour un mot),
`AutocorrectStats` (les compteurs), `LatencyHistogram` (les durées). Côté application : `SuggestionController` crée les compteurs
**dans les builds debug seulement** (`BuildConfig.DEBUG` ; en release l'objet n'existe pas : ni coût, ni trace) et les écrit dans
logcat. Test instrumenté : `AutocorrectionLatencyTest`. **Aucun comportement ne change** : `Dictionary.correctionFor` et
`Autocorrection.correctedText` reçoivent un paramètre facultatif (`trace`, `stats`, null par défaut) et rendent le même résultat avec
ou sans (`AutocorrectStatsTest`, « poser les compteurs ne change jamais le résultat »). Le banc et sa référence sont inchangés.

**Jamais de texte.** Seulement des nombres et des noms de types (`NEIGHBOR`, `dico-recherche`...). Aucun mot tapé, corrigé ou de
contexte n'est gardé, pas même en mémoire ; le rapport est testé pour ne contenir aucun des mots tapés. Rien n'est écrit sur disque :
les compteurs repartent de zéro à chaque lancement du processus, comme ceux des habitudes.

#### Ce qui est compté

Lire dans logcat : `adb logcat -s SuggestionController`. Le rapport sort tous les 100 mots examinés et à la sortie de chaque champ.

| Ligne | Contenu |
|---|---|
| `Autocorrection : mots=... etapes-appliquees=... (dico-direct=... dico-recherche=... contexte=... tournure=...) refus-habitudes=... annulees=... recherche-3-erreurs=...` | mots examinés ; étapes de correction appliquées par source (un mot corrigé par le dictionnaire puis par le contexte compte 2) ; corrections écartées parce qu'annulées deux fois ; annulations ; fois où la recherche à trois erreurs a tourné |
| `Par type de faute (appliquees/refusees-marge/annulees) : SWAP=a/r/u NEIGHBOR=...` | pour chaque `FaultKind` de la recherche : corrigés / meilleur candidat refusé faute de marge / annulés |
| `Marges <TYPE> [<0.25\|<0.5\|<1\|<2\|<4\|<8\|>=8] appliquees=... refusees=...` | pour un type, la marge du meilleur candidat rangée en 7 seaux, côté appliquées et côté refusées (un type sans décision est omis) |
| `Latence <section> : n=... p50<=...us p95<=...us p99<=...us max=...us lentes(>=16ms)=...` | durée de l'autocorrection, de l'autocorrection avec recherche à trois erreurs, de la bande de suggestions (une section sans mesure est omise) |

Les centiles de latence sont des **bornes hautes à un facteur deux près** (seaux par puissances de deux) ; `max` est exact.

#### Comment ajuster un seuil à partir de ces chiffres

Les seuils sont dans `CorrectionConfidence.STANDARD`, un seul endroit. Un refus de marge n'a lieu que sous le seuil de son type, une
correction appliquée que dessus : les deux lignes de seaux sont donc de part et d'autre du seuil (SWAP : 0,75 tombe dans le seau
`<1`, THREE_EDITS : 6,0 dans `<8`).

- **Beaucoup de refus juste sous le seuil** d'un type (seau voisin) : le moteur laisse passer des fautes qu'il aurait probablement
  bien corrigées. Candidat à une baisse.
- **Des annulations qui se concentrent sur un type** (colonne `annulees`, comparée à `appliquees`) : le moteur corrige trop. Candidat à une hausse.
- **Règles de prudence.** Pas de décision sous une trentaine de cas par type. Un seul seuil à la fois. Avant d'adopter un
  changement, le rejouer sur le banc : `typos.*.wrong`, `valides.*.faux` et `phrases.justes.changees` ne doivent pas monter.
- **Limite à connaître.** Le clavier ne voit qu'une annulation par Retour arrière. Une mauvaise correction que l'utilisateur ne
  défait pas, ou qu'il retape à la main plus tard, n'est pas comptée ; et un refus ne dit pas si le mot était juste. Ces chiffres
  disent où regarder, pas quel seuil est le bon : c'est le banc qui tranche. L'annulation est attribuée à la dernière
  correction appliquée (une annulation tardive d'une correction plus ancienne est attribuée à tort).

Aucun seuil n'a été changé en phase 6 : il n'y a pas encore de données d'usage.

#### Latence : ce qui existe pour la vérifier sur le Pixel 9

Trois mesures complémentaires, de la plus proche de l'usage à la plus propre :

1. **Usage réel, build debug** : la ligne `Latence ...` de logcat. Le build debug n'a ni R8 ni profil : ses durées surestiment la release.
2. **Moteur seul, vrai dictionnaire, sur l'appareil** : `AutocorrectionLatencyTest` (`androidTest`). Quatre corpus fixes (mots
   justes, fautes de frappe, mots longs inconnus qui déclenchent la recherche à trois erreurs, phrases tapées mot par mot),
   échauffement puis 30 passes, rapport dans logcat (balise `AutocorrectionLatency`). Aucun budget n'est imposé (décision
   antérieure) : le test ne vérifie que ce qu'il a mesuré, les chiffres se comparent d'un lancement à l'autre.
   `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=fr.junade.taipo.AutocorrectionLatencyTest`
3. **Release, avec l'interface** : les sections de trace `Taipo.autocorrection` et `Taipo.suggestions` du module `macrobenchmark`
   (voir `docs/performance.md`). Le seul chemin que `typeSomeKeys` n'exerce pas de façon fiable est la recherche à trois
   erreurs (il faut un mot long inconnu) : c'est la raison du test 2 et de la section « avec recherche à 3 erreurs » du rapport.

| Mesure sur le Pixel 9 | p50 | p95 | p99 | max |
|---|---|---|---|---|
| mots justes | à mesurer | | | |
| fautes de frappe | à mesurer | | | |
| mots longs inconnus (3 erreurs) | à mesurer | | | |
| phrases mot par mot | à mesurer | | | |

**Ce qui n'a pas été fait : la mesure elle-même.** Ni appareil, ni `kotlinc`, ni Gradle dans l'environnement de développement :
rien de la phase 6 n'a été compilé ni exécuté, et aucune latence n'a été mesurée sur le Pixel 9. Le tableau ci-dessus est vide
pour cette raison. La section de trace de la recherche à trois erreurs n'a pas été ajoutée : le module `core` n'a pas accès à
`android.os.Trace` ; l'histogramme dédié (`autocorrection avec recherche à 3 erreurs`) la remplace.

**À faire au premier build** :
1. lancer les tests du module `core` (deux nouveaux fichiers : `LatencyHistogramTest`, `AutocorrectStatsTest`) ;
2. lancer le banc et vérifier que ses mesures sont **identiques** (la phase 6 n'y change rien) ;
3. lancer `AutocorrectionLatencyTest` sur le Pixel 9 et remplir le tableau ci-dessus ;
4. taper un moment dans une appli en build debug et lire les compteurs ; l'annulation d'une correction doit faire monter `annulees`.
   Les compteurs d'avant la phase 6 (habitudes) restent ceux de `docs/habitudes-correction.md`.
5. la référence du banc reste à régénérer pour les phases 3 à 5 (voir plus haut) : la phase 6 n'y touche pas.

### Référence régénérée (01/11/2026, phases 3 à 5)

Le banc a été lancé pour la première fois depuis les phases 3 à 5 et `AutocorrectBaselineTest` échouait comme prévu. Différences
relues avant d'enregistrer : fautes courantes bien corrigées 91,67 -> 91,6 %, non corrigées 3,54 -> 4,49 %, mal corrigées
4,79 -> 3,91 % ; fautes variées 92,5 -> 92,1 % (oubli 76,9 -> 75,6 %), mal corrigées 6,03 -> 5,4 % ; mots justes changés et phrases
justes changées : 0 ; contexte 67,97 -> 96,48 % ; mesures `longues.*` et `inconnus.*` ajoutées. Les baisses sont celles de la phase 3
(plus de prudence) ; la référence est enregistrée avec `--write-baseline`.

Même passage : `HabitContext.keepsTyped` comptait une paire et un triplet appris d'un seul passage pour 5 occurrences (le triplet est
pondéré par 4) et refusait la correction dès la première fois ; la preuve de contexte compte maintenant des occurrences (le plus
grand de la paire et du triplet), comme l'annonce « une seule fois ne prouve rien ».

## Anglais (05/11/2026)

Le banc sait mesurer l'anglais : `--lang=en` (clavier QWERTY, seuil de proposition 149, `CorrectionConfidence.ENGLISH`, règles de
contexte `EnglishConfusions`) ; `--lang=en-legacy` rejoue le moteur d'avant (aucune décision de déclenchement, aucun contexte).
Jeux : `tools/autocorrect-bench/en/sentences-ok.txt` (phrases justes, dont les pièges its / it's, their / there...) et
`en/context-cases.tsv` (une centaine de cas). La référence est `en/baseline.txt` ; `AutocorrectBaselineTest` la garde.

Ce que le moteur anglais reprend du fonctionnement de Gboard (décrit dans le rapport fourni, jamais son code) : un lexique de clavier
validé (et non toute la liste du corpus), une décision de déclenchement par type de faute (marge de score), une canonicalisation
propre à la langue (contractions sans apostrophe : « thats » -> « that's », « i » -> « I »), et des règles de contexte.

| Mesure (courants / variés) | avant (moteur et liste d'origine) | après |
|---|---|---|
| Fautes bien corrigées | 81,13 / 86,87 % | 93,22 / 92,53 % |
| Fautes non corrigées | 13,70 / 7,32 % | 3,87 / 3,22 % |
| Fautes mal corrigées | 5,17 / 5,82 % | 2,92 / 4,25 % |
| Mots longs à 3 erreurs rétablis | 0 / 0 % | 73,3 / 77,0 % |
| Cas de contexte réussis | 15,7 % | 95,5 % |
| Mots justes remplacés / phrases justes changées | 0 / 0 | 0 / 0 |

Quatre cas de contexte échouent encore, tous ambigus sans le mot suivant ou volontairement non couverts : « Its to early to tell »,
« rather walk then drive », « The screw is to lose », « Let the dog lose in the park ».

**Seuils testés et non retenus** : abaisser les seuils de marge de l'anglais aux valeurs du français (touche voisine et lettre oubliée
0,25, lettre en trop et substitution 0,5, deux erreurs 0,5) corrige 0,3 point de fautes en plus (93,22 -> 93,50 %) mais fait passer
les mauvaises corrections de 2,92 à 3,40 % : environ 1,5 mauvaise correction pour 1 bonne. Un réglage intermédiaire donne le même
rapport. Les seuils restent ceux de `CorrectionConfidence.ENGLISH`.

**Point à connaître** : le même banc, lancé sur le français avec le `fr.txt` enrichi du 04/11/2026, est un peu en dessous de la référence
du 01/11/2026 (fautes variées : doublon 92,0 -> 91,2 %, inversion 97,1 -> 95,2 % ; mots longs à 2 erreurs courants 90,0 -> 87,7 %, mal corrigés
7,3 -> 9,3 %). Le code de l'anglais n'y est pour rien (mêmes chiffres avec le code d'avant) : c'est l'enrichissement. `AutocorrectBaselineTest`
français échouera tant que la référence n'est pas relue ; elle n'a pas été régénérée ici.

## Espagnol (05/11/2026)

Même démarche que l'anglais : on s'inspire du fonctionnement d'un clavier du commerce (liste de mots validée, décision prudente, contexte), sans reprendre son code. Les lexiques de clavier fournis (`es_ES`, `es_419`, `es_MX`, `es_US`, 4 listes de mots sans fréquence) servent de VALIDATEUR, comme pour l'anglais ; leurs fichiers binaires ne sont pas dans le projet.

**Important : rien de ce qui suit n'a été compilé ni exécuté.** L'environnement de rédaction n'a ni Kotlin ni Gradle. Mesurées : les données (Python). Non mesurées : toute la partie Kotlin et le banc `--lang=es`. Les règles de contexte ont été vérifiées par un portage Python lisant la vraie table de `SpanishConfusions.kt` : 0 faux positif sur les 131 phrases justes, 70 des 81 cas ; les 11 autres dépendent du dictionnaire (accents, fautes de frappe) et attendent le banc.

### Ce qui a changé

1. **Dictionnaire** (`tools/clean_es_dictionary.py`, listes dans `docs/dictionnaire-es-2026-11-05/`) : 335 938 -> 147 805 mots. Retirés : 188 032 mots absents des lexiques (fautes de sous-titres, fusions, mots rares), dont 104 formes sans accent fréquentes (« despues », « habia », « senor ») que le clavier tenait pour justes ; 217 mots anglais fréquents (« the », « to ») passent à la fréquence 20 (valides, jamais proposés) ; 429 formes accentuées plausibles absentes des lexiques (« golpéalo », « olvídenlo ») sont gardées à 20. Aucune fréquence d'un mot de lexique n'est touchée. Mesure simulée (19 255 fautes de frappe sur un QWERTY avec Ñ) : part des fautes qui tombent sur un mot « valide », donc jamais corrigées : 17,2 % -> 9,8 %.
2. **Politique d'accents** (`AccentPolicy`) : la règle d'origine (« une forme sans accent est une faute si l'accentuée est 2 fois plus fréquente ») était calibrée sur le français. En espagnol elle aurait remplacé à la frappe environ 2 400 mots justes des listes (« esta » -> « está », « paso » -> « pasó », « cual », « quien »). Seuil porté à 10 pour l'espagnol (1 418 paires restent : « tambien », « aqui », « asi », « mas », « corazon »), avec « papa », « seria », « sabia » jamais corrigés. Le français garde exactement son comportement.
3. **Touche Ñ** : `KeyProximity` repliait « ñ » sur « n » (elle héritait des voisines de « n »). Elle est maintenant une touche à part (voisines : l, p, o, k...).
4. **Décision de déclenchement** : `CorrectionConfidence.SPANISH`, valeurs de départ (celles de l'anglais), **non calibrées** : à régler avec le banc.
5. **Flexions** : `InflectionRules.SPANISH` (s, et ces <- z). Pas de règle « es ».
6. **Contexte** (`SpanishConfusions`, ~90 règles) : interrogatifs après « ¿ » (¿como estas? -> ¿cómo estás?), exclamations, interrogatifs après un verbe de savoir, tu/tú, el/él, mi/mí, si/sí, se/sé, de/dé, aun/aún, a/ha, haber/a ver, echo/hecho, mots soudés. Le mot de tête « ¿ » / « ¡ » est géré par le moteur (`Word.leading`, condition `self`). Branché dans `SuggestionController` pour l'espagnol.

### Pour mesurer

    ./gradlew :core:test --tests '*Spanish*'
    ./gradlew :core:run --args="--lang=es --write-baseline"   # écrit tools/autocorrect-bench/es/baseline.txt

Le test `AutocorrectBaselineTest` espagnol est ignoré tant que `baseline.txt` n'existe pas. Régler ensuite les marges de `CorrectionConfidence.SPANISH` comme pour l'anglais.

### Non fait

Prédiction du mot suivant, « de el » -> « del » et « a el » -> « al » (le moteur ne remplace qu'un mot), accent selon la variante (es_MX / es_419 : seuls les lexiques les distinguent, pas les fréquences), conjugaisons par règles (les formes viennent des lexiques), « ¿ » automatique.


## Allemand, phase 0 : banc (06/11/2026)

Plan : `plan-autocorrection-allemand.md`. Cette phase ne change **aucun comportement** de l'app : elle ajoute le banc et ses jeux de test, pour
mesurer l'allemand avant les phases suivantes (nettoyage de `de.txt`, umlauts et ß, noms, composés, contexte, variante suisse).

**Rien n'a été compilé ni exécuté** (ni Kotlin ni Gradle dans l'environnement de rédaction) : le banc `--lang=de` et `baseline.txt` sont à produire chez toi.

### Ce qui est ajouté
- `AutocorrectBench.GERMAN` : QWERTZ simple, seuil de proposition 61 (41 depuis la phase 1), `CorrectionConfidence.NONE`, `AccentPolicy.FRENCH` (`GERMAN` depuis la phase 2),
  `InflectionRules.NONE`, aucun contexte : l'état actuel de l'allemand. `--lang=de` dans `main`, test `AutocorrectBaselineTest` allemand
  (ignoré tant que `tools/autocorrect-bench/de/baseline.txt` n'existe pas).
- Deux types de fautes de frappe **propres à l'allemand** (ignorés pour les autres langues : leurs références ne bougent pas) :
  `typos.<jeu>.ss-pour-eszett.ok` (Straße -> Strasse) et `typos.<jeu>.ae-oe-ue.ok` (für -> fuer). Le type « accent » couvre déjà u -> ü, o -> ö, a -> ä.
- `composes.rares.faux` / `composes.rares.restaure` : 1 500 mots justes que `de.txt` ne contient pas (surtout des composés), tapés tels quels ;
  `faux` = remplacés par un autre mot (doit baisser). Jeu : `tools/autocorrect-bench/de/valid-rare-words.txt`, produit par `tools/de_bench_data.py`
  à partir des lexiques de_DE / de_AT / de_CH (hors de l'app, comme validateur : c'est un jeu de test, pas une donnée livrée).
- Comparaison des mots justes : « ß » = « ss » (`benchFold`), sans effet sur les autres langues.
- `tools/autocorrect-bench/de/sentences-ok.txt` : 144 phrases justes avec pièges (das / dass, seid / seit, wir / wie, wurde / würde, ß, umlauts,
  composés, élisions « geht's », « hab's »). `context-cases.tsv` : 117 cas dans 12 catégories (umlaut, eszett, translit, nomen, vorname, seid-seit,
  wir-wie, wurde-würde, das-dass, mal-Mal, tageszeit, tippfehler). Textes écrits sans relecture par un germanophone (décision du 06/11/2026).
  Les cas `nomen` et `vorname` attendent le futur dictionnaire de noms et prénoms (pas de majuscule automatique par réglage).

### Contrôles faits (Python, sur les fichiers)
- Les mots des phrases justes sont tous dans `de.txt`, sauf `entkalkt`, `homeoffice`, « e » (E-Mail) et les formes en apostrophe (`geht's`, `gibt's`, `hab's`) :
  ces phrases peuvent donc légitimement apparaître comme « changées » dans la première référence.
- Tous les mots des phrases attendues des cas de contexte sont dans `de.txt`.
- Exposition des composés rares : sur les 1 500 mots, 98 (6,5 %) ont un mot de `de.txt` (fréquence >= 61) à une seule modification. C'est un
  ordre de grandeur de ce que `composes.rares.faux` peut atteindre à distance 1, pas sa valeur (qui dépend du score et des marges).

### Pour mesurer
    ./gradlew :core:run --args="--lang=de --write-baseline"   # écrit tools/autocorrect-bench/de/baseline.txt
Relire le rapport avant d'enregistrer la référence : les cas en échec sont les **objectifs** des phases suivantes.

### Phase 1 : nettoyage de `de.txt` (06/11/2026)

`tools/clean_de_dictionary.py` (lexiques de_DE, de_AT, de_BE ; de_CH non disponible) : `de.txt` passe de 240 489 à 117 170 mots, seuil de
proposition 61 -> 41 (`DictionaryLoader`, `AutocorrectBench.GERMAN`), 880 formes sans umlaut retirées, ancienne orthographe et OCR retirés,
mots anglais fréquents rétrogradés à 20, 636 composés rares sauvés à 20. Détail : `docs/dictionnaire-de-2026-11-06/`.
**Rien n'a été compilé ni exécuté** : relancer `./gradlew :core:run --args="--lang=de --write-baseline"` (et la comparer à une exécution sur
l'ancien `de.txt` si tu veux l'effet isolé). Attendu : `composes.rares.faux` à surveiller (des mots justes comme `dahinten` ou `idiotin`
ont été retirés), les fautes `ss-pour-eszett` et `ae-oe-ue` inchangées jusqu'à la phase 2, les fautes sans umlaut (`fur`) enfin non validées.

### Phase 2 : umlauts et ß (06/11/2026)

Fichiers : `WordForms` (repli : ß -> ss ; `transliterateGerman` : ä -> ae, ö -> oe, ü -> ue, ß -> ss), `AccentPolicy.GERMAN`, `Dictionary`
(index des transcriptions, rattachées au mot à umlaut), `DictionaryLoader.accentPolicyOf`, profil `GERMAN` du banc. Tests : `GermanAutocorrectionTest`,
`WordFormsGermanTest`.
- **Mot absent de la liste** : umlaut oublié (« uber » -> « über »), transcription (« fuer », « koennen », « Maedchen ») et « ss » pour ß
  (« Strasse » -> « Straße ») sont rétablis d'office, avant la recherche par distance (comme les accents français).
- **Mot présent dans la liste** : corrigé seulement si la forme à umlaut est au moins 50 fois plus fréquente (10 fois pour ß), sauf une table de
  mots justes (`ware`, `schone`, `hohle`, `masse`, `busse`, `massig`...). Les paires de deux mots justes (konnte / könnte, schon / schön, wurde / würde,
  wurdest / würdest) ne sont plus tranchées par l'accent seul : c'est le travail du contexte (phase 5). Seuils choisis sur les paires de la nouvelle `de.txt`
  (à 50 : 66 paires, dont 62 dont la forme sans umlaut est un « mot » des lexiques mais en pratique un oubli d'umlaut : uber, tur, gluck).
- **Transcription présente dans la liste** (« ueber » 156, « buero » 11) : traitée comme une forme dominée.
- **Limite connue** : « Strasse », « heiss », « fussball » sont des mots justes en Suisse ; ils sont corrigés vers ß pour tous les claviers jusqu'à la
  phase 6 (réglage de-CH). « grosser » -> « großer » même quand la phrase veut « größer » (le contexte départagera).

Mesures (banc `--lang=de`, vraie `de.txt` nettoyée ; avant = phase 1 seule) : fautes courantes bien corrigées 82,3 -> 95,6 % (mal corrigées 17,56 -> 4,39 %),
variées 89,2 -> 96,0 %, `ss-pour-eszett` 34,1 -> 96,8 %, `ae-oe-ue` 55,6 -> 99,5 %, phrases justes changées 1 -> 0 (« Er konnte » n'est plus changé en
« könnte »), cas de contexte 36,7 -> 56,4 % (eszett 15 -> 92 %, translit 19 -> 100 %). Un cas passait par chance et échoue maintenant (« Was wurdest du tun ? »,
wurde-würde : contexte, phase 5). `composes.rares.faux` inchangé (23,8 %) : phase 4. Français, anglais, espagnol : mesures identiques à celles d'avant la phase.
Compilation et banc faits hors Gradle avec le compilateur Kotlin de Gradle ; `tools/autocorrect-bench/de/baseline.txt` est écrit par ce moteur. Le module `app`
(`DictionaryLoader`) n'est pas compilé.

### Phase 3 : noms et prénoms (06/11/2026)

Décision du 06/11/2026 (D1) : **pas de majuscule automatique** par réglage ni par contexte. Le plan prévoyait un correcteur de contexte (3b) et un
réglage (3c) : ils ne sont **pas faits**. À la place, une liste de noms communs et de prénoms, comme pour le français (3a). Source des noms (D2) : fixée
quand on travaillera les dictionnaires ; en attendant, une liste de départ de 363 noms écrite à la main. Textes de test : pas de relecture germanophone (D3).

Fichiers : `tools/build_de_names.py` (construit `dictionaries/names/de.txt`, `--self-test`, `--check`), `tools/de-names/` (`vornamen.txt`, `nomen-seed.txt`,
`ambigu-ok.txt`), `Dictionary` (`properNameForm`, index des noms rares par longueur, transcription des noms à umlaut), `AccentPolicy.nounsKeepValidWords`,
test `GermanNounsTest`.
- **Résultat** : `names/de.txt` = 442 entrées (90 prénoms + 352 noms). « haus » -> « Haus », « hauss » -> « Haus », « krankenhauss » -> « Krankenhaus » ; un nom
  déjà bien écrit, en capitales ou dans le dictionnaire personnel n'est pas touché ; les annulations passent par le système d'habitudes existant.
- **Garde-fou propre à l'allemand** : un mot présent dans les listes n'est jamais remplacé par un nom pour son seul umlaut (« hohe » reste « hohe », jamais « Höhe »).
- **Vitesse** : les noms trop rares pour être candidats sont rangés par longueur (avant : parcours de toute la liste à chaque correction, acceptable pour 90 prénoms,
  pas pour des dizaines de milliers de noms).
- **Filtre par les lexiques** : 10 noms de départ retirés (Arm, Junge, Klasse, Kosten, Laden, Regen, Rücken, Schmerzen, Stimme, Weg : vrais mots en minuscules).
  Les lexiques contiennent aussi des premières parties de composé en minuscules (« kinder », « garten ») : 46 noms relus à la main passent malgré le filtre
  (`ambigu-ok.txt`). Accord brut avec l'oracle : 90,0 % (398 / 442), **sous l'objectif de 95 %** du plan, à cause de ces 46 : l'objectif se mesurera sur la liste du corpus.
- **Limite connue** : un nom dont la forme en minuscules est un verbe conjugué absent des lexiques (« ich frage ») ne se détecte pas par eux ; seul le corpus
  (part de majuscules >= 95 % hors début de phrase) le fera. La liste de départ évite ceux que je connais.
- **Attendu au banc** : `contexte.nomen.ok` 0 -> ~100 % (les 16 cas ; « hausaufgaben » est dans la liste), `contexte.vorname.ok` inchangé, `phrases.justes.changees` 0
  (vérifié en Python : aucun mot en minuscules des 144 phrases justes n'est dans la liste, depuis le retrait de « Recht »). **Non mesuré** : ni Kotlin ni Gradle ici ;
  à faire chez toi : `./gradlew :core:test` puis `./gradlew :core:run --args="--lang=de --write-baseline"`, relire la référence avant de l'enregistrer.
- **À faire côté app** : déposer le nouveau `names/de.txt` sur R2 (`dictionnaries/names/de.txt`). Le module `app` n'a pas été modifié ni compilé.

**Extension de la liste de noms (07/11/2026)** : `names/de.txt` passe de 442 à 1 884 entrées (1 442 de plus : 1 161 noms communs, singuliers et pluriels courants, et 282 prénoms
allemands et internationaux). Choix à la main parmi les mots les plus fréquents de `de.txt` ; chaque forme est présente dans `de.txt`. Écartés : les noms dont la forme en
minuscules est aussi un verbe, un adjectif ou un adverbe courant (voir `dictionaries/SOURCES.txt`). Contrôles faits en Python : format (lettres seulement, majuscule initiale,
sans doublon), aucun mot en minuscules de `sentences-ok.txt` ni de `valid-rare-words.txt` n'est dans la liste, aucune entrée de l'ancienne liste perdue, `build_de_names.py --check` à jour.
**Non fait** : filtres « ambigu » et « verbe » (pas de lexique ici), banc et tests Kotlin non exécutés. À faire chez toi : `./gradlew :core:test`, puis le banc `--lang=de` et comparer à
`baseline.txt` avant de l'enregistrer (surveiller `phrases.justes.changees`, qui doit rester à 0 : un mot qui y bouge est un nom ambigu à retirer de `nomen-seed.txt`) ;
si tu relances `build_de_names.py --lexicon ...`, relire `dropped.txt` : des noms valides en première partie de composé (Garten, Kinder...) peuvent y figurer, à ajouter à `ambigu-ok.txt` ;
déposer `names/de.txt` sur R2 (`dictionnaries/names/de.txt`).


### Phase 4 : mots composés (06/11/2026)

Fichiers : `CompoundRules` et `InflectionRules.GERMAN` (`InflectionRules.kt`), `Dictionary.isCompoundForm` / `splitsIntoKnownParts` / `compoundMergeFor`,
branchement dans `DictionaryLoader.inflectionsFor` et dans le profil `GERMAN` du banc, test `GermanCompoundsTest`.
- **Règle** : un mot absent des listes est juste s'il se découpe en au moins deux parties connues (>= 4 lettres, fréquence >= celle des candidats du dictionnaire,
  pas une faute dont la forme accentuée domine), reliées par une liaison parmi « », s, n, en, e, er, es ; **sauf** s'il est à une faute de frappe (distance 1) d'un
  mot fréquent : c'est le garde-fou. Un mot à chiffre ou apostrophe n'est jamais un composé.
- **Réglage** (portage Python, `de.txt` + les 1 501 composés rares du banc, fautes simulées sur mots courants et variés ; script jeté, non conservé) :
  | Paramètres (partie min, fréquence des parties, garde-fou) | Rares acceptés | Fausses acceptations (courants / variés) |
  |---|---|---|
  | découpeur seul, sans garde-fou (plan) | — | 4,9 % mesuré en version simple |
  | **4 lettres, fréquence 41, garde-fou distance 1 (retenu)** | **50,3 %** | **0,07 % / 1,17 %** |
  | 4 lettres, fréquence 200 | 35,3 % | 0,04 % / 1,07 % |
  | 5 lettres, fréquence 300 | 20,5 % | 0,00 % / 0,27 % |
  Sur des mots longs à 2 ou 3 fautes : 0,05 à 0,35 % de fausses acceptations. Un réglage plus strict accepte moins de composés rares pour un gain de précision
  faible : le réglage retenu garde la fréquence des candidats (41).
- **Attendu au banc** : `composes.rares.faux` 23,8 % -> nettement plus bas (la moitié des rares n'est plus touchée) ; `inconnus.*.faux` en baisse ; fautes non corrigées
  et `typos.*.wrong` à surveiller (hausse possible de `surMotValide`). **Non mesuré en Kotlin** (ni Kotlin ni Gradle ici).
- **Proposition de réunion** (« Haus tür » -> « Haustür ») : la logique est faite et testée (`Dictionary.compoundMergeFor`, jamais appliquée d'office) : les deux mots doivent
  être dans la liste de noms, la réunion être un mot des listes ou un composé valide. **Non branchée dans l'app** : la bande ne sait remplacer que le dernier mot
  (`ContextualCorrection.lastWordFix` écarte toute correction qui toucherait un autre mot), il faut un emplacement qui remplace « mot + espace + mot ».
  Limite connue : la liste de noms contient des prénoms, « Herr Thomas » pourrait être proposé en « Herrthomas ».

### Phase 5 : contexte allemand (06/11/2026)

Fichiers : `GermanConfusions.kt` (table de règles + `GermanContextCorrector`), extension du moteur `FrenchConfusions.fixWith` (voir plus bas), branchement dans
`ContextualCorrection.correctWithoutHabits` (DE), `SuggestionController.CONTEXTUAL_LANGUAGES` (DE) et le profil `GERMAN` du banc, test `GermanConfusionsTest`.
- **Extensions du moteur** (sans effet sur le français, l'anglais et l'espagnol, dont les règles n'utilisent aucune des deux) : `ConfusionRule.next2` (condition sur le deuxième mot
  suivant : la règle attend ce mot, `lookahead` = 2) et `ConfusionRule.afterComma` (le mot qui précède peut être suivi d'une virgule : « Ich weiß, das du... »). `fixWith` essaie
  maintenant les mots à rebours de 2, 1 puis 0 rang ; une règle ne s'applique qu'à `dernier mot - lookahead`, ce qui est exactement l'ancien comportement pour 0 et 1.
- **Familles** : seid / seit, wie -> wir, wurde(st/n/t) -> würde(st/n/t), das -> dass, mal -> Mal, morgen / abend / nacht / mittag -> majuscule, ihn <-> ihm (voir l'en-tête de
  `GermanConfusions` pour les contextes exacts et ce qui est volontairement non couvert).
- **Mesure** (simulateur Python du moteur, mêmes règles que le Kotlin généré ; script jeté, non conservé) : 144 phrases justes du banc et 80 phrases justes pièges écrites pour
  ce test (« Wie sind die Preise ? », « Ich wurde gefragt », « Das Buch, das er liest », « Ihr seid drei Brüder »...) : **0 changée**. Cas de contexte du banc :
  seid-seit 6/7, wir-wie 3/7, wurde-würde 4/6, das-dass 6/6, mal-Mal 4/4, tageszeit 5/5 (avant : 0, 0, 0, 16,67, 25, 20 %).
- **Cas laissés en échec exprès** : « Seit ihr schon fertig ? » (« Seit ihr hier wohnt » est juste), « waren wie im Kino » et « essen wie zu Hause » (« wie » comparatif juste),
  « Wie gehen jetzt nach Hause » et « Wie können morgen kommen » (« Wie gehen jetzt die Geschäfte ? » est juste), « ich wurde das nie tun » (« Ihm wurde das nie gesagt » est juste),
  « wurden gern früher gehen » (« wurden gern früher geholt » est juste). Même principe qu'en français : une règle dont le contexte reste possible dans une phrase juste est retirée.
- **Non mesuré en Kotlin** (ni Kotlin ni Gradle ici) : `./gradlew :core:test`, puis le banc, avant de régénérer `baseline.txt`. Le banc devrait montrer `contexte.total.ok` en hausse nette,
  `phrases.justes.changees` à 0, et les cas français / anglais / espagnol inchangés (tests `FrenchConfusionsTest`, etc., à relancer pour le moteur étendu).
- **Pas fait (plan)** : konnte / könnte et hatte / hätte (promesse écartée dès le plan), ihnen, dass -> das, « Seit ihr » en début de phrase.

### Phase 6 : variantes pays (06/11/2026)

Fichiers : `GermanVariants.kt` (`usesSwissSpelling`, `swissFrequencies`, `swissNames`), `DictionaryLoader.load` / `swissSpelling`, formes « weiss », « weisst » et « bloss »
dans les listes de `GermanConfusions` (das -> dass et seid -> seit), test `GermanVariantsTest`.
- **de-CH** : pas de « ß » (0 sur environ 149 000 entrées des lexiques suisses). Quand la langue du clavier est l'allemand **et** que la région du système est la Suisse ou le
  Liechtenstein, le chargeur remplace tous les « ß » par « ss » dans la liste de fréquence (les formes qui se rejoignent, « masse » et « maße », additionnent leurs fréquences)
  et dans la liste de noms. « strasse » devient un mot juste au lieu d'être rétabli en « straße ». Aucun autre changement : umlauts, composés et contexte sont les mêmes.
- **de-AT** : rien, comme prévu. Les 50 000 mots propres au lexique autrichien sont surtout des composés ordinaires, déjà acceptés par la phase 4. À faire relire par un Autrichien.
- **Limites** : la région est lue au chargement du dictionnaire (mis en cache par langue) ; un changement de région du système ne prend effet qu'au rechargement (redémarrage du
  clavier ou `DictionaryLoader.evict`). Ce n'est pas un réglage dans l'app : c'est la région du système, comme le plan le demandait. La disposition clavier n'est pas touchée (le
  « ß » reste en appui long sur « s » aussi en Suisse ; à retirer à part si tu veux).
- **Non mesuré en Kotlin** (ni Kotlin ni Gradle ici) : `./gradlew :core:test`. Pas de banc suisse : le banc `de` mesure l'allemand d'Allemagne.

### Phase 4 (suite) : réunion de deux noms dans la bande (06/11/2026)

Fichiers : `WordSuggestion.Kind.COMPOUND`, `WordText.wordBeforeSingleSpace`, `SuggestionController.compoundSuggestion` / `withCompound`,
`TaipoIme.insertSuggestedCompound` (champ) et la branche `COMPOUND` de `onPromptWordSuggestionTapped` (prompt), tests dans `GermanCompoundsTest`.
- **Comportement** : en allemand, quand on tape un nom juste après un autre nom séparé par une seule espace (« Haus tür »), la bande met dans son **dernier** emplacement
  le composé (« Haustür », avec majuscule). Toucher cet emplacement efface les deux mots et l'espace entre eux, écrit le composé suivi d'une espace, et se défait comme une
  autre suggestion (`recordUndoEdit`). Jamais appliqué à l'espace ni en gras : seulement si on le touche.
- **Quand il apparaît** : tant que le second mot est en cours de frappe, curseur à la fin du mot (pas en mode « curseur posé sur un mot »). Il **disparaît dès l'espace** qui suit
  le second mot : il faut le toucher avant. Choix de départ, le plus simple ; on pourrait le garder après l'espace si ça gêne.
- **Si une adresse e-mail est aussi proposée**, elle prend le dernier emplacement (comme pour les mots) : le composé n'apparaît pas à ce moment-là.
- **Non essayé** : ni compilé ni lancé sur un téléphone (pas de Kotlin ni Android ici). À vérifier à la main : taper « Haus tür », voir « Haustür » à droite, le toucher, puis Supprimer
  pour revenir à « Haus tür » ; même chose dans le prompt.


**Banc allemand et majuscule des noms (07/11/2026)** : avec la liste de noms étendue, « haus » est corrigé en « Haus » (voulu). Le banc comptait cela comme une mauvaise correction
(`typos.*.wrong` et `longues.*.wrong` en hausse, `.ok` en baisse). `AutocorrectBench.sameWord` accepte désormais, en allemand seulement, un résultat qui ne diffère du mot attendu que par
la casse. Le code du moteur n'a pas changé. Le français est inchangé : son écart avec la référence est celui décrit plus haut (enrichissement de `fr.txt` du 04/11/2026), à relire
puis à régénérer avec `--write-baseline`.

**CI rouge du 07/11/2026 : « ss » pour « ß » et références régénérées** : `AutocorrectBaselineTest` échouait en allemand et en français.
- Allemand, vraie régression (`typos.*.ss-pour-eszett.ok` 96,8 -> 93,2 et 97,6 -> 88,8). Deux causes dans `Dictionary` :
  `isCompoundForm` passait avant `accentRestoration`, donc « grosszügig » était accepté comme « gross » + « zügig » au lieu de devenir « großzügig »
  (idem « gewissermassen », « grosseltern »). Un mot des listes qui ne diffère du mot tapé que par les accents, l'umlaut ou ß l'emporte maintenant
  sur le découpage. Et `accentRestoration` ignorait l'umlaut déjà tapé (« grösser » -> « großer », plus fréquent). Il garde maintenant les formes
  qui contiennent les accents tapés, quand il y en a (`keepsTypedAccents`). Résultat : ss-pour-eszett à 99,9 / 99,2. Rien ne change en fr, en et es.
- Allemand, compromis gardé : `longues.*.deux.ok` perd un cas sur 300 (« komandaten » accepté comme « koma » + « daten »). Chercher les composés
  à distance 2 le récupère, mais fait monter `inconnus.varies.faux` (81,67 -> 82,67) : on n'a pas fait ce changement.
- Les deux références sont régénérées avec `--write-baseline`. L'allemande datait d'avant les phases 4 et 5 (`contexte.total.ok` 56,41 -> 90,6) ;
  la française prend en compte l'enrichissement de `fr.txt` du 04/11/2026 (voir plus haut).
- À noter : `valides.*.restaure` (non contrôlé) passe de 0,16 à 7,94 en allemand. C'est la majuscule des noms (« haus » -> « Haus ») sur les mots justes.

**Allemand sans élision (07/11/2026)** : l'allemand utilisait `CorrectionConfidence.NONE`, qui garde la restauration d'élision du français
(« danne » -> « d'anne », « sins » -> « s'ins », « leiner » -> « l'einer »). Nouveau profil `CorrectionConfidence.GERMAN` = `NONE` sans élision,
utilisé par `DictionaryLoader` et le banc. Banc : `typos.courants.wrong` 3,88 -> 3,64, `contexte.tippfehler.ok` 86,67 -> 93,33, aucune mesure en baisse ;
référence `de/baseline.txt` régénérée. L'italien garde ses élisions ; le portugais et le néerlandais restent sur `NONE` (non mesurés).

**Feuille de route fr/en (07/11/2026)** : bigrammes livrés, score unique, protection des mots inconnus et complétion tolérante aux
fautes, avec un banc sur phrases réelles (`heldout.*`, `completion.*`). Détail, mesures et suites dans `docs/autocorrection-roadmap.md`.
Le banc lit `dictionaries/bigrams/<code>.txt` s'il existe ; les références fr et en sont régénérées.

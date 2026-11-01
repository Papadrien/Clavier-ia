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

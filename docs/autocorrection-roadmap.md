# Autocorrection française et anglaise : feuille de route (07/11/2026)

Objectif : rapprocher l'autocorrection de Taipo de celle de Gboard ou SwiftKey. Le principal écart était le **contexte** : le
moteur choisissait sa correction avec la distance d'édition et la fréquence, puis appliquait des règles de contexte écrites à la
main. Les claviers du marché pèsent ensemble « quel mot est attendu ici » et « quelle faute a été tapée ».

Toutes les mesures viennent du banc (`docs/autocorrection-bench.md`, `AutocorrectBench`) : clavier AZERTY (fr) ou QWERTY (en),
graine fixe, phrases jamais vues par le modèle de bigrammes.

## Ce qui est fait

| Point | Ce qui change | Où | Effet mesuré |
|---|---|---|---|
| 1. Modèle n-gram livré | Bigrammes tirés de Tatoeba (fr : 255 000 paires, en : 400 000), le mot précédent départage les candidats et empêche « la lune » -> « la l'une », « fallen ill » -> « I'll » | `NgramModel`, `Dictionary.correctionFor(previousWord)`, `CorrectionConfidence.contextWeight` (1,0), `tools/build_ngrams.py` | Fautes en phrase corrigées : fr 89,4 -> 92,4 % (mauvaises 5,2 -> 3,1 %) ; en 92,7 -> 95,5 % (2,8 -> 1,2 %). Mots justes changés à tort en phrase : fr 0,32 -> 0,18 %, en 0,05 -> 0 % |
| 2. Score unique | Les candidats à 1 et 2 erreurs entrent en concurrence sur un seul score, et un rival à 2 erreurs compte dans la marge de confiance | `CandidateContest(distancePenalty)`, `CorrectionConfidence.distancePenalty` (0) | Effet faible : le coût de saisie (20 à 40 par erreur) pénalisait déjà la distance. Mots longs à 2 erreurs : + 0,3 point ; en, mots inconnus corrigés à tort : 63 -> 59 % |
| 4. Mots inconnus | (a) un mot inconnu gardé deux fois n'est plus corrigé ; (b) un mot de moins de 6 lettres à deux erreurs n'est corrigé que si le mot précédent soutient la correction ; (c) fr et en : un mot inconnu à majuscule juste après un autre mot n'est remplacé que par un nom connu | `HabitContext.isLearnedWord`, `CorrectionConfidence.twoEditsMinLengthWithoutContext` (6), `protectsMidSentenceCapitals` | (b) mots justes inconnus remplacés : fr 82 -> 79 %, en 59 -> 58 %, sans bonne correction perdue. (a) et (c) : tests unitaires (le banc n'a ni habitudes ni majuscules) |
| 5. Complétion tolérante | Les emplacements libres de la bande reçoivent les mots dont le début est à une erreur de ce qui est tapé (« aujoudr » -> « aujourd'hui ») | `Dictionary.fuzzyCompletions`, `DictionarySuggestions.completionsFor` | Mot retrouvé malgré une faute dans ses 2 premières lettres : fr 60,7 -> 88,0 %, en 65,3 -> 93,6 %. Frappes économisées sur un texte juste : inchangées (fr 44,6 %, en 48,1 %). Temps de la bande p95 sur PC : 1,8 -> 2,1 ms |
| 6. Mesure | Banc sur phrases réelles mises de côté : fautes en contexte (`heldout.fautes.*`), mots justes changés (`heldout.justes.faux`), frappes économisées et complétion malgré une faute (`completion.*`) | `AutocorrectBench.heldoutMeasures`, `completionMeasures`, `tools/autocorrect-bench/heldout/` | Références fr et en régénérées, `AutocorrectBaselineTest` les garde |

Corrigés au passage grâce au banc sur phrases : le possessif pluriel anglais (« teachers' » perdait son apostrophe).

L'allemand, l'espagnol, l'italien, le portugais et le néerlandais ne changent pas : `contextWeight` 0, pas de pénalité de distance, pas de
bigrammes.

## Écarté

**Point 3, modèle spatial à partir des points de contact** : pas nécessaire. Les largeurs de touches s'adaptent déjà à la probabilité de la
lettre suivante (touches adaptatives, `NextLetterModel`), ce qui couvre l'essentiel du besoin.

## Pour plus tard (point 7)

- **Frappe glissée** (swipe) : incontournable chez Gboard et SwiftKey, mais c'est un projet à part entière (décodage d'une trace sur le
  clavier, avec le même dictionnaire et les mêmes bigrammes).
- **Petit réseau de neurones** pour le mot suivant et le reclassement des corrections (par exemple un LSTM de 1 à 2 millions de paramètres,
  comme celui de Gboard), via LiteRT, déjà présent. Le modèle Gemma du bouton Corriger reste trop lourd pour tourner à chaque touche.

## À faire de ton côté

1. **Déposer les bigrammes sur R2** : `dictionaries/bigrams/fr.txt` et `dictionaries/bigrams/en.txt` vers `dictionaries/bigrams/fr.txt` et
   `dictionaries/bigrams/en.txt` (voir `docs/dictionnaires-distants.md`). Sans eux, le clavier fonctionne exactement comme avant : le fichier est
   facultatif, téléchargé au mieux une fois par processus (`BigramDownloadWorker`).
2. **Vérifier sur un téléphone** : temps de la bande de suggestions avec la complétion tolérante (2,1 ms au p95 sur PC, la recherche parcourt les
   50 000 mots proposables) et mémoire des bigrammes (environ 5 Mo pour l'anglais).
3. **Décider du taux d'annulation en production** : les annulations sont déjà comptées (`AutocorrectStats` en debug, événement
   `autocorrect_undone`), mais sans le nombre de corrections appliquées on ne peut pas en faire un taux. Il faudrait un événement agrégé (par
   exemple un compte par session), ce qui change ce que les statistiques d'usage collectent : à décider (`docs/analytics.md`).

## Régénérer les bigrammes

```
curl -O https://downloads.tatoeba.org/exports/per_language/fra/fra_sentences.tsv.bz2
curl -O https://downloads.tatoeba.org/exports/per_language/eng/eng_sentences.tsv.bz2
python3 tools/build_ngrams.py --lang fr --sentences fra_sentences.tsv.bz2
python3 tools/build_ngrams.py --lang en --sentences eng_sentences.tsv.bz2
```

L'outil réécrit aussi les phrases du banc (`tools/autocorrect-bench/heldout/<code>.txt`) : une phrase Tatoeba sur 50 n'est jamais comptée dans
les bigrammes. Relancer ensuite le banc et relire la différence des références avant de les régénérer.

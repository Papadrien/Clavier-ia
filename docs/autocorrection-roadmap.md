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

## Autres langues : italien, portugais, néerlandais (07/11/2026)

Les trois langues du clavier qui gardaient le comportement d'avant (réglages du français, aucune règle de contexte) ont maintenant le
même moteur que le français et l'anglais, adapté à chacune. Banc : `--lang=it`, `--lang=pt`, `--lang=nl` (références
`tools/autocorrect-bench/<code>/baseline.txt`, gardées par `AutocorrectBaselineTest`) ; `--lang=it-legacy` (etc.) mesure l'état d'avant.

| Mesure | Italien | Portugais | Néerlandais |
|---|---|---|---|
| Phrases justes changées à tort | 8,6 -> 0 % | 9,2 -> 0 % | 4,8 -> 0 % |
| Cas de contexte réussis | 8 -> 92 % | 41 -> 97 % | 16 -> 96 % |
| Fautes en phrase mal corrigées | 3,8 -> 0,9 % | 4,0 -> 1,6 % | 4,7 -> 1,8 % |
| Fautes en phrase bien corrigées | 96,2 -> 98,4 % | 93,9 -> 95,6 % | 95,3 -> 97,0 % |
| Fautes isolées mal corrigées (courants) | 5,4 -> 2,2 % | 4,2 -> 2,7 % | 4,8 -> 2,9 % |
| Mots justes inconnus remplacés (courants) | 98 -> 84 % | 81 -> 73 % | 96 -> 79 % |
| Mot retrouvé malgré une faute au début | 83 -> 88 % | 82 -> 86 % | 82 -> 86 % |
| Accent oublié rétabli (courants) | 86 -> 99,8 % | 99,3 -> 95,9 % (1) | 100 % |

(1) Baisse de mesure, pas de moteur : 1 094 formes sans accent du corpus (« nao », « voce ») sont désormais des fautes, donc comptées ;
les échecs restants sont surtout des formes fautives tirées comme mot attendu (« vocé », « coraçao ») ou des variantes Portugal / Brésil
(« blasfémia » / « blasfêmia »).

**Ce qui change** :

- **Listes de mots réparées** (`tools/complete_from_tatoeba.py`) : les listes d'origine avaient perdu les mots à apostrophe (it : « c'è »,
  « dov'è », 282 mots ; nl : « zo'n », « m'n ») et des mots très courants (it : « un », d'où « un problema » -> « in problema » ; pt :
  « agente »). Les formes sans accent des sous-titres (it : « piu », « cosi », « citta » ; pt : « nao », « voce », « tambem » ; nl : « cafe »,
  « israel ») sont rétrogradées quand Tatoeba, correctement accentué, ne les emploie presque jamais (moins de 0,5 % de la forme accentuée) ;
  les paires justes (« torno » / « tornò », « forca » / « força ») restent. Fautes d'accent italiennes « perchè », « finchè »... rétrogradées.
- **Réglages** : `CorrectionConfidence.ITALIAN` / `PORTUGUESE` / `DUTCH` (seuils de l'anglais, mot précédent, score unique, protection des
  mots inconnus ; seul l'italien rétablit les élisions) ; `AccentPolicy.ITALIAN` / `PORTUGUESE` / `DUTCH` (seuil de 10 et paires justes :
  e / è, esta / está, een / één...) ; `InflectionRules.DUTCH` (« foto's » et mots composés, comme l'allemand) et `PORTUGUESE`.
- **Règles de contexte** : `ItalianConfusions` (e / è, c'è, po', ho / ha / hanno devant un participe, dà, sì, qual è),
  `PortugueseConfusions` (e / é, esta / está, há, mais, a gente, mots soudés), `DutchConfusions` (d / dt de worden, vinden, houden,
  antwoorden ; jou / jouw ; hun / zij ; 's avonds, zo'n). Chacune liste ce qu'elle ne couvre volontairement pas.
- **Bigrammes** Tatoeba : it 280 000 paires, pt 202 000, nl 88 000 (`dictionaries/bigrams/`).
- **Moteur, toutes langues** : une apostrophe initiale (« 's », « 't ») ou finale après un mot connu (« po' ») est voulue, jamais corrigée.

**À faire de ton côté** : déposer sur R2 les bigrammes `dictionaries/bigrams/{it,pt,nl}.txt` **et les listes modifiées**
`dictionaries/{it,pt,nl}.txt`. Attention : l'app ne remplace pas une liste déjà téléchargée (`docs/dictionnaires-distants.md`, « Limites ») ;
les utilisateurs qui ont déjà l'italien, le portugais ou le néerlandais gardent l'ancienne liste tant qu'elle n'est pas retéléchargée.

**Restent en échec dans le banc** (objectifs) : it « la citta e bella » (« e » + adjectif après un nom, ambigu : « rosso e bello ») et
« pero » (vrai mot, le poirier) ; pt « amanhá » -> « amanhã » ; nl « trien » (mot de la liste).

## Listes de mots alignées sur AOSP et LibreOffice (08/11/2026)

Les sept listes (`dictionaries/<code>.txt`) sont passées par `tools/align_open_lexicons.py`, avec deux sources ouvertes :

- **AOSP** (listes du clavier Android d'origine, Apache 2.0, publiées par HeliBoard : https://codeberg.org/Helium314/aosp-dictionaries,
  `wordlists/main_<locale>.combined`, versions « legacy ») : pour valider ET ajouter. Le portugais du Brésil (`main_pt_BR`, liste AOSP
  modifiée par un tiers, licence introuvable) sert seulement à valider ; les ajouts portugais viennent de `main_pt_PT`. Les listes
  « experimental » (corpus Leipzig) ne sont pas utilisées : elles ont les mêmes défauts que nos sous-titres.
- **LibreOffice** (dictionnaires Hunspell : https://github.com/LibreOffice/dictionaries) : pour valider seulement, aucun mot recopié.

**Retraits** (mot inconnu des deux sources, fréquence < 1 000), trois cas seulement : faute d'accent (« plutot », « riprendera »,
« mantem ») ; faute de frappe d'un mot au moins 50 fois plus fréquent (« appeller », « mourrir ») ; mot anglais dans une autre langue
(« said », « sugar »). Un mot inconnu des deux sources peut être juste : prénoms, argot (« lol », « vc »), diminutifs, impératifs
familiers allemands (« lern ») restent, de même qu'un mot employé dans Tatoeba, un mot que remplace une règle de contexte (« aswell »),
une élision, une forme régulière ou un composé de mots connus.
**Ajouts** : mots AOSP de note f >= 30 absents de la liste (en dessous : surtout des noms de famille et du bruit, « sher »), avec la
fréquence qu'ont dans la liste les mots AOSP de même note, plafonnée à 3 fois le seuil de proposition.

| Langue | Retirés | Ajoutés (dont proposables) |
|---|---|---|
| fr | 35 643 | 54 454 (2 649) |
| en | 12 161 | 16 496 (782) |
| es | 12 247 | 50 780 (544) |
| de | 9 754 | 120 540 (4 142) |
| it | 32 034 | 66 277 (2 852) |
| pt | 26 775 | 58 315 (783) |
| nl | 27 632 | 54 685 (1 992) |

Seuils de proposition recalés pour garder environ 50 000 mots proposables : espagnol 183 -> 142, allemand 41 -> 48.

**Mesure** : retirer des faux mots fait entrer dans le calcul des fautes qui tombaient avant sur eux (jamais corrigées au clavier, mais
écartées du banc). Les taux bruts du banc ne se comparent donc plus ; voici les taux sur **toutes** les fautes tirées :

| Langue | Fautes en phrase bien corrigées (1) | mal corrigées (1) | tombées sur un faux mot valide | Fautes isolées bien corrigées (1) | Frappes économisées |
|---|---|---|---|---|---|
| fr | 80,2 → 83,6 % | 2,7 → 2,9 % | 13,2 → 10,8 % | 29,1 → 74,8 % | 44,6 → 42,6 % |
| en | 76,7 → 81,3 % | 1,0 → 1,2 % | 19,7 → 14,6 % | 20,3 → 20,5 % | 48,1 → 47,0 % |
| es | — | — | — | 71,4 → 77,1 % | — |
| de | — | — | — | 65,7 → 65,9 % | — |
| it | 78,4 → 88,6 % | 0,7 → 1,1 % | 20,3 → 9,4 % | 73,0 → 76,3 % | 46,3 → 43,8 % |
| pt | 79,4 → 85,1 % | 1,3 → 1,7 % | 16,9 → 11,2 % | 20,2 → 20,8 % | 42,9 → 41,4 % |
| nl | 73,5 → 82,1 % | 1,3 → 1,6 % | 24,2 → 15,1 % | 19,0 → 43,3 % | 45,8 → 44,5 % |

(1) Sur toutes les fautes tirées, y compris celles qui tombent sur un mot valide. « Fautes en phrase » : phrases mises de côté
(`heldout`), pas encore de jeu pour l'espagnol et l'allemand. Les cas de contexte et les phrases justes ne bougent pas (0 % de phrases
justes changées dans les sept langues) ; les mots composés allemands justes remplacés à tort passent de 18,4 à 11,8 %.

**Compromis connu** : les frappes économisées sur un texte juste baissent de 1 à 2,5 points. Quand le début d'un mot tapé était lui-même
un faux mot valide (« beca… »), la bande montrait trois complétions ; il reçoit maintenant une correction en gras, et une seule
complétion reste visible. Piste : garder deux complétions quand la correction porte sur un mot encore en cours de frappe.

Références de toutes les langues régénérées (`AutocorrectBaselineTest`) ; l'espagnol a désormais la sienne. Détail des mots retirés
(avec la raison) et extrait des ajouts : `docs/dictionnaires-2026-11-08/`. Mention AOSP (Apache 2.0) ajoutée dans l'écran « Licences ».

**À faire de ton côté** : redéposer sur R2 les sept listes `dictionaries/<code>.txt` et les bigrammes `dictionaries/bigrams/{fr,en,it,pt,nl}.txt`.
Rappel : l'app ne remplace pas une liste déjà téléchargée.

# Dictionnaire allemand, 06/11/2026 (phase 1 du plan d'autocorrection allemande)

Produit par `tools/clean_de_dictionary.py` (règles dans son en-tête) avec les lexiques de clavier de_DE, de_AT et de_BE comme
validateurs seulement : aucun mot ni aucune fréquence n'en est repris. Le lexique de_CH n'était pas disponible : ses formes propres
(« ss » à la place de « ß ») ne sont donc pas validées par lui.
240 489 -> 117 170 mots. Fichiers :
- `umlaut-restores.txt` : formes sans umlaut retirées avec leur forme à umlaut, rétablie par `Dictionary` (fur -> für, fuer -> für) ;
- `old-spelling.txt` : ancienne orthographe retirée (laß -> lass) ;
- `ocr.txt` : « I » lu « l » retiré (lhr -> ihr) ;
- `debris.txt` : lettre répétée 4 fois ou plus (chffffff, aaaah) ;
- `demoted.txt` : mots anglais fréquents passés à la fréquence 20 (valides, jamais proposés ; seuil de proposition 41) ;
- `rescued-compounds.txt` : 636 composés rares (fréquence 100 à 999, deux parties courantes) gardés à la fréquence 20 ;
- `kept-unlisted.txt` : mots de fréquence >= 1 000 absents des lexiques, gardés tels quels.
Les 122 108 mots rares retirés ne sont pas conservés (relancer l'outil écrit `pruned.txt`). Y figurent aussi de vrais mots que les lexiques
ignorent (dahinten, rauswerfen, idiotin, tretet) : à surveiller avec `composes.rares.faux` et à compléter par `KEEP_WORDS`.
Seuil de proposition : 61 -> 41 (50 158 mots proposables). Exposition des 1 500 composés rares du banc : 106 (7,1 %) ont un mot proposable
à une modification.

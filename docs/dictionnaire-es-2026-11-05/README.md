# Dictionnaire espagnol, 05/11/2026

Produit par `tools/clean_es_dictionary.py` (voir son en-tête pour les règles). Fichiers :
- `plain-variants.txt` : formes sans accent retirées, avec leur forme accentuée (rétablie par `Dictionary`) ;
- `demoted.txt` : mots anglais fréquents passés à la fréquence 20 (valides, jamais proposés) ;
- `accented-kept.txt` : formes accentuées absentes des lexiques, gardées à 20 ;
- `kept-unlisted.txt` : mots de fréquence >= 1 000 absents des lexiques, gardés tels quels.
La liste des 188 032 mots retirés n'est pas conservée (2 Mo) : relancer l'outil la régénère (`pruned.txt`).

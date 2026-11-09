# Enrichissement de fr.txt (04/11/2026)

- `added.txt` : mots ajoutés (mot fréquence).
- `raised.txt` : mots déjà dans fr.txt mais ignorés (fréquence < 5), relevés à 20 ou 60.
- `demoted.txt` : mots passés à 99 (valides, plus proposés).
- `skipped.txt` : mots de référence non retenus, par motif.

Régénérer : `python3 tools/enrich_fr_dictionary.py --dict-dir dictionaries --gboard <fr_FR_lexicon.txt> --swiftkey-tsv <fr_FR_all.tsv> --out-dir <dossier>`.
Pour annuler un déclassement ou un ajout : retirer la ligne de fr.txt (ajout) ou remettre l'ancienne fréquence (voir les listes).
Le fichier est à déposer sur le bucket R2 ; les installations existantes ne le retéléchargent pas (DictionaryDownloadScheduler saute un fichier déjà installé).

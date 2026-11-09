# Listes alignées sur AOSP et LibreOffice, 08/11/2026

Produit par `tools/align_open_lexicons.py` (voir son en-tête et `docs/autocorrection-roadmap.md`, « Listes de mots alignées »).
- `<code>-retires.txt` : mots retirés, « mot fréquence raison » (accent:forme connue, faute:mot dont c'est la faute, anglais) ;
- `<code>-ajoutes-extrait.txt` : les 1 000 premiers mots ajoutés (fréquence attribuée, note AOSP) ; la liste complète se régénère ;
- `resume.txt` : chiffres de chaque langue.

Commandes (sources dans un dossier de travail ; `$W` = aosp-dictionaries/wordlists, `$L` = LibreOffice dictionaries, `$EN` = listes
AOSP anglaises main_en_US.combined et main_en_GB.combined, `$T` = exports Tatoeba) :
  fr : --aosp $W/main_fr.combined --hunspell $L/fr_FR/dictionaries/fr --tatoeba $T/fra_sentences.tsv.bz2 --english-aosp $EN
  en : --aosp $EN --hunspell $L/en/en_US $L/en/en_GB --tatoeba $T/eng_sentences.tsv.bz2
  es : --aosp $W/main_es.combined --hunspell $L/es/es_ES $L/es/es_MX --tatoeba $T/spa_sentences.tsv.bz2 --english-aosp $EN
  de : --aosp $W/main_de__legacy.combined --hunspell $L/de/de_DE_frami --tatoeba $T/deu_sentences.tsv.bz2 --english-aosp $EN
  it : --aosp $W/main_it.combined --hunspell $L/it_IT/it_IT --tatoeba $T/ita_sentences.tsv.bz2 --english-aosp $EN
  pt : --aosp $W/main_pt_PT.combined --aosp-validate $W/main_pt_BR.combined --hunspell $L/pt_BR/pt_BR $L/pt_PT/pt_PT --tatoeba $T/por_sentences.tsv.bz2 --english-aosp $EN
  nl : --aosp $W/main_nl.combined --hunspell $L/nl_NL/nl_NL --tatoeba $T/nld_sentences.tsv.bz2 --english-aosp $EN
Chaque fois : python3 tools/align_open_lexicons.py --lang <code> ... --report-dir <dossier> --apply, puis tools/build_ngrams.py.

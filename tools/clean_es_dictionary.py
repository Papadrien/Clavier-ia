#!/usr/bin/env python3
"""Nettoyage de dictionaries/es.txt à partir de listes de mots de référence (lexiques Gboard es_ES, es_419, es_MX, es_US).

Pourquoi : es.txt vient des sous-titres (OpenSubtitles). Deux défauts, mesurés le 05/11/2026 :
  - 188 834 de ses 335 938 mots n'existent dans aucun lexique de clavier : fautes de frappe, fusions de sous-titres, mais aussi
    des mots DÉPOURVUS D'ACCENT (« despues », « habia », « podria », « algun », « senor ») que les sous-titreurs omettent ; ces
    formes sont « valides » pour le clavier, donc jamais corrigées ;
  - des mots anglais fréquents (« the », « to », « of », « park ») proposés en correction d'une faute de frappe espagnole.
Les lexiques servent ici de VALIDATEUR, comme pour l'anglais (tools/clean_en_dictionary.py) : ils ne portent aucune fréquence.

Ce que fait l'outil (sur les mots ABSENTS de tous les lexiques ; un mot présent est toujours gardé, fréquence inchangée) :
  1. un mot rare (fréquence < KEEP_FREQ, 1 000) est retiré, SAUF une forme accentuée vraisemblable (« golpéalo », « olvídenlo »,
     « cocinaré » : verbes avec pronom, futurs) de fréquence >= ACCENTED_MIN_FREQ (183) : gardée à la fréquence TIER, valide mais
     jamais proposée, car les lexiques ignorent beaucoup de ces formes ;
  2. un mot sans accent dont la forme accentuée est dans les lexiques ET dans es.txt, au moins aussi fréquente que lui, est retiré
     (« despues » -> « después ») : Dictionary rétablit l'accent (et le « ñ » : « espanol » -> « español ») ; le garder le
     rendrait « valide ». Un mot dont la forme accentuée est plus rare reste (« to », « mason ») ;
  3. un mot anglais courant (présent dans en.txt, fréquence >= 100) passe à la fréquence TIER (20) : valide, jamais proposé
     (le seuil de proposition de l'espagnol est 183) ;
  4. un mot dont l'accent est mal placé (« bién », « alguién ») et dont la forme sans accent est un mot des lexiques est retiré,
     de même que deux mots courants collés (« delas », « enel ») ; une forme verbe + pronom (« dámelo ») ne l'est jamais ;
  5. fautes connues que les lexiques acceptent à tort (« haver », « exelente », « hiba ») : retirées ;
  6. mots soudés que SpanishConfusions corrige (« aveces », « porfavor ») : gardés à la fréquence TIER, valides, jamais proposés,
     pour que la règle de contexte les voie avant le dictionnaire.
Les autres mots de fréquence >= 1 000 absents des lexiques (noms propres, interjections, « muévanse ») sont gardés.
Aucune fréquence d'un mot de lexique n'est modifiée, aucun mot n'est ajouté.

Utilisation (Python 3.9+), depuis la racine du projet :
  python3 tools/clean_es_dictionary.py --dict-dir dictionaries --lexicon es_ES_lexicon_sorted.txt --lexicon es_419_lexicon_sorted.txt \
      --lexicon es_MX_lexicon_sorted.txt --lexicon es_US_lexicon_sorted.txt --out-dir /tmp/es-nettoye
Écrit es.txt (nouvelle liste), pruned.txt, plain-variants.txt, demoted.txt, accented-kept.txt et kept-unlisted.txt dans --out-dir.
"""
import argparse
import re
import unicodedata
from pathlib import Path

KEEP_FREQ = 1000
TIER = 20
EN_MIN_FREQ = 100
ACCENTED_MIN_FREQ = 183
ACCENTS = "áéíóú"
GLUE_PART_MIN_FREQ = 5000
# Fautes courantes que les lexiques acceptent à tort : corrigées par le dictionnaire (« haver » -> « haber »).
KNOWN_MISSPELLINGS = {"haver", "exelente", "hiba", "aber", "ecxelente", "escusa", "desiar", "bibir", "vien"}
# Mots soudés par erreur que SpanishConfusions sépare : valides (jamais proposés) pour que la règle de contexte les voie.
GLUED_WORDS = {"aveces", "porfavor", "osea", "enserio", "sinembargo", "alomejor", "atraves", "apesar", "aver"}
CLITIC_ENDINGS = ("me", "te", "se", "lo", "la", "le", "los", "las", "les", "nos", "os")


def norm(w: str) -> str:
    return unicodedata.normalize("NFC", w.strip()).replace("’", "'").lower()


def fold(w: str) -> str:
    """Sans accents ni tilde (« año » -> « ano »), comme WordForms.foldAccents."""
    return "".join(c for c in unicodedata.normalize("NFD", w) if unicodedata.category(c) != "Mn")


def read_freq(path: Path) -> dict:
    out = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        word, _, freq = line.rpartition(" ")
        if word:
            out[word] = int(freq)
    return out


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dict-dir", default="dictionaries")
    ap.add_argument("--lexicon", action="append", required=True)
    ap.add_argument("--out-dir", required=True)
    args = ap.parse_args()

    ours = read_freq(Path(args.dict_dir, "es.txt"))
    english = read_freq(Path(args.dict_dir, "en.txt"))

    ref: set = set()
    for path in args.lexicon:
        for line in Path(path).read_text(encoding="utf-8", errors="replace").splitlines():
            if line.strip():
                ref.add(norm(line))

    # Mots accentués (ou avec « ñ ») des lexiques présents dans es.txt, indexés par leur forme sans accent.
    accented_by_plain: dict = {}
    for w in ref:
        if w in ours and fold(w) != w:
            accented_by_plain.setdefault(fold(w), []).append(w)

    def glued(word: str) -> bool:
        if word.endswith(CLITIC_ENDINGS) or not word.isalpha():
            return False
        return any(
            ours.get(word[:i], 0) >= GLUE_PART_MIN_FREQ and ours.get(word[i:], 0) >= GLUE_PART_MIN_FREQ
            and word[:i] in ref and word[i:] in ref and max(i, len(word) - i) >= 3
            for i in range(2, len(word) - 1)
        )

    kept: dict = {}
    pruned: list = []
    plain_variants: list = []
    demoted: list = []
    unlisted: list = []
    accented_kept: list = []
    for word, freq in ours.items():
        if word in ref:
            kept[word] = freq
        elif freq < KEEP_FREQ:
            if (freq >= ACCENTED_MIN_FREQ and word.isalpha() and any(c in ACCENTS for c in word) and fold(word) not in ref
                    and english.get(word, 0) < EN_MIN_FREQ):
                kept[word] = TIER
                accented_kept.append(f"{word} {freq}")
            else:
                pruned.append(f"{word} {freq}")
        elif fold(word) == word and word in accented_by_plain and ours[max(accented_by_plain[word], key=ours.get)] >= freq:
            plain_variants.append(f"{word} {freq} -> {max(accented_by_plain[word], key=ours.get)}")
        elif english.get(word, 0) >= EN_MIN_FREQ:
            kept[word] = min(freq, TIER)
            demoted.append(f"{word} {freq}")
        elif fold(word) != word and fold(word) in ref and word.isalpha():
            pruned.append(f"{word} {freq} (accent mal placé)")
        elif "'" not in word and glued(word):
            pruned.append(f"{word} {freq} (collé)")
        else:
            kept[word] = freq
            unlisted.append(f"{word} {freq}")

    for word in KNOWN_MISSPELLINGS:
        if kept.pop(word, None) is not None:
            pruned.append(f"{word} (faute connue)")
    for word in GLUED_WORDS:
        kept[word] = min(kept.get(word, TIER), TIER)

    out = Path(args.out_dir)
    out.mkdir(parents=True, exist_ok=True)
    ordered = sorted(kept.items(), key=lambda item: (-item[1], item[0]))
    (out / "es.txt").write_text("\n".join(f"{w} {f}" for w, f in ordered) + "\n", encoding="utf-8")
    for name, rows in (("pruned.txt", pruned), ("plain-variants.txt", plain_variants), ("demoted.txt", demoted), ("kept-unlisted.txt", unlisted),
                        ("accented-kept.txt", accented_kept)):
        (out / name).write_text("\n".join(rows) + "\n", encoding="utf-8")
    print(f"es.txt : {len(ours)} -> {len(kept)} mots ; retirés {len(pruned)} ; sans accent retirés {len(plain_variants)} ; "
          f"formes accentuées gardées {len(accented_kept)} ; anglais rétrogradés {len(demoted)} ; gardés hors lexiques {len(unlisted)}")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Nettoyage de dictionaries/en.txt à partir de listes de mots de référence (lexiques Gboard en_US, en_GB, en_CA, en_AU).

Pourquoi : en.txt vient des sous-titres (OpenSubtitles) : plus de 200 000 de ses mots n'existent dans aucun lexique de
clavier (« wouidn », « ofyour », « son-in-iaw », « afterthat »...). Chacun est « valide » pour le clavier, donc jamais
corrigé : 79 % des fautes de frappe tirées au hasard tombent sur un mot déjà valide (banc, `--lang=en`). Les listes de
référence servent ici de VALIDATEUR, comme pour le français (tools/enrich_fr_dictionary.py), pas de source de fréquences :
elles n'en portent pas.

Ce que fait l'outil :
  1. ÉLAGAGE : un mot de en.txt est retiré s'il est absent de TOUTES les listes de référence ET de fréquence < KEEP_FREQ
     (1 000). Les formes des quatre variantes régionales sont toutes gardées (« colour » et « color », « labelled » et
     « labeled » : les deux sont justes, aucune n'est corrigée en l'autre). Les mots très fréquents que les listes ignorent
     (argot, interjections) restent, sauf deux mots de référence collés (« ofthe ») ou un « l » lu « i » (« iove »). Les contractions écrites à la main (it's, don't...) ne sont jamais retirées.
  2. FAUTES CONNUES : quelques fautes courantes que les listes de référence acceptent à tort (« wich », « occured ») sont
     retirées ; les mots soudés corrigés par EnglishConfusions (« alot », « atleast ») sont gardés à la fréquence TIER :
     valides, jamais proposés, de sorte que la correction du dictionnaire ne les change pas avant la règle de contexte.
  3. AJOUTS (OPTION --add, désactivée par défaut) : mots (lettres a-z seulement) des listes de référence absents de en.txt, à la fréquence TIER (20) : valides,
     jamais proposés (seuil de proposition 149). Mesure du 05/11/2026 : +72 000 mots en mémoire pour 0,3 à 0,5 point de moins
     sur les fautes de frappe, donc non retenus. Ils évitent qu'un mot rare mais juste soit « corrigé » en autre chose. Un
     ajout est écarté s'il est une faute de frappe plausible (QWERTY) d'un mot au moins 1 000 fois plus fréquent
     (même règle que PruneDictionaryTypos), ou un fragment de contraction (« didn », « doesn »).
Aucune fréquence de en.txt n'est modifiée.

Utilisation (Python 3.9+), depuis la racine du projet :
  python3 tools/clean_en_dictionary.py --dict-dir dictionaries --lexicon en_840_lexicon.txt --lexicon en_GB_lexicon.txt \
      --lexicon en_CA_lexicon.txt --lexicon en_AU_lexicon.txt --out-dir /tmp/en-nettoye [--add]
Écrit en.txt (nouvelle liste), pruned.txt, added.txt et kept-unlisted.txt (mots gardés que les listes ignorent) dans --out-dir.
"""
import argparse
import re
import unicodedata
from pathlib import Path

KEEP_FREQ = 1000
TIER = 20
DOMINANCE = 1000
# Fautes courantes présentes dans les listes de référence : retirées (corrigées par le dictionnaire).
KNOWN_MISSPELLINGS = {"wich", "occured", "untill", "realy", "definately", "recieve", "seperate", "wierd", "tommorow", "beleive",
                         "commited", "planed", "stoped", "droped", "refered", "prefered", "transfered", "controled", "equiped",
                         "admited", "submited", "ocurred", "occurence", "occurrance", "adress", "recomend", "accomodate"}
# Mots soudés que EnglishConfusions corrige : gardés valides (jamais proposés) pour que la règle de contexte les voie.
GLUED_WORDS = {"alot", "atleast", "aswell", "eachother", "infact", "alittle", "inspite", "thankyou"}
WORD_RE = re.compile(r"^[a-z]+$")
ROWS = ["qwertyuiop", "asdfghjkl", "zxcvbnm"]
ROW_OFFSET = [0.0, 0.25, 0.75]


def norm(w: str) -> str:
    return unicodedata.normalize("NFC", w.strip()).replace("’", "'").lower()


def build_neighbors():
    pos = {}
    for r, row in enumerate(ROWS):
        for i, c in enumerate(row):
            pos[c] = (r, i + ROW_OFFSET[r])
    nb = {c: set() for c in pos}
    for a, (ra, xa) in pos.items():
        for b, (rb, xb) in pos.items():
            if a != b and abs(ra - rb) <= 1 and abs(xa - xb) <= 1.01:
                nb[a].add(b)
    return nb


NB = build_neighbors()
ALPHABET = "abcdefghijklmnopqrstuvwxyz"


def slip_sources(w: str):
    """Mots v dont w est une faute de frappe plausible : lettre en trop, oubliée, voisine, doublée ou inversée."""
    for i in range(len(w)):
        yield w[:i] + w[i + 1:]  # w a une lettre de plus que v
    for i in range(len(w) + 1):
        for c in ALPHABET:
            yield w[:i] + c + w[i:]  # w a une lettre de moins que v
    for i, ch in enumerate(w):
        for c in NB.get(ch, ()):
            yield w[:i] + c + w[i + 1:]  # touche voisine
    for i in range(len(w) - 1):
        if w[i] != w[i + 1]:
            yield w[:i] + w[i + 1] + w[i] + w[i + 2:]  # inversion


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dict-dir", default="dictionaries")
    ap.add_argument("--lexicon", action="append", required=True)
    ap.add_argument("--out-dir", required=True)
    ap.add_argument("--add", action="store_true")
    args = ap.parse_args()

    ours: dict[str, int] = {}
    for line in Path(args.dict_dir, "en.txt").read_text(encoding="utf-8").splitlines():
        word, _, freq = line.partition(" ")
        ours[word] = int(freq)

    ref: set[str] = set()
    for path in args.lexicon:
        for line in Path(path).read_text(encoding="utf-8", errors="replace").splitlines():
            if line.strip():
                ref.add(norm(line))

    def glued(word: str) -> bool:
        """Deux mots de référence collés (« ofthe », « ifyou », « doyou ») : faute du corpus, même très fréquente."""
        return any(word[:i] in ref and word[i:] in ref and len(word[:i]) >= 2 and len(word[i:]) >= 2 and max(len(word[:i]), len(word[i:])) >= 3
                   for i in range(2, len(word) - 1))

    def ocr_i_for_l(word: str) -> bool:
        """« iove » pour « love », « couid » pour « could » : l lu comme i par la reconnaissance des sous-titres."""
        return "i" in word and any(word[:k] + "l" + word[k + 1:] in ref for k, c in enumerate(word) if c == "i")

    kept: dict[str, int] = {}
    pruned: list[str] = []
    unlisted: list[str] = []
    for word, freq in ours.items():
        if word in ref:
            kept[word] = freq
        elif freq >= KEEP_FREQ and "'" not in word and (glued(word) or ocr_i_for_l(word)):
            pruned.append(f"{word} {freq}")
        elif freq >= KEEP_FREQ:
            kept[word] = freq
            unlisted.append(f"{word} {freq}")
        else:
            pruned.append(f"{word} {freq}")

    for word in KNOWN_MISSPELLINGS:
        if kept.pop(word, None) is not None:
            pruned.append(f"{word} (faute connue)")
    for word in GLUED_WORDS:
        kept[word] = min(kept.get(word, TIER), TIER)

    added: list[str] = []
    skipped = 0
    if args.add:
        strong = {w for w, f in kept.items() if f >= TIER * DOMINANCE}
        for word in sorted(ref):
            if word in kept or not WORD_RE.match(word) or (len(word) == 1 and word not in ("a", "i")):
                continue
            if f"{word}'t" in kept:
                skipped += 1  # fragment de contraction (« didn », « doesn »)
                continue
            if any(v in strong for v in slip_sources(word)):
                skipped += 1
                continue
            kept[word] = TIER
            added.append(f"{word} {TIER}")

    out = Path(args.out_dir)
    out.mkdir(parents=True, exist_ok=True)
    ordered = sorted(kept.items(), key=lambda item: (-item[1], item[0]))
    (out / "en.txt").write_text("\n".join(f"{w} {f}" for w, f in ordered) + "\n", encoding="utf-8")
    (out / "pruned.txt").write_text("\n".join(pruned) + "\n", encoding="utf-8")
    (out / "added.txt").write_text("\n".join(added) + "\n", encoding="utf-8")
    (out / "kept-unlisted.txt").write_text("\n".join(unlisted) + "\n", encoding="utf-8")
    print(f"en.txt : {len(ours)} -> {len(kept)} mots ; retirés {len(pruned)} ; ajoutés {len(added)} ; "
          f"ajouts écartés {skipped} ; gardés hors listes {len(unlisted)}")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Jeu « composés justes rares » du banc allemand (tools/autocorrect-bench/de/valid-rare-words.txt).

Pourquoi : dictionaries/de.txt (sous-titres) ignore beaucoup de mots justes, surtout des composés (« Abendkleid »,
« Abdichtungsmaterial »). Le moteur les voit comme des fautes et peut les remplacer par un mot proche. Le banc mesure
cette part (`composes.rares.faux`). L'oracle de validité vient des lexiques de clavier fournis (de_DE, de_AT, de_CH), utilisés
HORS de l'app, comme validateur ; le fichier produit est un jeu de TEST (tools/), il n'est pas livré dans l'app.

Règles : mots des lexiques (sans fragments « _ »), lettres seules, 8 lettres ou plus, absents de de.txt, absents de en.txt
(mots anglais des lexiques), mis en minuscules ; tirage de 1 500 mots à graine fixe, triés.

Utilisation (Python 3.9+), depuis la racine du projet :
  python3 tools/de_bench_data.py --lexicon de_DE_lexicon_sorted.txt --lexicon de_AT_lexicon_sorted.txt --lexicon de_CH_lexicon_sorted.txt
"""
import argparse
import random
import re

LETTERS = re.compile(r"[a-zäöüß]+")


def words(path):
    with open(path, encoding="utf-8") as f:
        return {line.rstrip("\n") for line in f}


def listed(path):
    with open(path, encoding="utf-8") as f:
        return {line.rsplit(" ", 1)[0] for line in f if " " in line}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--lexicon", action="append", required=True)
    ap.add_argument("--dict-dir", default="dictionaries")
    ap.add_argument("--out", default="tools/autocorrect-bench/de/valid-rare-words.txt")
    ap.add_argument("--count", type=int, default=1500)
    args = ap.parse_args()
    lexicon = set()
    for path in args.lexicon:
        lexicon |= {w.lower() for w in words(path) if "_" not in w}
    de, en = listed(f"{args.dict_dir}/de.txt"), listed(f"{args.dict_dir}/en.txt")
    pool = sorted(w for w in lexicon if len(w) >= 8 and LETTERS.fullmatch(w) and w not in de and w not in en)
    sample = sorted(random.Random(20261106).sample(pool, min(args.count, len(pool))))
    with open(args.out, "w", encoding="utf-8") as f:
        f.write("# Mots justes (lexiques de clavier) absents de de.txt : le moteur ne doit pas les remplacer. Généré par tools/de_bench_data.py.\n")
        f.write("\n".join(sample) + "\n")
    print(f"{len(pool)} mots éligibles, {len(sample)} tirés -> {args.out}")


if __name__ == "__main__":
    main()

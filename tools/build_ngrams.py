#!/usr/bin/env python3
"""Modèle de bigrammes livré avec le dictionnaire (voir docs/autocorrection-roadmap.md, point 1).

Source : phrases Tatoeba (https://tatoeba.org, export « per_language », licence CC BY 2.0 FR). Ce sont des phrases courtes
de la vie courante, proches de ce qu'on tape au clavier. Une phrase sur 50 (identifiant multiple de 50) n'est JAMAIS
comptée : elle sert au banc sur phrases (tools/autocorrect-bench/heldout/<code>.txt), qui ne doit pas mesurer le modèle sur
ce qu'il a appris.

Découpage en mots : celui du clavier (WordText.isWordChar) : lettres, apostrophes et traits d'union contigus, en minuscules
(« c'est », « est-ce » sont un mot). Seuls les mots présents dans dictionaries/<code>.txt sont retenus ; une phrase est
coupée sur la ponctuation (le mot qui suit une virgule n'a pas de mot précédent).

Fichier écrit (dictionaries/bigrams/<code>.txt), UTF-8, une entrée par ligne, triée par nombre décroissant :
  « mot nombre »            : nombre de fois où le mot est suivi d'un autre mot retenu (total du contexte) ;
  « mot1 mot2 nombre »      : nombre de fois où mot2 suit directement mot1.
Paires vues moins de MIN_PAIR fois retirées, puis au plus MAX_PAIRS paires gardées (les plus fréquentes).

Utilisation, depuis la racine du projet :
  curl -O https://downloads.tatoeba.org/exports/per_language/fra/fra_sentences.tsv.bz2
  python3 tools/build_ngrams.py --lang fr --sentences fra_sentences.tsv.bz2
"""
import argparse
import bz2
import re
from collections import Counter
from pathlib import Path

MIN_PAIR = 2
MAX_PAIRS = 400_000
HELDOUT_MODULO = 50
HELDOUT_KEPT = 2_000

# Un « mot » du clavier : lettres, apostrophes, traits d'union (WordText.isWordChar). Le reste coupe le mot ; la ponctuation
# coupe aussi le contexte.
TOKEN_RE = re.compile(r"[^\W\d_]+(?:['\-][^\W\d_]+)*|[.,;:!?()\"«»…]|\d+")


def tokens(sentence):
    text = sentence.replace("’", "'").replace("‘", "'").lower()
    return TOKEN_RE.findall(text)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--lang", required=True)
    parser.add_argument("--sentences", required=True, help="export Tatoeba <iso3>_sentences.tsv.bz2")
    parser.add_argument("--root", default=".")
    args = parser.parse_args()
    root = Path(args.root)

    vocabulary = set()
    with open(root / "dictionaries" / f"{args.lang}.txt", encoding="utf-8") as f:
        for line in f:
            word = line.split(" ", 1)[0]
            if word:
                vocabulary.add(word)

    pairs = Counter()
    heldout = []
    opener = bz2.open if args.sentences.endswith(".bz2") else open
    with opener(args.sentences, "rt", encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 3:
                continue
            sentence_id, sentence = int(parts[0]), parts[2]
            if sentence_id % HELDOUT_MODULO == 0:
                heldout.append((sentence_id, sentence))
                continue
            previous = None
            for token in tokens(sentence):
                if token in vocabulary:
                    if previous is not None:
                        pairs[(previous, token)] += 1
                    previous = token
                else:
                    previous = None  # ponctuation, nombre ou mot inconnu : le contexte est coupé

    kept = [(pair, n) for pair, n in pairs.items() if n >= MIN_PAIR]
    kept.sort(key=lambda item: (-item[1], item[0]))
    kept = kept[:MAX_PAIRS]
    totals = Counter()
    for (first, _), n in pairs.items():
        totals[first] += n
    left_words = {first for (first, _), _ in kept}
    unigrams = sorted(((w, totals[w]) for w in left_words), key=lambda item: (-item[1], item[0]))

    out = root / "dictionaries" / "bigrams" / f"{args.lang}.txt"
    out.parent.mkdir(parents=True, exist_ok=True)
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        for word, n in unigrams:
            f.write(f"{word} {n}\n")
        for (first, second), n in kept:
            f.write(f"{first} {second} {n}\n")

    # Phrases mises de côté : les plus « clavier » (5 à 15 mots, toutes connues du dictionnaire), ordre stable.
    selected = []
    for _, sentence in sorted(heldout):
        words = [t for t in tokens(sentence) if t[0].isalpha()]
        if 5 <= len(words) <= 15 and all(w in vocabulary for w in words):
            selected.append(sentence.replace("’", "'"))
        if len(selected) >= HELDOUT_KEPT:
            break
    bench = root / "tools" / "autocorrect-bench" / "heldout" / f"{args.lang}.txt"
    bench.parent.mkdir(parents=True, exist_ok=True)
    with open(bench, "w", encoding="utf-8", newline="\n") as f:
        f.write(f"# Phrases Tatoeba (CC BY 2.0 FR) jamais comptées dans dictionaries/bigrams/{args.lang}.txt : voir tools/build_ngrams.py.\n")
        for sentence in selected:
            f.write(sentence + "\n")

    print(f"{args.lang}: {len(pairs)} paires vues, {len(kept)} gardées, {len(unigrams)} contextes, {len(selected)} phrases de banc")


if __name__ == "__main__":
    main()

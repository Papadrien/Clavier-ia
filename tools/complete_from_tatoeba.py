#!/usr/bin/env python3
"""Complète dictionaries/<code>.txt avec des mots courants absents (italien, portugais, néerlandais, 07/11/2026).

Les listes FrequencyWords de ces langues ont perdu à la tokenisation les mots à apostrophe (it : « c'è », « po' », « dov'è » ; nl :
« zo'n », « m'n ») et quelques mots très courants (it : « un » ; pt : « agente » ; nl : « 's »). Sans eux, l'autocorrection
« corrigeait » des phrases justes (« un problema » -> « in problema », « zo'n » -> « zoon »). Voir docs/autocorrection-roadmap.md.

Ajouts, à partir des phrases Tatoeba (CC BY 2.0 FR, voir tools/build_ngrams.py) :
  1. mots contenant une apostrophe vus au moins MIN_APOSTROPHE_COUNT fois ;
  2. la liste MANUAL ci-dessous (mots vérifiés à la main), quel que soit leur nombre.
Un mot déjà présent avec une fréquence >= 5 n'est pas touché. Les fautes d'accent de DEMOTED et les formes sans accent du corpus
(voir ACCENT_MAX_SHARE) passent à la fréquence 1 ; les formes de CONTEXT_FORMS restent valides (fréquence 5). La fréquence attribuée est le nombre Tatoeba multiplié par le rapport
médian fréquence de la liste / nombre Tatoeba des mots communs (vus au moins 100 fois des deux côtés), pour rester à l'échelle de la
liste. Le fichier reste trié par fréquence décroissante.

Utilisation, depuis la racine du projet :
  python3 tools/complete_from_tatoeba.py --lang it --sentences ita_sentences.tsv.bz2
"""
import argparse
import bz2
import re
import statistics
import unicodedata
from collections import Counter
from pathlib import Path

MIN_APOSTROPHE_COUNT = 50
# Une apostrophe au début (« 's » néerlandais) ou à la fin (« po' » italien) fait partie du mot.
TOKEN_RE = re.compile(r"'?[^\W\d_]+(?:['\-][^\W\d_]+)*'?")

MANUAL = {
    "it": ["un"],
    "pt": ["agente", "d'água"],
    "nl": ["zo'n", "m'n", "z'n"],
}
# Pas de mot qui commence ou finit par une apostrophe (« po' », « 's ») : DictionaryAssetsIntegrityTest l'interdit, et Dictionary les
# garde tels quels (apostrophe finale après un mot connu, apostrophe initiale).

# Fautes d'accent du corpus, toujours fautives (italien : « -ché » s'écrit avec l'accent aigu) : passées à la fréquence 1, sous le
# seuil de validité du chargeur (5), pour que l'autocorrection les corrige (« perchè » -> « perché »).
DEMOTED = {
    "it": ["perchè", "poichè", "affinchè", "benchè", "finchè", "sicchè", "purchè", "nonchè", "nè", "ventitrè", "trentatrè"],
}

# Formes sans accent du corpus (sous-titres sans accents : « piu », « cosi », « nao », « voce ») : rétrogradées à la fréquence 1 si leur
# forme accentuée est fréquente dans Tatoeba (au moins ACCENT_MIN_COUNT fois, phrases correctement accentuées) et la forme sans accent
# quasi absente (au plus ACCENT_MAX_SHARE de la forme accentuée). Deux formes justes et courantes (« torno » / « tornò », « capi » /
# « capì ») apparaissent toutes deux dans Tatoeba et restent. Jamais un prénom (names/<code>.txt) ni un mot de KEPT_PLAIN.
ACCENT_MIN_COUNT = 20
ACCENT_MAX_SHARE = 0.005
KEPT_PLAIN = {
    "it": {"e", "da", "si", "la", "li", "ne", "se", "te", "di", "papa", "meta"},
    "pt": {"esta", "estas", "e", "a", "nos", "vos", "por", "pode", "para", "pais", "tem", "vem", "sabia", "secretaria", "publico",
           "pratica", "critica", "da", "de", "mas", "pelo", "porque", "que", "avo"},
    "nl": {"een", "voor", "dat", "die", "is", "he"},
}

# Mots que les règles de contexte remplacent (« concerteza » -> « com certeza », « qual'è » -> « qual è ») : gardés valides à la fréquence 5
# (jamais proposés), sinon le dictionnaire les « corrige » en un autre mot (« incerteza ») avant que la règle ne les voie.
CONTEXT_FORMS = {
    "it": ["qual'è", "qual'era"],
    "pt": ["concerteza", "derrepente", "apartir", "porisso", "denovo"],
}

# Noms propres et mots propres à Tatoeba, à ne jamais ajouter.
EXCLUDED = {"tatoeba", "tatoeba's"}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--lang", required=True)
    parser.add_argument("--sentences", required=True)
    parser.add_argument("--root", default=".")
    args = parser.parse_args()
    path = Path(args.root) / "dictionaries" / f"{args.lang}.txt"

    entries = []
    for line in path.read_text(encoding="utf-8").splitlines():
        word, _, frequency = line.partition(" ")
        entries.append((word, int(frequency)))
    frequencies = dict(entries)

    counts = Counter()
    with bz2.open(args.sentences, "rt", encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 3:
                continue
            for token in TOKEN_RE.findall(parts[2].replace("’", "'").lower()):
                counts[token] += 1

    ratios = [frequencies[w] / n for w, n in counts.items() if n >= 100 and frequencies.get(w, 0) >= 100]
    scale = statistics.median(ratios)

    # Néerlandais : « foto's », « Maria's » (pluriel ou possessif après une voyelle) relèvent d'une règle de flexion, pas de la liste.
    wanted = [
        w for w, n in counts.items()
        if "'" in w.strip("'") and w[0] != "'" and w[-1] != "'" and n >= MIN_APOSTROPHE_COUNT
        and not (args.lang == "nl" and w.endswith("'s"))
    ]
    wanted += MANUAL.get(args.lang, [])
    added = []
    for word in dict.fromkeys(wanted):
        if word in EXCLUDED or frequencies.get(word, 0) >= 5:
            continue
        count = counts.get(word, 0)
        if count == 0:
            print(f"absent de Tatoeba, ignoré : {word}")
            continue
        frequency = max(5, round(count * scale))
        frequencies[word] = frequency
        added.append((word, frequency))

    names = set()
    names_path = Path(args.root) / "dictionaries" / "names" / f"{args.lang}.txt"
    if names_path.is_file():
        names = {line.strip().lower() for line in names_path.read_text(encoding="utf-8").splitlines() if line and not line.startswith("#")}
    by_plain = {}
    for word in frequencies:
        plain = "".join(c for c in unicodedata.normalize("NFD", word) if unicodedata.category(c) != "Mn")
        if plain != word:
            by_plain.setdefault(plain, []).append(word)
    accent_fixed = []
    for plain, accented in by_plain.items():
        if frequencies.get(plain, 0) < 5 or plain in names or plain in KEPT_PLAIN.get(args.lang, set()):
            continue
        best = max(accented, key=lambda w: counts.get(w, 0))
        if counts.get(best, 0) >= ACCENT_MIN_COUNT and counts.get(plain, 0) <= ACCENT_MAX_SHARE * counts[best]:
            frequencies[plain] = 1
            accent_fixed.append(f"{plain}>{best}")
    print(f"{len(accent_fixed)} formes sans accent rétrogradées : " + ", ".join(accent_fixed[:30]))

    for word in CONTEXT_FORMS.get(args.lang, []):
        if frequencies.get(word, 0) < 5:
            frequencies[word] = 5

    for word in DEMOTED.get(args.lang, []):
        if frequencies.get(word, 0) > 1:
            frequencies[word] = 1
            print(f"rétrogradé : {word}")

    lines = sorted(frequencies.items(), key=lambda item: (-item[1], item[0]))
    path.write_text("".join(f"{w} {n}\n" for w, n in lines), encoding="utf-8", newline="\n")
    print(f"{args.lang}: échelle {scale:.1f}, {len(added)} mots ajoutés : " + ", ".join(f"{w} ({n})" for w, n in added[:40]))


if __name__ == "__main__":
    main()

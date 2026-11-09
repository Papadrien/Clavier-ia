#!/usr/bin/env python3
"""Construit dictionaries/names/de.txt : prénoms ET noms communs allemands avec leur majuscule (phase 3 du plan d'autocorrection allemande).

Pourquoi : en allemand tous les noms prennent une majuscule, et de.txt (sous-titres) est en minuscules : le moteur ne sait pas que « haus » s'écrit
« Haus ». Décision du 06/11/2026 : pas de majuscule « automatique » par réglage ni par contexte ; une liste de noms et de prénoms, branchée sur le
mécanisme des prénoms du français (Dictionary.properNameForm : « haus » -> « Haus », « hauss » -> « Haus », « Strasse » -> « Straße »).
Le réglage « majuscules automatiques » et le correcteur de contexte du plan (3b, 3c) ne sont PAS faits.

Sources (rien n'est repris des lexiques de clavier : ils servent seulement à RETIRER des mots douteux, comme pour de.txt) :
  1. tools/de-names/vornamen.txt   prénoms (liste d'origine de names/de.txt) ;
  2. tools/de-names/nomen-seed.txt noms communs de départ, écrits à la main ;
  3. --corpus FICHIER...           (optionnel, décision D2 : à fournir au moment de travailler les dictionnaires) texte allemand QUI GARDE LA CASSE,
                                   à licence compatible CC BY-SA. Un nom y est retenu s'il est écrit avec une majuscule au moins 95 % du temps hors
                                   début de phrase, vu au moins --min-count fois.

Filtres (un mot retiré est écrit dans le rapport avec sa raison) :
  - ambigu       : la forme en minuscules est un mot des lexiques (essen/Essen, liebe/Liebe). Les lexiques y mettent aussi des premières parties de
                   composé (« kinder », « garten ») : les noms relus à la main sont dans tools/de-names/ambigu-ok.txt et passent. Ce filtre et le
                   filtre « verbe » ne s'appliquent qu'aux noms de départ : un nom du corpus est jugé sur sa part de majuscules (>= 95 %), qui mesure
                   aussi les formes verbales ;
  - verbe        : nom en -e dont « minuscule + n » est un verbe des lexiques (Frage/fragen, Reise/reisen, Rede/reden) : « ich frage » prendrait
                   une majuscule. Exceptions voulues dans KEEP_NOUN_E (Schule) ;
  - court        : moins de 3 lettres ;
  - forme        : autre chose que des lettres (ProperNamesAssetsTest) ou pas de majuscule initiale.
Les prénoms (vornamen.txt) ne passent que le filtre « forme » : un prénom qui est aussi un mot courant n'y figure déjà pas.
Rapport (non retiré mais à relire) : « inconnu » = nom absent des lexiques ; « umlaut » = nom dont la forme sans umlaut est un mot des lexiques
(Höhe/hohe) : Dictionary ne remplace jamais un mot de la liste pour son seul umlaut (AccentPolicy.nounsKeepValidWords), donc sans risque.
Accord avec l'oracle = part des noms connus des lexiques qui y sont écrits avec une majuscule seulement (objectif du plan : >= 95 %).

Utilisation (Python 3.9+), depuis la racine du projet :
  python3 tools/build_de_names.py --lexicon de_DE_lexicon.txt --lexicon de_AT_lexicon.txt --lexicon de_BE_lexicon.txt \\
      [--corpus corpus1.txt corpus2.txt] [--report-dir /tmp/de-names] [--check]
--check n'écrit rien : compare le résultat à dictionaries/names/de.txt (code de sortie 1 s'ils diffèrent).
python3 tools/build_de_names.py --self-test    : vérifie l'extraction sur un petit texte intégré.
"""
import argparse
import re
import sys
import unicodedata
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MIN_LENGTH = 3
SHARE = 0.95
MIN_COUNT = 20
# Noms en -e que le filtre « verbe » retirerait mais dont la forme en minuscules est, en pratique, bien plus souvent le nom (« die schule »).
KEEP_NOUN_E = {"Schule"}
WORD = re.compile(r"[^\W\d_]+(?:-[^\W\d_]+)*")
SENTENCE_BREAK = re.compile(r"[.!?:…„“\"»«]")


def nfc(w: str) -> str:
    return unicodedata.normalize("NFC", w.strip())


def read_list(path: Path) -> list:
    return [nfc(l) for l in path.read_text(encoding="utf-8").splitlines() if l.strip() and not l.lstrip().startswith("#")]


def read_lexicons(paths) -> tuple:
    """(entrées en minuscules, entrées commençant par une majuscule mises en minuscules). Les fragments de composés (« Abend_ ») sont ignorés."""
    lowers, capitals = set(), set()
    for path in paths:
        for line in Path(path).read_text(encoding="utf-8").splitlines():
            w = nfc(line)
            if len(w) < 2 or not w.isalpha():
                continue
            (capitals if w[0].isupper() else lowers).add(w.lower())
    return lowers, capitals


def has_letters_only(w: str) -> bool:
    return bool(w) and w.isalpha() and w[0].isupper()


def fold(w: str) -> str:
    w = w.lower().replace("ß", "ss")
    return "".join(c for c in unicodedata.normalize("NFD", w) if unicodedata.category(c) != "Mn")


def extract_from_corpus(paths, min_count: int = MIN_COUNT, share: float = SHARE) -> dict:
    """Noms écrits avec une majuscule >= share du temps hors début de phrase. Retourne {minuscules: forme avec majuscule}."""
    capital, lower = Counter(), Counter()
    spellings = defaultdict(Counter)
    for path in paths:
        with open(path, encoding="utf-8") as handle:
            for line in handle:
                position = 0
                at_start = True
                for match in WORD.finditer(line):
                    gap = line[position:match.start()]
                    if SENTENCE_BREAK.search(gap):
                        at_start = True
                    token = nfc(match.group())
                    position = match.end()
                    if "-" in token or token.isupper():
                        at_start = False
                        continue
                    key = token.lower()
                    if token[0].islower():
                        lower[key] += 1
                    elif not at_start:
                        capital[key] += 1
                        spellings[key][token] += 1
                    at_start = False
    out = {}
    for key, n in capital.items():
        if n >= min_count and n / (n + lower[key]) >= share and len(key) >= MIN_LENGTH:
            out[key] = spellings[key].most_common(1)[0][0]
    return out


def judge_noun(noun: str, lowers: set, forced: frozenset = frozenset()) -> str:
    """Raison du retrait d'un nom commun, ou '' s'il est gardé. [forced] : noms relus à la main (ambigu-ok.txt)."""
    key = noun.lower()
    if noun in forced and has_letters_only(noun) and len(noun) >= MIN_LENGTH:
        return ""
    if not has_letters_only(noun):
        return "forme"
    if len(noun) < MIN_LENGTH:
        return "court"
    if key in lowers:
        return "ambigu"
    if key.endswith("e") and key + "n" in lowers and noun not in KEEP_NOUN_E:
        return "verbe"
    return ""


def build(lexicons, corpus_paths, min_count=MIN_COUNT):
    lowers, capitals = read_lexicons(lexicons) if lexicons else (set(), set())
    first_names = read_list(ROOT / "tools/de-names/vornamen.txt")
    forced = frozenset(read_list(ROOT / "tools/de-names/ambigu-ok.txt"))
    candidates = {}  # minuscules -> (forme, source)
    for noun in read_list(ROOT / "tools/de-names/nomen-seed.txt"):
        candidates.setdefault(noun.lower(), (noun, "départ"))
    if corpus_paths:
        for key, form in extract_from_corpus(corpus_paths, min_count).items():
            candidates.setdefault(key, (form, "corpus"))
    kept, dropped = {}, []
    for name in first_names:
        if has_letters_only(name):
            kept[name.lower()] = name
        else:
            dropped.append((name, "prénom", "forme"))
    for key, (noun, source) in candidates.items():
        if key in kept:
            continue  # déjà un prénom
        if source == "corpus":
            reason = "" if has_letters_only(noun) and len(noun) >= MIN_LENGTH else "forme"
        else:
            reason = judge_noun(noun, lowers, forced) if lowers else ("" if has_letters_only(noun) and len(noun) >= MIN_LENGTH else "forme")
        if reason:
            dropped.append((noun, source, reason))
        else:
            kept[key] = noun
    report = {"unknown": [], "umlaut": []}
    known = agree = 0
    for key, form in kept.items():
        if not lowers:
            break
        if key in capitals or key in lowers:
            known += 1
            agree += key in capitals and key not in lowers
        else:
            report["unknown"].append(form)
        if fold(key) != key and fold(key) in lowers:
            report["umlaut"].append(form)
    return kept, dropped, report, (agree, known)


def sort_key(w: str):
    return (fold(w), w)


def render(kept: dict) -> str:
    header = (
        "# Prénoms et noms communs allemands avec leur majuscule (une forme par ligne). Voir SOURCES.txt.\n"
        "# Produit par tools/build_de_names.py ; les noms dont la forme en minuscules est aussi un mot (essen/Essen) n'y figurent pas.\n"
    )
    return header + "\n".join(sorted(kept.values(), key=sort_key)) + "\n"


def self_test() -> int:
    import tempfile
    text = (
        "Das Haus ist groß. Ich sehe das Haus und ein Haus am See.\n"
        "Wir essen Essen. Wir wollen essen gehen. Sie will essen. Er mag essen. Ich liebe Liebe.\n"
        "Der Hund und der Hund. Ein Hund! Mein Hund, dein Hund.\n"
    )
    with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False, encoding="utf-8") as f:
        f.write(text * 10)
        path = f.name
    got = extract_from_corpus([path], min_count=5)
    assert got.get("haus") == "Haus", got
    assert got.get("hund") == "Hund", got
    assert "essen" not in got, got  # 'Essen' mi-phrase 10 fois contre 30 'essen' : ambigu
    assert judge_noun("Frage", {"fragen"}) == "verbe"
    assert judge_noun("Schule", {"schulen"}) == ""
    assert judge_noun("Essen", {"essen"}) == "ambigu"
    assert judge_noun("Garten", {"garten"}, frozenset({"Garten"})) == ""
    assert fold("Höhe") == "hohe" and fold("Straße") == "strasse"
    print("auto-test réussi")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--lexicon", action="append", default=[], help="lexique de clavier (validateur seulement), répétable")
    ap.add_argument("--corpus", nargs="*", default=[], help="texte allemand qui garde la casse (décision D2)")
    ap.add_argument("--min-count", type=int, default=MIN_COUNT)
    ap.add_argument("--out", default=str(ROOT / "dictionaries/names/de.txt"))
    ap.add_argument("--report-dir")
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    kept, dropped, report, (agree, known) = build(args.lexicon, args.corpus, args.min_count)
    text = render(kept)
    print(f"{len(kept)} noms et prénoms gardés, {len(dropped)} retirés ({Counter(r for _, _, r in dropped)})")
    if known:
        print(f"accord avec l'oracle : {agree}/{known} = {100 * agree / known:.1f} % ; inconnus des lexiques : {len(report['unknown'])} ; umlaut/forme sans umlaut valide : {len(report['umlaut'])}")
    if args.report_dir:
        d = Path(args.report_dir)
        d.mkdir(parents=True, exist_ok=True)
        (d / "dropped.txt").write_text("\n".join(f"{w}\t{s}\t{r}" for w, s, r in sorted(dropped)) + "\n", encoding="utf-8")
        (d / "unknown.txt").write_text("\n".join(sorted(report["unknown"])) + "\n", encoding="utf-8")
        (d / "umlaut.txt").write_text("\n".join(sorted(report["umlaut"])) + "\n", encoding="utf-8")
    if args.check:
        current = Path(args.out).read_text(encoding="utf-8") if Path(args.out).is_file() else ""
        print("names/de.txt à jour" if current == text else "names/de.txt DIFFÈRE du résultat")
        return 0 if current == text else 1
    Path(args.out).write_text(text, encoding="utf-8")
    print(f"écrit : {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

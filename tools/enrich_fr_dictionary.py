#!/usr/bin/env python3
"""Enrichissement de dictionaries/fr.txt à partir de listes de mots de référence (Gboard, SwiftKey).

Ce que fait l'outil (voir dictionaries/SOURCES.txt, section du 04/11/2026) :
  1. AJOUTS : mots des listes de référence que le clavier ne reconnaît pas encore (ni dans fr.txt avec une fréquence
     >= 5, ni par les règles de flexion s / x / aux, ni comme élision dont le reste est connu). Un mot de fr.txt vu moins
     de 5 fois (donc ignoré au chargement) est « relevé » au lieu d'être ajouté.
  2. FILTRE FAUTES DE FRAPPE : même règle que tools/PruneDictionaryTypos.kt (une modification d'un mot au moins 1 000 fois
     plus fréquent, plausible sur AZERTY), appliquée à la fréquence attribuée au mot ajouté.
  3. DÉCLASSEMENT : mots de fréquence >= 100 que les listes de référence ignorent et qui sont de l'anglais (ou des
     débris) passent à la fréquence 99 : toujours valides (jamais « corrigés »), mais plus proposés en correction ou
     complétion (seuil de proposition : 100). Aucun mot n'est supprimé.

Fréquences attribuées (toujours < 100 : valides, jamais proposés) :
  TIER_BOTH = 60 : mot présent dans Gboard ET SwiftKey, ou dans les 20 000 mots principaux de SwiftKey ;
  TIER_ONE  = 20 : mot présent dans une seule des deux listes.

Utilisation (Python 3.9+), depuis la racine du projet :
  python3 tools/enrich_fr_dictionary.py --dict-dir dictionaries --gboard fr_FR_lexicon.txt \
      --swiftkey-tsv fr_FR_all.tsv --out-dir /tmp/fr-enrichi
Écrit fr.txt (nouvelle liste) et added.txt / raised.txt / demoted.txt / skipped.txt dans --out-dir.
"""
import argparse
import re
import sys
import unicodedata
from collections import defaultdict
from pathlib import Path

TIER_BOTH, TIER_ONE, DEMOTED_FREQ = 60, 20, 99
MIN_FREQ, CANDIDATE_MIN = 5, 100
LETTERS = "a-zàâäçéèêëîïôöùûüÿœæ"
WORD_RE = re.compile(rf"^[{LETTERS}]+(?:['\-][{LETTERS}]+)*$")
FR_ELISIONS = {"l", "d", "j", "t", "m", "s", "n", "c", "qu", "jusqu", "lorsqu", "puisqu", "quoiqu", "quelqu", "presqu"}
IT_ELISIONS = {"un", "dell", "all", "nell", "dall", "sull", "quest", "quell", "com", "anch", "sant", "bell"}
ELISIONS = FR_ELISIONS | IT_ELISIONS
PRUNE_ALPHABET = "abcdefghijklmnopqrstuvwxyzéèêàâùûôîïçëöüœæ'-"
DOMINANCE = 1000
VOWELS = set("aeiouyàâäéèêëîïôöùûüÿœæ")


def norm(w: str) -> str:
    return unicodedata.normalize("NFC", w.strip()).replace("\u2019", "'").replace("\u02bc", "'")


def fold(w: str) -> str:
    w = w.replace("œ", "oe").replace("æ", "ae")
    return "".join(c for c in unicodedata.normalize("NFD", w) if unicodedata.category(c) != "Mn")


# --- Proximité AZERTY (port de KeyProximity.fromRows / editCost, mêmes rangées que PruneDictionaryTypos.kt) ---
def build_neighbors():
    rows = [
        [(c, 1.0) for c in "azertyuiop"],
        [(c, 1.0) for c in "qsdfghjklm"],
        [(None, 1.4)] + [(c, 1.0) for c in "wxcvbn"] + [("'", 1.0), (None, 1.6)],
    ]
    placed = []
    for ri, row in enumerate(rows):
        total = sum(w for _, w in row)
        x = 0.0
        for ch, w in row:
            width = w / total
            if ch:
                placed.append((ch, ri, x, x + width))
            x += width
    nb = defaultdict(set)
    for a in placed:
        for b in placed:
            if a is b or a[0] == b[0] or abs(a[1] - b[1]) > 1:
                continue
            if min(a[3], b[3]) - max(a[2], b[2]) >= -0.01:
                nb[a[0]].add(b[0])
    return nb


NB = build_neighbors()


def kfold(c: str) -> str:
    lower = c.lower()
    if ord(lower) < 128:
        return lower
    f = fold(lower)
    return f[0] if f else lower


def are_neighbors(a: str, b: str) -> bool:
    x, y = kfold(a), kfold(b)
    return x != y and y in NB.get(x, ())


def same_or_neighbor(a: str, b: str) -> bool:
    return kfold(a) == kfold(b) or are_neighbors(a, b)


def extra_cost(typed: str, i: int) -> int:
    c = typed[i]
    before = typed[i - 1] if i > 0 else None
    after = typed[i + 1] if i + 1 < len(typed) else None
    tb = before is not None and (kfold(before) == kfold(c) or are_neighbors(before, c))
    ta = after is not None and (kfold(after) == kfold(c) or are_neighbors(after, c))
    return 2 if (tb or ta) else 4


def edit_cost(typed: str, cand: str, missing: int = 2) -> int:
    if typed == cand:
        return 0
    if not typed:
        return len(cand) * missing
    if not cand:
        return len(typed) * 4
    two = [0] * (len(cand) + 1)
    prev = [j * missing for j in range(len(cand) + 1)]
    for i in range(1, len(typed) + 1):
        tc = typed[i - 1]
        cur = [0] * (len(cand) + 1)
        cur[0] = prev[0] + extra_cost(typed, i - 1)
        for j in range(1, len(cand) + 1):
            cc = cand[j - 1]
            sub = 0 if tc == cc else (2 if same_or_neighbor(tc, cc) else 4)
            v = min(cur[j - 1] + missing, prev[j] + extra_cost(typed, i - 1), prev[j - 1] + sub)
            if i > 1 and j > 1 and tc == cand[j - 2] and typed[i - 2] == cc:
                v = min(v, two[j - 2] + 2)
            cur[j] = v
        two, prev = prev, cur
    return prev[len(cand)]


def neighbors(word: str):
    n = len(word)
    for i in range(n):
        yield word[:i] + word[i + 1:]
    for i in range(n):
        for c in PRUNE_ALPHABET:
            if c != word[i]:
                yield word[:i] + c + word[i + 1:]
    for i in range(n + 1):
        for c in PRUNE_ALPHABET:
            yield word[:i] + c + word[i:]
    for i in range(n - 1):
        if word[i] != word[i + 1]:
            yield word[:i] + word[i + 1] + word[i] + word[i + 2:]


def is_typo(word: str, freq: int, freqs: dict) -> bool:
    """Même règle que PruneDictionaryTypos.kt, avec la fréquence [freq] attribuée au mot."""
    if len(word) < 3:
        return False
    threshold = freq * DOMINANCE
    fw = fold(word)
    for nb in neighbors(word):
        if freqs.get(nb, 0) >= threshold and fold(nb) != fw and edit_cost(word, nb) <= 2:
            return True
    return False


# --- Lecture des sources ---
def read_taipo(path: Path):
    lines, freqs = [], {}
    for line in path.read_text(encoding="utf-8").split("\n"):
        if not line:
            continue
        word, _, f = line.rpartition(" ")
        lines.append((word, int(f)))
        freqs[word] = int(f)
    return lines, freqs


def strip_elisions(word: str):
    """« l'aquarelle » -> « aquarelle » (le clavier n'examine que le mot collé à l'apostrophe)."""
    while "'" in word:
        head, _, tail = word.partition("'")
        if head in ELISIONS and tail:
            word = tail
        else:
            break
    return word


def inflected(word: str, known) -> bool:
    """Règles FRENCH d'InflectionRules : s, x, aux -> al (base d'au moins 3 lettres, connue)."""
    for suffix, base_end in (("s", ""), ("x", ""), ("aux", "al")):
        if word.endswith(suffix):
            base = word[: len(word) - len(suffix)] + base_end
            if len(base) >= 3 and base in known:
                return True
    return False


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dict-dir", required=True)
    ap.add_argument("--gboard", required=True)
    ap.add_argument("--swiftkey-tsv", required=True)
    ap.add_argument("--out-dir", required=True)
    a = ap.parse_args()
    d, out = Path(a.dict_dir), Path(a.out_dir)
    out.mkdir(parents=True, exist_ok=True)

    lines, freqs = read_taipo(d / "fr.txt")
    en = {}
    for line in (d / "en.txt").read_text(encoding="utf-8").split("\n"):
        w, _, f = line.rpartition(" ")
        if w and f.isdigit():
            en[w] = int(f)
    loaded = {w for w, f in freqs.items() if f >= MIN_FREQ}

    # Mots de référence : flags par forme minuscule ; « lower » = la forme minuscule existe dans la source.
    flags = defaultdict(lambda: {"g": False, "s": False, "s_main": False, "lower": False})
    for raw in Path(a.gboard).read_text(encoding="utf-8", errors="replace").split("\n"):
        w = norm(raw)
        if not w or "\ufffd" in w:
            continue
        f = flags[w.lower()]
        f["g"] = True
        f["lower"] |= w == w.lower()
    with open(a.swiftkey_tsv, encoding="utf-8") as fh:
        next(fh)
        for line in fh:
            parts = line.rstrip("\n").split("\t")
            if len(parts) != 3:
                continue
            w = norm(parts[2])
            if not w:
                continue
            f = flags[w.lower()]
            f["s"] = True
            f["s_main"] |= parts[1] == "main"
            f["lower"] |= w == w.lower()
    endorsed = set(flags)

    # --- 1. Candidats à l'ajout ---
    skipped = defaultdict(list)
    cands = {}  # mot -> tier
    for w, f in flags.items():
        if w in ("_oov_", "^", "<epsilon>"):
            continue
        if not f["lower"]:
            skipped["nom propre ou sigle (forme minuscule absente des sources)"].append(w)
            continue
        stem = strip_elisions(w)
        if len(stem) < 2 or not WORD_RE.match(stem) or (stem.count("'") + stem.count("-")) > 3:
            skipped["caractères ou forme hors alphabet du dictionnaire"].append(w)
            continue
        if stem != w:
            skipped.setdefault("_élision_", [])
        tier = TIER_BOTH if ((f["g"] and f["s"]) or f["s_main"]) else TIER_ONE
        cands[stem] = max(cands.get(stem, 0), tier)
    n_elided = sum(1 for w in flags if strip_elisions(w) != w)

    # Mots déjà reconnus (liste chargée, flexion, élision) : rien à faire.
    pending = {}
    for w in sorted(cands, key=lambda x: (len(x), x)):
        if w in loaded or inflected(w, loaded | set(pending)):
            skipped["déjà reconnu (liste, pluriel s/x/aux ou élision)"].append(w)
            continue
        pending[w] = cands[w]

    # Collision d'accents avec un mot déjà chargé : on ne touche pas à la logique « forme accentuée dominante ».
    folded_loaded = defaultdict(set)
    for w in loaded:
        folded_loaded[fold(w)].add(w)
    kept = {}
    for w, tier in pending.items():
        others = folded_loaded.get(fold(w), set()) - {w}
        if others:
            skipped["collision d'accent avec un mot déjà chargé"].append(w)
            continue
        kept[w] = tier
    # Entre ajouts qui ne diffèrent que par l'accent : même fréquence (égalité = aucune forme dominante).
    groups = defaultdict(list)
    for w in kept:
        groups[fold(w)].append(w)
    for ws in groups.values():
        if len(ws) > 1:
            m = min(kept[w] for w in ws)
            for w in ws:
                kept[w] = m

    # --- 2. Filtre fautes de frappe ---
    final_add, final_raise = {}, {}
    for w in sorted(kept):
        if is_typo(w, kept[w], freqs):
            skipped["faute de frappe plausible (règle de PruneDictionaryTypos)"].append(w)
            continue
        if w in freqs:
            final_raise[w] = kept[w]
        else:
            final_add[w] = kept[w]

    # --- 3. Déclassement (anglais / débris absents des listes de référence) ---
    folded_all = defaultdict(set)
    for w in loaded | set(final_add) | set(final_raise):
        folded_all[fold(w)].add(w)
    demoted = {}
    for w, f in freqs.items():
        if f < CANDIDATE_MIN or w in endorsed or w in final_add:
            continue
        if not re.fullmatch(r"[a-z]+(?:'[a-z]+)?", w):
            continue
        if len(folded_all.get(fold(w), set()) - {w}) > 0:
            continue  # paire d'accents : laissée à la logique du dictionnaire
        if inflected(w, endorsed) or strip_elisions(w) in endorsed and strip_elisions(w) != w:
            continue
        ef = en.get(w, 0)
        junk = not (set(w) & VOWELS) or re.search(r"(.)\1\1", w) is not None
        if (ef >= 1000 and ef >= 10 * f) or (junk and ef < 1000):
            demoted[w] = f

    # --- Écriture : l'ordre des lignes existantes est conservé, tri par fréquence décroissante (stable) ---
    result = []
    for w, f in lines:
        if w in final_raise:
            f = final_raise[w]
        elif w in demoted:
            f = DEMOTED_FREQ
        result.append((w, f))
    result += list(final_add.items())
    result.sort(key=lambda t: -t[1])
    (out / "fr.txt").write_text("".join(f"{w} {f}\n" for w, f in result), encoding="utf-8", newline="")
    (out / "added.txt").write_text("".join(f"{w} {f}\n" for w, f in sorted(final_add.items())), encoding="utf-8")
    (out / "raised.txt").write_text("".join(f"{w} {freqs[w]} -> {f}\n" for w, f in sorted(final_raise.items())), encoding="utf-8")
    (out / "demoted.txt").write_text("".join(f"{w} {f} -> {DEMOTED_FREQ}\n" for w, f in sorted(demoted.items(), key=lambda t: -t[1])), encoding="utf-8")
    with open(out / "skipped.txt", "w", encoding="utf-8") as fh:
        for reason, ws in skipped.items():
            if reason.startswith("_"):
                continue
            fh.write(f"# {reason} : {len(ws)}\n")
            fh.writelines(f"{w}\n" for w in sorted(ws))

    print(f"lignes avant : {len(lines)} ; après : {len(result)}")
    print(f"mots de référence : {len(flags)} (dont {n_elided} élisions ramenées à leur mot collé)")
    print(f"ajoutés : {len(final_add)} ; relevés (< 5 -> {TIER_ONE}/{TIER_BOTH}) : {len(final_raise)} ; déclassés : {len(demoted)}")
    for reason, ws in skipped.items():
        if not reason.startswith("_"):
            print(f"  écartés ({reason}) : {len(ws)}")


if __name__ == "__main__":
    sys.exit(main())

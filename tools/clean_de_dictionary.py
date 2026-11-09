#!/usr/bin/env python3
"""Nettoyage de dictionaries/de.txt à partir de listes de mots de référence (lexiques de clavier de_DE, de_AT, de_CH).

Pourquoi : de.txt vient des sous-titres (OpenSubtitles). Défauts mesurés le 06/11/2026 (plan-autocorrection-allemand.md, phase 1) :
  - 130 952 de ses 240 489 mots n'existent dans aucun lexique : fautes de frappe, débris (« chffffff »), mots anglais (« the », « you »),
    OCR « Ihr » lu « lhr / lhre / lhnen », ancienne orthographe (« laß », « mußt ») ;
  - 982 formes SANS umlaut (« fur », « konnen », « mude ») absentes des lexiques : le moteur les tient pour valides, donc ne les corrige jamais.
Les lexiques servent ici de VALIDATEUR, comme pour l'anglais et l'espagnol : aucune fréquence n'en est reprise, aucun mot n'en est ajouté.
Les entrées « fragments de composés » des lexiques (« Abend_ », « _Abbau ») ne sont pas des mots : ignorées. La casse est ignorée
(de.txt est en minuscules).

Ce que fait l'outil, dans cet ordre, sur les mots ABSENTS de tous les lexiques (un mot présent est toujours gardé, fréquence inchangée) :
  1. débris : lettre répétée 4 fois de suite ou plus (« chffffff », « aaaaah ») : retiré, quelle que soit la fréquence ;
  2. umlaut oublié : un mot sans umlaut dont la forme avec umlaut (fur -> für) ou la transcription (fuer -> für) est un mot des
     lexiques ET de de.txt : retiré. Dictionary rétablit l'umlaut ; le garder le rendrait « valide » (AccentPolicy.GERMAN, phase 2) ;
  3. ancienne orthographe : « ß » après voyelle brève (laß, mußt) dont la forme en « ss » (lass, musst) est dans les lexiques et dans
     de.txt : retiré ;
  4. OCR : un « l » initial pris pour « I » (lhr, lhre, lhnen, lch) dont la forme en « i » est un mot des lexiques et de de.txt : retiré
     (le moteur le ramène à ihr / ihre / ihnen) ;
  5. mot anglais courant (présent dans en.txt, fréquence >= EN_MIN_FREQ) : passe à la fréquence TIER (20) : valide, jamais proposé
     (le seuil de proposition de l'allemand est bien plus haut) ;
  6. mot composé avec trait d'union dont toutes les parties sont dans les lexiques (« u-bahn », « e-mail ») : gardé ;
  6bis. composé plausible rare (fréquence de 100 à 999, deux parties courantes des lexiques, liaison s, n, en, e, er, es) : gardé à la
     fréquence TIER, valide mais jamais proposé (« mordwaffe », « zimmerservice »), pour ne pas faire de vrais composés des fautes ;
  7. mot rare (fréquence < KEEP_FREQ, 1 000) : retiré. Exceptions listées : KEEP_WORDS (mots courants que les lexiques ignorent) ;
  8. fautes courantes que les lexiques acceptent à tort : KNOWN_MISSPELLINGS, retirées.
Les autres mots de fréquence >= 1 000 absents des lexiques (noms propres, interjections) sont gardés tels quels.

Utilisation (Python 3.9+), depuis la racine du projet :
  python3 tools/clean_de_dictionary.py --dict-dir dictionaries --lexicon de_DE_lexicon_sorted.txt --lexicon de_AT_lexicon_sorted.txt \
      --lexicon de_CH_lexicon_sorted.txt --out-dir /tmp/de-nettoye
Écrit de.txt (nouvelle liste), pruned.txt, debris.txt, umlaut-restores.txt, old-spelling.txt, ocr.txt, demoted.txt et kept-unlisted.txt
dans --out-dir, et affiche le seuil de proposition (fréquence du 50 000e mot) à reporter dans DictionaryLoader.candidateMinFrequency.
"""
import argparse
import re
import unicodedata
from pathlib import Path

KEEP_FREQ = 1000
TIER = 20
EN_MIN_FREQ = 100
CANDIDATE_COUNT = 50000
RESCUE_MIN_FREQ = 100
RESCUE_PART_FREQ = 200
LINKS = ("", "s", "n", "en", "e", "er", "es")
REPEAT = re.compile(r"(.)\1{3,}")
# Mots courants que les lexiques ignorent et que le nettoyage ne doit pas retirer, même rares (à compléter après relecture de pruned.txt).
KEEP_WORDS = {"ok", "okay", "hm", "hmm", "mhm", "äh", "ähm", "pfui", "igitt", "ups"}
# Fautes courantes que les lexiques acceptent à tort : corrigées par le dictionnaire (rien de connu à ce jour, liste à enrichir).
KNOWN_MISSPELLINGS: set = set()


def norm(w: str) -> str:
    return unicodedata.normalize("NFC", w.strip()).replace("’", "'").lower()


def read_freq(path: Path) -> dict:
    out = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        word, _, freq = line.rpartition(" ")
        if word:
            out[word] = int(freq)
    return out


def umlaut_forms(word: str) -> set:
    """Formes avec umlaut possibles d'un mot sans umlaut : « fur » -> « für » (u -> ü), « fuer » -> « für » (ue -> ü).

    Une seule voyelle changée à la fois, puis toutes celles d'une même transcription : assez pour fur, konnen, mude, uber, Ubung.
    """
    out = set()
    for plain, umlaut in (("a", "ä"), ("o", "ö"), ("u", "ü")):
        for i, c in enumerate(word):
            if c == plain:
                out.add(word[:i] + umlaut + word[i + 1:])
    for digraph, umlaut in (("ae", "ä"), ("oe", "ö"), ("ue", "ü")):
        for m in re.finditer(digraph, word):
            out.add(word[:m.start()] + umlaut + word[m.end():])
    return out


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dict-dir", default="dictionaries")
    ap.add_argument("--lexicon", action="append", required=True)
    ap.add_argument("--out-dir", required=True)
    args = ap.parse_args()

    ours = read_freq(Path(args.dict_dir, "de.txt"))
    english = read_freq(Path(args.dict_dir, "en.txt"))

    ref: set = set()
    for path in args.lexicon:
        for line in Path(path).read_text(encoding="utf-8", errors="replace").splitlines():
            line = line.strip()
            if line and "_" not in line:
                ref.add(norm(line))

    def valid_target(candidate: str) -> bool:
        return candidate in ref and candidate in ours

    def is_compound(word: str) -> bool:
        """Deux parties courantes des lexiques (>= 4 lettres, liaison s, n, en, e, er, es) : un composé plausible (phase 4 du plan, version simple)."""
        if not word.isalpha() or len(word) < 8:
            return False
        for i in range(4, len(word) - 3):
            right = word[i:]
            if right not in ref or ours.get(right, 0) < RESCUE_PART_FREQ:
                continue
            left = word[:i]
            for link in LINKS:
                head = left[:len(left) - len(link)] if link and left.endswith(link) else (left if not link else None)
                if head and len(head) >= 4 and head in ref and ours.get(head, 0) >= RESCUE_PART_FREQ:
                    return True
        return False

    kept: dict = {}
    rescued: list = []
    pruned: list = []
    debris: list = []
    umlaut_restores: list = []
    old_spelling: list = []
    ocr: list = []
    demoted: list = []
    unlisted: list = []
    for word, freq in ours.items():
        if word in ref:
            kept[word] = freq
            continue
        if REPEAT.search(word):
            debris.append(f"{word} {freq}")
            continue
        target = None
        if not any(c in word for c in "äöüß"):
            targets = [c for c in umlaut_forms(word) if valid_target(c)]
            # Un mot anglais courant (« full », « sarge ») n'est rétabli que si la forme à umlaut est plus fréquente : sinon il est rétrogradé (5).
            if targets and english.get(word, 0) >= EN_MIN_FREQ and ours[max(targets, key=ours.get)] < freq:
                targets = []
            if targets:
                umlaut_restores.append(f"{word} {freq} -> {max(targets, key=ours.get)}")
                continue
        if "ß" in word and valid_target(word.replace("ß", "ss")):
            old_spelling.append(f"{word} {freq} -> {word.replace('ß', 'ss')}")
            continue
        if word.startswith("l") and len(word) >= 3 and valid_target("i" + word[1:]):
            ocr.append(f"{word} {freq} -> i{word[1:]}")
            continue
        if english.get(word, 0) >= EN_MIN_FREQ:
            kept[word] = min(freq, TIER)
            demoted.append(f"{word} {freq}")
        elif "-" in word and all(part in ref for part in word.split("-") if part):
            kept[word] = freq
        elif word in KEEP_WORDS:
            kept[word] = freq
        elif freq < KEEP_FREQ and freq >= RESCUE_MIN_FREQ and is_compound(word):
            kept[word] = TIER
            rescued.append(f"{word} {freq}")
        elif freq < KEEP_FREQ:
            pruned.append(f"{word} {freq}")
        else:
            kept[word] = freq
            unlisted.append(f"{word} {freq}")

    for word in KNOWN_MISSPELLINGS:
        if kept.pop(word, None) is not None:
            pruned.append(f"{word} (faute connue)")

    out = Path(args.out_dir)
    out.mkdir(parents=True, exist_ok=True)
    ordered = sorted(kept.items(), key=lambda item: (-item[1], item[0]))
    (out / "de.txt").write_text("\n".join(f"{w} {f}" for w, f in ordered) + "\n", encoding="utf-8")
    for name, rows in (("pruned.txt", pruned), ("debris.txt", debris), ("umlaut-restores.txt", umlaut_restores),
                        ("old-spelling.txt", old_spelling), ("ocr.txt", ocr), ("demoted.txt", demoted), ("rescued-compounds.txt", rescued), ("kept-unlisted.txt", unlisted)):
        (out / name).write_text("\n".join(rows) + "\n", encoding="utf-8")
    threshold = ordered[CANDIDATE_COUNT - 1][1] if len(ordered) >= CANDIDATE_COUNT else ordered[-1][1]
    print(f"de.txt : {len(ours)} -> {len(kept)} mots ; rares retirés {len(pruned)} ; débris {len(debris)} ; umlaut rétablis {len(umlaut_restores)} ; "
          f"ancienne orthographe {len(old_spelling)} ; OCR {len(ocr)} ; anglais rétrogradés {len(demoted)} ; composés sauvés {len(rescued)} ; gardés hors lexiques {len(unlisted)}")
    print(f"Seuil de proposition (fréquence du {CANDIDATE_COUNT}e mot) : {threshold} ; "
          f"mots proposables à ce seuil : {sum(1 for _, f in ordered if f >= threshold)}")


if __name__ == "__main__":
    main()

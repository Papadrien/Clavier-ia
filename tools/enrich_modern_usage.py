#!/usr/bin/env python3
"""Enrichit dictionaries/<code>.txt et dictionaries/names/<code>.txt avec le vocabulaire d'aujourd'hui (16/11/2026).

Pourquoi : les listes viennent des sous-titres de films (OpenSubtitles 2018). Le vocabulaire écrit et récent y est absent ou
trop rare pour être proposé : « télétravail » (21), « covid » (60), « visioconférence » (31) étaient valides mais jamais proposés
en correction (seuil 100) ; « déconfinement », « cryptomonnaie », « influenceur », « tkt » manquaient et pouvaient être remplacés
par un autre mot. Les noms propres (pays, villes, prénoms, marques) n'avaient pas leur majuscule.

Sources :
  - wordfreq (Robyn Speer, https://github.com/rspeer/wordfreq, données CC BY-SA 4.0) : fréquences de mots tirées de Wikipédia,
    de sous-titres, de la presse, de livres, du Web, de Twitter et de Reddit. Elles donnent la FRÉQUENCE d'aujourd'hui.
  - Hunspell (dictionnaires orthographiques des paquets hunspell-<langue> : les mêmes que ceux de LibreOffice) : VALIDE un mot
    (rien n'en est recopié). Un mot accepté seulement avec une majuscule est un nom propre (ou, en allemand, un nom commun).
  - AOSP (listes du clavier Android d'origine, Apache 2.0, voir tools/align_open_lexicons.py) : valide, et donne la casse des noms.
  - Lexique 3.83 (http://www.lexique.org, CC BY-SA 4.0), français seulement : valide.
  - Tatoeba (CC BY 2.0 FR) : casse des mots en milieu de phrase (nom propre ou non).
  - tools/modern-words/<code>.txt et names-<code>.txt : mots et noms écrits à la main (abréviations de messagerie, mots récents,
    marques), sans source externe.

Ce que fait l'outil :
  1. FRÉQUENCE MODERNE d'un mot = sa probabilité wordfreq x K, K étant le rapport médian « fréquence de la liste / probabilité
     wordfreq » sur les mots courants : les deux échelles sont alignées.
  2. AJOUT : un mot de wordfreq absent de la liste (ou vu moins de 5 fois) est ajouté avec sa fréquence moderne s'il est validé
     (Hunspell en minuscules ; ou Lexique / AOSP), sans apostrophe, d'au moins 4 lettres. Pas d'ajout pour : une élision ou une
     contraction sans son apostrophe (« javais », « sils » : le clavier doit rétablir « j'avais », « s'ils ») ; un mot à une faute
     de frappe d'un mot au moins 300 fois plus fréquent (« encor ») ; en français, une forme qui n'existe qu'au passé simple ou au
     subjonctif imparfait (« bottas », « privas » : ce sont des noms propres écrits en minuscules dans wordfreq).
  3. PROMOTION : un mot valide de la liste dont la fréquence moderne est plus haute la reçoit (jamais de baisse), aux mêmes
     conditions de validation. Un mot de 5 lettres ou moins doit être connu de deux sources (les mots courts se confondent).
     Ni ajout ni promotion pour :
     - une forme moins accentuée qu'un mot de la liste (« aout », « ile », « coute » : l'autocorrection doit continuer à rétablir
       « août », « île », « coûte ») ;
     - un mot anglais bien plus courant en anglais que dans la langue (« house », « wall », « fantasy » : wordfreq de l'anglais) ;
     - un mot qu'AOSP connaît surtout avec une majuscule (« mans », « eure » : la fréquence de wordfreq, tout en minuscules, est
       celle du nom propre).
  4. NOMS (names/<code>.txt) : mots acceptés par Hunspell seulement avec une majuscule (« France », « Marseille ») ou en capitales
     (« SNCF », s'ils sont aussi dans AOSP), assez fréquents, dont la forme en minuscules n'est un mot ni dans la langue ni en
     anglais. La casse est celle d'AOSP quand il connaît le nom ; un nom dont la casse commence par une minuscule (« iPad ») n'est
     pas ajouté.
  5. SEUIL : rien ne change par défaut (le nombre de mots proposables augmente). Avec --target-candidates N, les fréquences sont
     remises à l'échelle pour que le N-ième mot retombe sur le seuil de DictionaryLoader.candidateMinFrequency.
     Les mots vus moins de 5 fois (ignorés au chargement) ne changent pas.

Dépendances : python3 avec le module msgpack (lecture des fichiers de wordfreq), la commande hunspell.
Utilisation, depuis la racine du projet (sans --apply : rapport seulement, dans --report-dir) :
  pip download wordfreq --no-deps && unzip wordfreq-*.whl 'wordfreq/data/*'
  python3 tools/enrich_modern_usage.py --lang fr --wordfreq wordfreq/data/large_fr.msgpack.gz --hunspell /usr/share/hunspell/fr \
      --aosp main_fr.combined --lexique Lexique383.tsv --english-hunspell /usr/share/hunspell/en_US /usr/share/hunspell/en_GB \
      --tatoeba fra_sentences.tsv.bz2 --report-dir /tmp/fr [--apply]
Ensuite : python3 tools/build_ngrams.py (bigrammes sur le nouveau vocabulaire), liste de démarrage (head -40000), banc.
"""
import argparse
import bz2
import gzip
import re
import statistics
import subprocess
import unicodedata
from pathlib import Path

MIN_FREQ = 5
WORD_RE = re.compile(r"^[^\W\d_]+(?:-[^\W\d_]+)*$")
# Longueur minimale d'un mot ajouté ou promu par l'outil : les mots très courts sont saturés (une faute de frappe y tombe sur un
# autre mot) ; ceux qui manquent sont écrits à la main dans tools/modern-words/.
MIN_LENGTH = 4
# Un mot court ajouté doit être courant (fréquence moderne au moins égale au seuil de proposition).
SHORT_LENGTH = 4
# Un mot de cette longueur ou moins n'est promu que s'il est connu de deux sources.
TWO_SOURCES_LENGTH = 5
# Un mot anglais n'est ajouté ou promu que si sa probabilité dans la langue atteint cette part de sa probabilité en anglais.
ENGLISH_SHARE = 0.3
# Un mot ajouté à une faute de frappe d'un mot au moins TYPO_DOMINANCE fois plus fréquent est sans doute cette faute ; pour un mot de
# plus de TYPO_SHORT lettres (« équipez » / « équipe »), il faut un écart dix fois plus grand.
TYPO_DOMINANCE = 300
TYPO_SHORT = 5
# Fréquences que les passes précédentes ont données aux mots anglais écartés des propositions (SOURCES.txt) : jamais promus.
FROZEN_ENGLISH = {"fr": {99}, "de": {20}}
# Élisions : préfixe + mot commençant par une voyelle ou un h. Le mot collé (« javais ») n'est jamais ajouté.
ELISIONS = {
    "fr": ("l", "d", "j", "n", "m", "t", "s", "c", "qu", "jusqu", "lorsqu", "puisqu"),
    "it": ("l", "d", "un", "dell", "nell", "all", "sull", "dall", "c", "m", "t", "s", "v", "quest", "quell", "bell", "sant"),
}
# Noms propres : fréquence moderne minimale, en multiples du seuil de proposition (un nom rare est une faute de frappe possible).
NAME_MIN_SHARE = 3
NAME_MIN_SHARE_LONG = 1
NAME_LONG = 7
# Un nom de 3 lettres (« Luc », « Kim ») doit être très courant : un mot de 3 lettres tapé en minuscules est souvent une faute.
NAME_MIN_SHARE_SHORT = 30
ACRONYM_MIN_SHARE = 10
# Tatoeba : un mot vu au moins ce nombre de fois en milieu de phrase, presque toujours avec sa majuscule, est un nom propre, même si
# sa forme en minuscules est un mot rare du dictionnaire (« Chine » / « chine », « Canada » / « canada »).
TATOEBA_MIN = 30
TATOEBA_CAPITAL_SHARE = 0.97
# Débris du Web dans wordfreq.
BLOCKED = {"http", "https", "www", "html", "href", "jpeg", "nbsp", "quot", "utm", "php", "amp"}


def read_wordfreq(path):
    """Fichier « cBpack » de wordfreq : en-tête puis une liste de mots par centibel (indice i = probabilité 10^(-i/100))."""
    import msgpack

    with gzip.open(path, "rb") as f:
        data = msgpack.load(f, raw=False)
    words = {}
    for index, bucket in enumerate(data[1:]):
        probability = 10 ** (-index / 100)
        for word in bucket:
            words[word] = probability
    return words


def read_aosp(paths):
    """Mots AOSP -> note f. Les mots marqués grossiers ou « not_a_word » sont ignorés."""
    words = {}
    for path in paths:
        for line in Path(path).read_text(encoding="utf-8").splitlines():
            if not line.startswith(" word="):
                continue
            fields = dict(part.split("=", 1) for part in line.strip().split(",") if "=" in part)
            if fields.get("possibly_offensive") == "true" or fields.get("not_a_word") == "true":
                continue
            word, f = fields.get("word", ""), fields.get("f", "0")
            f = 255 if f == "whitelist" else int(f) if f.isdigit() else 0
            if f > words.get(word, -1):
                words[word] = f
    return words


def hunspell_rejected(tokens, dic):
    text = "".join(t + "\n" for t in tokens)
    out = subprocess.run(["hunspell", "-i", "utf-8", "-l", "-d", dic], input=text, capture_output=True, text=True).stdout
    return set(out.split("\n"))


def hunspell_status(tokens, dictionaries):
    """Pour chaque morceau : « lower » (accepté tel quel), « cap » (seulement avec une majuscule), « upper » (seulement en capitales)
    ou « no », par le premier des dictionnaires qui l'accepte, la forme la plus permissive gagnant."""
    rank = {"lower": 3, "cap": 2, "upper": 1, "no": 0}
    status = {t: "no" for t in tokens}
    for dic in dictionaries:
        todo = sorted(t for t in tokens if status[t] != "lower")
        rejected = hunspell_rejected(todo, dic)
        capitalized = {t: t[:1].upper() + t[1:] for t in todo if t in rejected}
        rejected_cap = hunspell_rejected(sorted(set(capitalized.values())), dic)
        upper = {t: t.upper() for t in capitalized if capitalized[t] in rejected_cap}
        rejected_upper = hunspell_rejected(sorted(set(upper.values())), dic)
        for t in todo:
            if t not in rejected:
                found = "lower"
            elif capitalized[t] not in rejected_cap:
                found = "cap"
            elif upper[t] not in rejected_upper:
                found = "upper"
            else:
                found = "no"
            if rank[found] > rank[status[t]]:
                status[t] = found
    return status


def hunspell_rare_only(words, dic, rare_tags):
    """Mots de [words] dont toutes les analyses morphologiques (hunspell -m) portent une étiquette de [rare_tags] (temps rares)."""
    import os

    env = dict(os.environ, LC_ALL="C.UTF-8", LANG="C.UTF-8")
    text = "".join(w + "\n" for w in words)
    out = subprocess.run(["hunspell", "-i", "utf-8", "-m", "-d", dic], input=text, capture_output=True, text=True, env=env).stdout
    analyses = {}
    for line in out.splitlines():
        word, _, tags = line.partition(" ")
        if tags.strip():
            analyses.setdefault(word, []).append(tags)
    return {w for w, found in analyses.items() if all(any(f"po:{tag}" in tags for tag in rare_tags) for tags in found)}


def fold(word):
    return "".join(c for c in unicodedata.normalize("NFD", word) if unicodedata.category(c) != "Mn")


def marks(word):
    return sum(1 for a, b in zip(word, fold(word)) if a != b)


def read_curated(path):
    """Lignes « mot » ou « mot fréquence » (# = commentaire) -> {mot: fréquence ou None}."""
    words = {}
    if path and Path(path).is_file():
        for line in Path(path).read_text(encoding="utf-8").splitlines():
            line = line.split("#", 1)[0].strip()
            if line:
                word, _, frequency = line.partition(" ")
                words[word] = int(frequency) if frequency.strip().isdigit() else None
    return words


def tatoeba_case_counts(path):
    """Mots en milieu de phrase -> (nombre avec majuscule initiale, nombre total), clé en minuscules."""
    counts = {}
    token_re = re.compile(r"[^\W\d_]+")
    with bz2.open(path, "rt", encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 3:
                continue
            tokens = token_re.findall(parts[2])
            for token in tokens[1:]:
                if token.isupper() and len(token) > 1:
                    continue  # phrase ou mot en capitales : ne dit rien
                key = token.lower()
                capital, total = counts.get(key, (0, 0))
                counts[key] = (capital + (1 if token[0].isupper() else 0), total + 1)
    return counts


def candidate_min(root, lang):
    """Seuil de proposition de la langue, lu dans DictionaryLoader.candidateMinFrequency."""
    source = (root / "app/src/main/java/fr/junade/taipo/dictionary/DictionaryLoader.kt").read_text(encoding="utf-8")
    match = re.search(r"KeyboardLanguage\.%s -> (\d+)L" % lang.upper(), source)
    if not match:
        raise SystemExit(f"seuil de {lang} introuvable dans DictionaryLoader")
    return int(match.group(1))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--lang", required=True)
    parser.add_argument("--wordfreq", required=True, help="wordfreq/data/large_<code>.msgpack.gz (ou small_)")
    parser.add_argument("--hunspell", nargs="*", default=[], help="dictionnaires de la langue, chemins sans extension")
    parser.add_argument("--aosp", nargs="*", default=[])
    parser.add_argument("--lexique", help="Lexique383.tsv (français)")
    parser.add_argument("--english-hunspell", nargs="*", default=[], help="dictionnaires anglais : mots anglais à ne pas promouvoir")
    parser.add_argument("--english-wordfreq", help="wordfreq de l'anglais : un mot anglais doit être assez courant dans la langue")
    parser.add_argument("--rare-morph", nargs="*", default=[], help="étiquettes Hunspell de temps rares (français : ipsi simp)")
    parser.add_argument("--target-candidates", type=int, default=0, help="nombre de mots proposables à garder (0 : pas de remise à l'échelle)")
    parser.add_argument("--tatoeba", help="phrases Tatoeba de la langue (<iso3>_sentences.tsv.bz2) : casse des noms")
    parser.add_argument("--nouns-are-capitalized", action="store_true", help="allemand : un mot accepté avec majuscule est un nom commun, valide")
    parser.add_argument("--no-names", action="store_true", help="ne pas toucher à names/<code>.txt")
    parser.add_argument("--root", default=".")
    parser.add_argument("--report-dir", required=True)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    root, lang = Path(args.root), args.lang
    report = Path(args.report_dir)
    report.mkdir(parents=True, exist_ok=True)
    threshold = candidate_min(root, lang)

    list_path = root / "dictionaries" / f"{lang}.txt"
    entries = {}
    for line in list_path.read_text(encoding="utf-8").splitlines():
        word, _, frequency = line.partition(" ")
        entries[word] = int(frequency)
    names_path = root / "dictionaries" / "names" / f"{lang}.txt"
    names_lines = names_path.read_text(encoding="utf-8").splitlines()
    names = {l.strip().lower(): l.strip() for l in names_lines if l.strip() and not l.startswith("#")}

    wordfreq = {w: p for w, p in read_wordfreq(args.wordfreq).items() if w not in BLOCKED}
    ratios = [entries[w] / p for w, p in wordfreq.items() if entries.get(w, 0) >= 1000 and p >= 1e-6]
    scale = statistics.median(ratios)
    modern = lambda w: wordfreq.get(w, 0.0) * scale

    aosp = read_aosp(args.aosp)
    aosp_lower = {w for w in aosp if w == w.lower()}
    aosp_cased = {}
    for word, f in aosp.items():
        if word != word.lower() and f > aosp.get(aosp_cased.get(word.lower(), ""), -1):
            aosp_cased[word.lower()] = word
    lexique = set()
    if args.lexique:
        with open(args.lexique, encoding="utf-8") as f:
            next(f)
            lexique = {line.split("\t", 1)[0] for line in f}

    valid = {w for w, n in entries.items() if n >= MIN_FREQ}
    curated = read_curated(root / "tools" / "modern-words" / f"{lang}.txt")
    curated_names = [w for w in read_curated(root / "tools" / "modern-words" / f"names-{lang}.txt")]
    pool = {w for w in set(wordfreq) | valid if WORD_RE.match(w)}
    tokens = {part for w in pool for part in w.split("-") if part}
    status = hunspell_status(tokens, args.hunspell) if args.hunspell else {}
    english = hunspell_status(tokens, args.english_hunspell) if args.english_hunspell and lang != "en" else {}
    accepted = {"lower", "cap"} if args.nouns_are_capitalized else {"lower"}

    def spelled(word):
        return all(status.get(part, "no") in accepted for part in word.split("-"))

    def is_english(word):
        return english.get(word) == "lower"

    english_wordfreq = read_wordfreq(args.english_wordfreq) if args.english_wordfreq and lang != "en" else {}

    frozen = FROZEN_ENGLISH.get(lang, set())

    def english_leak(word, current):
        """Mot anglais dont la fréquence vient de textes anglais mêlés au corpus : écarté des propositions par une passe précédente,
        ou absent de la liste et bien plus courant en anglais que dans la langue."""
        if not is_english(word):
            return False
        if current in frozen:
            return True
        return current < MIN_FREQ and wordfreq.get(word, 0.0) < ENGLISH_SHARE * english_wordfreq.get(word, 0.0)

    def second_source(word):
        return word in lexique or word in aosp_lower

    def name_dominant(word):
        """AOSP connaît le mot surtout avec une majuscule."""
        cased = aosp_cased.get(word)
        return cased is not None and aosp[cased] > aosp.get(word, -1)

    by_fold = {}
    for word in valid:
        by_fold.setdefault(fold(word), []).append(word)

    def shadowed(word):
        """Vrai si un mot valide plus accentué, au moins aussi fréquent dans la liste, a la même forme sans accent (« aout » / « août »)."""
        own = entries.get(word, 0)
        return any(v != word and marks(v) > marks(word) and entries[v] >= own for v in by_fold.get(fold(word), ()))

    contractions = {w.replace("'", "") for w in valid if "'" in w}
    contractions |= {fold(w) for w in contractions}
    vowels = set("aeiouyhàâäéèêëîïôöùûüœæ")
    alphabet = sorted({c for w in valid for c in w if c.isalpha()})

    def elision(word, target):
        if word in contractions:
            return True
        for prefix in ELISIONS.get(lang, ()):
            rest = word[len(prefix):]
            if word.startswith(prefix) and len(rest) >= 2 and rest[0] in vowels and max(entries.get(rest, 0), entries.get(fold(rest), 0)) >= 20 * target:
                return True
        return False

    def typo_of_common(word, target):
        floor = TYPO_DOMINANCE * target * (1 if len(word) <= TYPO_SHORT else 10)
        for i in range(len(word) + 1):
            if i < len(word):
                if entries.get(word[:i] + word[i + 1:], 0) >= floor:
                    return True
                for c in alphabet:
                    if c != word[i] and entries.get(word[:i] + c + word[i + 1:], 0) >= floor:
                        return True
            if i + 1 < len(word) and entries.get(word[:i] + word[i + 1] + word[i] + word[i + 2:], 0) >= floor:
                return True
            for c in alphabet:
                if entries.get(word[:i] + c + word[i:], 0) >= floor:
                    return True
        return False

    added, promoted, skipped = {}, {}, {}
    for word, probability in wordfreq.items():
        if not WORD_RE.match(word) or len(word) < MIN_LENGTH:
            continue
        target = round(probability * scale)
        current = entries.get(word, 0)
        if target <= current or target < MIN_FREQ:
            continue
        if not (spelled(word) if args.hunspell else word in aosp_lower):
            continue
        if english_leak(word, current):
            skipped[word] = "anglais"
        elif name_dominant(word):
            skipped[word] = "nom propre"
        elif shadowed(word):
            skipped[word] = "moins accentué"
        elif current < MIN_FREQ:
            if len(word) <= SHORT_LENGTH and target < threshold:
                continue
            if elision(word, target):
                skipped[word] = "élision"
            elif typo_of_common(word, target):
                skipped[word] = "faute de frappe"
            else:
                added[word] = target
        elif len(word) <= TWO_SOURCES_LENGTH and args.hunspell and not second_source(word):
            skipped[word] = "mot court, une seule source"
        else:
            promoted[word] = (current, target)
    if args.hunspell:
        # Préfixes (« sino », po:pfx) et, en français, formes qui n'existent qu'à un temps rare : jamais ajoutés.
        for word in hunspell_rare_only(sorted(added), args.hunspell[0], ["pfx"] + args.rare_morph):
            skipped[word] = "préfixe ou temps rare"
            del added[word]
    # Mots écrits à la main : toujours valides ; fréquence donnée, sinon fréquence moderne, sinon sous le seuil de proposition.
    for word, frequency in curated.items():
        target = frequency if frequency is not None else max(round(modern(word)), int(threshold * 0.6))
        current = entries.get(word, 0)
        if target > current:
            added.pop(word, None)
            promoted.pop(word, None)
            skipped.pop(word, None)
            if current < MIN_FREQ:
                added[word] = target
            else:
                promoted[word] = (current, target)

    merged = dict(entries)
    merged.update(added)
    merged.update({w: target for w, (_, target) in promoted.items()})

    # Remise à l'échelle (facultative) : le N-ième mot retombe sur le seuil de proposition du code.
    ranked = sorted(merged.values(), reverse=True)
    pivot = threshold
    if args.target_candidates and len(ranked) >= args.target_candidates:
        pivot = max(ranked[args.target_candidates - 1], threshold)

    def rescale(frequency):
        if frequency < MIN_FREQ or pivot == threshold:
            return frequency
        if frequency >= pivot:
            return max(threshold, round(frequency * threshold / pivot))
        return MIN_FREQ + (frequency - MIN_FREQ) * (threshold - MIN_FREQ) // (pivot - MIN_FREQ)

    final = {w: rescale(n) for w, n in merged.items()}

    # Noms propres.
    new_names, name_entries = {}, {}
    if not args.no_names:
        case_counts = tatoeba_case_counts(args.tatoeba) if args.tatoeba else {}

        not_names = set(read_curated(root / "tools" / "modern-words" / f"not-names-{lang}.txt"))

        def common_word(lower, tatoeba_name):
            """La forme en minuscules (ou sans accent) est un mot : jamais un nom, sauf si Tatoeba l'écrit presque toujours avec une majuscule."""
            if lower in not_names or is_english(lower):
                return True
            if (status.get(lower) == "lower" or lower in lexique) and not tatoeba_name:
                return True
            folded = fold(lower)
            return folded != lower and (status.get(folded) == "lower" or is_english(folded) or folded in lexique)

        for word in wordfreq:
            if word in names or word in curated or not word.isalpha() or len(word) < 3:
                continue
            capital, total = case_counts.get(word, (0, 0))
            tatoeba_name = total >= TATOEBA_MIN and capital >= total * TATOEBA_CAPITAL_SHARE
            if common_word(word, tatoeba_name):
                continue
            share = modern(word) / threshold
            state = status.get(word, "no")
            if total >= TATOEBA_MIN and capital < total * 0.5:
                continue  # écrit en minuscules dans les phrases : pas un nom
            cased = aosp_cased.get(word)
            display = None
            if state == "upper" and not args.nouns_are_capitalized:
                if share >= ACRONYM_MIN_SHARE and word.upper() in aosp:
                    display = word.upper()
            elif state == "cap" or tatoeba_name or (cased and word not in aosp_lower):
                if args.nouns_are_capitalized and state != "cap":
                    continue
                if cased and not (cased[0].isupper() and cased.isalpha()):
                    continue  # « iPad », « eBay », « kWh » : casse impossible à rendre par une simple liste de noms
                minimum = NAME_MIN_SHARE_SHORT if len(word) == 3 else NAME_MIN_SHARE_LONG if len(word) >= NAME_LONG else NAME_MIN_SHARE
                if share >= minimum:
                    display = cased or word[:1].upper() + word[1:]
            if display:
                new_names[word] = display
        for display in curated_names:
            if display.lower() not in names:
                new_names[display.lower()] = display
        # Un nom doit aussi être un mot valide de la liste : sinon sa forme en minuscules passerait par la recherche de correction
        # (« lorient » -> « l'orient ») avant de recevoir sa majuscule. Fréquence moderne ; un nom écrit à la main est proposable.
        for lower in new_names:
            wanted = max(MIN_FREQ * 4, round(modern(lower)), threshold if lower in {n.lower() for n in curated_names} else 0)
            if final.get(lower, 0) < wanted:
                name_entries[lower] = wanted
                final[lower] = wanted

    lost = sorted((w for w, n in entries.items() if n >= threshold and final[w] < threshold), key=lambda w: -entries[w])
    gained = sorted((w for w, n in final.items() if n >= threshold and entries.get(w, 0) < threshold), key=lambda w: -final[w])
    lines = sorted(final.items(), key=lambda item: (-item[1], item[0]))
    candidates = sum(1 for _, n in lines if n >= threshold)
    summary = (
        f"{lang}: {len(entries)} lignes -> {len(final)} ; valides {len(valid)} -> {sum(1 for n in final.values() if n >= MIN_FREQ)} ; "
        f"K = {scale:.3g} ; ajoutés {len(added)} ; promus {len(promoted)} ; écartés {len(skipped)} ; "
        f"pivot {pivot} -> seuil {threshold} ; proposables {sum(1 for n in entries.values() if n >= threshold)} -> {candidates} "
        f"(entrés {len(gained)}, sortis {len(lost)}) ; noms {len(names)} -> {len(names) + len(new_names)} (dont {len(name_entries)} ajoutés ou relevés dans la liste)"
    )
    print(summary)
    (report / "summary.txt").write_text(summary + "\n", encoding="utf-8")
    (report / "added.txt").write_text("".join(f"{w} {n}\n" for w, n in sorted(added.items(), key=lambda i: -i[1])), encoding="utf-8")
    (report / "promoted.txt").write_text(
        "".join(f"{w} {a} -> {b}\n" for w, (a, b) in sorted(promoted.items(), key=lambda i: -i[1][1])), encoding="utf-8"
    )
    (report / "candidates-gained.txt").write_text("".join(f"{w} {entries.get(w, 0)} -> {final[w]}\n" for w in gained), encoding="utf-8")
    (report / "candidates-lost.txt").write_text("".join(f"{w} {entries[w]} -> {final[w]}\n" for w in lost), encoding="utf-8")
    (report / "names-added.txt").write_text(
        "".join(f"{d} {round(modern(w))}\n" for w, d in sorted(new_names.items(), key=lambda i: -modern(i[0]))), encoding="utf-8"
    )
    (report / "skipped.txt").write_text(
        "".join(f"{w} {round(modern(w))} {reason}\n" for w, reason in sorted(skipped.items(), key=lambda i: (i[1], -modern(i[0])))), encoding="utf-8"
    )

    if args.apply:
        list_path.write_text("".join(f"{w} {n}\n" for w, n in lines), encoding="utf-8", newline="\n")
        if new_names:
            header = [l for l in names_lines if l.startswith("#")]
            body = sorted(set(names.values()) | set(new_names.values()), key=lambda n: (fold(n.lower()), n))
            names_path.write_text("\n".join(header + body) + "\n", encoding="utf-8", newline="\n")


if __name__ == "__main__":
    main()

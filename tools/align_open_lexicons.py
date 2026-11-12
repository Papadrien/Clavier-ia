#!/usr/bin/env python3
"""Aligne dictionaries/<code>.txt sur deux sources ouvertes (08/11/2026, docs/autocorrection-roadmap.md, « Listes de mots »).

Sources :
  - AOSP : listes du clavier Android d'origine (« legacy », Apache 2.0), reprises par HeliBoard :
    https://codeberg.org/Helium314/aosp-dictionaries (wordlists/main_<locale>.combined). Elles servent à VALIDER et à AJOUTER.
  - LibreOffice : dictionnaires orthographiques Hunspell (https://github.com/LibreOffice/dictionaries). Ils servent seulement à
    VALIDER (aucun mot n'en est recopié). Ils sont interrogés avec la commande `hunspell` (mode -l, UTF-8).

Pourquoi : les listes viennent des sous-titres (OpenSubtitles). Un mot qu'aucune source ne connaît est souvent une faute de frappe, une
fusion ou un débris du corpus ; « valide », il empêche l'autocorrection de le corriger. À l'inverse, des mots justes manquent.

Ce que fait l'outil :
  1. RETRAIT : un mot inconnu d'AOSP ET de Hunspell (forme telle quelle ou avec majuscule), de fréquence < KEEP_FREQ, est retiré
     dans trois cas seulement : sa forme accentuée est connue (« plutot », « riprendera ») ; il est à une erreur de frappe d'un mot connu
     au moins TYPO_DOMINANCE fois plus fréquent ; c'est un mot anglais (listes AOSP anglaises) dans une autre langue. Un mot inconnu des
     deux sources peut être juste (prénom, argot « lol », diminutif « cositas », impératif familier « lern ») : hors de ces cas, il reste.
     Jamais retirés non plus : un mot de messagerie (CHAT : « lol », « vc »), un mot employé dans les phrases Tatoeba de la langue
     (au moins TATOEBA_EVIDENCE fois), en allemand un impératif familier sans « e » (« lern ») d'un mot connu, un prénom (names/<code>.txt), un mot de PROTECTED, un mot à apostrophe dont la partie après l'apostrophe est
     connue (élision), un mot qui se découpe en deux mots connus (composés : allemand, néerlandais), une forme régulière d'un mot
     connu (pluriel en s / x, possessif néerlandais en 's).
  2. AJOUT : un mot d'AOSP de note f >= MIN_ADD_F absent de la liste (ou de fréquence < 5) est ajouté. Sa fréquence est celle qu'ont, dans la liste, les mots
     d'AOSP de même note f (médiane), plafonnée à ADD_CAP_SHARE x le seuil de proposition de la langue : un mot ajouté est valide, et
     proposé seulement s'il est très courant. Pas d'ajout : mot à majuscule dans AOSP (nom propre ; en allemand, les noms communs sont
     mis en minuscules), mot marqué « possibly_offensive » ou « not_a_word », abréviation, mot qui n'est pas fait de lettres (avec
     apostrophe ou trait d'union intérieurs).
Aucune fréquence d'un mot gardé n'est modifiée. Le fichier reste trié par fréquence décroissante.

Utilisation, depuis la racine du projet (voir docs/autocorrection-roadmap.md pour les fichiers de chaque langue) :
  python3 tools/align_open_lexicons.py --lang it --aosp main_it.combined --hunspell lo/it_IT/it_IT --report-dir /tmp/it [--apply]
Sans --apply, écrit seulement le rapport (removed.txt, added.txt, résumé).
"""
import argparse
import bz2
import re
import statistics
import subprocess
import unicodedata
from pathlib import Path

KEEP_FREQ = 1000
# Une faute de frappe probable : à une erreur d'un mot connu au moins 50 fois plus fréquent (même règle que tools/PruneDictionaryTypos.kt, plus large).
TYPO_DOMINANCE = 50
MIN_FREQ = 5
# Seuils de proposition de DictionaryLoader.candidateMinFrequency.
CANDIDATE_MIN = {"fr": 100, "en": 149, "es": 142, "de": 48, "it": 102, "pt": 91, "nl": 77,
                 "pl": 221, "ro": 166, "cs": 198, "hu": 214, "sv": 42, "da": 29, "nb": 19, "fi": 134}
# Un mot ajouté ne dépasse pas ce multiple du seuil de proposition (au-delà, il serait proposé avant des mots déjà connus).
ADD_CAP_SHARE = 3
# Note AOSP minimale d'un mot ajouté : en dessous, surtout des noms de famille et du bruit (« sher » en allemand, f = 23).
MIN_ADD_F = 30
WORD_RE = re.compile(r"^[^\W\d_]+(?:['\-][^\W\d_]+)*$")
COMPOUND_LANGS = {"de": ("", "s", "n", "en", "e", "er", "es"), "nl": ("", "s", "en", "e")}
# Mots gardés même inconnus des deux sources (remplacés par les règles de contexte, voir tools/complete_from_tatoeba.py).
PROTECTED = {
    "it": {"qual'è", "qual'era"},
    "pt": {"concerteza", "derrepente", "apartir", "porisso", "denovo"},
}
# Mots de messagerie que les deux sources ignorent : jamais retirés (« lol » n'est pas une faute de « lot »).
CHAT = {
    "fr": {"lol", "mdr", "ptdr", "stp", "svp", "bcp", "jsp", "jpp", "wesh", "ouais", "ok", "okay", "wifi", "selfie", "emoji", "dsl", "tkt", "pk", "pq", "slt", "bjr", "cc", "bisous", "bises"},
    "en": {"lol", "lmao", "omg", "btw", "idk", "imo", "tbh", "brb", "thx", "pls", "plz", "ok", "okay", "wifi", "selfie", "emoji", "bae", "fam", "luv", "app", "apps", "ipad", "iphone", "uber"},
    "es": {"jaja", "jajaja", "xd", "ok", "okey", "wifi", "selfie", "emoji", "porfa", "q", "xq", "tqm", "bss"},
    "de": {"lol", "ok", "okay", "wifi", "selfie", "emoji", "hdl", "hdgdl", "lg", "vg", "mfg", "jaja", "nö", "jo", "ne"},
    "it": {"lol", "ok", "okay", "wifi", "selfie", "emoji", "cmq", "nn", "xké", "xchè", "tvb", "tvtb", "grz", "ahah", "ahahah"},
    "pt": {"kkk", "kkkk", "vc", "vcs", "tb", "tbm", "blz", "ok", "okay", "wifi", "selfie", "emoji", "rs", "rsrs", "obg", "pq", "tô", "tá", "pra"},
    "nl": {"lol", "ok", "oké", "okay", "wifi", "selfie", "emoji", "doei", "hoi", "ff", "idd", "wss", "lekkerrr"},
    "pl": {"lol", "ok", "okej", "wifi", "selfie", "emoji", "xd", "nwm", "jj", "spoko", "elo", "siema", "pzdr", "dzk", "dzięki"},
    "ro": {"lol", "ok", "okay", "wifi", "selfie", "emoji", "pa", "pupici", "mersi", "msg", "nush", "bn", "pls"},
    "cs": {"lol", "ok", "okay", "wifi", "selfie", "emoji", "jj", "nj", "čau", "ahoj", "dík", "díky", "pls", "xd"},
    "hu": {"lol", "ok", "oké", "okés", "wifi", "selfie", "emoji", "szia", "sziasztok", "pls", "köszi", "xd", "puszi"},
    "sv": {"lol", "ok", "okej", "wifi", "selfie", "emoji", "hej", "tjena", "mvh", "typ", "asså", "nån", "nåt"},
    "da": {"lol", "ok", "okay", "wifi", "selfie", "emoji", "hej", "mvh", "vh", "fedt", "nå", "nej"},
    "nb": {"lol", "ok", "okei", "wifi", "selfie", "emoji", "hei", "mvh", "nei", "ikke", "kos", "digg"},
}
# Un mot employé au moins ce nombre de fois dans les phrases Tatoeba de la langue est en usage : jamais retiré (« tofu » en espagnol).
TATOEBA_EVIDENCE = 3


def read_aosp(paths):
    """Mots AOSP -> (note f, ajoutable). Lignes « word=x,f=n,flags=...,possibly_offensive=true ». Les raccourcis sont ignorés."""
    words = {}
    for path in paths:
        for line in Path(path).read_text(encoding="utf-8").splitlines():
            if not line.startswith(" word="):
                continue
            fields = dict(part.split("=", 1) for part in line.strip().split(",") if "=" in part)
            word = fields.get("word", "")
            f = fields.get("f", "0")
            f = 255 if f == "whitelist" else int(f) if f.isdigit() else 0
            addable = not (fields.get("possibly_offensive") == "true" or fields.get("not_a_word") == "true" or "abbreviation" in fields.get("flags", ""))
            if word not in words or f > words[word][0]:
                words[word] = (f, addable and words.get(word, (0, True))[1])
    return words


def hunspell_rejected(tokens, dic):
    """Morceaux de [tokens] (sans apostrophe ni trait d'union) que le dictionnaire [dic] refuse : mode -l, sans suggestions (rapide)."""
    text = "".join(t + "\n" for t in tokens)
    out = subprocess.run(["hunspell", "-i", "utf-8", "-l", "-d", dic], input=text, capture_output=True, text=True).stdout
    return set(out.split())


def hunspell_unknown(words, dictionaries):
    """Mots de [words] qu'AUCUN des dictionnaires Hunspell n'accepte (ni tels quels, ni avec une majuscule initiale). Un mot à
    apostrophe ou trait d'union est accepté si chacun de ses morceaux l'est (« dell'acqua », « est-ce »)."""
    parts = {w: [p for p in re.split(r"['\-]", w) if p] for w in words}
    unknown = set(words)
    for dic in dictionaries:
        tokens = sorted({p for w in unknown for p in parts[w]})
        rejected = hunspell_rejected(tokens, dic)
        rejected_capitalized = hunspell_rejected([t[:1].upper() + t[1:] for t in tokens if t in rejected], dic)
        still = {t for t in rejected if (t[:1].upper() + t[1:]) in rejected_capitalized}
        unknown = {w for w in unknown if any(p in still for p in parts[w])}
    return unknown


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--lang", required=True)
    parser.add_argument("--aosp", nargs="+", required=True)
    parser.add_argument("--hunspell", nargs="+", required=True, help="chemins sans extension (…/it_IT)")
    parser.add_argument("--english-aosp", nargs="*", default=[], help="listes AOSP anglaises (mots anglais à retirer des autres langues)")
    parser.add_argument("--aosp-validate", nargs="*", default=[], help="listes AOSP qui servent seulement à valider (licence non vérifiée)")
    parser.add_argument("--min-add-f", type=int, default=MIN_ADD_F, help="note AOSP minimale d'un mot ajouté")
    parser.add_argument("--tatoeba", help="phrases Tatoeba de la langue (<iso3>_sentences.tsv.bz2) : preuve d'usage")
    parser.add_argument("--root", default=".")
    parser.add_argument("--report-dir", required=True)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    root = Path(args.root)
    lang = args.lang
    path = root / "dictionaries" / f"{lang}.txt"
    report = Path(args.report_dir)
    report.mkdir(parents=True, exist_ok=True)

    entries = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        word, _, frequency = line.partition(" ")
        entries[word] = int(frequency)
    names_path = root / "dictionaries" / "names" / f"{lang}.txt"
    names = set()
    if names_path.is_file():
        names = {l.strip().lower() for l in names_path.read_text(encoding="utf-8").splitlines() if l.strip() and not l.startswith("#")}

    aosp_raw = read_aosp(args.aosp)
    validate_only = {w if lang == "de" else w for w in read_aosp(args.aosp_validate)} if args.aosp_validate else set()
    aosp = {}
    for word, (f, addable) in aosp_raw.items():
        if lang == "de":
            key = word.lower()
        elif word != word.lower():
            continue  # nom propre
        else:
            key = word
        if key not in aosp or f > aosp[key][0]:
            aosp[key] = (f, addable and WORD_RE.match(key) is not None)

    # Mots que les règles de contexte remplacent toujours (« aswell » -> « as well ») : à garder, sinon le dictionnaire les « corrige »
    # d'abord (« swell ») et la règle ne les voit jamais.
    context_forms = set()
    for kt in (root / "core" / "src" / "main" / "kotlin" / "fr" / "junade" / "taipo" / "dictionary").glob("*Confusions.kt"):
        for match in re.finditer(r'ConfusionRule\("([^"]+)",[^\n]*always = true', kt.read_text(encoding="utf-8")):
            context_forms.add(match.group(1))

    tatoeba = {}
    if args.tatoeba:
        token_re = re.compile(r"'?[^\W\d_]+(?:['\-][^\W\d_]+)*'?")
        with bz2.open(args.tatoeba, "rt", encoding="utf-8") as f:
            for line in f:
                parts = line.rstrip("\n").split("\t")
                if len(parts) >= 3:
                    for token in token_re.findall(parts[2].replace("’", "'").lower()):
                        tatoeba[token] = tatoeba.get(token, 0) + 1

    valid = {w for w, n in entries.items() if n >= MIN_FREQ}
    not_in_aosp = [w for w in valid if w not in aosp and w not in validate_only]
    unknown = hunspell_unknown(not_in_aosp, args.hunspell)
    known = lambda w: w in aosp or w in validate_only or (w in valid and w not in unknown)

    def protected(word):
        if word in names or word in PROTECTED.get(lang, set()) or word in CHAT.get(lang, set()) or word in context_forms:
            return True
        if tatoeba.get(word, 0) >= TATOEBA_EVIDENCE:
            return True
        # Allemand : impératif ou première personne familière sans « e » (« lern », « möcht », « schnall ») d'un mot connu.
        if lang == "de" and known(word + "e"):
            return True
        if "'" in word and known(word.split("'")[-1]):
            return True
        for suffix in ("s", "x", "'s"):
            if word.endswith(suffix) and known(word[: -len(suffix)]):
                return True
        links = COMPOUND_LANGS.get(lang)
        if links:
            for i in range(4, len(word) - 3):
                for link in links:
                    head, tail = word[:i], word[i:]
                    if link and not tail.startswith(link):
                        continue
                    tail = tail[len(link):]
                    if len(tail) >= 4 and known(head) and known(tail):
                        return True
        return False

    # Seuls trois cas sûrs sont retirés (un mot inconnu des deux sources peut être un vrai mot : prénom, argot, diminutif, impératif
    # familier) : faute d'accent, faute de frappe d'un mot bien plus fréquent, mot anglais dans une autre langue.
    def fold(word):
        return "".join(c for c in unicodedata.normalize("NFD", word) if unicodedata.category(c) != "Mn")

    known_by_fold = {}
    for word in set(aosp) | {w for w in valid if w not in unknown}:
        known_by_fold.setdefault(fold(word), set()).add(word)
    alphabet = sorted({c for w in valid for c in w if c.isalpha()})

    def neighbors(word):
        for i in range(len(word) + 1):
            if i < len(word):
                yield word[:i] + word[i + 1:]
                for c in alphabet:
                    yield word[:i] + c + word[i + 1:]
            if i + 1 < len(word):
                yield word[:i] + word[i + 1] + word[i] + word[i + 2:]
            for c in alphabet:
                yield word[:i] + c + word[i:]

    english = set()
    if args.english_aosp:
        english = {w for w in read_aosp(args.english_aosp) if w == w.lower()}  # les noms propres (majuscule) n'en font pas partie

    reasons = {}
    for word in unknown:
        if entries[word] >= KEEP_FREQ or protected(word):
            continue
        # Faute d'accent : la forme connue a plus d'accents (« plutot » -> « plutôt »), ou autant mais elle est bien plus fréquente
        # (« probléme » -> « problème »). Une variante plus accentuée et juste (« apanhámo-lo », portugais du Portugal) reste.
        marks = lambda w: sum(1 for a, b in zip(w, fold(w)) if a != b)
        accented = [
            k for k in known_by_fold.get(fold(word), set()) - {word}
            if marks(k) > marks(word) or (marks(k) == marks(word) and entries.get(k, 0) >= 10 * entries[word])
        ]
        if accented:
            reasons[word] = "accent:" + max(accented, key=lambda w: entries.get(w, 0))
            continue
        frequency = entries[word]
        typo_of = [n for n in set(neighbors(word)) if n != word and known(n) and entries.get(n, 0) >= TYPO_DOMINANCE * frequency]
        if typo_of:
            reasons[word] = "faute:" + max(typo_of, key=lambda w: entries[w])
            continue
        if lang != "en" and word in english:
            reasons[word] = "anglais"
    removed = sorted(reasons, key=lambda w: -entries[w])

    # Fréquence de la liste selon la note AOSP f (médiane des mots communs, valides).
    by_f = {}
    for word, (f, _) in aosp.items():
        if entries.get(word, 0) >= MIN_FREQ:
            by_f.setdefault(f, []).append(entries[word])
    medians = {f: statistics.median(v) for f, v in by_f.items() if len(v) >= 20}
    cap = ADD_CAP_SHARE * CANDIDATE_MIN[lang]

    def frequency_for(f):
        nearest = min(medians, key=lambda g: abs(g - f))
        return int(max(MIN_FREQ * 4, min(cap, medians[nearest])))

    added = []
    for word, (f, addable) in aosp.items():
        if not addable or entries.get(word, 0) >= MIN_FREQ or f < args.min_add_f:
            continue
        if len(word) < 2 or not (word[0].isalpha() and word[-1].isalpha()):
            continue  # une lettre isolée n'est un mot que dans une courte liste par langue (DictionaryAssetsIntegrityTest)
        added.append((word, frequency_for(f) if addable else 20, f))
    added.sort(key=lambda item: (-item[1], item[0]))

    (report / "removed.txt").write_text("".join(f"{w} {entries[w]} {reasons[w]}\n" for w in removed), encoding="utf-8")
    (report / "added.txt").write_text("".join(f"{w} {n} (f={f})\n" for w, n, f in added), encoding="utf-8")
    proposable_added = sum(1 for _, n, _ in added if n >= CANDIDATE_MIN[lang])
    summary = (
        f"{lang}: {len(entries)} lignes, {len(valid)} valides ; inconnus des deux sources : {len(unknown)} ; "
        f"retirés : {len(removed)} ; ajoutés : {len(added)} (dont {proposable_added} proposables)"
    )
    (report / "summary.txt").write_text(summary + "\n", encoding="utf-8")
    print(summary)

    if args.apply:
        for word in removed:
            del entries[word]
        for word, frequency, _ in added:
            entries[word] = frequency
        lines = sorted(entries.items(), key=lambda item: (-item[1], item[0]))
        path.write_text("".join(f"{w} {n}\n" for w, n in lines), encoding="utf-8", newline="\n")


if __name__ == "__main__":
    main()

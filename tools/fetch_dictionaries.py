#!/usr/bin/env python3
"""Génère les dictionnaires des langues du clavier autres que le français (en, es, de, it, pt, nl).

Usage (depuis la racine du projet) :
    python3 tools/fetch_dictionaries.py --from=DOSSIER   # lit DOSSIER/<code>_full.txt (pas de réseau)
    python3 tools/fetch_dictionaries.py                  # télécharge <code>_full.txt (accès réseau requis)
    python3 tools/fetch_dictionaries.py es de            # certaines langues seulement

Source : listes de fréquence FrequencyWords (hermitdave/FrequencyWords, contenu 2018, corpus OpenSubtitles2018),
licence CC-BY-SA 4.0 (attribution reprise dans dictionaries/SOURCES.txt). Le fichier
`<code>_full.txt` (une ligne « mot fréquence ») est téléchargé depuis GitHub (miroir Hugging Face en secours) ou lu
dans le dossier donné par --from, puis nettoyé et écrit sous dictionaries/ (racine du projet ; à envoyer ensuite sur le bucket R2, voir docs/dictionnaires-distants.md) :
  <code>.txt      liste COMPLÈTE nettoyée (plus de liste « _50k » : le repli mémoire de DictionaryLoader relit cette liste
                  en ne gardant que les mots de fréquence >= candidateMinFrequency, ~50 000 mots).

Nettoyage : minuscules, forme NFC ; seuls les mots faits des lettres de l'alphabet de la langue sont gardés (avec au
plus 3 apostrophes ou traits d'union au milieu) : les débris d'encodage et les caractères d'autres alphabets
sont retirés ; lettres isolées retirées sauf les vrais mots ; mots vus moins de MIN_FREQUENCY fois retirés
(fautes de frappe du corpus, jamais utilisés par le chargeur).
Anglais : le corpus sépare les contractions (don + 't) ; les contractions écrites à la main de l'ancienne liste
(it's, don't...) sont reprises depuis l'en.txt existant (lu avant d'être réécrit), et leurs formes sans apostrophe qui ne sont pas des mots
(dont, im, thats, ive...) sont retirées, comme pour le français.
"""
import re
import sys
import unicodedata
import urllib.request
from pathlib import Path

MIN_FREQUENCY = 5
# Rang du mot dont la fréquence sert de seuil de repli (DictionaryLoader.candidateMinFrequency) : à reporter dans le chargeur.
THRESHOLD_RANK = 50_000

# Lettres isolées qui sont de vrais mots.
SINGLE_LETTER_WORDS = {
    "en": set("ai"),
    "es": set("aeouy"),
    "de": set("aio"),
    "it": set("aeiouè"),
    "pt": set("aeoéà"),
    "nl": set("eu"),
    # Langues ajoutées le 11/11/2026.
    "pl": set("aiouwz"),
    "ro": set("aeio"),
    "cs": set("aikosuvz"),
    "hu": set("as"),
    "sv": set("iåö"),
    "da": set("iåø"),
    "nb": set("iåø"),
    "fi": set(),
}

# Alphabet de chaque langue (minuscules) : tout autre caractère fait rejeter le mot.
ALPHABETS = {
    "en": "abcdefghijklmnopqrstuvwxyzéèêñç",  # fiancé, café, señor, résumé...
    "es": "abcdefghijklmnopqrstuvwxyzáéíóúüñ",
    "de": "abcdefghijklmnopqrstuvwxyzäöüß",
    "it": "abcdefghijklmnopqrstuvwxyzàèéìíîòóùú",
    "pt": "abcdefghijklmnopqrstuvwxyzáàâãçéêíóôõú",
    "nl": "abcdefghijklmnopqrstuvwxyzáäçéèêëíïóöúüû",
    "pl": "abcdefghijklmnopqrstuvwxyząćęłńóśźż",
    "ro": "abcdefghijklmnopqrstuvwxyzăâîșț",
    "cs": "abcdefghijklmnopqrstuvwxyzáčďéěíňóřšťúůýž",
    "hu": "abcdefghijklmnopqrstuvwxyzáéíóöőúüű",
    "sv": "abcdefghijklmnopqrstuvwxyzåäöé",
    "da": "abcdefghijklmnopqrstuvwxyzæøåé",
    "nb": "abcdefghijklmnopqrstuvwxyzæøåéèêóòô",
    "fi": "abcdefghijklmnopqrstuvwxyzäöåšž",
}

# Code de la liste source quand il diffère de celui du clavier : FrequencyWords nomme « no » le norvégien bokmål.
SOURCE_CODES = {"nb": "no"}

# Lettres réécrites avant le contrôle de l'alphabet : roumain, s et t à cédille (anciens encodages) -> virgule souscrite ;
# hongrois, õ et û (encodage Latin-1 de ő et ű) -> ő, ű.
LETTER_FIXES = {
    "ro": str.maketrans({"ş": "ș", "ţ": "ț"}),
    "hu": str.maketrans({"õ": "ő", "û": "ű", "ô": "ő"}),
}

# Anglais : formes sans apostrophe qui ne sont pas des mots (fautes du corpus, corrigées vers la contraction).
ENGLISH_NON_WORDS = set(
    "dont im thats ive youre theyre hes shes whats wasnt didnt doesnt isnt couldnt wouldnt shouldnt havent hasnt "
    "hadnt arent werent aint theyll youll theyve youve weve wheres theres heres whos hed".split()
)

URLS = [
    "https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/{code}/{code}_full.txt",
    "https://huggingface.co/datasets/StephanAkkerman/frequency-words-2018/resolve/main/{code}/{code}_full.txt",
]

# Lettres, avec jusqu'à 3 apostrophes / traits d'union au milieu (« brother-in-law », « rock'n'roll »).
WORD = re.compile(r"^[^\W\d_]+(?:['’\-][^\W\d_]+){0,3}$")

# Débris du corpus propres à une langue : « e'o », « e'ato »... (italien : « è » mal saisi).
REJECTED_PREFIXES = {"it": ("e'",)}
OUTPUT_DIR = Path(__file__).resolve().parent.parent / "dictionaries"


def read_source(code: str, source_dir: Path | None) -> str:
    code = SOURCE_CODES.get(code, code)
    if source_dir is not None:
        return (source_dir / f"{code}_full.txt").read_text(encoding="utf-8")
    last_error = None
    for template in URLS:
        url = template.format(code=code)
        try:
            with urllib.request.urlopen(url, timeout=120) as response:
                return response.read().decode("utf-8")
        except Exception as error:  # noqa: BLE001 - on essaie la source suivante
            last_error = error
            print(f"  {url} : {error}", file=sys.stderr)
    raise SystemExit(f"Impossible de télécharger la liste « {code}_full » : {last_error}")


def clean(code: str, raw: str) -> dict[str, int]:
    singles = SINGLE_LETTER_WORDS[code]
    allowed = set(ALPHABETS[code]) | {"'", "-"}
    merged: dict[str, int] = {}
    for line in raw.splitlines():
        parts = line.strip().split()
        if len(parts) != 2 or not parts[1].isdigit():
            continue
        word = unicodedata.normalize("NFC", parts[0].lower().replace("’", "'"))
        if code in LETTER_FIXES:
            word = word.translate(LETTER_FIXES[code])
        count = int(parts[1])
        if count < MIN_FREQUENCY or not WORD.match(word) or not set(word) <= allowed:
            continue
        if len(word) == 1 and word not in singles:
            continue
        if word.startswith(REJECTED_PREFIXES.get(code, ())):
            continue
        merged[word] = merged.get(word, 0) + count
    return merged


def english_contractions() -> dict[str, int]:
    """Contractions écrites à la main (avec apostrophe) de l'ancienne liste, absentes du corpus."""
    previous = OUTPUT_DIR / "en.txt"
    result: dict[str, int] = {}
    for line in previous.read_text(encoding="utf-8").splitlines():
        word, _, count = line.partition(" ")
        if "'" in word and count.isdigit():
            result[word] = int(count)
    return result


def render(words: dict[str, int]) -> list[str]:
    ordered = sorted(words.items(), key=lambda item: (-item[1], item[0]))
    return [f"{word} {count}" for word, count in ordered]


def main(args: list[str]) -> None:
    source_dir = None
    codes = []
    for arg in args:
        if arg.startswith("--from="):
            source_dir = Path(arg.removeprefix("--from=")).expanduser()
        else:
            codes.append(arg)
    codes = codes or list(SINGLE_LETTER_WORDS)  # le français a son propre nettoyage (voir SOURCES.txt)
    unknown = [code for code in codes if code not in SINGLE_LETTER_WORDS]
    if unknown:
        raise SystemExit(f"Langues non gérées : {', '.join(unknown)} (attendu : {', '.join(SINGLE_LETTER_WORDS)})")
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    for code in codes:
        print(f"{code} : lecture / nettoyage...")
        words = clean(code, read_source(code, source_dir))
        if code == "en":
            for contraction, count in english_contractions().items():
                words[contraction] = max(words.get(contraction, 0), count)
            for non_word in ENGLISH_NON_WORDS:
                words.pop(non_word, None)
        lines = render(words)
        (OUTPUT_DIR / f"{code}.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
        print(f"{code} : {len(lines)} mots écrits ; fréquence du {THRESHOLD_RANK}e mot = "
              f"{lines[THRESHOLD_RANK - 1].split()[1] if len(lines) >= THRESHOLD_RANK else 'n/a'} "
              f"(seuil de repli de DictionaryLoader.candidateMinFrequency)")


if __name__ == "__main__":
    main(sys.argv[1:])

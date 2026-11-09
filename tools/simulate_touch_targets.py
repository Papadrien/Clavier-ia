#!/usr/bin/env python3
"""Zones de frappe adaptatives, étape 3 : simulation chiffrée.

Miroir en Python de NextLetterModel.kt (core) et de TouchTargets.kt (app), parité vérifiée à l'étape 1 (brute-force) et à
l'étape 2 (tests). Compare le taux d'erreur de touche avec les zones nominales et avec les zones adaptatives, sur de vrais
mots du dictionnaire, avec des frappes bruitées (gaussienne horizontale autour du centre de la touche visée).

Hypothèses (voir docs/zones-de-frappe-simulation.md) : AZERTY français, bruit horizontal seul (la rangée est toujours
la bonne), sans biais systématique, sans autocorrection ; le préfixe donné au modèle est ce qui a VRAIMENT été tapé (une
erreur fausse donc la prédiction suivante, comme sur le téléphone).

Usage : python3 tools/simulate_touch_targets.py [--sigma 0.25] [--core 0.6] [--reg 0.05] [--exp 1.0] [--min-prefix 0] [--min-words 1]
"""
import argparse
import bisect
import random
import unicodedata
from functools import lru_cache
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CANDIDATE_MIN_FREQ = 100   # DictionaryLoader : fr
VALID_MIN_FREQ = 5         # DictionaryLoader : MIN_FREQUENCY

# --- Disposition AZERTY (KeyboardLayout.kt) : (lettre ou None = touche fixe, largeur)
ROWS = [
    [(c, 1.0) for c in "azertyuiop"],
    [(c, 1.0) for c in "qsdfghjklm"],
    [(None, 1.4)] + [(c, 1.0) for c in "wxcvbn"] + [("'", 1.0), (None, 1.6)],
]
POSITION = {}  # lettre -> (rangée, indice, centre en unités depuis le début de la rangée)
for r, row in enumerate(ROWS):
    x = 0.0
    for i, (char, w) in enumerate(row):
        if char is not None:
            POSITION[char] = (r, i, x + w / 2)
        x += w


def fold(text):
    text = text.lower().replace("\u2019", "'")
    if all(ord(c) < 128 for c in text):
        return text
    text = text.replace("œ", "oe").replace("æ", "ae")
    return "".join(c for c in unicodedata.normalize("NFD", text) if unicodedata.category(c) != "Mn")


def read_list(name):
    entries = {}
    for line in open(ROOT / "dictionaries" / name, encoding="utf-8"):
        parts = line.rstrip("\n").rsplit(" ", 1)
        if len(parts) == 2 and parts[1].isdigit():
            entries[parts[0]] = int(parts[1])
    return entries


class NextLetterModel:
    """Miroir de NextLetterModel.kt."""

    def __init__(self, entries, exponent=1.0):
        folded = [(fold(w), max(f, 1)) for w, f in entries.items()]
        folded.sort(key=lambda t: t[0])
        self.words = [w for w, _ in folded]
        self.cum = [0.0]
        for _, f in folded:
            self.cum.append(self.cum[-1] + (f if exponent == 1.0 else f ** exponent))
        self._cache = {}

    def after(self, prefix):
        """-> (dict char -> proba, proba fin de mot, nombre de mots) ou None."""
        hit = self._cache.get(prefix, 0)
        if hit != 0:
            return hit
        p, S, cum = fold(prefix), self.words, self.cum
        lo = bisect.bisect_left(S, p)
        l, h = lo, len(S)
        while l < h:
            m = (l + h) // 2
            if S[m].startswith(p):
                l = m + 1
            else:
                h = m
        hi = l
        if lo == hi:
            self._cache[prefix] = None
            return None
        total = cum[hi] - cum[lo]
        cur, end = lo, 0.0
        while cur < hi and len(S[cur]) == len(p):
            end += cum[cur + 1] - cum[cur]
            cur += 1
        nxt = {}
        while cur < hi:
            c = S[cur][len(p)]
            l, h = cur + 1, hi
            while l < h:
                m = (l + h) // 2
                if S[m][len(p)] == c:
                    l = m + 1
                else:
                    h = m
            nxt[c] = (cum[l] - cum[cur]) / total
            cur = l
        result = (nxt, end / total, hi - lo)
        self._cache[prefix] = result
        return result


def edges(widths, probs, core, reg, strength=1.0):
    """Miroir de TouchTargets.edges."""
    nominal = [0.0]
    for w in widths:
        nominal.append(nominal[-1] + w)
    out = nominal[:]
    margin = (1 - core) / 2
    for i in range(len(widths) - 1):
        left, right = probs[i], probs[i + 1]
        if left is None or right is None:
            continue
        lean = (left - right) / (left + right + reg) * strength
        b = nominal[i + 1]
        lo, hi = b - margin * widths[i], b + margin * widths[i + 1]
        out[i + 1] = b + lean * (hi - b) if lean >= 0 else b + lean * (b - lo)
    return out


def index_at(e, x):
    for i in range(1, len(e)):
        if x < e[i]:
            return i - 1
    return len(e) - 2


NOMINAL = [edges([w for _, w in row], [None] * len(row), 0.6, 0.05) for row in ROWS]


class Config:
    def __init__(self, core=0.6, reg=0.05, exp=1.0, min_prefix=0, min_words=1, strength=1.0):
        self.core, self.reg, self.exp = core, reg, exp
        self.min_prefix, self.min_words, self.strength = min_prefix, min_words, strength

    def label(self):
        return f"core={self.core} reg={self.reg} exp={self.exp} minPrefix={self.min_prefix} minWords={self.min_words}"


class Simulator:
    def __init__(self, models):
        self.models = models  # exposant -> NextLetterModel
        self._edges = {}

    def row_edges(self, cfg, prefix, row):
        """Bords adaptatifs de la rangée ; nominal si pas de prédiction."""
        if len(prefix) < cfg.min_prefix:
            return NOMINAL[row]
        key = (cfg.core, cfg.reg, cfg.exp, cfg.min_words, cfg.strength, prefix, row)
        hit = self._edges.get(key)
        if hit is not None:
            return hit
        prediction = self.models[cfg.exp].after(prefix)
        if prediction is None or prediction[2] < cfg.min_words:
            result = NOMINAL[row]
        else:
            nxt = prediction[0]
            probs = [None if c is None else nxt.get(c, 0.0) for c, _ in ROWS[row]]
            result = edges([w for _, w in ROWS[row]], probs, cfg.core, cfg.reg, cfg.strength)
        self._edges[key] = result
        return result

    def type_word(self, word, noise, sigma, cfg):
        """Tape [word] (lettres repliées) avec ces tirages. -> liste de (position dans le mot, erreur ?)."""
        prefix, results = "", []
        for pos, ch in enumerate(word):
            row, idx, center = POSITION[ch]
            x = center + sigma * noise[pos]
            e = self.row_edges(cfg, prefix, row) if cfg is not None else NOMINAL[row]
            landed = index_at(e, x)
            results.append((pos, landed != idx))
            landed_char = ROWS[row][landed][0]
            if landed_char is not None:
                prefix += landed_char
        return results


def typable(word):
    f = fold(word)
    return f if f and all(c in POSITION for c in f) else None


def sample_scenarios(n_text, n_rare, n_foreign, seed):
    rng = random.Random(seed)
    fr = read_list("fr.txt")
    en = read_list("en.txt")
    valid = {w: f for w, f in fr.items() if f >= VALID_MIN_FREQ and typable(w)}
    candidates = {w: f for w, f in valid.items() if f >= CANDIDATE_MIN_FREQ}
    words = list(valid)
    text = [typable(w) for w in rng.choices(words, weights=[valid[w] for w in words], k=n_text)]
    rare_pool = [w for w, f in valid.items() if f < CANDIDATE_MIN_FREQ]
    rare = [typable(w) for w in rng.sample(rare_pool, n_rare)]
    foreign_pool = [w for w, f in en.items() if f >= 100 and len(w) >= 4 and w not in fr and typable(w)]
    foreign = [typable(w) for w in rng.sample(foreign_pool, min(n_foreign, len(foreign_pool)))]
    return {"texte courant": text, "mot rare (valide, hors modèle)": rare, "hors dictionnaire (anglais)": foreign}, candidates


def draw_noise(scenarios, seed):
    rng = random.Random(seed + 1)
    return {name: [[rng.gauss(0, 1) for _ in w] for w in words] for name, words in scenarios.items()}


def evaluate(sim, scenarios, noise, sigma, cfg):
    """-> {scénario: stats}. Mêmes tirages pour le nominal et l'adaptatif (comparaison appariée)."""
    out = {}
    for name, words in scenarios.items():
        taps = err_nom = err_ada = fixed = introduced = w_nom = w_ada = 0
        by_pos = {}
        for word, z in zip(words, noise[name]):
            nom = sim.type_word(word, z, sigma, None)
            ada = sim.type_word(word, z, sigma, cfg)
            taps += len(word)
            en_, ea_ = sum(e for _, e in nom), sum(e for _, e in ada)
            err_nom += en_
            err_ada += ea_
            w_nom += en_ > 0
            w_ada += ea_ > 0
            for (pos, a), (_, b) in zip(nom, ada):
                fixed += a and not b
                introduced += b and not a
                s = by_pos.setdefault(min(pos, 5), [0, 0, 0])
                s[0] += 1
                s[1] += a
                s[2] += b
        out[name] = dict(taps=taps, words=len(words), err_nom=err_nom, err_ada=err_ada, fixed=fixed,
                         introduced=introduced, w_nom=w_nom, w_ada=w_ada, by_pos=by_pos)
    return out


def build(seed=1, n_text=20000, n_rare=10000, n_foreign=8000):
    scenarios, candidates = sample_scenarios(n_text, n_rare, n_foreign, seed)
    sim = Simulator({1.0: NextLetterModel(candidates, 1.0), 0.5: NextLetterModel(candidates, 0.5)})
    return sim, scenarios, draw_noise(scenarios, seed)


def pct(a, b):
    return 100.0 * a / b if b else 0.0


def report(result):
    for name, s in result.items():
        gain = 100.0 * (s["err_nom"] - s["err_ada"]) / s["err_nom"] if s["err_nom"] else 0.0
        print(f"  {name:34s} touches {pct(s['err_nom'], s['taps']):5.2f}% -> {pct(s['err_ada'], s['taps']):5.2f}%  "
              f"({gain:+5.1f}% d'erreurs)  mots {pct(s['w_nom'], s['words']):5.2f}% -> {pct(s['w_ada'], s['words']):5.2f}%  "
              f"corrigées {s['fixed']}  introduites {s['introduced']}")


TEXT, RARE, FOREIGN = "texte courant", "mot rare (valide, hors modèle)", "hors dictionnaire (anglais)"


def blended(result, key):
    """Taux d'erreur de touche d'un usage mixte pessimiste : 70 % de texte courant, 30 % de mots hors modèle."""
    rate = lambda name: pct(result[name][key], result[name]["taps"])
    return 0.7 * rate(TEXT) + 0.3 * (rate(RARE) + rate(FOREIGN)) / 2


def sweep(kind, seed):
    sim, scn, nz = build(seed)
    if kind == "sigma":
        print("Bruit (écart-type en largeurs de touche), réglages par défaut :")
        for sigma in (0.15, 0.20, 0.25, 0.30, 0.35, 0.40):
            r = evaluate(sim, scn, nz, sigma, Config())
            print(f"\n sigma={sigma}  usage mixte : {blended(r, 'err_nom'):.2f}% -> {blended(r, 'err_ada'):.2f}%")
            report(r)
    elif kind == "grid":
        rows = []
        for exp in (1.0, 0.5):
            for core in (0.5, 0.6, 0.7, 0.8):
                for reg in (0.02, 0.05, 0.1, 0.2):
                    cfg = Config(core, reg, exp)
                    per_sigma = [evaluate(sim, scn, nz, sg, cfg) for sg in (0.2, 0.25, 0.3)]
                    gain = [100 * (blended(r, "err_nom") - blended(r, "err_ada")) / blended(r, "err_nom") for r in per_sigma]
                    intro = sum(sum(s["introduced"] for s in r.values()) for r in per_sigma)
                    rows.append((sum(gain) / 3, gain, intro, cfg))
        rows.sort(key=lambda t: -t[0])
        print("Gain d'erreurs de touche en usage mixte, moyenne sur sigma 0,20 / 0,25 / 0,30 :")
        for mean, gain, intro, cfg in rows:
            print(f" {mean:5.1f}%  ({gain[0]:4.1f} / {gain[1]:4.1f} / {gain[2]:4.1f})  introduites={intro:6d}  {cfg.label()}")
    elif kind == "extras":
        base = dict(core=0.6, reg=0.05, exp=1.0)
        for label, kw in [("référence", {}), ("minPrefix=1", dict(min_prefix=1)), ("minPrefix=2", dict(min_prefix=2)),
                          ("minPrefix=3", dict(min_prefix=3)), ("minWords=2", dict(min_words=2)),
                          ("minWords=3", dict(min_words=3)), ("minWords=5", dict(min_words=5))]:
            cfg = Config(**base, **kw)
            r = evaluate(sim, scn, nz, 0.25, cfg)
            print(f" {label:12s} usage mixte {blended(r, 'err_nom'):.2f}% -> {blended(r, 'err_ada'):.2f}%   "
                  f"texte {pct(r[TEXT]['err_ada'], r[TEXT]['taps']):.2f}%  rare {pct(r[RARE]['err_ada'], r[RARE]['taps']):.2f}%  "
                  f"étranger {pct(r[FOREIGN]['err_ada'], r[FOREIGN]['taps']):.2f}%  introduites "
                  f"{sum(s['introduced'] for s in r.values())}")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--sigma", type=float, default=0.25)
    ap.add_argument("--core", type=float, default=0.6)
    ap.add_argument("--reg", type=float, default=0.05)
    ap.add_argument("--exp", type=float, default=1.0)
    ap.add_argument("--min-prefix", type=int, default=0)
    ap.add_argument("--min-words", type=int, default=1)
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--sweep", choices=["sigma", "grid", "extras"], help="balayage au lieu d'un seul calcul")
    args = ap.parse_args()
    if args.sweep:
        sweep(args.sweep, args.seed)
        raise SystemExit
    simulator, scn, nz = build(args.seed)
    cfg = Config(args.core, args.reg, args.exp, args.min_prefix, args.min_words)
    print(f"sigma={args.sigma} (largeurs de touche)  {cfg.label()}")
    report(evaluate(simulator, scn, nz, args.sigma, cfg))

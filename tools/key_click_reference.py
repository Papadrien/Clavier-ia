#!/usr/bin/env python3
"""Synthèse de référence du son des touches (maquettes « sec doux » I1 et I2, 19/10/2026).

Le port Kotlin (`KeyClickSynth.kt`) reproduit ce script à l'arrondi près ; les valeurs de référence de
`KeyClickSynthTest` viennent de `python3 tools/key_click_reference.py --check`.
Usage : python3 tools/key_click_reference.py [dossier_de_sortie]   (écrit key.wav et space.wav)
        python3 tools/key_click_reference.py --check               (affiche les valeurs de référence)
Dépend de numpy.
"""
import sys
import wave

import numpy as np

SR = 44100
BASE = {  # « sec doux » : 5 ms ; résonances (Hz, décroissance ms, gain), bruit, filtre final, saturation
    "len_ms": 5.0, "atk_ms": 0.4, "rel_ms": 1.8,
    "modes": [(180.0, 1.8, 0.5), (1500.0, 1.2, 0.5), (2900.0, 0.8, 0.25)],
    "noise": {"hp": 1200.0, "lp": 4500.0, "decay": 1.2, "gain": 0.6},
    "lp": 4800.0, "drive": 1.3,
}


def scaled(p, s):
    q = dict(p)
    q["modes"] = [(f * s, d, g) for f, d, g in p["modes"]]
    q["noise"] = dict(p["noise"], hp=p["noise"]["hp"] * s, lp=p["noise"]["lp"] * s)
    q["lp"] = p["lp"] * s
    return q


KEY = scaled(BASE, 0.7)     # I1 : lettres, chiffres, symboles
SPACE = scaled(BASE, 1.35)  # I2 : barre d'espace


def lcg(seed):
    s = seed & 0xFFFFFFFF
    while True:
        s = (s * 1664525 + 1013904223) & 0xFFFFFFFF
        yield (s / 0x80000000) - 1.0


def one_pole(x, fc, hp=False):
    a = np.exp(-2 * np.pi * fc / SR)
    y = np.zeros_like(x)
    z = 0.0
    for i, v in enumerate(x):
        z = (1 - a) * v + a * z
        y[i] = v - z if hp else z
    return y


def render(p, seed=1, var=0.03):
    n = int(SR * p["len_ms"] / 1000)
    t = np.arange(n) / SR
    r = lcg(seed)
    detune = 1 + var * next(r)
    gv = 1 + 0.08 * next(r)
    out = np.zeros(n)
    for f, d, g in p["modes"]:
        f *= detune
        out += g * np.exp(-t / (d / 1000)) * np.sin(2 * np.pi * np.cumsum(np.full(n, f)) / SR)
    nz = p["noise"]
    w = np.array([next(r) for _ in range(n)])
    w = one_pole(one_pole(w, nz["hp"], True), nz["lp"]) * np.exp(-t / (nz["decay"] / 1000)) * nz["gain"] * 3
    out += w
    atk = max(1, int(SR * p["atk_ms"] / 1000))
    out[:atk] *= np.linspace(0, 1, atk)
    rel = int(SR * p["rel_ms"] / 1000)
    out[-rel:] *= np.linspace(1, 0, rel)
    out = np.tanh(one_pole(out, p["lp"]) * p["drive"]) * gv
    return out / np.max(np.abs(out)) * 0.7


def save(path, x):
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes((x * 32767).astype("<i2").tobytes())


if __name__ == "__main__":
    if "--check" in sys.argv:
        for name, p in (("KEY", KEY), ("SPACE", SPACE)):
            q = np.round(render(p, 1) * 32767).astype(int)
            print(name, q[:6].tolist(), "sumAbs", int(np.abs(q).sum()), "max", int(q.max()), "min", int(q.min()))
    else:
        out_dir = sys.argv[1] if len(sys.argv) > 1 else "."
        save(f"{out_dir}/key.wav", render(KEY, 1))
        save(f"{out_dir}/space.wav", render(SPACE, 1))

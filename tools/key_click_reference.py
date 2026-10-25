#!/usr/bin/env python3
"""Synthèse de référence du son des touches (maquettes « sec doux » I1 et I2, 19/10/2026).

Le port Kotlin (`KeyClickSynth.kt`) reproduit ce script à l'arrondi près ; les valeurs de référence de
`KeyClickSynthTest` viennent de `python3 tools/key_click_reference.py --check`.
Usage : python3 tools/key_click_reference.py [dossier_de_sortie]   (écrit key.wav, space.wav, control.wav, backspace.wav, cursor_move.wav, bar_button.wav, ai_button.wav, voice_start.wav, voice_end.wav et transcription_*.wav)
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
CONTROL = scaled(BASE, 0.62)  # touches de commande : Maj, Effacer, Entrée, bascules, emoji


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


def render_eraser(seed=1, var=0.03):
    """Touche Effacer « gomme » (EraserClickSynth.kt) : glissando descendant doux de 14 ms (900 vers 260 Hz) + grain de bruit."""
    r = lcg(seed)
    det = 1 + var * next(r)
    gv = 1 + 0.08 * next(r)
    n = int(SR * 14.0 / 1000)
    t = np.arange(n) / SR
    f = (900.0 + (260.0 - 900.0) * (1 - np.exp(-t / 0.005))) * det
    ph = 2 * np.pi * np.cumsum(f) / SR
    body = np.exp(-t / 0.005) * (np.sin(ph) * 0.6 + np.sin(2.01 * ph) * 0.2)
    w = np.array([next(r) for _ in range(n)])
    w = one_pole(one_pole(w, 900.0, True), 3500.0) * np.exp(-t / 0.0015) * 1.2
    out = body + w
    atk = int(SR * 0.0006)
    out[:atk] *= np.linspace(0, 1, atk)
    rel = int(SR * 0.005)
    out[-rel:] *= np.linspace(1, 0, rel)
    out = np.tanh(one_pole(out, 4200.0) * 1.2) * gv
    return out / np.max(np.abs(out)) * 0.7


def render_cursor(seed=1, var=0.03):
    """Cran de curseur « goutte » (CursorClickSynth.kt, proposition D du 21/10/2026) : petite montée douce de 6 ms (450 vers 900 Hz)."""
    r = lcg(seed)
    det = 1 + var * next(r)
    n = int(SR * 6.0 / 1000)
    t = np.arange(n) / SR
    f = (450.0 + (900.0 - 450.0) * (1 - np.exp(-t / 0.002))) * det
    ph = 2 * np.pi * np.cumsum(f) / SR
    out = np.exp(-t / 0.002) * (np.sin(ph) * 0.7 + np.sin(2.0 * ph) * 0.1)
    atk = int(SR * 0.8 / 1000)
    out[:atk] *= np.linspace(0, 1, atk)
    rel = int(SR * 2.5 / 1000)
    out[-rel:] *= np.linspace(1, 0, rel)
    out = np.tanh(one_pole(out, 3500.0) * 1.1)
    return out / np.max(np.abs(out)) * 0.30


def render_clicks(clicks, len_ms, pitch, seed=1, var=0.03):
    """Suite de clics « interrupteur » (SwitchClickSynth.kt) : chaque clic = (début en ms, gain, facteur de hauteur)."""
    r = lcg(seed)
    f = pitch * (1 + var * next(r))
    n = int(SR * len_ms / 1000)
    x = np.zeros(n)

    def click(t0_ms, g, ff):
        i0 = int(SR * t0_ms / 1000)
        m = n - i0
        tt = np.arange(m) / SR
        w = np.array([next(r) for _ in range(m)])
        body = g * np.exp(-tt / 0.002) * np.sin(2 * np.pi * 1200 * ff * tt) \
            + g * 0.5 * np.exp(-tt / 0.0012) * np.sin(2 * np.pi * 2600 * ff * tt)
        x[i0:] += body + one_pole(w, 1500 * ff, True) * np.exp(-tt / 0.0012) * g * 0.5

    for t0, g, fac in clicks:
        click(t0, g, f * fac)
    atk = max(1, int(SR * 0.4 / 1000))
    x[:atk] *= np.linspace(0, 1, atk)
    rel = int(SR * 4.0 / 1000)
    x[-rel:] *= np.linspace(1, 0, rel)
    x = np.tanh(one_pole(x, 7000.0) * 1.2)
    return x / np.max(np.abs(x)) * 0.6


def render_switch(seed=1, var=0.03):
    """Boutons Paramètres, Smart Clipboard et Annuler : double clic de 32 ms, hauteur × 1,25 (BAR_BUTTON)."""
    return render_clicks([(0.0, 1.0, 1.0), (14.0, 0.6, 0.8)], 32.0, 1.25, seed, var)


def render_ai(seed=1, var=0.03):
    """Boutons IA (robot, Chat, Corriger, Transcription IA) : « triple montée » grave de 48 ms (AI_BUTTON)."""
    return render_clicks([(0.0, 1.0, 1.0), (11.0, 0.8, 1.2), (22.0, 0.65, 1.45)], 48.0, 1.0, seed, var)


def render_voice_start(seed=1, var=0.03):
    """Début de la saisie vocale (VOICE_START) : deux clics espacés de 70 ms, le second plus aigu."""
    return render_clicks([(0.0, 1.0, 1.0), (70.0, 0.85, 1.25)], 90.0, 1.25, seed, var)


def render_voice_end(seed=1, var=0.03):
    """Fin de la saisie vocale (VOICE_END) : deux clics espacés de 70 ms, le second plus grave et plus faible."""
    return render_clicks([(0.0, 1.0, 1.25), (70.0, 0.6, 0.8)], 90.0, 1.25, seed, var)


TRANSCRIPTION = {  # « rapprochés d'abord » : tic-tic (16 ms) puis tac (60 ms plus tard), hauteur × 1,0
    "TRANSCRIPTION_START": [(0.0, 1.0, 1.0), (16.0, 0.8, 1.15), (76.0, 0.9, 1.4)],
    "TRANSCRIPTION_LISTEN_END": [(0.0, 1.0, 1.3), (16.0, 0.7, 1.15), (76.0, 0.6, 0.8)],
    "TRANSCRIPTION_DONE": [(0.0, 1.0, 1.0), (16.0, 0.8, 1.2), (76.0, 0.95, 1.6)],
}


def render_transcription(name, seed=1, var=0.03):
    """Sons de la transcription IA : démarrage, fin d'écoute, fin de traitement (96 ms)."""
    return render_clicks(TRANSCRIPTION[name], 96.0, 1.0, seed, var)


def save(path, x):
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes((x * 32767).astype("<i2").tobytes())


if __name__ == "__main__":
    if "--check" in sys.argv:
        for name, p in (("KEY", KEY), ("SPACE", SPACE), ("CONTROL", CONTROL)):
            q = np.round(render(p, 1) * 32767).astype(int)
            print(name, q[:6].tolist(), "sumAbs", int(np.abs(q).sum()), "max", int(q.max()), "min", int(q.min()))
        q = np.round(render_eraser(1) * 32767).astype(int)
        print("BACKSPACE", len(q), q[:6].tolist(), "sumAbs", int(np.abs(q).sum()), "max", int(q.max()), "min", int(q.min()))
        q = np.round(render_cursor(1) * 32767).astype(int)
        print("CURSOR_MOVE", len(q), q[:6].tolist(), "sumAbs", int(np.abs(q).sum()), "max", int(q.max()), "min", int(q.min()))
        q = np.round(render_switch(1) * 32767).astype(int)
        print("BAR_BUTTON", len(q), q[:6].tolist(), "sumAbs", int(np.abs(q).sum()), "max", int(q.max()), "min", int(q.min()))
        q = np.round(render_ai(1) * 32767).astype(int)
        for name, fn in (("VOICE_START", render_voice_start), ("VOICE_END", render_voice_end)):
            v = np.round(fn(1) * 32767).astype(int)
            print(name, len(v), v[:6].tolist(), "sumAbs", int(np.abs(v).sum()), "max", int(v.max()), "min", int(v.min()))
        for name in TRANSCRIPTION:
            v = np.round(render_transcription(name, 1) * 32767).astype(int)
            print(name, len(v), v[:6].tolist(), "sumAbs", int(np.abs(v).sum()), "max", int(v.max()), "min", int(v.min()))
        print("AI_BUTTON", len(q), q[:6].tolist(), "sumAbs", int(np.abs(q).sum()), "max", int(q.max()), "min", int(q.min()))
    else:
        out_dir = sys.argv[1] if len(sys.argv) > 1 else "."
        save(f"{out_dir}/key.wav", render(KEY, 1))
        save(f"{out_dir}/space.wav", render(SPACE, 1))
        save(f"{out_dir}/control.wav", render(CONTROL, 1))
        save(f"{out_dir}/backspace.wav", render_eraser(1))
        save(f"{out_dir}/cursor_move.wav", render_cursor(1))
        save(f"{out_dir}/bar_button.wav", render_switch(1))
        save(f"{out_dir}/ai_button.wav", render_ai(1))
        save(f"{out_dir}/voice_start.wav", render_voice_start(1))
        save(f"{out_dir}/voice_end.wav", render_voice_end(1))
        for name in TRANSCRIPTION:
            save(f"{out_dir}/{name.lower()}.wav", render_transcription(name, 1))

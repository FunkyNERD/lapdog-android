#!/usr/bin/env python3
"""Generates the lap sounds in app/src/main/res/raw/ (lap_*.wav): synthesised here, so
there's no sample licence to worry about. Edit and re-run:

    python3 tools/gen-sounds.py

Needs numpy. 22.05 kHz, 16-bit mono, each peak-normalised to the same level.
"""
import os
import wave

import numpy as np

RATE = 22050
OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res", "raw")
rng = np.random.default_rng(7)  # fixed, so re-running gives the same files


def t(seconds):
    return np.arange(int(RATE * seconds)) / RATE


def env(n, attack, decay):
    """Linear attack, then exponential decay with time constant `decay` (seconds)."""
    x = np.arange(n) / RATE
    e = np.exp(-np.maximum(x - attack, 0) / decay)
    a = int(RATE * attack)
    if a:
        e[:a] *= np.linspace(0, 1, a)
    return e


def fade_out(s, seconds=0.01):
    n = int(RATE * seconds)
    s[-n:] *= np.linspace(1, 0, n)
    return s


def resonator(x, freq, bw):
    """Two-pole band-pass (a formant), unity gain at `freq`."""
    r = np.exp(-np.pi * bw / RATE)
    a1, a2 = -2 * r * np.cos(2 * np.pi * freq / RATE), r * r
    g = (1 - r * r) / 2
    y = np.zeros_like(x)
    y1 = y2 = x2 = 0.0
    for i, xi in enumerate(x):
        yi = g * (xi - x2) - a1 * y1 - a2 * y2
        x2 = x[i - 1] if i else 0.0
        y[i], y2, y1 = yi, y1, yi
    return y


def tone():
    # A clean 880 Hz beep with soft edges.
    x = t(0.18)
    s = np.sin(2 * np.pi * 880 * x) * env(len(x), 0.005, 1.0)
    return fade_out(s, 0.03)


def ding():
    # A bright struck note: 1320 Hz plus an octave, decaying fast.
    x = t(0.7)
    s = (np.sin(2 * np.pi * 1320 * x) + 0.35 * np.sin(2 * np.pi * 2640 * x) * np.exp(-x / 0.08))
    return fade_out(s * env(len(x), 0.002, 0.18))


def bell():
    # A small bell: inharmonic partials, the high ones dying first.
    x = t(1.4)
    f0 = 660
    s = np.zeros_like(x)
    for ratio, amp, decay in ((0.5, 0.35, 0.9), (1.0, 1.0, 0.6), (1.183, 0.5, 0.45), (1.506, 0.45, 0.35),
                              (2.0, 0.35, 0.25), (2.74, 0.25, 0.15), (3.76, 0.15, 0.1)):
        s += amp * np.sin(2 * np.pi * f0 * ratio * x) * np.exp(-x / decay)
    return fade_out(s * env(len(x), 0.002, 10))


def chirp():
    # A quick rising whistle, 1.2 -> 3.2 kHz.
    x = t(0.16)
    f = np.linspace(1200, 3200, len(x))
    s = np.sin(2 * np.pi * np.cumsum(f) / RATE)
    return fade_out(s * np.abs(np.sin(np.pi * x / x[-1])) ** 0.5)


def double_beep():
    # Two short beeps, like a stopwatch lap button.
    one = np.sin(2 * np.pi * 1760 * t(0.07)) * env(int(RATE * 0.07), 0.003, 1.0)
    one = fade_out(one, 0.01)
    return np.concatenate([one, np.zeros(int(RATE * 0.06)), one])


def bark():
    # One "woof": a buzzy voice whose pitch jumps up then falls, through dog-sized
    # formants, with breath noise and a hard onset.
    x = t(0.26)
    n = len(x)
    f0 = np.interp(x, [0, 0.03, 0.09, 0.26], [430, 620, 470, 300])
    f0 *= 1 + 0.03 * rng.standard_normal(n).cumsum() / np.sqrt(np.arange(1, n + 1))  # rough
    phase = np.cumsum(f0) / RATE
    pulses = 2 * (phase % 1) - 1  # sawtooth: rich in harmonics, like a voice
    src = pulses + 0.45 * rng.standard_normal(n)
    v = (resonator(src, 650, 220) * 1.0 + resonator(src, 1250, 300) * 0.8
         + resonator(src, 2400, 450) * 0.35 + resonator(src, 3600, 600) * 0.12)
    e = np.interp(x, [0, 0.012, 0.06, 0.16, 0.26], [0, 1, 0.85, 0.35, 0])
    s = v * e
    # A little low "thump" at the start gives it body.
    s += 0.6 * np.sin(2 * np.pi * 180 * x) * np.exp(-x / 0.03) * np.minimum(x / 0.004, 1)
    return fade_out(s, 0.02)


SOUNDS = {
    "lap_bark": bark,
    "lap_tone": tone,
    "lap_ding": ding,
    "lap_bell": bell,
    "lap_chirp": chirp,
    "lap_double_beep": double_beep,
}


def write(name, s):
    s = s / np.max(np.abs(s)) * 0.89  # about -1 dBFS
    data = (s * 32767).astype("<i2").tobytes()
    path = os.path.join(OUT, name + ".wav")
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(data)
    print(f"{path}: {len(s) / RATE:.2f} s")


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    for name, make in SOUNDS.items():
        write(name, make().astype(float))

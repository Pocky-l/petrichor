"""Builds the rain sounds of Petrichor from CC0 field recordings on Freesound.

Usage: python tools/prepare_sounds.py   (from the mod folder; needs numpy, scipy, soundfile)

The recordings (all Creative Commons 0, see SOURCES) are downloaded into tools/.cache (not committed). From each
one the steadiest stretches without events are cut, rumble below ~90 Hz is removed, the harshest highs are softened, and
each stretch is turned into a seamless loop with an equal-power crossfade. The loops are mono: the game places them in
the world where the rain actually falls (ground around you, canopies, the roof above), so they need to be point
sources. Each surface gets two loops cut from different parts of its recording, so neighbouring sources never play the
same thing in sync. Footsteps are cut out of a recording of someone walking through shallow water.
"""
import math
import os
import re
import urllib.request

import numpy as np
import soundfile as sf
from scipy import signal

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, ".cache")
OUT = os.path.join(HERE, "..", "src", "main", "resources", "assets", "petrichor", "sounds")
RATE = 44100

# Freesound id -> (author, title). All CC0.
SOURCES = {
    673958: ("felix.blume", "Rain in a village behind a hut with drops on soil ground"),
    160699: ("klangfabrik", "mediumrain"),
    870823: ("CHallSmith", "Rain, Heavy in Woods"),
    865323: ("newlocknew", "RAINVege_Forest. Drops On Grass, Bushes, Ferns And Leaves"),
    663381: ("richwise", "Rain on the roof"),
    861369: ("ChristopherJngs", "Splashing Footsteps Shallow Water"),
}


def fetch(url, binary=False):
    request = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    data = urllib.request.urlopen(request, timeout=120).read()
    return data if binary else data.decode("utf-8", "replace")


def source(sound_id):
    os.makedirs(CACHE, exist_ok=True)
    path = os.path.join(CACHE, f"{sound_id}.ogg")
    if not os.path.exists(path):
        page = fetch(f"https://freesound.org/s/{sound_id}/")
        if "publicdomain/zero" not in page:
            raise RuntimeError(f"Freesound {sound_id} is not CC0")
        preview = re.search(r"https://cdn.freesound.org/previews/\d+/\d+_\d+-hq\.mp3", page).group(0)
        with open(path, "wb") as f:
            f.write(fetch(preview[:-4] + ".ogg", True))
    x, rate = sf.read(path, always_2d=True)
    if x.shape[1] == 1:
        x = np.repeat(x, 2, axis=1)
    if rate != RATE:
        x = signal.resample_poly(x, RATE, rate, axis=0)
    return x[:, :2]


def filt(x, kind, cut, order=2):
    sos = signal.butter(order, cut, btype=kind, fs=RATE, output="sos")
    return signal.sosfiltfilt(sos, x, axis=0)


def soften(x, rumble_cut=90.0, air_cut=8000.0, air_cut_amount=0.35):
    x = filt(x, "high", rumble_cut)
    return x - filt(x, "high", air_cut) * air_cut_amount


def steadiest(x, seconds, avoid=None):
    """Start of the stretch of {seconds} whose loudness varies least (no thunder, cars or gusts), not overlapping {avoid}."""
    hop = RATE // 2
    frames = len(x) // hop
    db = np.array([10 * np.log10(np.mean(x[i * hop:(i + 1) * hop] ** 2) + 1e-12) for i in range(frames)])
    span = int(seconds * 2)
    best, best_score = 0, float("inf")
    for start in range(0, frames - span):
        if avoid is not None and abs(start * hop - avoid) < span * hop:
            continue
        window = db[start:start + span]
        score = np.std(window) + 0.5 * (window.max() - np.median(window))
        if score < best_score:
            best, best_score = start, score
    return best * hop


def make_loop(x, seconds, fade, start):
    n = int(seconds * RATE)
    f = int(fade * RATE)
    take = x[start:start + n + f]
    out = take[:n].copy()
    t = np.linspace(0, math.pi / 2, f)
    if take.ndim == 2:
        t = t[:, None]
    out[:f] = take[:f] * np.sin(t) + take[n:n + f] * np.cos(t)
    return out


def normalize(x, rms_db):
    x = x * (10 ** (rms_db / 20) / (np.sqrt(np.mean(x ** 2)) + 1e-12))
    peak = np.max(np.abs(x))
    return x * (0.95 / peak) if peak > 0.95 else x


def write(name, data):
    path = os.path.join(OUT, name + ".ogg")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    data = data.astype(np.float32)
    channels = 1 if data.ndim == 1 else data.shape[1]
    # libsndfile crashes on large single Vorbis writes; feed it in blocks.
    with sf.SoundFile(path, "w", RATE, channels, format="OGG", subtype="VORBIS") as out:
        for start in range(0, len(data), 8192):
            out.write(data[start:start + 8192])


def loops_from(sound_id, name, seconds, rms_db, **soften_args):
    """Two mono loops, {name}_a and {name}_b, from different stretches of the recording."""
    x = soften(source(sound_id), **soften_args).mean(axis=1)
    seconds = min(seconds, (len(x) / RATE - 8.0) / 2)
    first = steadiest(x, seconds + 3.0)
    second = steadiest(x, seconds + 3.0, avoid=first)
    for suffix, start in (("a", first), ("b", second)):
        write(f"{name}_{suffix}", normalize(make_loop(x, seconds, 3.0, start), rms_db))


def footsteps(sound_id, count):
    x = source(sound_id).mean(axis=1)
    x = filt(x, "high", 70.0)
    envelope = filt(np.abs(x), "low", 20.0)
    peaks, props = signal.find_peaks(envelope, distance=int(0.3 * RATE), prominence=envelope.max() * 0.15)
    # The clearest steps, in a natural loudness range.
    order = np.argsort(props["prominences"])[::-1][:count]
    for k, peak in enumerate(sorted(peaks[order])):
        start = max(0, peak - int(0.06 * RATE))
        step = x[start:start + int(0.45 * RATE)].copy()
        fade = np.ones(len(step))
        tail = int(0.12 * RATE)
        fade[-tail:] = np.linspace(1, 0, tail)
        fade[:int(0.004 * RATE)] = np.linspace(0, 1, int(0.004 * RATE))
        write(f"step/puddle{k + 1}", normalize(step * fade, -16))


def main():
    for old in os.listdir(os.path.join(OUT, "ambient")) if os.path.isdir(os.path.join(OUT, "ambient")) else []:
        os.remove(os.path.join(OUT, "ambient", old))
    loops_from(673958, "ambient/ground_light", 24, -20)
    loops_from(160699, "ambient/ground_medium", 24, -20)
    loops_from(870823, "ambient/ground_heavy", 24, -19)
    loops_from(865323, "ambient/leaves", 24, -20)
    loops_from(663381, "ambient/roof", 22, -20, rumble_cut=60.0, air_cut=5000.0, air_cut_amount=0.5)
    footsteps(861369, 6)


if __name__ == "__main__":
    main()

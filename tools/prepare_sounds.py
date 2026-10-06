"""Builds the sounds of Petrichor from CC0 field recordings on Freesound.

Usage: python tools/prepare_sounds.py   (from the mod folder; needs numpy, scipy, soundfile)

The recordings (all Creative Commons 0, see SOURCES) are downloaded into tools/.cache (not committed).

* Loops: from each recording the steadiest stretch without events is cut (optionally only inside a time window, so one
  recording that swells from light to heavy rain gives both a light and a heavy loop of the same surface), rumble is
  removed, the harshest highs are softened and the stretch becomes a seamless loop with an equal-power crossfade. Loops
  are mono: the game places them in the world (ground around you, a tin roof above, a window to the left), so they
  must be point sources.
* One-shots: single drops are found as isolated, clean onsets in recordings of dripping water; thunder is cut around
  the loudest event of a recording and given a natural fade.

Every file is normalized to a loudness that encodes how loud that sound is meant to be relative to the others; the
game only scales them by distance, rain intensity and the volume options.
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

# Freesound id -> (author, title). All CC0. Listed in the README credits.
SOURCES = {
    # Rain on the open ground, by intensity.
    768872: ("Gustavo_C", "soft_rain_outside"),
    160699: ("klangfabrik", "mediumrain"),
    870823: ("CHallSmith", "Rain, Heavy in Woods"),
    # Surfaces.
    865323: ("newlocknew", "RAINVege_Forest. Drops On Grass, Bushes, Ferns And Leaves"),
    454143: ("kyles", "rain medium on street pavement"),
    338109: ("SpliceSound", "Heavy rain, splatty on stone"),
    611421: ("cocaine", "Rain On Wood Deck (Burya rain library)"),
    717874: ("TRP", "Rain, on metallic tin roof, heavy med light"),
    242724: ("rucisko", "rooflight raindrops"),
    718977: ("clement.bernardeau", "Rain under a skylight"),
    869851: ("SignatureSoundsOrg", "Rain_Hitting_Window_9"),
    484723: ("Breviceps", "Rain on tent"),
    817155: ("craigsmith", "Rain on Tent"),
    572429: ("TRP", "Rain, on water lake, modulating light to med"),
    124168: ("alienistcog", "md10trk1 (rain falling into a puddle)"),
    # Roofs heard from below.
    521773: ("MrFossy", "Indoors_Shed_RainOnTinRoof_01"),
    771981: ("Sassaby", "Medium rain on a tin roof"),
    414162: ("felix.blume", "Rain in a barn"),
    451156: ("kyles", "rain medium on roof or large wood shed"),
    428603: ("Erbsland-Music", "Strong Rain on Roof from Inside of the Room"),
    # Wind.
    574556: ("TRP", "Wind gusting from window"),
    # Drops.
    843505: ("vfrattaroli", "eau qui coule - water dripping"),
    180948: ("jc144940", "water dripping from gutter to ground"),
    770443: ("SpinOpel", "Dripping water"),
    442480: ("BonnyOrbit", "Water dripping under bridge in small town"),
    628404: ("xkeril", "Water dripping after the rain"),
    249927: ("launemax", "gully with water drips"),
    683783: ("Elements-Library", "Water Dripping on Wood (off mic)"),
    683778: ("Elements-Library", "Water Dripping on Thin Metal"),
    577303: ("TRP", "Rain, on leaves, close up, popping, brittle"),
    715698: ("TRP", "Rain, light close drops on leaves"),
    # Thunder.
    717907: ("TRP", "Thunder, close crack crash big"),
    717909: ("TRP", "Thunder, close crack light rain"),
    567945: ("TRP", "Thunder, pretty close crack"),
    191992: ("pyer75", "thunder"),
    865693: ("Valerie-Vivegnis", "Spectacular thunder clap"),
    570351: ("JPBILLINGSLEYJR", "Loud Thunderclap"),
    197738: ("ragamuffin", "thunder-rain-middle-distance"),
    584946: ("richwise", "Distant rumbles"),
    581123: ("Fission9", "Distant Thunder 2"),
    243782: ("bastipictures", "peal of thunder - distant"),
    # Footsteps.
    861369: ("ChristopherJngs", "Splashing Footsteps Shallow Water"),
}


def fetch(url, binary=False):
    request = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    data = urllib.request.urlopen(request, timeout=120).read()
    return data if binary else data.decode("utf-8", "replace")


def source(sound_id):
    """The recording as a mono signal at RATE."""
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
    x = x.mean(axis=1)
    if rate != RATE:
        x = signal.resample_poly(x, RATE, rate)
    return x


def filt(x, kind, cut, order=2):
    sos = signal.butter(order, cut, btype=kind, fs=RATE, output="sos")
    return signal.sosfiltfilt(sos, x, axis=0)


def soften(x, rumble_cut=90.0, air_cut=8000.0, air_cut_amount=0.35, lowpass=None):
    x = filt(x, "high", rumble_cut)
    x = x - filt(x, "high", air_cut) * air_cut_amount
    if lowpass:
        x = filt(x, "low", lowpass, order=4)
    return x


def loudness_db(x, hop):
    frames = len(x) // hop
    return np.array([10 * np.log10(np.mean(x[i * hop:(i + 1) * hop] ** 2) + 1e-12) for i in range(frames)])


def steadiest(x, seconds, window=None, avoid=None):
    """Start of the stretch of {seconds} whose loudness varies least (no thunder, cars or gusts).

    {window} limits the search to (start, end) seconds of the recording; {avoid} is a start to keep away from.
    """
    hop = RATE // 2
    db = loudness_db(x, hop)
    span = int(seconds * 2)
    first, last = 0, len(db) - span
    if window is not None:
        first = max(0, int(window[0] * 2))
        last = min(last, int(window[1] * 2) - span)
    best, best_score = first, float("inf")
    for start in range(first, max(first + 1, last)):
        if avoid is not None and abs(start * hop - avoid) < span * hop:
            continue
        frame = db[start:start + span]
        if len(frame) == 0:
            break
        score = np.std(frame) + 0.5 * (frame.max() - np.median(frame))
        if score < best_score:
            best, best_score = start, score
    return best * hop


def make_loop(x, seconds, fade, start):
    n = int(seconds * RATE)
    f = int(fade * RATE)
    take = x[start:start + n + f]
    out = take[:n].copy()
    t = np.linspace(0, math.pi / 2, f)
    out[:f] = take[:f] * np.sin(t) + take[n:n + f] * np.cos(t)
    return out


def normalize(x, rms_db):
    x = x * (10 ** (rms_db / 20) / (np.sqrt(np.mean(x ** 2)) + 1e-12))
    peak = np.max(np.abs(x))
    return x * (0.95 / peak) if peak > 0.95 else x


def write(name, data, rate=RATE):
    path = os.path.join(OUT, name + ".ogg")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    if rate != RATE:
        data = signal.resample_poly(data, rate, RATE)
    data = data.astype(np.float32)
    # libsndfile crashes on large single Vorbis writes; feed it in blocks.
    with sf.SoundFile(path, "w", rate, 1, format="OGG", subtype="VORBIS") as out:
        for start in range(0, len(data), 8192):
            out.write(data[start:start + 8192])


def loops_from(sound_id, name, seconds, rms_db, variants=2, window=None, rate=RATE, **soften_args):
    """Mono loops {name}_a and {name}_b (or just {name}) from different stretches of the recording."""
    x = soften(source(sound_id), **soften_args)
    length = (window[1] - window[0]) if window else len(x) / RATE
    if variants == 1:
        seconds = min(seconds, length - 4.0)
        write(name, normalize(make_loop(x, seconds, 3.0, steadiest(x, seconds + 3.0, window)), rms_db), rate)
        return
    if (length - 8.0) / 2 < seconds * 0.75:
        # A short recording: one loop as long as it allows, the second variant is the same loop started half way.
        seconds = min(seconds, length - 4.0)
        loop = normalize(make_loop(x, seconds, 3.0, steadiest(x, seconds + 3.0, window)), rms_db)
        write(f"{name}_a", loop, rate)
        write(f"{name}_b", np.roll(loop, len(loop) // 2), rate)
        return
    seconds = min(seconds, (length - 8.0) / 2)
    first = steadiest(x, seconds + 3.0, window)
    second = steadiest(x, seconds + 3.0, window, avoid=first)
    for suffix, start in (("a", first), ("b", second)):
        write(f"{name}_{suffix}", normalize(make_loop(x, seconds, 3.0, start), rms_db), rate)


def drops_from(sound_ids, name, count, length=0.45, highpass=150.0, peak_db=-6.0, min_snr=6.0, min_decay=14.0,
               start_index=1):
    """Single drops: the cleanest isolated onsets of the recordings, written as {name}{n}. Returns the next index."""
    found = []
    for sound_id in sound_ids:
        x = filt(source(sound_id), "high", highpass)
        bright = filt(x, "high", 1000.0)
        envelope = filt(np.abs(bright), "low", 80.0)
        floor = signal.medfilt(envelope[:: RATE // 100], 201)
        peaks, props = signal.find_peaks(envelope, distance=int(0.05 * RATE), prominence=np.median(envelope) * 3)
        heights = envelope[peaks]
        for peak, height in zip(peaks, heights):
            noise = floor[min(len(floor) - 1, peak // (RATE // 100))] + 1e-9
            snr = 20 * np.log10(height / noise)
            if snr < min_snr:
                continue
            # Isolated: nothing comparable shortly before or during the drop's ring.
            near = peaks[(peaks > peak - int(0.15 * RATE)) & (peaks < peak + int(length * 0.8 * RATE)) & (peaks != peak)]
            if np.any(envelope[near] > height * 0.35):
                continue
            # A clean drop dies away: its tail is much quieter than its strike (no rain wash behind it).
            strike = np.sqrt(np.mean(x[peak:peak + int(0.03 * RATE)] ** 2)) + 1e-12
            tail = np.sqrt(np.mean(x[peak + int(length * 0.5 * RATE):peak + int(length * RATE)] ** 2)) + 1e-12
            decay = 20 * np.log10(strike / tail)
            if decay < min_decay:
                continue
            found.append((snr + decay * 0.5, sound_id, peak, x))
    found.sort(key=lambda item: -item[0])
    index = start_index
    used = []
    for snr, sound_id, peak, x in found:
        if index - start_index >= count:
            break
        if any(s == sound_id and abs(p - peak) < RATE for s, p in used):
            continue
        used.append((sound_id, peak))
        start = max(0, peak - int(0.012 * RATE))
        drop = x[start:start + int(length * RATE)].copy()
        fade = np.ones(len(drop))
        tail = int(len(drop) * 0.6)
        fade[-tail:] = np.linspace(1, 0, tail) ** 2
        fade[:int(0.003 * RATE)] = np.linspace(0, 1, int(0.003 * RATE))
        drop *= fade
        drop *= 10 ** (peak_db / 20) / (np.max(np.abs(drop)) + 1e-12)
        write(f"{name}{index}", drop)
        index += 1
    return index


def thunder(sound_id, name, seconds, peak_db, lowpass=None, at=None, rate=32000):
    """A peal of thunder: from just before its loudest moment (or {at} seconds) for {seconds}, with a long fade."""
    x = filt(source(sound_id), "high", 25.0)
    if lowpass:
        x = filt(x, "low", lowpass, order=4)
    if at is None:
        envelope = filt(np.abs(x), "low", 10.0)
        loudest = int(np.argmax(envelope))
        # Back from the loudest point to where the event begins (the leader of the crack, or the start of a roll).
        quiet = np.median(envelope)
        start = loudest
        while start > 0 and envelope[start] > quiet * 1.6 and loudest - start < 4 * RATE:
            start -= RATE // 100
        start = max(0, start - int(0.25 * RATE))
    else:
        start = int(at * RATE)
    take = x[start:start + int(seconds * RATE)].copy()
    fade_in = int(0.05 * RATE)
    take[:fade_in] *= np.linspace(0, 1, fade_in)
    tail = int(len(take) * 0.45)
    take[-tail:] *= np.linspace(1, 0, tail) ** 1.6
    take *= 10 ** (peak_db / 20) / (np.max(np.abs(take)) + 1e-12)
    write(name, take, rate)


def footsteps(sound_id, count):
    x = filt(source(sound_id), "high", 70.0)
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


def clean(folder):
    path = os.path.join(OUT, folder)
    if os.path.isdir(path):
        for old in os.listdir(path):
            os.remove(os.path.join(path, old))


def main():
    for folder in ("ambient", "surface", "roof", "water", "drop", "thunder"):
        clean(folder)

    # Open ground around the listener. Drizzle: a soft, even hush of fine rain, not a patter of drops.
    loops_from(768872, "ambient/ground_light", 24, -24, air_cut=6000.0, air_cut_amount=0.4)
    loops_from(160699, "ambient/ground_medium", 24, -20)
    loops_from(870823, "ambient/ground_heavy", 24, -19)
    # Rain far away: the heavy wash with the highs taken off by the distance.
    loops_from(870823, "ambient/far", 20, -21, variants=1, lowpass=1600.0, rumble_cut=50.0, rate=32000)
    loops_from(574556, "ambient/wind", 20, -22, variants=1, rumble_cut=35.0, air_cut=3000.0, air_cut_amount=0.6, rate=32000)

    # Surfaces the rain hits near the listener; light and heavy from the same surface where possible.
    loops_from(865323, "surface/leaves", 24, -20)
    loops_from(454143, "surface/hard_light", 14, -25, variants=1)
    loops_from(338109, "surface/hard_heavy", 16, -21, variants=1)
    loops_from(611421, "surface/wood_light", 14, -25, variants=1, window=(0, 90), rumble_cut=120.0)
    loops_from(611421, "surface/wood_heavy", 14, -21, variants=1, window=(95, 143), rumble_cut=120.0)
    loops_from(717874, "surface/metal_light", 16, -25, variants=1, window=(30, 195))
    loops_from(717874, "surface/metal_heavy", 16, -21, variants=1, window=(215, 445))
    loops_from(124168, "surface/puddle", 16, -24, variants=1, rumble_cut=250.0)
    loops_from(572429, "water/lake_light", 16, -26, variants=1, window=(0, 75))
    loops_from(572429, "water/lake_heavy", 16, -21, variants=1, window=(165, 240))
    loops_from(869851, "surface/window", 16, -24, variants=1, rumble_cut=120.0)

    # Roofs heard from below: thin roofs ring, a thick roof is a muffled rumble.
    loops_from(521773, "roof/metal_light", 14, -24, variants=1, window=(35, 95), rumble_cut=70.0)
    loops_from(771981, "roof/metal_heavy", 14, -20, variants=1, window=(40, 122), rumble_cut=70.0)
    loops_from(414162, "roof/wood_light", 14, -25, variants=1, window=(0, 100), rumble_cut=70.0, rate=32000)
    loops_from(451156, "roof/wood_heavy", 14, -21, variants=1, window=(85, 160), rumble_cut=70.0)
    loops_from(242724, "roof/glass_light", 14, -25, variants=1, rumble_cut=120.0)
    loops_from(718977, "roof/glass_heavy", 14, -21, variants=1, window=(5, 85), rumble_cut=120.0)
    loops_from(484723, "roof/fabric_light", 14, -24, variants=1)
    loops_from(817155, "roof/fabric_heavy", 14, -20, variants=1)
    loops_from(428603, "roof/thick", 16, -24, variants=1, rumble_cut=45.0, air_cut=2500.0, air_cut_amount=0.5, rate=32000)

    # Single drops by what they land on.
    drops_from([180948, 843505, 770443, 442480], "drop/puddle", 8, length=0.3, min_decay=11.0)
    drops_from([249927, 628404, 180948], "drop/hard", 6, length=0.35, min_decay=11.0)
    drops_from([683783], "drop/wood", 5, min_snr=10.0)
    drops_from([683778], "drop/metal", 5, min_snr=10.0)
    drops_from([577303, 715698], "drop/leaves", 6, length=0.14, highpass=300.0, min_decay=10.0)

    # Thunder by distance: a crack overhead, a clap with a long roll, a low rumble far away.
    thunder(717907, "thunder/close1", 22, -1.0)
    thunder(717909, "thunder/close2", 20, -1.0)
    thunder(567945, "thunder/close3", 14, -1.0)
    thunder(191992, "thunder/close4", 20, -1.0)
    thunder(865693, "thunder/mid1", 26, -3.0)
    thunder(570351, "thunder/mid2", 16, -3.0)
    thunder(197738, "thunder/mid3", 24, -3.0)
    thunder(584946, "thunder/far1", 18, -6.0, lowpass=2500.0, at=36.0)
    thunder(584946, "thunder/far2", 18, -6.0, lowpass=2500.0, at=55.0)
    thunder(581123, "thunder/far3", 20, -6.0, lowpass=2500.0, at=0.0)
    thunder(243782, "thunder/far4", 16, -6.0, lowpass=2500.0, at=3.0)

    footsteps(861369, 6)


if __name__ == "__main__":
    main()

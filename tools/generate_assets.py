"""Generates the procedural assets of Petrichor: the effects texture atlas and the synthesized sounds.

Usage: python tools/generate_assets.py   (from the mod folder; needs numpy, scipy, pillow, soundfile)

Everything is synthesized from noise and simple shapes, so the assets carry no third-party rights.
"""
import math
import os

import numpy as np
import soundfile as sf
from PIL import Image
from scipy import signal

ROOT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "petrichor")
RATE = 44100
rng = np.random.default_rng(20261005)


# ---------------------------------------------------------------------------------------------------------------------
# Texture atlas: 4x4 tiles of 32 px, white with alpha shapes (tinted in game by vertex colour).
# ---------------------------------------------------------------------------------------------------------------------

TILE = 32
SS = 4  # supersampling


def grid(size=TILE * SS):
    c = (np.arange(size) + 0.5) / size  # 0..1
    return np.meshgrid(c, c)  # x, y with y downwards


def downsample(a):
    n = a.shape[0] // SS
    return a.reshape(n, SS, n, SS).mean(axis=(1, 3))


def blob(x, y, cx, cy, r):
    return np.exp(-((x - cx) ** 2 + (y - cy) ** 2) / (2 * r * r))


def segment(x, y, ax, ay, bx, by, r):
    dx, dy = bx - ax, by - ay
    t = np.clip(((x - ax) * dx + (y - ay) * dy) / (dx * dx + dy * dy + 1e-9), 0, 1)
    px, py = ax + dx * t, ay + dy * t
    return np.exp(-((x - px) ** 2 + (y - py) ** 2) / (2 * r * r))


def splash_frame(frame):
    x, y = grid()
    a = np.zeros_like(x)
    local = np.random.default_rng(77)
    jets = 9
    grow = [0.35, 0.65, 0.85, 0.9][frame]
    for j in range(jets):
        ang = math.radians(-60 + 120 * j / (jets - 1) + local.normal(0, 6))
        length = (0.42 + local.uniform(-0.1, 0.12)) * grow
        bx = 0.5 + math.sin(ang) * length * 0.9
        by = 0.97 - math.cos(ang) * length
        width = 0.018 if frame < 2 else 0.014
        if frame < 3:
            a = np.maximum(a, segment(x, y, 0.5 + math.sin(ang) * 0.05, 0.97, bx, by, width) * (0.9 - 0.15 * frame))
        # droplets at the jet tips
        drop_y = by + (0.0 if frame < 2 else 0.12 * (frame - 1))
        a = np.maximum(a, blob(x, y, bx, drop_y, 0.026) * (1.0 if frame < 3 else 0.6))
    a = np.maximum(a, blob(x, y, 0.5, 0.98, 0.09) * (0.8 - 0.2 * frame))
    return np.clip(a, 0, 1)


def ripple():
    x, y = grid()
    r = np.sqrt((x - 0.5) ** 2 + (y - 0.5) ** 2)
    ring = np.exp(-((r - 0.42) ** 2) / (2 * 0.022 ** 2))
    inner = np.exp(-((r - 0.27) ** 2) / (2 * 0.018 ** 2)) * 0.45
    return np.clip(ring + inner, 0, 1)


def droplet():
    x, y = grid()
    return np.clip(blob(x, y, 0.5, 0.5, 0.2) * 1.3, 0, 1)


def mist():
    x, y = grid()
    base = blob(x, y, 0.5, 0.5, 0.24)
    noise = np.zeros_like(x)
    local = np.random.default_rng(5)
    for _ in range(14):
        noise += blob(x, y, local.uniform(0.25, 0.75), local.uniform(0.25, 0.75), local.uniform(0.08, 0.16))
    noise /= noise.max()
    return np.clip(base * (0.5 + 0.5 * noise), 0, 1)


def glow():
    x, y = grid()
    r = np.sqrt((x - 0.5) ** 2 + (y - 0.5) ** 2) / 0.5
    a = np.clip(1 - r, 0, 1) ** 2.2 * 0.75 + np.exp(-(r ** 2) / 0.02) * 0.25
    return np.clip(a, 0, 1)


def flake():
    x, y = grid()
    a = blob(x, y, 0.5, 0.5, 0.11)
    for k in range(6):
        ang = k * math.pi / 3
        a = np.maximum(a, segment(x, y, 0.5, 0.5, 0.5 + math.cos(ang) * 0.32, 0.5 + math.sin(ang) * 0.32, 0.035) * 0.8)
    return np.clip(a, 0, 1)


def streak():
    x, y = grid()
    across = np.exp(-((x - 0.5) ** 2) / (2 * 0.16 ** 2))
    return np.clip(across, 0, 1)


def drip():
    x, y = grid()
    # Head at the bottom (v1), thin tail upwards.
    head = blob(x, y, 0.5, 0.8, 0.13)
    tail = segment(x, y, 0.5, 0.15, 0.5, 0.8, 0.06) * np.clip((y - 0.1) / 0.7, 0, 1)
    return np.clip(np.maximum(head * 1.2, tail), 0, 1)


def spark():
    x, y = grid()
    return np.clip(blob(x, y, 0.5, 0.5, 0.07) * 1.5 + blob(x, y, 0.5, 0.5, 0.22) * 0.35, 0, 1)


def build_atlas():
    tiles = [splash_frame(0), splash_frame(1), splash_frame(2), splash_frame(3), ripple(), droplet(), mist(), glow(),
             flake(), streak(), drip(), spark()]
    atlas = np.zeros((TILE * 4, TILE * 4), dtype=np.float64)
    for i, t in enumerate(tiles):
        tx, ty = i % 4, i // 4
        atlas[ty * TILE:(ty + 1) * TILE, tx * TILE:(tx + 1) * TILE] = downsample(t)
    rgba = np.zeros((TILE * 4, TILE * 4, 4), dtype=np.uint8)
    rgba[..., 0:3] = 255
    rgba[..., 3] = np.clip(atlas * 255 + 0.5, 0, 255).astype(np.uint8)
    path = os.path.join(ROOT, "textures", "fx")
    os.makedirs(path, exist_ok=True)
    Image.fromarray(rgba, "RGBA").save(os.path.join(path, "rain_fx.png"))


# ---------------------------------------------------------------------------------------------------------------------
# Sounds
# ---------------------------------------------------------------------------------------------------------------------

def band(x, lo, hi, order=4):
    sos = signal.butter(order, [lo, hi], btype="band", fs=RATE, output="sos")
    return signal.sosfilt(sos, x)


def low(x, cut, order=4):
    sos = signal.butter(order, cut, btype="low", fs=RATE, output="sos")
    return signal.sosfilt(sos, x)


def high(x, cut, order=2):
    sos = signal.butter(order, cut, btype="high", fs=RATE, output="sos")
    return signal.sosfilt(sos, x)


def pink(n):
    white = rng.standard_normal(n)
    spectrum = np.fft.rfft(white)
    f = np.fft.rfftfreq(n, 1 / RATE)
    f[0] = 1
    spectrum /= np.sqrt(f)
    return np.fft.irfft(spectrum, n)


def slow_wobble(n, period, depth):
    points = int(n / (period * RATE)) + 3
    knots = 1 + rng.uniform(-depth, depth, points)
    xs = np.linspace(0, n, points)
    return np.interp(np.arange(n), xs, knots)


def drop_kernel(kind):
    """One drop hitting something: a short decaying tick with a random pitch."""
    if kind == "roof":
        length = int(RATE * 0.06)
        t = np.arange(length) / RATE
        f = rng.uniform(140, 420)
        tone = np.sin(2 * math.pi * f * t + rng.uniform(0, 6.28)) * np.exp(-t / rng.uniform(0.012, 0.03))
        click = rng.standard_normal(length) * np.exp(-t / 0.002) * 0.4
        return low(tone + click, 1400, 2)
    length = int(RATE * 0.02)
    t = np.arange(length) / RATE
    f = rng.uniform(1200, 6500)
    tone = np.sin(2 * math.pi * f * t + rng.uniform(0, 6.28)) * np.exp(-t / rng.uniform(0.0015, 0.006))
    click = rng.standard_normal(length) * np.exp(-t / rng.uniform(0.0006, 0.002))
    mix = rng.uniform(0.2, 0.8)
    return tone * mix + click * (1 - mix)


def impacts(n, rate_per_second, kind, loudness_sigma=0.9):
    out = np.zeros((n, 2))
    count = int(rate_per_second * n / RATE)
    bank = [drop_kernel(kind) for _ in range(96)]
    for _ in range(count):
        k = bank[rng.integers(len(bank))]
        start = rng.integers(0, n - len(k))
        amp = rng.lognormal(0, loudness_sigma)
        pan = rng.uniform(0.15, 0.85)
        out[start:start + len(k), 0] += k * amp * math.sqrt(1 - pan)
        out[start:start + len(k), 1] += k * amp * math.sqrt(pan)
    return out


def hiss(n, lo, hi):
    out = np.zeros((n, 2))
    for ch in range(2):
        out[:, ch] = band(pink(n), lo, hi) * slow_wobble(n, 2.5, 0.18)
    return out


def rumble(n, cut):
    out = np.zeros((n, 2))
    common = low(pink(n), cut)
    for ch in range(2):
        out[:, ch] = (common * 0.7 + low(pink(n), cut) * 0.3) * slow_wobble(n, 4.0, 0.25)
    return out


def normalize(x, rms_db):
    rms = np.sqrt(np.mean(x ** 2))
    x = x * (10 ** (rms_db / 20) / (rms + 1e-12))
    peak = np.max(np.abs(x))
    if peak > 0.97:
        x *= 0.97 / peak
    return x


def loop(x, seconds, fade):
    """Cuts a seamless loop of {seconds} from a longer take by crossfading its tail into its start."""
    n = int(seconds * RATE)
    f = int(fade * RATE)
    out = x[:n].copy()
    t = np.linspace(0, math.pi / 2, f)[:, None]
    out[:f] = x[:f] * np.sin(t) + x[n:n + f] * np.cos(t)
    return out


def write(name, data):
    path = os.path.join(ROOT, "sounds", name + ".ogg")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    data = data.astype(np.float32)
    channels = 1 if data.ndim == 1 else data.shape[1]
    # libsndfile crashes on large single Vorbis writes; feed it in blocks.
    with sf.SoundFile(path, "w", RATE, channels, format="OGG", subtype="VORBIS") as out:
        for start in range(0, len(data), 8192):
            out.write(data[start:start + 8192])


def rain_loop(name, seconds, drops_per_second, hiss_gain, rumble_gain, rms_db, hiss_band=(500, 9000)):
    n = int((seconds + 1.5) * RATE)
    x = impacts(n, drops_per_second, "ground") * 0.5
    x += hiss(n, *hiss_band) * hiss_gain
    if rumble_gain > 0:
        x += rumble(n, 320) * rumble_gain
    x = high(x.T, 60).T
    write(name, normalize(loop(x, seconds, 1.5), rms_db))


def roof_loop(seconds):
    n = int((seconds + 1.5) * RATE)
    x = impacts(n, 700, "roof", 0.7) * 0.6
    x += low(hiss(n, 200, 4000).T, 900).T * 0.6
    x += rumble(n, 200) * 0.35
    x = high(x.T, 45).T
    write("ambient/rain_roof", normalize(loop(x, seconds, 1.5), -20))


def puddle_step(index):
    n = int(0.42 * RATE)
    t = np.arange(n) / RATE
    attack = np.minimum(1, t / 0.004)
    slosh = low(rng.standard_normal(n), 700) * np.exp(-t / 0.09) * 1.4
    spray = band(rng.standard_normal(n), 900, 5000) * np.exp(-t / 0.05) * 0.7
    bubbles = np.zeros(n)
    for _ in range(rng.integers(3, 7)):
        start = rng.uniform(0.01, 0.2)
        f0 = rng.uniform(700, 1800)
        length = rng.uniform(0.015, 0.04)
        mask = (t >= start) & (t < start + length)
        tt = t[mask] - start
        bubbles[mask] += np.sin(2 * math.pi * (f0 + 9000 * tt) * tt) * np.exp(-tt / (length / 3)) * rng.uniform(0.2, 0.5)
    x = (slosh + spray + bubbles) * attack
    x = high(x, 80)
    write("step/puddle" + str(index), normalize(x, -16))


def main():
    build_atlas()
    rain_loop("ambient/rain_light", 14, 70, 0.55, 0.0, -24, (900, 10000))
    rain_loop("ambient/rain_medium", 14, 900, 0.75, 0.15, -20)
    rain_loop("ambient/rain_heavy", 14, 4500, 1.0, 0.45, -17, (350, 9000))
    roof_loop(14)
    for i in range(1, 4):
        puddle_step(i)


if __name__ == "__main__":
    main()

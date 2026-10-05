"""Generates the effects texture atlas of Petrichor. The sounds come from tools/prepare_sounds.py.

Usage: python tools/generate_assets.py   (from the mod folder; needs numpy and pillow)

The shapes are drawn procedurally, so the texture carries no third-party rights.
"""
import math
import os

import numpy as np
from PIL import Image

ROOT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "petrichor")


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


def main():
    build_atlas()


if __name__ == "__main__":
    main()

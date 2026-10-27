#!/usr/bin/env python3
"""Solve the world -> atlas-pixel affine from captured samples.

Usage:
  scripts/calib_solve.py [--atlas path/to/iv.png] [--verbose]

For each `claude/calibration-samples/sample_NN/` dir:
  - loads primary.png (1920x1080) + state.json (world pos)
  - detects the orange "M" (Niko marker) via color filter + central-area
    bounding box
  - maps the M pixel to atlas-pixel coords using the FIXED pause-map rect
    inside primary (template-matched once below)

Then runs a least-squares affine fit (world -> atlas pixel) under the
square-pixel assumption (a = -e, b = d = 0) and prints:

  - the derived (a, c, e, f) for iv.json
  - the implied kWorldMinX / MaxX / MinY / MaxY to drop into atlas.cpp
  - per-sample residuals (atlas-pixel distance between measured and
    predicted M)

The template-matched pause-map rect inside primary is a constant of
the GTA IV pause-screen UI; measured 2026-10-27 by multi-scale NCC
against the 1536x1152 stitched atlas (match score 0.75 at scale
0.755, same aspect as the atlas → no x/y stretch to compensate for).
"""

from __future__ import annotations

import argparse
import json
import math
import sys
from collections import deque
from pathlib import Path

import numpy as np
from PIL import Image

REPO = Path(__file__).resolve().parent.parent
SAMPLES_DIR = REPO / "claude" / "calibration-samples"
DEFAULT_ATLAS = REPO / "claude" / "calibration-samples" / "iv_atlas.png"

# Pause-map rect inside the 1920x1080 primary. Values are the top-left and
# bottom-right pixel coordinates of the atlas-sized map art.
PAUSE_MAP_L = 341
PAUSE_MAP_T = 67
PAUSE_MAP_R = 1501
PAUSE_MAP_B = 937

ATLAS_W = 1536
ATLAS_H = 1152


def find_niko_marker(primary_path: Path) -> tuple[float, float]:
    """Locate the Niko marker (green diamond) in a primary pause-map
    screenshot. Returns centroid pixel (px, py). Raises if not found.

    GTA IV's pause-map draws the player as a small dark-green diamond
    (~20 px across) with a path trail behind him. Confirmed 2026-10-27
    by sample_02 (world (2476, 240) at airport): bright-green cluster
    at primary (1440, 554) with RGB (62, 100, 25) at the center.

    The orange "M" labels on the pause-map are STORY MISSION markers
    (Mallorie / Michelle / etc.), NOT Niko. The previous atlas.cpp
    defaults were anchored on one of those by mistake. We ignore them.
    """
    arr = np.array(Image.open(primary_path).convert("RGB"))
    R = arr[:, :, 0].astype(int)
    G = arr[:, :, 1].astype(int)
    B = arr[:, :, 2].astype(int)
    # Dark-green diamond: G notably > R, G notably > B, G not too high.
    mask = (G > R + 25) & (G > B + 25) & (G > 70)

    # Mask off everything outside the pause-map rect.
    full_mask = np.zeros_like(mask, dtype=bool)
    full_mask[PAUSE_MAP_T:PAUSE_MAP_B, PAUSE_MAP_L:PAUSE_MAP_R] = \
        mask[PAUSE_MAP_T:PAUSE_MAP_B, PAUSE_MAP_L:PAUSE_MAP_R]
    mask = full_mask

    # Connected-component scan; keep the largest cluster. Blips can also
    # be colored; the M is usually by far the biggest marker on the page.
    H, W = mask.shape
    visited = np.zeros_like(mask, dtype=bool)
    comps = []
    for y0 in range(PAUSE_MAP_T, PAUSE_MAP_B):
        row = mask[y0]
        for x0 in range(PAUSE_MAP_L, PAUSE_MAP_R):
            if row[x0] and not visited[y0, x0]:
                q = deque([(y0, x0)])
                visited[y0, x0] = True
                xs, ys = [], []
                while q:
                    y, x = q.popleft()
                    xs.append(x); ys.append(y)
                    for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                        ny, nx = y + dy, x + dx
                        if (0 <= ny < H and 0 <= nx < W
                                and mask[ny, nx] and not visited[ny, nx]):
                            visited[ny, nx] = True
                            q.append((ny, nx))
                comps.append((len(xs), xs, ys))

    if not comps:
        raise RuntimeError(f"no green-diamond candidate pixels in "
                           f"{primary_path}. Did the pause-map tab fully "
                           f"load before capture?")
    comps.sort(key=lambda c: -c[0])
    size, xs, ys = comps[0]
    if size < 15:
        raise RuntimeError(f"largest green cluster in {primary_path} is "
                           f"only {size} px; detection probably failed")
    cx = sum(xs) / len(xs)
    cy = sum(ys) / len(ys)
    return cx, cy


def primary_to_atlas(px: float, py: float) -> tuple[float, float]:
    """Map a primary pixel inside the pause-map rect to atlas pixels."""
    fx = (px - PAUSE_MAP_L) / (PAUSE_MAP_R - PAUSE_MAP_L)
    fy = (py - PAUSE_MAP_T) / (PAUSE_MAP_B - PAUSE_MAP_T)
    return fx * ATLAS_W, fy * ATLAS_H


def solve_affine_square(samples):
    """Solve world -> atlas-pixel with constraints b = d = 0, a = -e.

    Unknowns: a, c, f (3). Each sample contributes two equations:
      a * wx + c = ax
      -a * wy + f = ay

    Stack and least-squares.
    """
    rows = []
    rhs = []
    for wx, wy, ax, ay in samples:
        rows.append([wx, 1.0, 0.0])
        rhs.append(ax)
        rows.append([-wy, 0.0, 1.0])
        rhs.append(ay)
    A = np.array(rows, dtype=float)
    b = np.array(rhs, dtype=float)
    sol, *_ = np.linalg.lstsq(A, b, rcond=None)
    a, c, f = sol
    e = -a
    return a, c, e, f


def solve_affine_free(samples):
    """Solve world -> atlas-pixel allowing independent x/y scale (free a, e;
    still no rotation / shear). Unknowns: a, c, e, f.
    """
    rows_x, rhs_x = [], []
    rows_y, rhs_y = [], []
    for wx, wy, ax, ay in samples:
        rows_x.append([wx, 1.0]); rhs_x.append(ax)
        rows_y.append([wy, 1.0]); rhs_y.append(ay)
    (a, c), *_ = np.linalg.lstsq(np.array(rows_x), np.array(rhs_x), rcond=None)
    (e, f), *_ = np.linalg.lstsq(np.array(rows_y), np.array(rhs_y), rcond=None)
    return a, c, e, f


def residuals(samples, a, c, e, f):
    out = []
    for wx, wy, ax, ay in samples:
        pax = a * wx + c
        pay = e * wy + f
        d = math.hypot(pax - ax, pay - ay)
        out.append(d)
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--verbose", action="store_true")
    args = ap.parse_args()

    samples = []
    sample_meta = []
    for d in sorted(SAMPLES_DIR.iterdir()):
        if not d.is_dir() or not d.name.startswith("sample_"):
            continue
        state_path = d / "state.json"
        prim_path = d / "primary.png"
        if not state_path.exists() or not prim_path.exists():
            continue
        state = json.loads(state_path.read_text())
        wx = float(state["pos"]["x"])
        wy = float(state["pos"]["y"])
        px, py = find_niko_marker(prim_path)
        ax, ay = primary_to_atlas(px, py)
        samples.append((wx, wy, ax, ay))
        sample_meta.append((d.name, wx, wy, px, py, ax, ay))
        print(f"{d.name}: world=({wx:+8.2f},{wy:+8.2f}) "
              f"primary=({px:7.2f},{py:7.2f}) "
              f"atlas=({ax:7.2f},{ay:7.2f})")

    if len(samples) < 3:
        print(f"\nNeed at least 3 samples; have {len(samples)}.",
              file=sys.stderr)
        return 1

    print(f"\n{len(samples)} samples; solving...")
    a_s, c_s, e_s, f_s = solve_affine_square(samples)
    res_sq = residuals(samples, a_s, c_s, e_s, f_s)
    print("\nSquare-pixel fit (a = -e):")
    print(f"  a = {a_s:+.6f}")
    print(f"  c = {c_s:+.6f}")
    print(f"  e = {e_s:+.6f}")
    print(f"  f = {f_s:+.6f}")
    print(f"  max residual = {max(res_sq):.2f} px   "
          f"mean = {sum(res_sq)/len(res_sq):.2f} px")
    for (name, *_), r in zip(sample_meta, res_sq):
        print(f"    {name}: {r:5.2f} px")

    a_f, c_f, e_f, f_f = solve_affine_free(samples)
    res_fr = residuals(samples, a_f, c_f, e_f, f_f)
    print("\nFree x/y scale fit (independent a and e):")
    print(f"  a = {a_f:+.6f}  (asymmetry a/|e| = {a_f/abs(e_f):.4f})")
    print(f"  c = {c_f:+.6f}")
    print(f"  e = {e_f:+.6f}")
    print(f"  f = {f_f:+.6f}")
    print(f"  max residual = {max(res_fr):.2f} px   "
          f"mean = {sum(res_fr)/len(res_fr):.2f} px")

    # Derive world bounds from the square-pixel fit.
    # pixel_x = a*wx + c → wx at px=0 is -c/a; at px=1536 is (1536-c)/a.
    # pixel_y = e*wy + f → wy at py=0 is -f/e (max); at py=1152 is (1152-f)/e (min).
    world_min_x = -c_s / a_s
    world_max_x = (ATLAS_W - c_s) / a_s
    world_max_y = -f_s / e_s
    world_min_y = (ATLAS_H - f_s) / e_s
    print("\nImplied world bounds (square-pixel fit):")
    print(f"  kWorldMinX = {world_min_x:+.1f}")
    print(f"  kWorldMaxX = {world_max_x:+.1f}")
    print(f"  kWorldMinY = {world_min_y:+.1f}")
    print(f"  kWorldMaxY = {world_max_y:+.1f}")
    print("\nPatch for asi/src/atlas.cpp:")
    print(f"    constexpr float kWorldMinX = {world_min_x:.1f}f, "
          f"kWorldMaxX = {world_max_x:.1f}f;")
    print(f"    constexpr float kWorldMinY = {world_min_y:.1f}f, "
          f"kWorldMaxY = {world_max_y:.1f}f;")

    return 0


if __name__ == "__main__":
    sys.exit(main())

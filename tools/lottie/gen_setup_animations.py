#!/usr/bin/env python3
# SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
"""Generate the setup-wizard Lottie animations (app/src/main/res/raw/setup_*.json).

The animations follow the Material 3 Expressive vocabulary: soft "cookie",
"clover" and "sunny" shapes that morph into each other, emphasized and
overshooting easing, and colour roles instead of fixed colours. Every fill or
stroke is named after the Material colour role it stands for (primary,
primaryContainer, secondary, tertiary, onPrimary, ...); the app replaces those
colours at runtime with the active colour scheme, so dynamic colour and dark
theme apply. The colours below are only fallbacks.

Shapes that morph share one vertex count, sampled from a polar radius
function and smoothed with Catmull-Rom tangents, so Lottie can interpolate
them vertex by vertex.

    python3 tools/lottie/gen_setup_animations.py [--out app/src/main/res/raw]
"""
from __future__ import annotations

import argparse
import json
import math
from pathlib import Path

FPS = 60
SIZE = 240
C = SIZE / 2
POINTS = 48

FALLBACK = {
    "primary": (0x5B, 0x5B, 0xF0),
    "primaryContainer": (0xE2, 0xE0, 0xFF),
    "secondary": (0x00, 0xC2, 0xB8),
    "secondaryContainer": (0xC8, 0xF4, 0xEF),
    "tertiary": (0xFF, 0xB6, 0x4A),
    "tertiaryContainer": (0xFF, 0xE2, 0xB8),
    "onPrimary": (0xFF, 0xFF, 0xFF),
    "onTertiary": (0x2A, 0x18, 0x00),
    "outline": (0x8A, 0x8A, 0x99),
}
# Brand logo colours are fixed (ic_zk_logo.xml); they are not colour roles.
BRAND = {"brandOuter": (0x5B, 0x5B, 0xF0), "brandInner": (0x00, 0xC2, 0xB8), "brandDot": (0xFF, 0xB6, 0x4A)}

# Easing as (out-handle of the starting key, in-handle of the next key).
EMPHASIZED = ((0.2, 0.0), (0.0, 1.0))
STANDARD = ((0.4, 0.0), (0.2, 1.0))
LINEAR = ((0.0, 0.0), (1.0, 1.0))
SINE = ((0.37, 0.0), (0.63, 1.0))


def rgba(role: str) -> list[float]:
    r, g, b = {**FALLBACK, **BRAND}[role]
    return [round(r / 255, 4), round(g / 255, 4), round(b / 255, 4), 1]


# ---------------------------------------------------------------- shapes


def polar(radius_fn, cx=0.0, cy=0.0, n=POINTS, phase=0.0) -> dict:
    """A closed smooth path through n points of r(theta), as a Lottie bezier."""
    pts = []
    for i in range(n):
        t = phase + 2 * math.pi * i / n - math.pi / 2
        r = radius_fn(t - phase)
        pts.append((cx + r * math.cos(t), cy + r * math.sin(t)))
    vs, ins, outs = [], [], []
    for i in range(n):
        p0, p2 = pts[i - 1], pts[(i + 1) % n]
        tx, ty = (p2[0] - p0[0]) / 6, (p2[1] - p0[1]) / 6
        vs.append([round(pts[i][0], 3), round(pts[i][1], 3)])
        ins.append([round(-tx, 3), round(-ty, 3)])
        outs.append([round(tx, 3), round(ty, 3)])
    return {"c": True, "v": vs, "i": ins, "o": outs}


def cookie(r: float, lobes: int, depth: float):
    """M3 'cookie'/'clover'/'sunny' family: a circle with soft lobes."""
    return lambda t: r * (1 + depth * math.cos(lobes * t))


def circle_fn(r: float):
    return lambda _t: r


def polygon_path(points: list[tuple[float, float]]) -> dict:
    return {"c": True, "v": [[round(x, 3), round(y, 3)] for x, y in points],
            "i": [[0, 0]] * len(points), "o": [[0, 0]] * len(points)}


def open_path(points: list[tuple[float, float]]) -> dict:
    return {"c": False, "v": [[round(x, 3), round(y, 3)] for x, y in points],
            "i": [[0, 0]] * len(points), "o": [[0, 0]] * len(points)}


# ---------------------------------------------------------------- animation primitives


def static(value) -> dict:
    return {"a": 0, "k": value}


def animated(keys: list[tuple[float, object]], ease=EMPHASIZED) -> dict:
    """keys: [(frame, value), ...]; value is a number, list, or path dict."""
    out = []
    for idx, (frame, value) in enumerate(keys):
        s = value if isinstance(value, list) and not isinstance(value, dict) else [value]
        if isinstance(value, dict):
            s = [value]
        k = {"t": frame, "s": s}
        if idx < len(keys) - 1:
            (ox, oy), (ix, iy) = ease
            dims = 1 if isinstance(value, dict) else len(s)
            k["o"] = {"x": [ox] * dims, "y": [oy] * dims}
            k["i"] = {"x": [ix] * dims, "y": [iy] * dims}
        out.append(k)
    return {"a": 1, "k": out}


def transform(p=(C, C), s=100, r=0, o=100, a=(0, 0)) -> dict:
    def wrap(v, dims):
        if isinstance(v, dict):
            return v
        if dims == 1:
            return static(v)
        return static(list(v) + [0] if dims == 3 else list(v))
    return {
        "o": wrap(o, 1),
        "r": wrap(r, 1),
        "p": p if isinstance(p, dict) else static([p[0], p[1], 0]),
        "a": static([a[0], a[1], 0]),
        "s": s if isinstance(s, dict) else static([s, s, 100]),
    }


def group_tr() -> dict:
    return {"ty": "tr", "p": static([0, 0]), "a": static([0, 0]), "s": static([100, 100]),
            "r": static(0), "o": static(100), "sk": static(0), "sa": static(0), "nm": "Transform"}


def fill(role: str, opacity=100) -> dict:
    return {"ty": "fl", "nm": role, "c": static(rgba(role)),
            "o": opacity if isinstance(opacity, dict) else static(opacity), "r": 1}


def stroke(role: str, width, opacity=100) -> dict:
    return {"ty": "st", "nm": role, "c": static(rgba(role)),
            "o": opacity if isinstance(opacity, dict) else static(opacity),
            "w": width if isinstance(width, dict) else static(width), "lc": 2, "lj": 2, "ml": 4}


def path_item(ks) -> dict:
    return {"ty": "sh", "nm": "Path", "ks": ks if "a" in ks else static(ks)}


def ellipse(size, pos=(0, 0)) -> dict:
    return {"ty": "el", "nm": "Ellipse", "p": static(list(pos)),
            "s": size if isinstance(size, dict) else static([size, size])}


def rect(w, h, roundness, pos=(0, 0)) -> dict:
    return {"ty": "rc", "nm": "Rect", "p": pos if isinstance(pos, dict) else static(list(pos)),
            "s": static([w, h]), "r": roundness if isinstance(roundness, dict) else static(roundness)}


def trim(start=0, end=100, offset=0) -> dict:
    def wrap(v):
        return v if isinstance(v, dict) else static(v)
    return {"ty": "tm", "nm": "Trim", "s": wrap(start), "e": wrap(end), "o": wrap(offset), "m": 1}


def group(name: str, items: list) -> dict:
    return {"ty": "gr", "nm": name, "it": items + [group_tr()]}


class Comp:
    def __init__(self, name: str, frames: int):
        self.name, self.frames, self.layers = name, frames, []

    def layer(self, name: str, shapes: list, ks: dict | None = None) -> None:
        self.layers.append({
            "ddd": 0, "ind": len(self.layers) + 1, "ty": 4, "nm": name, "sr": 1,
            "ks": ks or transform(), "ao": 0, "shapes": shapes,
            "ip": 0, "op": self.frames, "st": 0, "bm": 0,
        })

    def json(self) -> dict:
        # Lottie draws the first layer on top; layers are added bottom-up here.
        layers = list(reversed(self.layers))
        for i, layer in enumerate(layers):
            layer["ind"] = i + 1
        return {"v": "5.7.4", "fr": FPS, "ip": 0, "op": self.frames, "w": SIZE, "h": SIZE,
                "nm": self.name, "ddd": 0, "assets": [], "layers": layers}


def loop_keys(frames: int, values: list) -> list:
    """Evenly spaced keys over a loop; the first value is repeated at the end."""
    vals = values + [values[0]]
    return [(round(frames * i / (len(vals) - 1)), v) for i, v in enumerate(vals)]


# ---------------------------------------------------------------- animations


def welcome() -> dict:
    n = 4 * FPS
    comp = Comp("setup_welcome", n)
    # Soft 9-lobe cookie behind the logo, breathing and turning one lobe per loop.
    shapes = [polar(cookie(92, 9, 0.07)), polar(cookie(96, 9, 0.11)), polar(cookie(90, 9, 0.05))]
    comp.layer("cookie", [group("cookie", [path_item(animated(loop_keys(n, shapes), SINE)), fill("primaryContainer")])],
               transform(r=animated([(0, 0), (n, 40)], LINEAR)))
    # Two orbiting accents.
    for idx, (role, radius, size, start) in enumerate([("secondary", 104, 16, 0), ("tertiary", 104, 11, 180)]):
        comp.layer(f"orbit{idx}", [group("dot", [ellipse(animated(loop_keys(n, [[size, size], [size * 1.35, size * 1.35]]), SINE),
                                                         pos=(0, -radius)), fill(role)])],
                   transform(r=animated([(0, start), (n, start + 360)], LINEAR)))
    # The brand hexagon, floating.
    def hexpts(pts):
        return [(120 + (x - 54) * 1.45, 120 + (y - 54) * 1.45) for x, y in pts]
    outer = hexpts([(54, 22), (80, 37), (80, 71), (54, 86), (28, 71), (28, 37)])
    inner = hexpts([(54, 34), (69, 43), (69, 61), (54, 70), (39, 61), (39, 43)])
    float_p = animated(loop_keys(n, [[0, 0, 0], [0, -6, 0]]), SINE)
    comp.layer("logo", [
        group("dot", [ellipse(animated(loop_keys(n, [[20.3, 20.3], [23, 23]]), SINE), pos=(120, 120 + (45 - 54) * 1.45)),
                      fill("brandDot")]),
        group("inner", [path_item(polygon_path(inner)), fill("brandInner")]),
        group("outer", [path_item(polygon_path(outer)), fill("brandOuter")]),
    ], transform(p=float_p, a=(0, 0)))
    return comp.json()


def scan() -> dict:
    n = int(2.4 * FPS)
    comp = Comp("setup_scan", n)

    def ring_keys(phase: float):
        # scale 60 -> 140 and fade, wrapping while invisible.
        def at(f):
            return round(f * n)
        if phase == 0:
            scale = [(0, 60), (at(1.0), 140)]
            op = [(0, 0), (at(0.12), 55), (at(1.0), 0)]
        else:
            scale = [(0, 100), (at(0.5), 140), (at(0.5) + 1, 60), (n, 100)]
            op = [(0, 30), (at(0.5), 0), (at(0.62), 55), (n, 30)]
        return scale, op
    for idx, phase in enumerate((0.0, 0.5)):
        scale, op = ring_keys(phase)
        comp.layer(f"ring{idx}", [group("ring", [ellipse(150), stroke("primary", 4)])],
                   transform(s=animated([(f, [v, v, 100]) for f, v in scale], LINEAR),
                             o=animated(op, LINEAR)))
    # Radar sweep: a quarter arc travelling round.
    comp.layer("sweep", [group("arc", [ellipse(188), stroke("tertiary", 8), trim(0, 22, animated([(0, 0), (n, 360)], LINEAR))])])
    # Clover centre morphing between 4 and 6 lobes, turning a quarter per loop.
    shapes = [polar(cookie(46, 4, 0.16)), polar(cookie(48, 6, 0.10))]
    comp.layer("clover", [group("clover", [path_item(animated(loop_keys(n, shapes), SINE)), fill("primary")])],
               transform(r=animated([(0, 0), (n, 90)], LINEAR)))
    comp.layer("core", [group("core", [ellipse(animated(loop_keys(n, [[18, 18], [26, 26]]), SINE)), fill("onPrimary")])])
    return comp.json()


def tune() -> dict:
    n = 3 * FPS
    comp = Comp("setup_tune", n)
    xs = (72, 120, 168)
    roles = ("primary", "secondary", "tertiary")
    # Knob travel per slider: three staggered positions per loop.
    paths = ([-40, 30, -10], [20, -45, 35], [-25, 15, -50])
    for x, role, travel in zip(xs, roles, paths):
        comp.layer(f"track{x}", [group("track", [rect(14, 150, 7, (x, 120)), fill("outline", 35)])], transform(p=(0, 0)))
        comp.layer(f"knob{x}", [group("knob", [
            # Squircle <-> circle while travelling.
            rect(44, 44, animated(loop_keys(n, [22, 13, 22]), SINE), (0, 0)),
            fill(role),
        ])], transform(p=animated(loop_keys(n, [[x, 120 + t, 0] for t in travel]), EMPHASIZED)))
    return comp.json()


def success() -> dict:
    n = 2 * FPS
    comp = Comp("setup_success", n)
    # Confetti: alternating circles and rounded squares flying out, then fading.
    for i in range(10):
        ang = math.radians(i * 36 + 12)
        start = (C + 38 * math.cos(ang), C + 38 * math.sin(ang))
        end = (C + 108 * math.cos(ang), C + 108 * math.sin(ang))
        role = ("primary", "secondary", "tertiary")[i % 3]
        shape = ellipse(12) if i % 2 == 0 else rect(12, 12, 3)
        comp.layer(f"confetti{i}", [group("piece", [shape, fill(role)])], transform(
            p=animated([(18, [*start, 0]), (60, [*end, 0])], EMPHASIZED),
            s=animated([(18, [0, 0, 100]), (30, [110, 110, 100]), (40, [100, 100, 100])], STANDARD),
            r=animated([(18, 0), (96, 200 if i % 2 else -160)], STANDARD),
            o=animated([(18, 100), (70, 100), (96, 0)], STANDARD),
        ))
    # Expanding ring.
    comp.layer("burst", [group("ring", [ellipse(120), stroke("primary", animated([(16, 10), (60, 0)], EMPHASIZED))])],
               transform(s=animated([(16, [80, 80, 100]), (60, [190, 190, 100])], EMPHASIZED),
                         o=animated([(15, 0), (16, 80), (60, 0)], STANDARD)))
    # Sunny 12-lobe badge popping in and settling into a circle.
    sunny = polar(cookie(66, 12, 0.07))
    circle = polar(circle_fn(64))
    comp.layer("badge", [group("badge", [path_item(animated([(0, sunny), (30, sunny), (54, circle)], EMPHASIZED)),
                                         fill("primary")])],
               transform(s=animated([(0, [0, 0, 100]), (22, [116, 116, 100]), (34, [96, 96, 100]), (44, [100, 100, 100])], STANDARD),
                         r=animated([(0, -30), (54, 0)], EMPHASIZED)))
    check = open_path([(C - 26, C + 2), (C - 8, C + 20), (C + 28, C - 18)])
    comp.layer("check", [group("check", [path_item(check), stroke("onPrimary", 13),
                                         trim(0, animated([(28, 0), (56, 100)], EMPHASIZED))])],
               transform(p=(0, 0)))
    return comp.json()


def attention() -> dict:
    n = int(1.6 * FPS)
    comp = Comp("setup_attention", n)
    comp.layer("badge", [group("badge", [path_item(polar(cookie(64, 5, 0.08))), fill("tertiary")])],
               transform(s=animated([(0, [0, 0, 100]), (20, [112, 112, 100]), (32, [97, 97, 100]), (42, [100, 100, 100])], STANDARD),
                         r=animated([(0, -18), (22, 8), (36, -3), (48, 0)], STANDARD)))
    comp.layer("glyph", [
        group("dot", [ellipse(15, (C, C - 24)), fill("onTertiary")]),
        group("stem", [rect(13, 40, 6.5, (C, C + 10)), fill("onTertiary")]),
    ], transform(p=(C, C), a=(C, C), s=animated([(18, [0, 0, 100]), (34, [110, 110, 100]), (44, [100, 100, 100])], STANDARD)))
    return comp.json()


ANIMATIONS = {
    "setup_welcome": welcome,
    "setup_scan": scan,
    "setup_tune": tune,
    "setup_success": success,
    "setup_attention": attention,
}

# Colour roles the app must map (MaterialTheme.colorScheme). Kept in sync by a check below.
ROLES = sorted(FALLBACK)


def roles_used(node) -> set[str]:
    found = set()
    if isinstance(node, dict):
        if node.get("ty") in ("fl", "st"):
            found.add(node["nm"])
        for v in node.values():
            found |= roles_used(v)
    elif isinstance(node, list):
        for v in node:
            found |= roles_used(v)
    return found


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=str(Path(__file__).resolve().parents[2] / "app/src/main/res/raw"))
    args = ap.parse_args()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    for name, build in ANIMATIONS.items():
        data = build()
        unknown = roles_used(data) - set(FALLBACK) - set(BRAND)
        if unknown:
            raise SystemExit(f"{name}: unknown colour roles {sorted(unknown)}")
        (out / f"{name}.json").write_text(json.dumps(data, separators=(",", ":")) + "\n")
        print(f"{name}.json  {data['op'] / FPS:.1f}s  roles={sorted(roles_used(data) - set(BRAND))}")


if __name__ == "__main__":
    main()

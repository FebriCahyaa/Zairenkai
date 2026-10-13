#!/usr/bin/env python3
# SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
"""Generate the setup-wizard Lottie animations (app/src/main/res/raw/setup_*.json).

The animations use the setup's line language: thin gradient strokes (the
Zairenkai mark, a four-petal pinwheel, turning; a bundle of tuning waves),
with emphasized easing and colour roles instead of fixed colours. Every fill or
stroke is named after the Material colour role it stands for (primary,
primaryContainer, secondary, tertiary, onPrimary, ...); the app replaces those
colours at runtime with the active colour scheme, so dynamic colour and dark
theme apply. Gradient strokes are named "brandGradient" and recoloured the
same way. The colours below are only fallbacks.

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


def transform(p=(0, 0), s=100, r=0, o=100, a=(0, 0)) -> dict:
    # Shapes are drawn in absolute canvas coordinates; layers that scale or
    # turn around the centre pass p=(C, C), a=(C, C).
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
            "s": size if isinstance(size, dict) else static(list(size) if isinstance(size, (list, tuple)) else [size, size])}


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


# ---------------------------------------------------------------- gradient line language
#
# The setup's character: thin gradient strokes (blue -> violet -> pink ->
# amber) that burst, bundle, orbit and coil, and resolve into the Zairenkai
# mark, a four-petal pinwheel. The gradient is named "brandGradient"; the app
# replaces its colours at runtime with a light- or dark-tuned version.

GRADIENT = [(0x4C, 0x6E, 0xF5), (0x8B, 0x5C, 0xF6), (0xE6, 0x49, 0x80), (0xFF, 0xA9, 0x4D)]


def gstroke(width, opacity=100, start=(20, 120), end=(220, 120)) -> dict:
    stops = []
    for i, (r, g, b) in enumerate(GRADIENT):
        stops += [round(i / (len(GRADIENT) - 1), 3), round(r / 255, 4), round(g / 255, 4), round(b / 255, 4)]
    return {"ty": "gs", "nm": "brandGradient", "o": opacity if isinstance(opacity, dict) else static(opacity),
            "w": width if isinstance(width, dict) else static(width),
            "g": {"p": len(GRADIENT), "k": static(stops)},
            "s": static(list(start)), "e": static(list(end)), "t": 1, "lc": 2, "lj": 2, "ml": 4}


def smooth_open(points: list[tuple[float, float]]) -> dict:
    """Open Catmull-Rom curve through the points."""
    n = len(points)
    vs, ins, outs = [], [], []
    for i in range(n):
        p0 = points[max(i - 1, 0)]
        p2 = points[min(i + 1, n - 1)]
        tx, ty = (p2[0] - p0[0]) / 6, (p2[1] - p0[1]) / 6
        vs.append([round(points[i][0], 3), round(points[i][1], 3)])
        ins.append([round(-tx, 3), round(-ty, 3)])
        outs.append([round(tx, 3), round(ty, 3)])
    return {"c": False, "v": vs, "i": ins, "o": outs}


def petal(angle_deg: float, length=78.0, bend=0.55) -> dict:
    """One pinwheel petal: a comma-shaped curve from the centre outward."""
    a = math.radians(angle_deg)
    pts = []
    for k in range(9):
        t = k / 8
        r = length * t
        sweep = a + bend * math.sin(math.pi * t) * (1 - 0.3 * t)
        pts.append((C + r * math.cos(sweep), C + r * math.sin(sweep)))
    return smooth_open(pts)


def mark_layer(comp, name, ks, width=9.0, extra=None):
    """The Zairenkai mark: four petals and a centre dot."""
    items = [group(f"petal{i}", [path_item(petal(i * 90 - 45)), gstroke(width, start=(40, 40), end=(200, 200))] + (extra or []))
             for i in range(4)]
    items.append(group("core", [ellipse(14, (C, C)), fill("primary")]))
    comp.layer(name, items, ks)


def wave(y: float, amp: float, phase: float, cycles=2.0, x0=20.0, x1=220.0) -> dict:
    pts = [(x0 + (x1 - x0) * k / 16, y + amp * math.sin(2 * math.pi * (cycles * k / 16) + phase)) for k in range(17)]
    return smooth_open(pts)


def coil(cy: float, loops=5, r=22.0, x0=20.0, x1=220.0) -> dict:
    """A spring seen from the side: loops travelling along a line."""
    pts = []
    steps = loops * 10
    for k in range(steps + 1):
        t = k / steps
        th = 2 * math.pi * loops * t
        pts.append((x0 + (x1 - x0) * t - r * 0.9 * math.sin(th), cy - r * math.cos(th) + r))
    return smooth_open(pts)


# ---------------------------------------------------------------- animations


def welcome() -> dict:
    """The mark, turning slowly and breathing; four-fold symmetry makes 90 degrees a seamless loop."""
    n = 6 * FPS
    comp = Comp("setup_welcome", n)
    comp.layer("halo", [group("halo", [ellipse(animated(loop_keys(n, [[170, 170], [186, 186]]), SINE), (C, C)),
                                       gstroke(2, 35, start=(40, 40), end=(200, 200))])])
    mark_layer(comp, "mark", transform(p=(C, C), a=(C, C), r=animated([(0, 0), (n, 90)], LINEAR),
                                       s=animated(loop_keys(n, [[100, 100, 100], [106, 106, 100]]), SINE)))
    return comp.json()


def tune() -> dict:
    """A bundle of waves, each a little behind the next, like strings being tuned."""
    n = 3 * FPS
    comp = Comp("setup_tune", n)
    for i in range(6):
        y = 80 + i * 16
        shapes = [wave(y, 14 - i * 1.2, i * 0.6 + j * math.pi / 2) for j in range(4)]
        comp.layer(f"wave{i}", [group("wave", [path_item(animated(loop_keys(n, shapes), SINE)), gstroke(2.6, 95 - i * 9)])])
    return comp.json()


def success() -> dict:
    """The signature sequence: particles gather, burst into lines, bundle,
    orbit, coil, and resolve into the mark (the last frame is the still)."""
    n = int(3.6 * FPS)
    comp = Comp("setup_success", n)

    # 1. particles gather (0-28)
    for i in range(9):
        ang = math.radians(i * 40 + 10)
        far = (C + 84 * math.cos(ang), C + 70 * math.sin(ang))
        comp.layer(f"p{i}", [group("p", [ellipse(7 + (i % 3) * 3, (0, 0)), fill(("primary", "secondary", "tertiary")[i % 3])])],
                   transform(p=animated([(0, [*far, 0]), (26, [C, C, 0])], EMPHASIZED),
                             o=animated([(0, 0), (6, 100), (24, 100), (28, 0)], STANDARD)))

    # 2. burst lines with tipped dots (26-62)
    for i in range(10):
        ang = math.radians(i * 36 + 8)
        length = 70 + (i % 3) * 14
        tip = (C + length * math.cos(ang), C + length * math.sin(ang))
        comp.layer(f"ray{i}", [
            group("ray", [path_item(open_path([(C, C), tip])), gstroke(2, start=(C, C), end=tip),
                          trim(0, animated([(26, 0), (40, 100)], EMPHASIZED))]),
            group("tip", [ellipse(6 + (i % 2) * 4, tip), fill("primary")]),
        ], transform(o=animated([(25, 0), (27, 100), (54, 100), (62, 0)], STANDARD)))

    # 3. line bundle opening from the centre (58-96)
    for i in range(6):
        y = C - 25 + i * 10
        comp.layer(f"bundle{i}", [group("line", [path_item(wave(y, 4 + i, i * 0.5, 1.0)), gstroke(2.2)])],
                   transform(p=(C, C), a=(C, C), s=animated([(58, [0, 100, 100]), (76, [100, 100, 100])], EMPHASIZED),
                             o=animated([(57, 0), (59, 100), (88, 100), (96, 0)], STANDARD)))

    # 4. orbit rings turning in (92-140)
    for i in range(6):
        comp.layer(f"orbit{i}", [group("ring", [ellipse([150 - i * 10, 150 - i * 10], (C, C)), gstroke(2.2, 90 - i * 6)])],
                   transform(p=(C, C), a=(C, C),
                             s=animated([(92, [40, 20, 100]), (114, [100, 70 - i * 4, 100])], EMPHASIZED),
                             r=animated([(92, -40 + i * 6), (132, 10 + i * 6)], STANDARD),
                             o=animated([(91, 0), (93, 100), (130, 100), (140, 0)], STANDARD)))

    # 5. coil drawn in and out (136-176)
    comp.layer("coil", [group("coil", [path_item(coil(C - 10)), gstroke(2.6),
                                       trim(animated([(150, 0), (176, 100)], STANDARD), animated([(136, 0), (160, 100)], EMPHASIZED))])])

    # 6. the mark resolves and stays (168-end)
    mark_layer(comp, "mark", transform(p=(C, C), a=(C, C),
                                       s=animated([(168, [0, 0, 100]), (186, [112, 112, 100]), (198, [96, 96, 100]), (208, [100, 100, 100])], STANDARD),
                                       r=animated([(168, -60), (208, 0)], EMPHASIZED)),
               width=9.0)
    return comp.json()


# The check states are the mascot's (ui/mascot, tools/lottie/gen_mascot.py);
# the completion keeps the signature line sequence around the celebrating mascot.
ANIMATIONS = {
    "setup_welcome": welcome,
    "setup_tune": tune,
    "setup_success": success,
}

# Colour roles the app must map (MaterialTheme.colorScheme). Kept in sync by a check below.
ROLES = sorted(FALLBACK)


def roles_used(node) -> set[str]:
    found = set()
    if isinstance(node, dict):
        if node.get("ty") in ("fl", "st", "gs"):
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
        unknown = roles_used(data) - set(FALLBACK) - set(BRAND) - {"brandGradient"}
        if unknown:
            raise SystemExit(f"{name}: unknown colour roles {sorted(unknown)}")
        (out / f"{name}.json").write_text(json.dumps(data, separators=(",", ":")) + "\n")
        print(f"{name}.json  {data['op'] / FPS:.1f}s  roles={sorted(roles_used(data) - set(BRAND))}")


if __name__ == "__main__":
    main()

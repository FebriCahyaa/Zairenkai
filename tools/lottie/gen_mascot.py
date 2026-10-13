#!/usr/bin/env python3
# SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
"""Generate the mascot's Lottie animations (app/src/main/res/raw/mascot_*.json).

One animation per state, each built from the owner's artwork for that pose
(art/mascot/<state>.png):

    welcome  idle  inspecting  ready  warning  celebrating

The pose is split into image layers so parts move on their own: decorations
that stand apart from the character (stars, confetti, hearts, the "!" marks,
sparkles, the warning sign) become separate layers, the rest is the body.
Layers are embedded in the JSON as WebP (data URIs), so each animation is
one self-contained, offline file. Vector sparkles and confetti are added as
shape layers where a pose needs more motion than its own decorations give.

Every animation loops seamlessly: the first and last keyframes match.

    python3 tools/lottie/gen_mascot.py [--out app/src/main/res/raw]
"""
from __future__ import annotations

import argparse
import base64
import io
import json
import math
from collections import deque
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
ART = ROOT / "art/mascot"
FPS = 60
SINE = ((0.37, 0.0), (0.63, 1.0))
OUT_IN = ((0.33, 0.0), (0.67, 1.0))
UP = ((0.2, 0.0), (0.4, 1.0))       # decelerating rise
DOWN = ((0.6, 0.0), (0.8, 1.0))     # accelerating fall
LINEAR = ((0.0, 0.0), (1.0, 1.0))
FORMAT = "webp"


# ---------------------------------------------------------------- image split

def components(alpha: np.ndarray, thr: int = 20) -> list[tuple[int, np.ndarray, tuple[int, int, int, int]]]:
    """8-connected components of opaque pixels: (size, mask, box), largest first."""
    mask = alpha > thr
    h, w = mask.shape
    lab = np.zeros((h, w), np.int32)
    out = []
    n = 0
    for y in range(h):
        for x in np.nonzero(mask[y] & (lab[y] == 0))[0]:
            if lab[y, x]:
                continue
            n += 1
            q = deque([(y, x)])
            lab[y, x] = n
            ys, xs = [], []
            while q:
                cy, cx = q.popleft()
                ys.append(cy)
                xs.append(cx)
                for dy in (-1, 0, 1):
                    for dx in (-1, 0, 1):
                        ny, nx = cy + dy, cx + dx
                        if 0 <= ny < h and 0 <= nx < w and mask[ny, nx] and not lab[ny, nx]:
                            lab[ny, nx] = n
                            q.append((ny, nx))
            out.append((len(ys), lab == n, (min(xs), min(ys), max(xs) + 1, max(ys) + 1)))
    out.sort(key=lambda c: -c[0])
    return out


def grow(mask: np.ndarray, r: int = 2) -> np.ndarray:
    """Dilate a mask by r pixels, so soft edges go with their part."""
    out = mask.copy()
    for _ in range(r):
        m = out.copy()
        m[1:, :] |= out[:-1, :]
        m[:-1, :] |= out[1:, :]
        m[:, 1:] |= out[:, :-1]
        m[:, :-1] |= out[:, 1:]
        out = m
    return out


def fill_holes(mask: np.ndarray) -> np.ndarray:
    """Pixels enclosed by mask become part of it (the dark "!" inside the sign)."""
    h, w = mask.shape
    outside = np.zeros_like(mask)
    q = deque([(y, x) for y in range(h) for x in (0, w - 1)] + [(y, x) for x in range(w) for y in (0, h - 1)])
    while q:
        y, x = q.popleft()
        if 0 <= y < h and 0 <= x < w and not mask[y, x] and not outside[y, x]:
            outside[y, x] = True
            q.extend(((y + 1, x), (y - 1, x), (y, x + 1), (y, x - 1)))
    return ~outside


def webp_uri(img: Image.Image) -> str:
    buf = io.BytesIO()
    if FORMAT == "png":
        img.save(buf, "PNG", optimize=True)
    else:
        img.save(buf, "WEBP", quality=88, method=6)
    return f"data:image/{FORMAT};base64," + base64.b64encode(buf.getvalue()).decode()


class Pose:
    """A pose and the parts cut out of it."""

    def __init__(self, name: str):
        self.rgba = np.array(Image.open(ART / f"{name}.png").convert("RGBA"))
        self.h, self.w = self.rgba.shape[:2]
        self.comps = components(self.rgba[..., 3])
        self.taken = np.zeros((self.h, self.w), bool)

    def part_in(self, box: tuple[int, int, int, int]) -> np.ndarray:
        """The union of separate components lying inside box (never the body)."""
        x0, y0, x1, y1 = box
        m = np.zeros((self.h, self.w), bool)
        for _, cm, (a, b, c, d) in self.comps[1:]:
            if a >= x0 and b >= y0 and c <= x1 and d <= y1:
                m |= cm
        return m

    def by_colour(self, box, pred) -> np.ndarray:
        """Pixels in box whose colour matches pred, holes filled (cut from the body)."""
        x0, y0, x1, y1 = box
        r, g, b, a = (self.rgba[y0:y1, x0:x1, i].astype(int) for i in range(4))
        sub = pred(r, g, b) & (a > 20)
        sub = fill_holes(sub)
        m = np.zeros((self.h, self.w), bool)
        m[y0:y1, x0:x1] = sub
        return m

    def cut(self, mask: np.ndarray):
        """A part as (image, box); removed from the body."""
        mask = grow(mask, 2)
        self.taken |= mask
        ys, xs = np.nonzero(mask & (self.rgba[..., 3] > 0))
        box = (int(xs.min()), int(ys.min()), int(xs.max()) + 1, int(ys.max()) + 1)
        img = self.rgba.copy()
        img[~mask, 3] = 0
        return Image.fromarray(img).crop(box), box

    def body(self) -> Image.Image:
        img = self.rgba.copy()
        img[self.taken, 3] = 0
        return Image.fromarray(img)


# ---------------------------------------------------------------- lottie

def kf(keys: list[tuple[float, object]], ease=SINE) -> dict:
    out = []
    for i, (t, v) in enumerate(keys):
        s = list(v) if isinstance(v, (list, tuple)) else [v]
        k = {"t": t, "s": s}
        if i < len(keys) - 1:
            (ox, oy), (ix, iy) = ease
            k["o"] = {"x": [ox] * len(s), "y": [oy] * len(s)}
            k["i"] = {"x": [ix] * len(s), "y": [iy] * len(s)}
        out.append(k)
    return {"a": 1, "k": out}


def st(v) -> dict:
    return {"a": 0, "k": list(v) if isinstance(v, (list, tuple)) else v}


def ks(p, a, s=(100, 100), r=0, o=100) -> dict:
    return {
        "p": p if isinstance(p, dict) else st([p[0], p[1], 0]),
        "a": st([a[0], a[1], 0]),
        "s": s if isinstance(s, dict) else st([s[0], s[1], 100]),
        "r": r if isinstance(r, dict) else st(r),
        "o": o if isinstance(o, dict) else st(o),
    }


def loop(n: int, values: list, ease=SINE) -> dict:
    """values spread evenly over the loop; the first value closes it."""
    step = n / len(values)
    return kf([(round(i * step), v) for i, v in enumerate(values)] + [(n, values[0])], ease)


class Anim:
    def __init__(self, name: str, pose: Pose, seconds: float):
        self.name, self.pose, self.n = name, pose, round(seconds * FPS)
        self.assets: list[dict] = []
        self.layers: list[dict] = []   # bottom-up

    def image(self, nm: str, img: Image.Image, ks_: dict) -> None:
        aid = f"img_{len(self.assets)}"
        self.assets.append({"id": aid, "w": img.width, "h": img.height, "u": "", "p": webp_uri(img), "e": 1})
        self.layers.append({"ddd": 0, "ty": 2, "nm": nm, "refId": aid, "sr": 1, "ks": ks_, "ao": 0,
                            "ip": 0, "op": self.n, "st": 0, "bm": 0})

    def part(self, nm: str, cut, motion) -> None:
        """A cut part pivoting on pivot (fraction of its box); motion(box, pivot) -> ks."""
        img, box = cut
        self.image(nm, img, motion(box))

    def shape(self, nm: str, shapes: list, ks_: dict) -> None:
        self.layers.append({"ddd": 0, "ty": 4, "nm": nm, "sr": 1, "ks": ks_, "ao": 0, "shapes": shapes,
                            "ip": 0, "op": self.n, "st": 0, "bm": 0})

    def json(self) -> dict:
        layers = list(reversed(self.layers))  # Lottie draws the first layer on top
        for i, l in enumerate(layers):
            l["ind"] = i + 1
        return {"v": "5.12.0", "fr": FPS, "ip": 0, "op": self.n, "w": self.pose.w, "h": self.pose.h,
                "nm": self.name, "ddd": 0, "assets": self.assets, "layers": layers}


def pivot(box, fx=0.5, fy=0.5):
    x0, y0, x1, y1 = box
    return (fx * (x1 - x0), fy * (y1 - y0)), (x0 + fx * (x1 - x0), y0 + fy * (y1 - y0))


def place(box, fx=0.5, fy=0.5, **anim):
    """ks for a part at its own place, pivoting at (fx, fy) of its box."""
    a, p = pivot(box, fx, fy)
    pos = anim.pop("dp", None)
    if pos is not None:
        pos = kf([(t, [p[0] + dx, p[1] + dy, 0]) for t, (dx, dy) in pos[0]], pos[1])
    return ks(pos or p, a, **anim)


def sparkle_path(r: float) -> dict:
    """A four-point sparkle centred on 0,0."""
    k = 0.18 * r
    v = [[0, -r], [k, -k], [r, 0], [k, k], [0, r], [-k, k], [-r, 0], [-k, -k]]
    return {"ty": "sh", "ks": st({"c": True, "v": v, "i": [[0, 0]] * 8, "o": [[0, 0]] * 8})}


def fill(rgb, opacity=100) -> dict:
    return {"ty": "fl", "c": st([c / 255 for c in rgb] + [1]), "o": st(opacity), "r": 1}


def group(items: list) -> dict:
    return {"ty": "gr", "it": items + [{"ty": "tr", "p": st([0, 0]), "a": st([0, 0]), "s": st([100, 100]),
                                         "r": st(0), "o": st(100)}]}


BLUE = (100, 181, 255)
VIOLET = (139, 156, 255)
CYAN = (127, 243, 255)
CONFETTI = [(100, 181, 255), (139, 156, 255), (247, 131, 172), (255, 212, 59), (59, 130, 246)]


def twinkle(a: Anim, x: float, y: float, r: float, rgb, phase: float) -> None:
    """A vector sparkle that appears, grows and fades once per loop, starting at phase (0..0.6)."""
    n = a.n
    t0 = round(phase * n)
    t1, t2 = t0 + round(0.2 * n), t0 + round(0.4 * n)
    a.shape("sparkle", [group([sparkle_path(r), fill(rgb)])], ks(
        (x, y), (0, 0),
        s=kf([(0, [0, 0]), (t0, [0, 0]), (t1, [110, 110]), (t2, [0, 0]), (n, [0, 0])], OUT_IN),
        r=kf([(0, 0), (t0, 0), (t2, 45), (n, 45)], OUT_IN),
        o=kf([(0, 0), (t0, 0), (t1, 100), (t2, 0), (n, 0)], OUT_IN),
    ))


# ---------------------------------------------------------------- states

def body_ks(a: Anim, pos: dict | None = None, rot: dict | None = None, scale: dict | None = None):
    w, h = a.pose.w, a.pose.h
    base = (w / 2, h * 0.92)  # feet: jumps and sways pivot here
    p = pos if pos is not None else st([base[0], base[1], 0])
    return ks(p, base, s=scale or (100, 100), r=rot or 0)


def bob(a: Anim, amp: float, dx: float = 0, cycles: int = 1) -> dict:
    w, h = a.pose.w, a.pose.h
    bx, by = w / 2, h * 0.92
    vals = []
    for c in range(cycles):
        vals += [[bx, by, 0], [bx + dx, by - amp, 0]]
    return loop(a.n, vals)


def welcome() -> Anim:
    p = Pose("welcome")
    a = Anim("mascot_welcome", p, 3.0)
    heart_r = p.cut(p.part_in((400, 40, 475, 110)))
    bang = p.cut(p.part_in((40, 60, 125, 155)))
    heart_l = p.cut(p.part_in((60, 145, 110, 195)))
    n = a.n
    a.image("body", p.body(), body_ks(a, pos=bob(a, 7), rot=loop(n, [-2.2, 2.2]), scale=loop(n, [[100, 100], [101.2, 101.2]])))
    # The "!" calls out twice per loop, pivoting at its tip by the head.
    a.part("bang", bang, lambda b: place(b, 0.95, 0.95, s=kf([(0, [100, 100]), (12, [120, 120]), (30, [100, 100]), (90, [100, 100]),
                                                              (102, [120, 120]), (120, [100, 100]), (n, [100, 100])], OUT_IN),
                                         r=kf([(0, 0), (12, -6), (30, 0), (90, 0), (102, -6), (120, 0), (n, 0)], OUT_IN)))
    a.part("heart_r", heart_r, lambda b: place(b, s=loop(n, [[100, 100], [112, 112]]), r=loop(n, [-6, 8]),
                                               dp=([(0, (0, 0)), (n // 2, (0, -8)), (n, (0, 0))], SINE)))
    a.part("heart_l", heart_l, lambda b: place(b, s=loop(n, [[110, 110], [96, 96]]), r=loop(n, [6, -8]),
                                               dp=([(0, (0, -6)), (n // 2, (0, 2)), (n, (0, -6))], SINE)))
    return a


def idle() -> Anim:
    p = Pose("idle")
    a = Anim("mascot_idle", p, 4.0)
    a.image("body", p.body(), body_ks(a, pos=bob(a, 4), scale=loop(a.n, [[100, 100], [100.8, 101.6]])))
    twinkle(a, p.w * 0.12, p.h * 0.30, 9, VIOLET, 0.0)
    twinkle(a, p.w * 0.90, p.h * 0.22, 7, BLUE, 0.4)
    twinkle(a, p.w * 0.86, p.h * 0.62, 6, CYAN, 0.7)
    return a


def inspecting() -> Anim:
    p = Pose("inspecting")
    a = Anim("mascot_inspecting", p, 2.4)
    n = a.n
    w, h = p.w, p.h
    # Leans left and right, looking around through the magnifier.
    a.image("body", p.body(), body_ks(a, pos=bob(a, 4, dx=5), rot=loop(n, [-3, 3])))
    # A glint sweeping across the lens (the lens sits left of centre).
    lens = (w * 0.36, h * 0.56)
    glint = group([{"ty": "rc", "p": st([0, 0]), "s": st([14, 120]), "r": st(7)}, fill((255, 255, 255), 55)])
    a.shape("glint", [glint], ks(kf([(0, [lens[0] - 60, lens[1], 0]), (40, [lens[0] - 60, lens[1], 0]),
                                      (80, [lens[0] + 60, lens[1], 0]), (n, [lens[0] + 60, lens[1], 0])], OUT_IN),
                                  (0, 0), r=st(30), o=kf([(0, 0), (40, 0), (55, 70), (80, 0), (n, 0)])))
    # The crystals sparkle in turn.
    twinkle(a, w * 0.10, h * 0.33, 9, CYAN, 0.0)
    twinkle(a, w * 0.24, h * 0.55, 7, BLUE, 0.35)
    twinkle(a, w * 0.05, h * 0.52, 6, VIOLET, 0.7)
    return a


def ready() -> Anim:
    p = Pose("ready")
    a = Anim("mascot_ready", p, 2.4)
    big = p.cut(p.part_in((40, 80, 105, 170)))
    small = p.cut(p.part_in((75, 50, 115, 100)))
    right = p.cut(p.part_in((365, 50, 410, 110)))
    n = a.n
    w, h = p.w, p.h
    bx, by = w / 2, h * 0.92
    # A happy hop with squash and stretch, then a gentle bob.
    a.image("body", p.body(), body_ks(
        a,
        pos=kf([(0, [bx, by, 0]), (10, [bx, by, 0]), (28, [bx, by - 16, 0]), (46, [bx, by, 0]), (90, [bx, by - 3, 0]), (n, [bx, by, 0])], OUT_IN),
        scale=kf([(0, [100, 100]), (10, [104, 95]), (28, [97, 104]), (46, [104, 96]), (58, [100, 100]), (n, [100, 100])], OUT_IN),
    ))
    for i, (nm, part) in enumerate((("spark_big", big), ("spark_small", small), ("spark_right", right))):
        sh = i * 20
        a.part(nm, part, lambda b, sh=sh: place(
            b,
            s=kf([(0, [100, 100]), (20 + sh, [125, 125]), (50 + sh, [90, 90]), (n, [100, 100])], OUT_IN),
            r=kf([(0, 0), (20 + sh, 15), (50 + sh, -5), (n, 0)], OUT_IN),
            o=kf([(0, 100), (20 + sh, 100), (50 + sh, 70), (n, 100)]),
        ))
    return a


def warning() -> Anim:
    p = Pose("warning")
    a = Anim("mascot_warning", p, 2.0)
    lines = p.cut(p.part_in((40, 90, 100, 150)))
    # The sign touches the hair: cut it by colour (orange/yellow), holes filled.
    sign = p.cut(p.by_colour((0, 135, 100, 235), lambda r, g, b: (r > 190) & (b < 120) & (r - b > 110)))
    n = a.n
    w, h = p.w, p.h
    bx, by = w / 2, h * 0.92
    shake = [0, -6, 6, -5, 5, -3, 3, -1, 0]
    a.image("body", p.body(), body_ks(
        a,
        pos=kf([(i * 4, [bx + d, by, 0]) for i, d in enumerate(shake)] + [(70, [bx, by - 3, 0]), (n, [bx, by, 0])], OUT_IN),
        rot=kf([(0, 0), (8, -2), (36, -2.5), (70, -1), (n, 0)], OUT_IN),
    ))
    a.part("sign", sign, lambda b: place(
        b, 0.5, 0.9,
        s=kf([(0, [100, 100]), (10, [118, 118]), (24, [100, 100]), (60, [100, 100]), (70, [112, 112]), (84, [100, 100]), (n, [100, 100])], OUT_IN),
        r=kf([(0, 0), (6, -8), (12, 8), (18, -5), (24, 0), (n, 0)], OUT_IN),
    ))
    a.part("lines", lines, lambda b: place(b, 0.0, 1.0, o=kf([(0, 100), (10, 30), (20, 100), (60, 100), (70, 30), (80, 100), (n, 100)], LINEAR),
                                           s=kf([(0, [100, 100]), (10, [80, 80]), (20, [110, 110]), (30, [100, 100]), (n, [100, 100])], OUT_IN)))
    return a


def celebrating() -> Anim:
    p = Pose("celebrating")
    a = Anim("mascot_celebrating", p, 2.0)
    star_r = p.cut(p.part_in((420, 60, 482, 128)))
    star_l = p.cut(p.part_in((70, 25, 132, 88)))
    confetti = [p.cut(p.part_in(b)) for b in ((100, 420, 140, 465), (32, 72, 72, 118), (62, 96, 98, 142), (443, 146, 488, 184),
                                               (368, 41, 406, 84), (138, 42, 176, 80), (310, 7, 354, 43))]
    n = a.n
    w, h = p.w, p.h
    bx, by = w / 2, h * 0.92
    # Two jumps per loop with squash on landing.
    jump = [(0, [bx, by, 0]), (6, [bx, by, 0]), (24, [bx, by - 26, 0]), (42, [bx, by, 0]), (66, [bx, by, 0]), (84, [bx, by - 20, 0]), (102, [bx, by, 0]), (n, [bx, by, 0])]
    squash = [(0, [100, 100]), (6, [104, 95]), (24, [97, 104]), (42, [105, 94]), (52, [100, 100]), (66, [104, 96]), (84, [98, 103]), (102, [104, 95]), (112, [100, 100]), (n, [100, 100])]
    a.image("body", p.body(), body_ks(a, pos=kf(jump, OUT_IN), rot=loop(n, [-2.5, 2.5, -2.5, 2.5]), scale=kf(squash, OUT_IN)))
    a.part("star_r", star_r, lambda b: place(b, s=loop(n, [[100, 100], [122, 122]]), r=kf([(0, 0), (n, 72)], LINEAR)))
    a.part("star_l", star_l, lambda b: place(b, s=loop(n, [[118, 118], [96, 96]]), r=kf([(0, 0), (n, -72)], LINEAR)))
    for i, c in enumerate(confetti):
        ph = (i * 37) % n
        a.part(f"confetti{i}", c, lambda b, ph=ph, i=i: place(
            b,
            r=kf([(0, 0), (n, 360 if i % 2 else -360)], LINEAR),
            dp=([(0, (0, 0)), (n // 2, (6 if i % 2 else -6, 10)), (n, (0, 0))], SINE),
        ))
    # Extra confetti falling through the frame, each in its own window (hidden outside it).
    for i in range(14):
        x = ((i * 0.071 + 0.04) % 1) * w
        t0 = round(((i * 0.29) % 0.4) * n)
        t1 = t0 + round(0.6 * n)
        piece = group([{"ty": "rc", "p": st([0, 0]), "s": st([10, 5]), "r": st(1.5)}, fill(CONFETTI[i % len(CONFETTI)])])
        a.shape(f"fall{i}", [piece], ks(
            kf([(0, [x, -20, 0]), (t0, [x, -20, 0]), (t1, [x + (12 if i % 2 else -12), h * 0.9, 0]), (n, [x, -20, 0])], LINEAR),
            (0, 0),
            r=kf([(0, 0), (t0, 0), (t1, 540 if i % 2 else -540), (n, 0)], LINEAR),
            o=kf([(0, 0), (t0, 0), (t0 + 6, 100), (t1 - 10, 100), (t1, 0), (n, 0)], LINEAR),
        ))
    return a


STATES = {"welcome": welcome, "idle": idle, "inspecting": inspecting, "ready": ready, "warning": warning, "celebrating": celebrating}


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=str(ROOT / "app/src/main/res/raw"))
    args = ap.parse_args()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    for name, build in STATES.items():
        anim = build()
        data = anim.json()
        path = out / f"mascot_{name}.json"
        path.write_text(json.dumps(data, separators=(",", ":")) + "\n")
        print(f"mascot_{name}.json  {anim.n / FPS:.1f}s  layers={len(data['layers'])}  {path.stat().st_size // 1024} KB")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""
Compose a lock-screen wallpaper for the Rabbit R1: the openclaw mascot centred on a
flat background, rendered at the panel's exact pixel size so Android doesn't crop it.

Panel size is what Android reports, not the marketing spec: on the LineageOS 21 arm64_bvN
GSI the R1 reports 480x640 @ density 220 (~349x465dp), NOT the 240x282 of the bare panel.
Confirm yours with `adb shell wm size` and pass --size if it differs.

Usage:
  python3 tools/make-lock-wallpaper.py
  python3 tools/make-lock-wallpaper.py --size 480x640 --bg '#14110D' --scale 0.42
  python3 tools/make-lock-wallpaper.py --bg transparent      # mascot on alpha, for live wallpapers

Output: dist/lock-wallpaper.png
"""
import argparse
import os
import sys

try:
    from PIL import Image
except ImportError:
    sys.exit("Pillow is required:  pip install Pillow")

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_SRC = os.path.join(REPO, "assets", "mascot.png")
DEFAULT_OUT = os.path.join(REPO, "dist", "lock-wallpaper.png")


def parse_size(s: str):
    try:
        w, h = s.lower().split("x")
        return int(w), int(h)
    except ValueError:
        raise argparse.ArgumentTypeError(f"--size must look like 480x640, got {s!r}")


def parse_bg(s: str):
    if s.lower() in ("transparent", "none"):
        return (0, 0, 0, 0)
    t = s.lstrip("#")
    if len(t) == 3:
        t = "".join(c * 2 for c in t)
    if len(t) not in (6, 8):
        raise argparse.ArgumentTypeError(f"--bg must be #RGB/#RRGGBB/#RRGGBBAA or 'transparent', got {s!r}")
    vals = tuple(int(t[i:i + 2], 16) for i in range(0, len(t), 2))
    return vals if len(vals) == 4 else vals + (255,)


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--src", default=DEFAULT_SRC, help="mascot PNG (default: assets/mascot.png)")
    p.add_argument("--out", default=DEFAULT_OUT, help="output PNG (default: dist/lock-wallpaper.png)")
    p.add_argument("--size", type=parse_size, default=(480, 640), metavar="WxH",
                   help="panel size in px (default: 480x640, verified on the R1 GSI)")
    p.add_argument("--bg", type=parse_bg, default="#14110D", metavar="COLOR",
                   help="background colour, or 'transparent' (default: #14110D)")
    p.add_argument("--scale", type=float, default=0.42, metavar="F",
                   help="mascot height as a fraction of panel height (default: 0.42)")
    p.add_argument("--offset-y", type=int, default=0, metavar="PX",
                   help="nudge the mascot vertically; negative is up (default: 0)")
    a = p.parse_args()

    if not 0 < a.scale <= 1:
        sys.exit(f"--scale must be in (0, 1], got {a.scale}")
    if not os.path.exists(a.src):
        sys.exit(f"mascot not found: {a.src}")

    w, h = a.size
    canvas = Image.new("RGBA", (w, h), a.bg)

    mascot = Image.open(a.src).convert("RGBA")
    target_h = max(1, int(h * a.scale))
    target_w = max(1, round(mascot.width * (target_h / mascot.height)))
    if target_w > w:  # too wide once scaled — fit to width instead
        target_w = w
        target_h = max(1, round(mascot.height * (w / mascot.width)))
    mascot = mascot.resize((target_w, target_h), Image.LANCZOS)

    x = (w - target_w) // 2
    y = (h - target_h) // 2 + a.offset_y
    canvas.alpha_composite(mascot, (x, y))

    os.makedirs(os.path.dirname(a.out), exist_ok=True)
    canvas.save(a.out, "PNG")
    print(f"wrote {a.out}  ({w}x{h}, mascot {target_w}x{target_h} at {x},{y})")
    print("Install it:  see tools/README.md")


if __name__ == "__main__":
    main()

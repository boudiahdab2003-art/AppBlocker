#!/usr/bin/env python3
"""Turn raw device screenshots into the setup-guide pictures the app ships.

WHY THIS EXISTS. The first-run wizard shows a real photo of the Settings screen the user is about
to land on, with the row they need ringed. Raw screencaps are 1080x2400 PNGs — far too big to ship
and far too tall to read inside a card — so each one is cropped to just the part that matters,
scaled down, and written as WebP into res/drawable-nodpi/.

THE HIGHLIGHT IS NOT DRAWN HERE. The ring is drawn by the app at runtime so it follows the theme,
which means the app needs the ring's position *relative to the cropped image*. This script prints
exactly that, as the Kotlin literal to paste into data/SetupGuides.kt — computing it by hand from
two sets of pixel coordinates is a mistake waiting to happen.

    python3 tools/setup_shots.py

Coordinates in SHOTS below are in the ORIGINAL screenshot's pixels, which is what you read off a
screenshot viewer. `crop` is the region to keep; `ring` is the thing to tap.
"""
import os
import sys

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SRC = os.path.join(ROOT, "build", "shots")
OUT = os.path.join(ROOT, "app", "src", "main", "res", "drawable-nodpi")

# Target width in pixels. 480 is a deliberate compromise: these are shown inside a card a few
# hundred dp wide, so more resolution is bytes nobody sees.
WIDTH = 480
QUALITY = 80

SHOTS = [
    # name                    source file                  crop (l, t, r, b)        ring (l, t, r, b)
    ("setup_stock_a11y_list", "stock-01-a11y-list.png", (0, 640, 1080, 940), (40, 750, 860, 916)),
    ("setup_stock_a11y_switch", "stock-02-service-page.png", (40, 600, 1060, 860), (66, 646, 1032, 830)),
    ("setup_stock_a11y_allow", "stock-03-confirm.png", (74, 1500, 1006, 1990), (74, 1560, 1006, 1680)),
    ("setup_stock_overlay_row", "stock-04-overlay.png", (0, 860, 1080, 1130), (40, 900, 900, 1090)),
]


def main() -> int:
    os.makedirs(OUT, exist_ok=True)
    total = 0
    print("Kotlin for data/SetupGuides.kt — ring rects are fractions of the CROPPED image:\n")
    for name, src, crop, ring in SHOTS:
        path = os.path.join(SRC, src)
        if not os.path.exists(path):
            print("  !! missing %s — capture it first (tools/rtl.sh shot)" % src, file=sys.stderr)
            continue
        im = Image.open(path).convert("RGB").crop(crop)
        cw, ch = im.size
        scale = WIDTH / cw
        im = im.resize((WIDTH, max(1, round(ch * scale))), Image.LANCZOS)
        dest = os.path.join(OUT, name + ".webp")
        im.save(dest, "WEBP", quality=QUALITY, method=6)
        size = os.path.getsize(dest)
        total += size

        # The ring, expressed relative to the crop and then as 0..1 fractions.
        l = (ring[0] - crop[0]) / cw
        t = (ring[1] - crop[1]) / ch
        r = (ring[2] - crop[0]) / cw
        b = (ring[3] - crop[1]) / ch
        print(
            '    R.drawable.%-24s ring = Ring(%.3ff, %.3ff, %.3ff, %.3ff),   // %s, %d bytes'
            % (name + ",", l, t, r, b, im.size, size)
        )
        if not (0 <= l < r <= 1 and 0 <= t < b <= 1):
            print("      !! ring falls outside the crop — fix the coordinates", file=sys.stderr)

    print("\n  total shipped: %.1f KB" % (total / 1024))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

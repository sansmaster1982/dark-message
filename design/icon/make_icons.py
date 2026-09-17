"""Cut every app-icon asset for both platforms from ONE master image.

    python design/icon/make_icons.py            # from the repository root

The master is design/icon/dark-message-icon-1024.png: a full-bleed 1024x1024 square with
the padlock centred on a flat near-black background. Both apps must show the same icon,
so nothing here is drawn by hand - every file is a resize or a composite of that one
picture. Re-run after replacing the master.

What it writes:

iOS   ios/DarkMessage/Resources/Assets.xcassets/AppIcon.appiconset/*.png
      One PNG per entry in Contents.json, at size x scale, RGB without alpha (the App
      Store rejects a marketing icon with an alpha channel). Contents.json is not
      touched. iOS applies its own rounded mask to the full square.

Android  android/app/src/main/res/drawable-{m,h,x,xx,xxx}dpi/ic_launcher_foreground.png
         android/app/src/main/res/drawable/ic_launcher_background.xml
      An adaptive icon: the launcher masks the CENTRAL 72 dp of a 108 dp canvas with a
      circle, squircle or rounded square of its choosing, so the artwork has to sit
      inside the 33 dp-radius circle around the centre or the padlock loses its
      corners on round launchers. The master is pasted at 72/108 of the canvas, which
      makes that visible 72 dp square a pixel-for-pixel copy of the iOS icon - the same
      app must not show a bigger padlock on Android than on iPhone or in the store
      listing. Fitting the padlock TO the safe circle instead would enlarge it by a
      third; the safe circle is kept only as an upper bound. The paste sits on the
      master's own background colour, which is flat, so the seam is invisible, and the
      background layer is that colour alone. minSdk is 26, so no legacy mipmap PNGs are
      needed.

Store   design/icon/dark-message-icon-512.png (RuStore wants exactly 512x512)
"""
from __future__ import annotations

import json
import os
import sys
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
MASTER = ROOT / "design" / "icon" / "dark-message-icon-1024.png"
IOS_SET = ROOT / "ios" / "DarkMessage" / "Resources" / "Assets.xcassets" / "AppIcon.appiconset"
ANDROID_RES = ROOT / "android" / "app" / "src" / "main" / "res"

DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
ADAPTIVE_DP = 108
VISIBLE_DP = 72                 # the square every launcher shows of the 108 dp canvas
SAFE_RADIUS = 33 / 108          # the circle a launcher is guaranteed to keep
FIT_MARGIN = 0.97               # stay a little inside it


def background_colour(im: Image.Image) -> tuple[int, int, int]:
    """The flat colour around the artwork: the median of a 32x32 patch in each corner.

    A rendered background is dithered by about half a level, so a single pixel could
    land on either side of the true value and bake a colour into ic_launcher_background
    that is one level off the canvas fill. The median of four patches cannot.
    """
    w, h = im.size
    patch = 32
    medians = []
    for (x0, y0) in ((0, 0), (w - patch, 0), (0, h - patch), (w - patch, h - patch)):
        pixels = list(im.crop((x0, y0, x0 + patch, y0 + patch)).getdata())
        medians.append(tuple(sorted(p[i] for p in pixels)[len(pixels) // 2] for i in range(3)))
    spread = max(max(c[i] for c in medians) - min(c[i] for c in medians) for i in range(3))
    if spread > 6:
        sys.exit(f"the master's corners differ by {spread} levels - the background is not flat, "
                 "and the Android composite would show a seam")
    return tuple(sorted(c[i] for c in medians)[1] for i in range(3))


def artwork_radius(im: Image.Image, bg: tuple[int, int, int]) -> float:
    """How far from the centre, as a fraction of the width, the artwork reaches."""
    w, h = im.size
    px = im.load()
    furthest = 0.0
    for y in range(0, h, 2):
        for x in range(0, w, 2):
            r, g, b = px[x, y]
            if abs(r - bg[0]) + abs(g - bg[1]) + abs(b - bg[2]) > 40:
                d = ((x / w - 0.5) ** 2 + (y / h - 0.5) ** 2) ** 0.5
                if d > furthest:
                    furthest = d
    return furthest


def write_png(im: Image.Image, path: Path, size: int) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    im.resize((size, size), Image.LANCZOS).save(path, "PNG", optimize=True)


def make_ios(master: Image.Image) -> list[str]:
    contents = json.loads((IOS_SET / "Contents.json").read_text(encoding="utf-8"))
    written = []
    for entry in contents["images"]:
        points = float(entry["size"].split("x")[0])
        scale = int(entry["scale"].rstrip("x"))
        size = round(points * scale)
        target = IOS_SET / entry["filename"]
        if target.name in written:
            continue
        write_png(master, target, size)
        written.append(target.name)
    # An unreferenced full-size copy has always lived next to the set; keep it in step.
    if (IOS_SET / "AppIcon.png").exists():
        write_png(master, IOS_SET / "AppIcon.png", 1024)
        written.append("AppIcon.png")
    return written


def make_android(master: Image.Image, bg: tuple[int, int, int]) -> list[str]:
    reach = artwork_radius(master, bg)
    # Scale the master so its VISIBLE 72 dp square is a pixel-for-pixel copy of the iOS
    # square: one framing for all three assets, so the launcher, the App Store icon and
    # the RuStore listing cannot disagree. The safe-circle term stays only as a cap, for
    # a future master whose artwork reaches further than this one's.
    factor = min(VISIBLE_DP / ADAPTIVE_DP, SAFE_RADIUS * FIT_MARGIN / reach)
    written = []
    for density, mult in DENSITIES.items():
        canvas_px = round(ADAPTIVE_DP * mult)
        art_px = round(canvas_px * factor)
        canvas = Image.new("RGB", (canvas_px, canvas_px), bg)
        art = master.resize((art_px, art_px), Image.LANCZOS)
        offset = (canvas_px - art_px) // 2
        canvas.paste(art, (offset, offset))
        target = ANDROID_RES / f"drawable-{density}" / "ic_launcher_foreground.png"
        target.parent.mkdir(parents=True, exist_ok=True)
        canvas.save(target, "PNG", optimize=True)
        written.append(str(target.relative_to(ROOT)))

    hex_bg = "#{:02X}{:02X}{:02X}".format(*bg)
    background_xml = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="108dp"\n'
        '    android:height="108dp"\n'
        '    android:viewportWidth="108"\n'
        '    android:viewportHeight="108">\n'
        '    <path\n'
        f'        android:fillColor="{hex_bg}"\n'
        '        android:pathData="M0,0h108v108h-108z" />\n'
        '</vector>\n'
    )
    (ANDROID_RES / "drawable" / "ic_launcher_background.xml").write_text(background_xml, encoding="utf-8")
    written.append("android/app/src/main/res/drawable/ic_launcher_background.xml")

    # The hand-drawn vector foreground is replaced by the density PNGs above. Leaving it
    # in drawable/ would still compile, but two definitions of one icon invite drift.
    old_vector = ANDROID_RES / "drawable" / "ic_launcher_foreground.xml"
    if old_vector.exists():
        old_vector.unlink()
        written.append("removed " + str(old_vector.relative_to(ROOT)))
    print(f"android: artwork reach {reach:.3f} of width, scaled by {factor:.3f} -> padlock "
          f"radius {reach * factor * ADAPTIVE_DP:.1f} dp inside the {SAFE_RADIUS * ADAPTIVE_DP:.0f} dp "
          f"safe circle, {reach * factor * ADAPTIVE_DP / VISIBLE_DP:.4f} of the visible tile "
          f"(iOS shows {reach:.4f}); background {hex_bg}")
    return written


def main() -> None:
    if not MASTER.exists():
        sys.exit(f"master not found: {MASTER}")
    master = Image.open(MASTER).convert("RGB")
    if master.size != (1024, 1024):
        sys.exit(f"the master must be 1024x1024, this one is {master.size}")
    bg = background_colour(master)

    ios_files = make_ios(master)
    android_files = make_android(master, bg)
    write_png(master, ROOT / "design" / "icon" / "dark-message-icon-512.png", 512)

    print(f"ios: {len(ios_files)} files in {IOS_SET.relative_to(ROOT)}")
    for f in android_files:
        print("android:", f)
    print("store: design/icon/dark-message-icon-512.png")


if __name__ == "__main__":
    main()

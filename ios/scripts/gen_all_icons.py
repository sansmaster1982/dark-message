"""Generate all required iOS app icon sizes from the 1024x1024 base icon."""
from PIL import Image, ImageDraw
import os, json

OUT_DIR = os.path.join(os.path.dirname(__file__), "..", "DarkMessage", "Resources",
                       "Assets.xcassets", "AppIcon.appiconset")

# Colors matching Android icon
BG = (14, 17, 23)
LOCK = (144, 202, 249)

def generate_base_icon(size=1024):
    img = Image.new("RGB", (size, size), BG)
    draw = ImageDraw.Draw(img)
    s = size / 108.0
    bx1, by1, bx2, by2 = int(38*s), int(52*s), int(74*s), int(80*s)
    draw.rounded_rectangle([(bx1, by1), (bx2, by2)], radius=int(4*s), fill=LOCK)
    shackle_w = int(4 * s)
    draw.rectangle([(int(43*s)-shackle_w//2, int(42*s)), (int(43*s)+shackle_w//2, int(52*s))], fill=LOCK)
    draw.rectangle([(int(65*s)-shackle_w//2, int(42*s)), (int(65*s)+shackle_w//2, int(52*s))], fill=LOCK)
    cx, cy = int(54*s), int(42*s)
    rx, ry = int(11*s), int(12*s)
    for t in range(-shackle_w//2, shackle_w//2 + 1):
        draw.arc([(cx-rx, cy-ry+t), (cx+rx, cy+ry+t)], start=180, end=360, fill=LOCK, width=shackle_w)
    kx, ky = int(54*s), int(66*s)
    kr = int(4*s)
    draw.ellipse([(kx-kr, ky-kr), (kx+kr, ky+kr)], fill=BG)
    draw.polygon([(int(51*s), int(69*s)), (int(57*s), int(69*s)),
                  (int(56*s), int(74*s)), (int(52*s), int(74*s))], fill=BG)
    return img

# All required icon sizes for iOS
ICONS = [
    # iPhone
    {"size": "20x20", "scale": "2x", "px": 40},
    {"size": "20x20", "scale": "3x", "px": 60},
    {"size": "29x29", "scale": "2x", "px": 58},
    {"size": "29x29", "scale": "3x", "px": 87},
    {"size": "40x40", "scale": "2x", "px": 80},
    {"size": "40x40", "scale": "3x", "px": 120},
    {"size": "60x60", "scale": "2x", "px": 120},
    {"size": "60x60", "scale": "3x", "px": 180},
    # iPad
    {"size": "20x20", "scale": "1x", "px": 20},
    {"size": "29x29", "scale": "1x", "px": 29},
    {"size": "40x40", "scale": "1x", "px": 40},
    {"size": "76x76", "scale": "1x", "px": 76},
    {"size": "76x76", "scale": "2x", "px": 152},
    {"size": "83.5x83.5", "scale": "2x", "px": 167},
    # App Store
    {"size": "1024x1024", "scale": "1x", "px": 1024},
]

base = generate_base_icon(1024)
images = []

for icon in ICONS:
    px = icon["px"]
    filename = f"AppIcon-{px}.png"
    resized = base.resize((px, px), Image.LANCZOS)
    resized.save(os.path.join(OUT_DIR, filename), "PNG")

    entry = {
        "filename": filename,
        "idiom": "universal",
        "scale": icon["scale"],
        "size": icon["size"]
    }
    images.append(entry)
    print(f"  {filename} ({px}x{px})")

# Also save the universal single-size format
base.save(os.path.join(OUT_DIR, "AppIcon.png"), "PNG")
images.append({
    "filename": "AppIcon.png",
    "idiom": "universal",
    "platform": "ios",
    "size": "1024x1024"
})

contents = {
    "images": images,
    "info": {
        "author": "xcode",
        "version": 1
    }
}

with open(os.path.join(OUT_DIR, "Contents.json"), "w") as f:
    json.dump(contents, f, indent=2)

print(f"\nGenerated {len(ICONS)} icon sizes + Contents.json")

"""Generate 1024x1024 iOS app icon matching Android Dark Message icon (lock on dark bg)."""
from PIL import Image, ImageDraw
import os

SIZE = 1024
BG = (14, 17, 23)       # #0E1117
LOCK = (144, 202, 249)  # #90CAF9
KEYHOLE = BG

img = Image.new("RGB", (SIZE, SIZE), BG)
draw = ImageDraw.Draw(img)

# Scale factor from 108dp viewport to 1024px
s = SIZE / 108.0

# Lock body: rounded rectangle from (38,52) to (74,80), radius ~4
bx1, by1, bx2, by2 = int(38*s), int(52*s), int(74*s), int(80*s)
draw.rounded_rectangle([(bx1, by1), (bx2, by2)], radius=int(4*s), fill=LOCK)

# Lock shackle: arc from (43,52) up to (43,42) curve to (65,42) down to (65,52)
# Draw as thick outline
shackle_w = int(4 * s)
# Left side
draw.rectangle([(int(43*s)-shackle_w//2, int(42*s)), (int(43*s)+shackle_w//2, int(52*s))], fill=LOCK)
# Right side
draw.rectangle([(int(65*s)-shackle_w//2, int(42*s)), (int(65*s)+shackle_w//2, int(52*s))], fill=LOCK)
# Top arc
cx = int(54 * s)
cy = int(42 * s)
rx = int(11 * s)  # half of 65-43=22
ry = int(12 * s)
# Draw ellipse arc for the top
for t in range(-shackle_w//2, shackle_w//2 + 1):
    draw.arc([(cx-rx, cy-ry+t), (cx+rx, cy+ry+t)], start=180, end=360, fill=LOCK, width=shackle_w)

# Keyhole: circle + triangle pointing down
kx, ky = int(54*s), int(66*s)
kr = int(4*s)
draw.ellipse([(kx-kr, ky-kr), (kx+kr, ky+kr)], fill=KEYHOLE)
# Triangle/trapezoid down
draw.polygon([
    (int(51*s), int(69*s)),
    (int(57*s), int(69*s)),
    (int(56*s), int(74*s)),
    (int(52*s), int(74*s)),
], fill=KEYHOLE)

out = os.path.join(os.path.dirname(__file__), "..", "DarkMessage", "Resources", "Assets.xcassets", "AppIcon.appiconset", "AppIcon.png")
img.save(out, "PNG")
print(f"Icon saved: {out}")

"""Extract app icon from Android home screen screenshot and resize to 1024x1024."""
from PIL import Image
import os

src = os.path.join(os.path.dirname(__file__), "..", "Screenshot_20260314_230445_One UI Home.jpg")
img = Image.open(src)

# Icon center is approximately at (175, 330) in 1080x2316 image, radius ~75px
cx, cy = 185, 310
r = 75

icon = img.crop((cx - r, cy - r, cx + r, cy + r))

# Resize to 1024x1024
result = icon.resize((1024, 1024), Image.LANCZOS)

out = os.path.join(os.path.dirname(__file__), "..", "DarkMessage", "Resources",
                   "Assets.xcassets", "AppIcon.appiconset", "AppIcon.png")
result.save(out, "PNG")
print(f"Icon saved: {out}")

# Cleanup debug
debug = os.path.join(os.path.dirname(__file__), "..", "icon_debug.png")
if os.path.exists(debug):
    os.remove(debug)

from PIL import Image
import os

src = r"D:\JavaScript\dev-sidecar\packages\gui\dist\icon-1.png"
root = r"D:\dev-sidecar-android\res"
sizes = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}

img = Image.open(src).convert("RGBA")
# Trim fully transparent margins so the artwork fills the icon.
bbox = img.getbbox()
if bbox:
    img = img.crop(bbox)
print("source", img.size)

for name, px in sizes.items():
    out = os.path.join(root, f"mipmap-{name}", "ic_launcher.png")
    img.resize((px, px), Image.LANCZOS).save(out, optimize=True)
    print(f"{name}: {px}x{px} -> {os.path.getsize(out)} bytes")

# Draws the app icon (docs/images/icon.svg, by hand: no SVG library needed) into icon.ico and the window
# icon ../src/main/resources/icon.png.   uv run --with pillow desktop/icons/make.py
from pathlib import Path
from PIL import Image, ImageDraw

HERE = Path(__file__).parent
S = 1024; k = S / 108
c0, c1, c2 = (0x5B, 0x5B, 0xF7), (0x8B, 0x5C, 0xF6), (0xEC, 0x48, 0x99)
lerp = lambda a, b, t: tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))
g = Image.new("RGB", (S, S)); px = g.load()
for y in range(S):
    for x in range(S):
        t = (x + y) / (2 * (S - 1))
        px[x, y] = lerp(c0, c1, t / 0.55) if t < 0.55 else lerp(c1, c2, (t - 0.55) / 0.45)
mask = Image.new("L", (S, S), 0); ImageDraw.Draw(mask).rounded_rectangle([0, 0, S - 1, S - 1], radius=int(24 * k), fill=255)
im = Image.new("RGBA", (S, S), (0, 0, 0, 0)); im.paste(g, (0, 0), mask)
d = ImageDraw.Draw(im)
def rect(x0, y0, x1, y1, fill, r=0):
    box = [x0 * k, y0 * k, x1 * k, y1 * k]
    d.rounded_rectangle(box, radius=r * k, fill=fill) if r else d.rectangle(box, fill=fill)
rect(26, 30, 82, 72, "white", 10)                                       # bubble
d.polygon([(44 * k, 72 * k), (54 * k, 72 * k), (44 * k, 81 * k)], fill="white")  # its tail
for box in [(38, 44, 41, 52), (44, 40, 47, 56), (50, 43, 53, 53)]: rect(*box, "#6D5DF6")  # sound wave
rect(58, 43, 74, 47, "#EC4899", 2); rect(58, 50, 70, 54, "#B4ACFA", 2); rect(38, 61, 74, 65, "#8B5CF6", 2)  # lines
im.save(HERE / "icon.ico", sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
im.resize((256, 256), Image.LANCZOS).save(HERE.parent / "src/main/resources/icon.png")

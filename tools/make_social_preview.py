#!/usr/bin/env python3
"""Render docs/social-preview.png (1280x640) for the GitHub repository card.

Usage:  python3 tools/make_social_preview.py   (needs Pillow)
"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
FONT = ROOT / "app" / "src" / "main" / "res" / "font" / "roboto_flex.ttf"
ICON = ROOT / "docs" / "icon" / "zenit-icon-1024.png"
OUT = ROOT / "docs" / "social-preview.png"

W, H = 1280, 640
BG = (0x0B, 0x12, 0x10)
TEXT = (0xE8, 0xEA, 0xED)
SECONDARY = (0x9A, 0xA0, 0xA6)
GREEN = (0x5B, 0xE3, 0x8A)


def font(size: int, weight: int) -> ImageFont.FreeTypeFont:
    f = ImageFont.truetype(str(FONT), size)
    axes = f.get_variation_axes()
    values = []
    for a in axes:
        name = a.get("name", b"")
        name = name.decode() if isinstance(name, bytes) else str(name)
        if name.lower() in ("weight", "wght"):
            values.append(weight)
        elif name.lower() in ("optical size", "opsz"):
            values.append(max(a["minimum"], min(a["maximum"], size)))
        else:
            values.append(a["default"])
    f.set_variation_by_axes(values)
    return f


def main() -> None:
    img = Image.new("RGB", (W, H), BG)
    icon_size = 400
    icon = Image.open(ICON).convert("RGBA").resize((icon_size, icon_size), Image.LANCZOS)
    mask = Image.new("L", (icon_size, icon_size), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, icon_size - 1, icon_size - 1), radius=int(icon_size * 0.22), fill=255)
    x0, y0 = 110, (H - icon_size) // 2
    img.paste(icon, (x0, y0), mask)
    # The tile shares the page colour, so outline it to show its shape.
    ImageDraw.Draw(img).rounded_rectangle(
        (x0 - 1, y0 - 1, x0 + icon_size, y0 + icon_size), radius=int(icon_size * 0.22), outline=(0x24, 0x33, 0x2E), width=3,
    )

    d = ImageDraw.Draw(img)
    tx = x0 + icon_size + 90
    d.text((tx, 168), "Zenit", font=font(150, 600), fill=TEXT)
    d.text((tx + 4, 362), "GNSS satellites, live on a globe.", font=font(40, 400), fill=SECONDARY)
    d.text((tx + 4, 418), "Real orbits · OpenGL globe · Android", font=font(32, 400), fill=SECONDARY)
    pill_font = font(28, 500)
    label = "Open source · MIT"
    l, t, r, b = d.textbbox((0, 0), label, font=pill_font)
    pw, ph = r - l + 44, 52
    d.rounded_rectangle((tx + 4, 494, tx + 4 + pw, 494 + ph), radius=14, outline=GREEN, width=3)
    d.text((tx + 4 + (pw - (r - l)) / 2 - l, 494 + (ph - (b - t)) / 2 - t), label, font=pill_font, fill=GREEN)
    img.save(OUT, optimize=True)
    print(OUT.relative_to(ROOT))


if __name__ == "__main__":
    main()

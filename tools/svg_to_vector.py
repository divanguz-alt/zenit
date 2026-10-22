#!/usr/bin/env python3
"""Convert Zenit's icon SVGs into Android VectorDrawables, and render legacy launcher PNG/WebP.

Supports the subset the icon uses: <rect> (with rx), <circle>, <path>, nested <g> with
translate()/rotate() transforms, and fill / stroke / stroke-width / stroke-linecap inherited
from groups. The SVG viewBox origin (-18 -18) becomes a top-level translate so the drawable's
viewport is exactly 108 x 108.

Usage:  python3 tools/svg_to_vector.py <icon-dir>
  <icon-dir> holds zenit-icon-{background,foreground,monochrome}.svg and zenit-icon-1024.png.
"""
from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app" / "src" / "main" / "res"
SVG = "{http://www.w3.org/2000/svg}"
ANDROID = "http://schemas.android.com/apk/res/android"


def fmt(v: float) -> str:
    s = f"{v:.3f}".rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


def color(c: str | None) -> str | None:
    if c is None or c == "none":
        return None
    c = c.strip()
    if c.lower() in ("#fff", "white"):
        return "#FFFFFFFF"
    if re.fullmatch(r"#[0-9a-fA-F]{6}", c):
        return "#FF" + c[1:].upper()
    if re.fullmatch(r"#[0-9a-fA-F]{3}", c):
        return "#FF" + "".join(ch * 2 for ch in c[1:]).upper()
    raise ValueError(f"unsupported colour {c}")


def circle_path(cx: float, cy: float, r: float) -> str:
    return (f"M{fmt(cx - r)},{fmt(cy)} a{fmt(r)},{fmt(r)} 0 1,0 {fmt(2 * r)},0 "
            f"a{fmt(r)},{fmt(r)} 0 1,0 {fmt(-2 * r)},0 Z")


def rect_path(x: float, y: float, w: float, h: float, rx: float) -> str:
    if rx <= 0:
        return f"M{fmt(x)},{fmt(y)} h{fmt(w)} v{fmt(h)} h{fmt(-w)} Z"
    rx = min(rx, w / 2, h / 2)
    return (f"M{fmt(x + rx)},{fmt(y)} h{fmt(w - 2 * rx)} a{fmt(rx)},{fmt(rx)} 0 0,1 {fmt(rx)},{fmt(rx)} "
            f"v{fmt(h - 2 * rx)} a{fmt(rx)},{fmt(rx)} 0 0,1 {fmt(-rx)},{fmt(rx)} "
            f"h{fmt(-(w - 2 * rx))} a{fmt(rx)},{fmt(rx)} 0 0,1 {fmt(-rx)},{fmt(-rx)} "
            f"v{fmt(-(h - 2 * rx))} a{fmt(rx)},{fmt(rx)} 0 0,1 {fmt(rx)},{fmt(-rx)} Z")


def parse_transform(t: str | None) -> list[tuple[str, list[float]]]:
    if not t:
        return []
    return [(name, [float(v) for v in re.split(r"[ ,]+", args.strip()) if v])
            for name, args in re.findall(r"(\w+)\(([^)]*)\)", t)]


STYLE_KEYS = ("fill", "stroke", "stroke-width", "stroke-linecap")


def emit(el: ET.Element, style: dict, indent: str, out: list[str]) -> None:
    style = dict(style)
    for k in STYLE_KEYS:
        if el.get(k) is not None:
            style[k] = el.get(k)
    tag = el.tag.replace(SVG, "")
    if tag == "g":
        tf = parse_transform(el.get("transform"))
        attrs = []
        for name, args in tf:
            if name == "translate":
                attrs += [f'android:translateX="{fmt(args[0])}"', f'android:translateY="{fmt(args[1] if len(args) > 1 else 0)}"']
            elif name == "rotate":
                if len(args) != 1:
                    raise ValueError("rotate about a point is not supported")
                attrs.append(f'android:rotation="{fmt(args[0])}"')
            else:
                raise ValueError(f"unsupported transform {name}")
        # SVG applies translate then rotate (outer to inner) == VectorDrawable rotate-then-translate on the point.
        if tf and [n for n, _ in tf] not in (["translate"], ["rotate"], ["translate", "rotate"]):
            raise ValueError("unsupported transform order")
        out.append(f"{indent}<group {' '.join(attrs)}>" if attrs else f"{indent}<group>")
        for child in el:
            emit(child, style, indent + "    ", out)
        out.append(f"{indent}</group>")
        return
    if tag == "metadata":
        return
    if tag == "circle":
        d = circle_path(float(el.get("cx", 0)), float(el.get("cy", 0)), float(el.get("r")))
    elif tag == "rect":
        d = rect_path(float(el.get("x", 0)), float(el.get("y", 0)), float(el.get("width")),
                      float(el.get("height")), float(el.get("rx", 0)))
    elif tag == "path":
        d = el.get("d")
    else:
        raise ValueError(f"unsupported element {tag}")
    attrs = [f'android:pathData="{d}"']
    fill = color(style.get("fill", "#000"))
    stroke = color(style.get("stroke"))
    if fill:
        attrs.append(f'android:fillColor="{fill}"')
    if stroke:
        attrs.append(f'android:strokeColor="{stroke}"')
        attrs.append(f'android:strokeWidth="{fmt(float(style.get("stroke-width", 1)))}"')
        cap = style.get("stroke-linecap")
        if cap:
            attrs.append(f'android:strokeLineCap="{cap}"')
    out.append(f"{indent}<path {' '.join(attrs)} />")


def convert(svg_path: Path, out_path: Path) -> None:
    root = ET.parse(svg_path).getroot()
    vb = [float(v) for v in root.get("viewBox").split()]
    if vb[2] != 108 or vb[3] != 108:
        raise ValueError(f"{svg_path.name}: expected a 108x108 viewBox, got {vb}")
    out = [
        '<?xml version="1.0" encoding="utf-8"?>',
        f"<!-- Generated by tools/svg_to_vector.py from {svg_path.name}. Do not edit by hand. -->",
        f'<vector xmlns:android="{ANDROID}"',
        '    android:width="108dp" android:height="108dp"',
        '    android:viewportWidth="108" android:viewportHeight="108">',
        f'    <group android:translateX="{fmt(-vb[0])}" android:translateY="{fmt(-vb[1])}">',
    ]
    for child in root:
        emit(child, {}, "        ", out)
    out += ["    </group>", "</vector>", ""]
    out_path.write_text("\n".join(out))
    print(f"{out_path.relative_to(ROOT)}")


def legacy_icons(png_1024: Path) -> None:
    """Legacy launcher bitmaps (unused on API 26+, kept as a safety net)."""
    from PIL import Image, ImageDraw

    src = Image.open(png_1024).convert("RGBA")
    sizes = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    big = 1024
    square = Image.new("L", (big, big), 0)
    ImageDraw.Draw(square).rounded_rectangle((0, 0, big - 1, big - 1), radius=int(big * 0.18), fill=255)
    circle = Image.new("L", (big, big), 0)
    ImageDraw.Draw(circle).ellipse((0, 0, big - 1, big - 1), fill=255)
    for density, px in sizes.items():
        d = RES / f"mipmap-{density}"
        d.mkdir(parents=True, exist_ok=True)
        for name, mask in (("ic_launcher", square), ("ic_launcher_round", circle)):
            im = src.copy()
            im.putalpha(mask)
            im.resize((px, px), Image.LANCZOS).save(d / f"{name}.webp", "WEBP", lossless=True)
    print("legacy mipmaps written")


def main() -> int:
    icon_dir = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "docs" / "icon"
    drawable = RES / "drawable"
    for layer in ("background", "foreground", "monochrome"):
        convert(icon_dir / f"zenit-icon-{layer}.svg", drawable / f"ic_launcher_{layer}.xml")
    legacy_icons(icon_dir / "zenit-icon-1024.png")
    return 0


if __name__ == "__main__":
    sys.exit(main())

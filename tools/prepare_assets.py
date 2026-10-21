#!/usr/bin/env python3
"""Download and process Zenit's bundled assets so the build is reproducible.

Outputs (written into app/src/main):
  assets/land_dots.bin    float32 little-endian pairs (lat, lon) in degrees, dot-matrix land
  assets/earth_day.jpg    NASA Blue Marble Next Generation, 4096x2048
  assets/earth_night.jpg  NASA Black Marble 2016, 2048x1024
  res/font/roboto_flex.ttf  Roboto Flex variable font (OFL)

Requires Python 3.9+ and Pillow (pip install pillow). Raw downloads are cached
in tools/.cache/ (git-ignored).

Usage:  python3 tools/prepare_assets.py [--preview]
"""
from __future__ import annotations

import argparse
import json
import math
import struct
import sys
import urllib.request
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
CACHE = ROOT / "tools" / ".cache"
ASSETS = ROOT / "app" / "src" / "main" / "assets"
FONTS = ROOT / "app" / "src" / "main" / "res" / "font"

SOURCES = {
    # Natural Earth 1:50m land polygons (public domain).
    "ne_50m_land.geojson": "https://raw.githubusercontent.com/nvkelso/natural-earth-vector/v5.1.2/geojson/ne_50m_land.geojson",
    # NASA Visible Earth, Blue Marble Next Generation w/ topography and bathymetry, December 2004.
    "blue_marble.jpg": "https://eoimages.gsfc.nasa.gov/images/imagerecords/73000/73909/world.topo.bathy.200412.3x5400x2700.jpg",
    # NASA Earth Observatory, Earth at Night (Black Marble) 2016, 0.1 degree.
    "black_marble.jpg": "https://eoimages.gsfc.nasa.gov/images/imagerecords/144000/144898/BlackMarble_2016_01deg.jpg",
    # Google Fonts, Roboto Flex (SIL Open Font License 1.1).
    "roboto_flex.ttf": "https://github.com/google/fonts/raw/main/ofl/robotoflex/RobotoFlex%5BGRAD%2CXOPQ%2CXTRA%2CYOPQ%2CYTAS%2CYTDE%2CYTFI%2CYTLC%2CYTUC%2Copsz%2Cslnt%2Cwdth%2Cwght%5D.ttf",
}

# Dot matrix parameters (match the prototype): 1.2 degree rows, equal-area spacing per row.
DOT_STEP = 1.2
LAT_MIN, LAT_MAX = -80.0, 82.0
MASK_SCALE = 10  # mask pixels per degree


def fetch(name: str) -> Path:
    CACHE.mkdir(parents=True, exist_ok=True)
    path = CACHE / name
    if not path.exists() or path.stat().st_size == 0:
        url = SOURCES[name]
        print(f"downloading {url}")
        req = urllib.request.Request(url, headers={"User-Agent": "zenit-asset-builder"})
        with urllib.request.urlopen(req, timeout=120) as r, open(path.with_suffix(".part"), "wb") as f:
            while chunk := r.read(1 << 16):
                f.write(chunk)
        path.with_suffix(".part").rename(path)
    return path


def unwrap_ring(ring: list[list[float]]) -> list[tuple[float, float]]:
    """Make longitudes continuous so rings that cross the antimeridian don't smear across the map."""
    out: list[tuple[float, float]] = []
    offset = 0.0
    prev = None
    for lon, lat in ring:
        if prev is not None:
            d = lon + offset - prev
            if d > 180:
                offset -= 360
            elif d < -180:
                offset += 360
        prev = lon + offset
        out.append((prev, lat))
    return out


def land_mask() -> Image.Image:
    data = json.loads(fetch("ne_50m_land.geojson").read_text())
    w, h = 360 * MASK_SCALE, 180 * MASK_SCALE
    mask = Image.new("1", (w, h), 0)
    draw = ImageDraw.Draw(mask)

    def px(lon: float, lat: float, shift: float) -> tuple[float, float]:
        return ((lon + shift + 180.0) * MASK_SCALE, (90.0 - lat) * MASK_SCALE)

    for feature in data["features"]:
        geom = feature["geometry"]
        polys = [geom["coordinates"]] if geom["type"] == "Polygon" else geom["coordinates"]
        for poly in polys:
            rings = [unwrap_ring(r) for r in poly]
            outer = rings[0]
            # A ring that wraps the globe (Antarctica) ends ~360 degrees from where it starts:
            # close it through the nearest pole instead of across the map.
            wraps = abs(outer[-1][0] - outer[0][0]) > 180
            if wraps:
                pole = -90.0 if min(p[1] for p in outer) < 0 else 90.0
                outer = outer + [(outer[-1][0], pole), (outer[0][0], pole)]
            # Draw shifted copies so parts pushed past +/-180 by unwrapping land back on the map.
            for shift in (-360.0, 0.0, 360.0):
                draw.polygon([px(lo, la, shift) for lo, la in outer], fill=1)
            for hole in rings[1:]:
                for shift in (-360.0, 0.0, 360.0):
                    draw.polygon([px(lo, la, shift) for lo, la in hole], fill=0)
    return mask


def build_dots(preview: bool) -> None:
    mask = land_mask()
    w, h = mask.size
    pix = mask.load()
    dots: list[tuple[float, float]] = []
    lat = LAT_MIN
    while lat <= LAT_MAX + 1e-9:
        n = max(1, round(360.0 * math.cos(math.radians(lat)) / DOT_STEP))
        for i in range(n):
            lon = -180.0 + (i + 0.5) * 360.0 / n
            x = min(w - 1, int((lon + 180.0) * MASK_SCALE))
            y = min(h - 1, int((90.0 - lat) * MASK_SCALE))
            if pix[x, y]:
                dots.append((lat, lon))
        lat = round(lat + DOT_STEP, 6)
    ASSETS.mkdir(parents=True, exist_ok=True)
    with open(ASSETS / "land_dots.bin", "wb") as f:
        for la, lo in dots:
            f.write(struct.pack("<ff", la, lo))
    print(f"land_dots.bin: {len(dots)} dots")
    if preview:
        img = Image.new("RGB", (1440, 720), (3, 6, 12))
        d = ImageDraw.Draw(img)
        for la, lo in dots:
            x, y = (lo + 180) * 4, (90 - la) * 4
            d.rectangle([x - 1, y - 1, x + 1, y + 1], fill=(214, 230, 255))
        out = ROOT / "tools" / ".cache" / "land_dots_preview.png"
        img.save(out)
        print(f"preview: {out}")


def build_textures() -> None:
    Image.MAX_IMAGE_PIXELS = None
    day = Image.open(fetch("blue_marble.jpg")).convert("RGB").resize((4096, 2048), Image.LANCZOS)
    day.save(ASSETS / "earth_day.jpg", quality=86, optimize=True, progressive=False)
    night = Image.open(fetch("black_marble.jpg")).convert("RGB").resize((2048, 1024), Image.LANCZOS)
    night.save(ASSETS / "earth_night.jpg", quality=86, optimize=True, progressive=False)
    print("textures written")


def build_font() -> None:
    FONTS.mkdir(parents=True, exist_ok=True)
    (FONTS / "roboto_flex.ttf").write_bytes(fetch("roboto_flex.ttf").read_bytes())
    print("font written")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--preview", action="store_true", help="also render tools/.cache/land_dots_preview.png")
    args = ap.parse_args()
    build_dots(args.preview)
    build_textures()
    build_font()
    return 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""Generate Compose ImageVector definitions for the desktop port from the Android
controller vector drawables (app/src/main/res/drawable/controller_outline_*.xml).

Android vector XML -> Compose ImageVector mapping:
  android:viewportWidth/Height  -> ImageVector.Builder viewportWidth/Height
  android:width/height (dp)     -> defaultWidth/defaultHeight
  <path android:fillColor>      -> addPath(pathData, fill = SolidColor(...))
  <path android:strokeColor + strokeWidth> -> addPath(pathData, stroke = ..., strokeLineWidth = ...)
  pathData strings are passed verbatim to androidx.compose.ui.graphics.vector.PathParser
  (the same SVG-path grammar Android's vector renderer uses).
"""
import argparse
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

NS = {"android": "http://schemas.android.com/apk/res/android"}
REPO = Path(__file__).resolve().parents[1]
SRC = REPO / "app/src/main/res/drawable"
OUT = REPO / "desktop/src/main/kotlin/com/romm/desktop/ui/controller/ControllerArtworkVectors.kt"
PNG_OUT = REPO / "assets/controllers"

def k_name(resource: str) -> str:
    return "".join(p.capitalize() for p in resource.split("_"))

def color_lit(hexcolor: str) -> str:
    h = hexcolor.lstrip("#")
    if len(h) == 6:
        h = "FF" + h
    return f"Color(0x{h.upper()})"

def render_pngs(files: list[Path]) -> None:
    browser = next(
        (path for command in ("chromium", "google-chrome", "chromium-browser")
         if (path := shutil.which(command))),
        None,
    )
    if browser is None:
        raise SystemExit("PNG generation requires Chromium or Google Chrome")

    PNG_OUT.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".controller-art-", dir=REPO / "assets") as temp:
        temp_dir = Path(temp)
        for source in files:
            root = ET.parse(source).getroot()
            vw = root.get(f"{{{NS['android']}}}viewportWidth")
            vh = root.get(f"{{{NS['android']}}}viewportHeight")
            svg_paths = []
            for path in root.findall("path"):
                data = path.get(f"{{{NS['android']}}}pathData")
                fill = path.get(f"{{{NS['android']}}}fillColor", "none")
                stroke = path.get(f"{{{NS['android']}}}strokeColor")
                stroke_width = path.get(f"{{{NS['android']}}}strokeWidth")
                attributes = [f'd="{data}"', f'fill="{fill}"']
                if stroke:
                    attributes += [f'stroke="{stroke}"', f'stroke-width="{stroke_width}"']
                svg_paths.append(f"<path {' '.join(attributes)}/>")
            svg = (
                f'<svg xmlns="http://www.w3.org/2000/svg" width="720" height="720" '
                f'viewBox="0 0 {vw} {vh}">'
                '<rect width="100%" height="100%" fill="#0F1F21"/>'
                f'{"".join(svg_paths)}</svg>'
            )
            svg_path = temp_dir / f"{source.stem}.svg"
            svg_path.write_text(svg)
            output = PNG_OUT / f"{source.stem}.png"
            subprocess.run(
                [
                    browser,
                    "--headless",
                    "--disable-gpu",
                    "--no-sandbox",
                    "--hide-scrollbars",
                    "--default-background-color=00000000",
                    "--window-size=720,720",
                    f"--screenshot={output}",
                    svg_path.as_uri(),
                ],
                check=True,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
            print(f"wrote {output}")


parser = argparse.ArgumentParser()
parser.add_argument(
    "--png-only",
    action="store_true",
    help="Generate shared PNG artwork without rewriting the Compose vectors",
)
args = parser.parse_args()

files = sorted(SRC.glob("controller_outline_*.xml"))
if args.png_only:
    render_pngs(files)
    raise SystemExit

blocks = []
manifest = []  # (resource_name, kname, viewport_w, viewport_h, n_paths)

for f in files:
    root = ET.parse(f).getroot()
    resource = f.stem
    vw = float(root.get("{http://schemas.android.com/apk/res/android}viewportWidth"))
    vh = float(root.get("{http://schemas.android.com/apk/res/android}viewportHeight"))
    w = float(root.get("{http://schemas.android.com/apk/res/android}width").rstrip("dp"))
    h = float(root.get("{http://schemas.android.com/apk/res/android}height").rstrip("dp"))

    def fmt(x: float) -> str:
        return f"{x:g}"

    path_calls = []
    n_paths = 0
    # Element tags are plain `path` (only the attributes are android:-namespaced).
    for p in root.findall("path"):
        n_paths += 1
        data = p.get("{http://schemas.android.com/apk/res/android}pathData")
        assert data, f"missing pathData in {f}"
        fill = p.get("{http://schemas.android.com/apk/res/android}fillColor")
        stroke = p.get("{http://schemas.android.com/apk/res/android}strokeColor")
        sw = p.get("{http://schemas.android.com/apk/res/android}strokeWidth")
        if fill:
            style = f"fill = SolidColor({color_lit(fill)})"
        elif stroke:
            assert sw, f"stroke without strokeWidth in {f}"
            style = (f"stroke = SolidColor({color_lit(stroke)}),\n"
                     f"            strokeLineWidth = {fmt(float(sw))}f")
        else:
            raise SystemExit(f"path with neither fill nor stroke in {f}")
        path_calls.append(
            "    addPath(\n"
            f'        pathData = PathParser().parsePathString("{data}").toNodes(),\n'
            f"            {style},\n"
            "    )"
        )

    kn = k_name(resource)
    blocks.append(
        f"val {kn}: ImageVector = ImageVector.Builder(\n"
        f'    name = "{resource}",\n'
        f"    defaultWidth = {fmt(w)}.dp,\n"
        f"    defaultHeight = {fmt(h)}.dp,\n"
        f"    viewportWidth = {fmt(vw)}f,\n"
        f"    viewportHeight = {fmt(vh)}f,\n"
        ").apply {\n"
        + "\n".join(path_calls)
        + "\n}.build()"
    )
    manifest.append((resource, kn, vw, vh, n_paths))

header = """package com.romm.desktop.ui.controller

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

// GENERATED FILE — do not edit by hand.
//
// Converts the 15 Android controller vector drawables
// (app/src/main/res/drawable/controller_outline_*.xml) into Compose ImageVectors so the
// desktop can render the same silhouettes without the Android drawable runtime. The path
// data is passed verbatim to [PathParser] — the same SVG-path grammar Android's vector
// renderer uses — so rendering is 1:1 with the Android drawables.
//
// Regenerate with: python3 tools/generate_controller_artwork.py

"""

OUT.parent.mkdir(parents=True, exist_ok=True)
OUT.write_text(header + "\n" + "\n\n".join(blocks) + "\n")
print(f"wrote {OUT} ({OUT.stat().st_size} bytes)")
for resource, kn, vw, vh, n in manifest:
    print(f"  {kn}: viewport={vw:g}x{vh:g} paths={n}")

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
import xml.etree.ElementTree as ET
from pathlib import Path

NS = {"android": "http://schemas.android.com/apk/res/android"}
SRC = Path("/Users/zackduford/workspaces/romm-android-tv/app/src/main/res/drawable")
OUT = Path("/Users/zackduford/workspaces/romm-android-tv/desktop/src/main/kotlin/com/romm/desktop/ui/controller/ControllerArtworkVectors.kt")

def k_name(resource: str) -> str:
    return "".join(p.capitalize() for p in resource.split("_"))

def color_lit(hexcolor: str) -> str:
    h = hexcolor.lstrip("#")
    if len(h) == 6:
        h = "FF" + h
    return f"Color(0x{h.upper()})"

files = sorted(SRC.glob("controller_outline_*.xml"))
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

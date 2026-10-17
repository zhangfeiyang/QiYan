#!/usr/bin/env python3
"""Android res -> ios/QiYan/Resources/Assets.xcassets (generated, do not edit by hand).

- every <color> in values*/ becomes a colorset; values-night/ supplies the dark appearance
- vector drawables (ic_*, ink_*) become single-scale SVG imagesets; ic_* render as templates,
  ink_* get a second SVG for the dark appearance because their colors are theme tokens
- the effect sprites (drawable-nodpi) and launcher layers are copied as PNG
- AppIcon is the launcher background + foreground composited at 1024 px

Run from anywhere: python3 ios/tools/make_assets.py
"""
import json
import os
import re
import shutil
import xml.etree.ElementTree as ET

from PIL import Image

IOS = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(IOS, "..", "app", "src", "main", "res")
OUT = os.path.join(IOS, "QiYan", "Resources", "Assets.xcassets")
A = "{http://schemas.android.com/apk/res/android}"
INFO = {"author": "xcode", "version": 1}


def write_json(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        json.dump(obj, f, indent=2)


def load_colors(folder):
    out = {}
    for name in sorted(os.listdir(os.path.join(RES, folder))):
        if not name.endswith(".xml"):
            continue
        root = ET.parse(os.path.join(RES, folder, name)).getroot()
        for el in root.iter("color"):
            out[el.get("name")] = (el.text or "").strip()
    return out


LIGHT = load_colors("values")
DARK = dict(LIGHT, **load_colors("values-night"))
ANDROID_COLORS = {"white": "#FFFFFFFF", "black": "#FF000000", "transparent": "#00000000"}


def resolve(value, table):
    seen = 0
    while value.startswith("@") and seen < 10:
        if value.startswith("@android:color/"):
            value = ANDROID_COLORS[value.split("/")[1]]
        else:
            value = table[value.split("/")[1]]
        seen += 1
    return value


def argb(value):
    """'#RGB' / '#RRGGBB' / '#AARRGGBB' -> (r, g, b, a) floats."""
    h = value.lstrip("#")
    if len(h) == 3:
        h = "".join(c * 2 for c in h)
    if len(h) == 6:
        h = "FF" + h
    a, r, g, b = (int(h[i:i + 2], 16) for i in (0, 2, 4, 6))
    return r, g, b, a / 255


def colorset(name):
    def comp(value):
        r, g, b, a = argb(value)
        return {"color-space": "srgb", "components": {
            "red": f"0x{r:02X}", "green": f"0x{g:02X}", "blue": f"0x{b:02X}", "alpha": f"{a:.3f}"}}
    light = resolve(LIGHT[name], LIGHT)
    dark = resolve(DARK[name], DARK)
    colors = [{"idiom": "universal", "color": comp(light)}]
    if dark != light:
        colors.append({"idiom": "universal", "color": comp(dark),
                       "appearances": [{"appearance": "luminosity", "value": "dark"}]})
    write_json(os.path.join(OUT, "Colors", f"{name}.colorset", "Contents.json"), {"colors": colors, "info": INFO})


def svg_color(value, table):
    r, g, b, a = argb(resolve(value, table))
    return f"#{r:02X}{g:02X}{b:02X}", a


def vector_to_svg(path, table, template):
    root = ET.parse(path).getroot()
    vw, vh = root.get(A + "viewportWidth"), root.get(A + "viewportHeight")
    w = float(root.get(A + "width").replace("dp", ""))
    h = float(root.get(A + "height").replace("dp", ""))
    defs, body = [], []

    def walk(el, indent):
        for child in el:
            tag = child.tag
            if tag == "group":
                tx, ty = child.get(A + "translateX", "0"), child.get(A + "translateY", "0")
                rot = child.get(A + "rotation", "0")
                sx, sy = child.get(A + "scaleX", "1"), child.get(A + "scaleY", "1")
                px, py = child.get(A + "pivotX", "0"), child.get(A + "pivotY", "0")
                t = (f"translate({tx} {ty}) translate({px} {py}) rotate({rot}) scale({sx} {sy}) "
                     f"translate({-float(px)} {-float(py)})")
                body.append(f'{indent}<g transform="{t}">')
                walk(child, indent + "  ")
                body.append(f"{indent}</g>")
            elif tag == "path":
                attrs = [f'd="{child.get(A + "pathData")}"']
                fill = child.get(A + "fillColor")
                gradient = None
                for attr in child:
                    if attr.get("name") == "android:fillColor":
                        gradient = attr.find("gradient")
                if template:
                    attrs.append('fill="#000000"')
                elif gradient is not None:
                    gid = f"g{len(defs)}"
                    stops = "".join(
                        f'<stop offset="{it.get(A + "offset")}" stop-color="{svg_color(it.get(A + "color"), table)[0]}" '
                        f'stop-opacity="{svg_color(it.get(A + "color"), table)[1]:.3f}"/>' for it in gradient.findall("item"))
                    defs.append(
                        f'<linearGradient id="{gid}" gradientUnits="userSpaceOnUse" x1="{gradient.get(A + "startX")}" '
                        f'y1="{gradient.get(A + "startY")}" x2="{gradient.get(A + "endX")}" y2="{gradient.get(A + "endY")}">'
                        f"{stops}</linearGradient>")
                    attrs.append(f'fill="url(#{gid})"')
                elif fill:
                    c, a = svg_color(fill, table)
                    attrs.append(f'fill="{c}" fill-opacity="{a * float(child.get(A + "fillAlpha", "1")):.3f}"')
                else:
                    attrs.append('fill="none"')
                stroke = child.get(A + "strokeColor")
                if stroke:
                    c, a = ("#000000", 1.0) if template else svg_color(stroke, table)
                    attrs.append(f'stroke="{c}" stroke-opacity="{a:.3f}" stroke-width="{child.get(A + "strokeWidth", "1")}"')
                    cap = child.get(A + "strokeLineCap")
                    if cap:
                        attrs.append(f'stroke-linecap="{cap}"')
                if child.get(A + "fillType") == "evenOdd":
                    attrs.append('fill-rule="evenodd"')
                body.append(f'{indent}<path {" ".join(attrs)}/>')

    walk(root, "  ")
    d = f"<defs>{''.join(defs)}</defs>\n" if defs else ""
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{w:g}" height="{h:g}" viewBox="0 0 {vw} {vh}">\n'
            f"{d}" + "\n".join(body) + "\n</svg>\n")


def vector_imageset(name):
    src = os.path.join(RES, "drawable", name + ".xml")
    folder = os.path.join(OUT, "Drawables", f"{name}.imageset")
    os.makedirs(folder, exist_ok=True)
    template = name.startswith("ic_")
    images = []
    light = vector_to_svg(src, LIGHT, template)
    with open(os.path.join(folder, f"{name}.svg"), "w") as f:
        f.write(light)
    images.append({"idiom": "universal", "filename": f"{name}.svg"})
    if not template:
        dark = vector_to_svg(src, DARK, template)
        if dark != light:
            with open(os.path.join(folder, f"{name}_dark.svg"), "w") as f:
                f.write(dark)
            images.append({"idiom": "universal", "filename": f"{name}_dark.svg",
                           "appearances": [{"appearance": "luminosity", "value": "dark"}]})
    props = {"preserves-vector-representation": True}
    if template:
        props["template-rendering-intent"] = "template"
    write_json(os.path.join(folder, "Contents.json"), {"images": images, "info": INFO, "properties": props})


def raster_imageset(src, name):
    folder = os.path.join(OUT, "Drawables", f"{name}.imageset")
    os.makedirs(folder, exist_ok=True)
    Image.open(src).save(os.path.join(folder, f"{name}.png"))
    write_json(os.path.join(folder, "Contents.json"),
               {"images": [{"idiom": "universal", "filename": f"{name}.png"}], "info": INFO})


def app_icon():
    bg = Image.open(os.path.join(RES, "drawable", "ic_launcher_background.png")).convert("RGBA")
    fg = Image.open(os.path.join(RES, "drawable", "ic_launcher_foreground.png")).convert("RGBA")
    icon = Image.alpha_composite(bg.resize((1024, 1024)), fg.resize((1024, 1024))).convert("RGB")
    folder = os.path.join(OUT, "AppIcon.appiconset")
    os.makedirs(folder, exist_ok=True)
    icon.save(os.path.join(folder, "AppIcon.png"))
    write_json(os.path.join(folder, "Contents.json"), {"images": [
        {"filename": "AppIcon.png", "idiom": "universal", "platform": "ios", "size": "1024x1024"}], "info": INFO})
    # 启动页与「关于」用的前景徽记
    raster_imageset(os.path.join(RES, "drawable", "ic_launcher_foreground.png"), "brand_mark")


def main():
    if os.path.isdir(OUT):
        shutil.rmtree(OUT)
    write_json(os.path.join(OUT, "Contents.json"), {"info": INFO})
    for group in ("Colors", "Drawables"):
        write_json(os.path.join(OUT, group, "Contents.json"), {"info": INFO, "properties": {"provides-namespace": False}})
    for name in sorted(LIGHT):
        colorset(name)
    write_json(os.path.join(OUT, "AccentColor.colorset", "Contents.json"), {"colors": [
        {"idiom": "universal", "color": {"color-space": "srgb", "components": {
            "red": "0xC0", "green": "0x39", "blue": "0x2B", "alpha": "1.000"}}}], "info": INFO})
    vectors = sorted(n[:-4] for n in os.listdir(os.path.join(RES, "drawable"))
                     if re.match(r"(ic|ink)_.*\.xml$", n) and not n.startswith("ic_launcher"))
    for name in vectors:
        vector_imageset(name)
    for name in sorted(os.listdir(os.path.join(RES, "drawable-nodpi"))):
        raster_imageset(os.path.join(RES, "drawable-nodpi", name), os.path.splitext(name)[0])
    app_icon()
    print(f"wrote {len(LIGHT)} colors, {len(vectors)} vectors -> {OUT}")


if __name__ == "__main__":
    main()

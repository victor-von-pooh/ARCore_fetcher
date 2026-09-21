"""SVG (draw.py が出す限定サブセット) を Android VectorDrawable に変換する。

VectorDrawable は <path> しか持てないので、rect / circle / line / polygon /
polyline はすべて pathData に落とす。text と stroke-dasharray は使えないため、
図では最初から使っていない。

使い方:
    python3 convert.py <res/drawable のパス> [<確認用 SVG の出力先>]
"""
import math
import os
import sys
import xml.etree.ElementTree as ET

import draw
import figures

# colors.xml の figure_* と対応させる
COLOR_RES = {
    draw.INK: "@color/figure_ink",
    draw.DIM: "@color/figure_dim",
    draw.ACCENT: "@color/figure_accent",
    draw.GOOD: "@color/figure_good",
    draw.BAD: "@color/figure_bad",
    draw.SURFACE: "@color/figure_surface",
}


def f(v):
    """余計な小数を落とす。"""
    v = round(float(v), 2)
    return str(int(v)) if v == int(v) else str(v)


def arc(rx, ry, large, sweep, x, y):
    return f"A{f(rx)},{f(ry)} 0 {large},{sweep} {f(x)},{f(y)}"


def to_path(el):
    """1 要素を pathData 文字列にする。"""
    t = el.tag.split("}")[-1]
    g = lambda k, d=0: float(el.get(k, d))

    if t == "path":
        return el.get("d")
    if t == "line":
        return f"M{f(g('x1'))},{f(g('y1'))} L{f(g('x2'))},{f(g('y2'))}"
    if t in ("polygon", "polyline"):
        pts = [p.split(",") for p in el.get("points").split()]
        d = "M" + " L".join(f"{f(x)},{f(y)}" for x, y in pts)
        return d + (" Z" if t == "polygon" else "")
    if t == "circle":
        cx, cy, r = g("cx"), g("cy"), g("r")
        return (f"M{f(cx - r)},{f(cy)} " + arc(r, r, 1, 0, cx + r, cy) + " "
                + arc(r, r, 1, 0, cx - r, cy) + " Z")
    if t == "rect":
        x, y, w, h, rx = g("x"), g("y"), g("width"), g("height"), g("rx")
        if not rx:
            return f"M{f(x)},{f(y)} L{f(x + w)},{f(y)} L{f(x + w)},{f(y + h)} L{f(x)},{f(y + h)} Z"
        rx = min(rx, w / 2, h / 2)
        return (f"M{f(x + rx)},{f(y)} L{f(x + w - rx)},{f(y)} "
                + arc(rx, rx, 0, 1, x + w, y + rx)
                + f" L{f(x + w)},{f(y + h - rx)} " + arc(rx, rx, 0, 1, x + w - rx, y + h)
                + f" L{f(x + rx)},{f(y + h)} " + arc(rx, rx, 0, 1, x, y + h - rx)
                + f" L{f(x)},{f(y + rx)} " + arc(rx, rx, 0, 1, x + rx, y) + " Z")
    raise SystemExit(f"未対応の要素: {t}")


CAPS = {"round": "round", "butt": "butt", "square": "square"}


def attrs_of(el):
    """描画属性を VectorDrawable の属性名に写す。"""
    out = {}
    fill, stroke = el.get("fill", "none"), el.get("stroke", "none")
    if fill != "none":
        out["android:fillColor"] = COLOR_RES.get(fill, fill)
        if el.get("fill-opacity"):
            out["android:fillAlpha"] = f(el.get("fill-opacity"))
    if stroke != "none" and float(el.get("stroke-width", 0)) > 0:
        out["android:strokeColor"] = COLOR_RES.get(stroke, stroke)
        out["android:strokeWidth"] = f(el.get("stroke-width"))
        if el.get("stroke-opacity"):
            out["android:strokeAlpha"] = f(el.get("stroke-opacity"))
        if el.get("stroke-linecap"):
            out["android:strokeLineCap"] = CAPS[el.get("stroke-linecap")]
        if el.get("stroke-linejoin"):
            out["android:strokeLineJoin"] = el.get("stroke-linejoin")
    return out


# 変換後の pathData を目で確かめるため、VectorDrawable の属性を SVG に戻す
SVG_ATTR = {
    "android:fillColor": "fill",
    "android:fillAlpha": "fill-opacity",
    "android:strokeColor": "stroke",
    "android:strokeAlpha": "stroke-opacity",
    "android:strokeWidth": "stroke-width",
    "android:strokeLineCap": "stroke-linecap",
    "android:strokeLineJoin": "stroke-linejoin",
}
RES_COLOR = {v: k for k, v in COLOR_RES.items()}


def svg_attrs(a):
    out = []
    for k, v in a.items():
        out.append(f'{SVG_ATTR[k]}="{RES_COLOR.get(v, v)}"')
    if "android:fillColor" not in a:
        out.append('fill="none"')
    return " ".join(out)


HEADER = '''<?xml version="1.0" encoding="utf-8"?>
<!--
    tools/figures/figures.py から生成。直接編集しないこと。
    再生成: cd tools/figures && python3 convert.py ../../app/src/main/res/drawable
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="320dp"
    android:height="240dp"
    android:viewportWidth="320"
    android:viewportHeight="240">
'''


def convert(name, body):
    root = ET.fromstring(draw.svg(body))
    if root.find(".//{*}g") is not None:
        raise SystemExit(f"{name}: <g> は未対応（図では使っていないはず）")
    paths = []
    flat = []
    for el in root:
        a = attrs_of(el)
        if not a:
            continue
        d = to_path(el)
        lines = "\n".join(f'        {k}="{v}"' for k, v in a.items())
        paths.append(f'    <path\n        android:pathData="{d}"\n{lines} />')
        flat.append(f'<path d="{d}" {svg_attrs(a)}/>')
    return HEADER + "\n".join(paths) + "\n</vector>\n", "".join(flat)


def main():
    out_dir = sys.argv[1]
    os.makedirs(out_dir, exist_ok=True)
    flats = {}
    for name, fn in figures.FIGURES.items():
        xml, flat = convert(name, fn())
        open(os.path.join(out_dir, name + ".xml"), "w").write(xml)
        flats[name] = flat
        print(f"{name}.xml  {len(xml):>6} bytes")
    if len(sys.argv) > 2:
        # 変換が忠実かを目で確かめるため、path だけに落とした SVG も出す
        cols, gap = 2, 14
        W, H = draw.W, draw.H
        rows = (len(flats) + cols - 1) // cols
        body = [draw.rect(0, 0, cols * (W + gap) + gap, rows * (H + gap) + gap, fill="#0D1117")]
        for i, (n, fl) in enumerate(flats.items()):
            x = gap + (i % cols) * (W + gap)
            y = gap + (i // cols) * (H + gap)
            body.append(f'<g transform="translate({x},{y})">'
                        + draw.rect(0, 0, W, H, fill=draw.BG, rx=10) + fl + "</g>")
        open(sys.argv[2], "w").write(
            f'<svg xmlns="http://www.w3.org/2000/svg" width="{cols*(W+gap)+gap}" '
            f'height="{rows*(H+gap)+gap}">' + "".join(body) + "</svg>")


if __name__ == "__main__":
    main()

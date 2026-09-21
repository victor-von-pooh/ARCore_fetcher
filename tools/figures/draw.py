"""図の部品。座標系は 320x240 (4:3) で固定。"""

W, H = 320, 240

# ダークテーマ前提の配色。colors.xml の figure_* と同じ値にすること。
INK     = "#E6E9EF"   # 主線
DIM     = "#7C8798"   # 補助・背景
ACCENT  = "#8AB4F8"   # カメラ・姿勢
GOOD    = "#7FD1A0"   # 良い例
BAD     = "#F2857F"   # 悪い例
SURFACE = "#2A3442"   # 面・箱の塗り
BG      = "#161C26"   # プレビュー用。書き出す図は背景を持たない


def rect(x, y, w, h, fill="none", stroke="none", sw=2, rx=0, alpha=None):
    a = f' fill-opacity="{alpha}"' if alpha is not None and fill != "none" else ""
    r = f' rx="{rx}"' if rx else ""
    return (f'<rect x="{x}" y="{y}" width="{w}" height="{h}"{r} fill="{fill}" '
            f'stroke="{stroke}" stroke-width="{sw}"{a}/>')


def circle(cx, cy, r, fill="none", stroke="none", sw=2, alpha=None):
    a = f' fill-opacity="{alpha}"' if alpha is not None and fill != "none" else ""
    return (f'<circle cx="{cx}" cy="{cy}" r="{r}" fill="{fill}" '
            f'stroke="{stroke}" stroke-width="{sw}"{a}/>')


def line(x1, y1, x2, y2, stroke=INK, sw=2, cap="round", alpha=None):
    a = f' stroke-opacity="{alpha}"' if alpha is not None else ""
    return (f'<line x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" stroke="{stroke}" '
            f'stroke-width="{sw}" stroke-linecap="{cap}"{a}/>')


def poly(points, fill="none", stroke="none", sw=2, alpha=None, closed=True):
    tag = "polygon" if closed else "polyline"
    pts = " ".join(f"{x},{y}" for x, y in points)
    a = f' fill-opacity="{alpha}"' if alpha is not None and fill != "none" else ""
    return (f'<{tag} points="{pts}" fill="{fill}" stroke="{stroke}" '
            f'stroke-width="{sw}" stroke-linejoin="round"{a}/>')


def path(d, fill="none", stroke="none", sw=2, cap="round", alpha=None):
    a = f' stroke-opacity="{alpha}"' if alpha is not None else ""
    return (f'<path d="{d}" fill="{fill}" stroke="{stroke}" stroke-width="{sw}" '
            f'stroke-linecap="{cap}" stroke-linejoin="round"{a}/>')


def group(children, tx=0, ty=0, rot=None, cx=0, cy=0):
    """translate と rotate だけ使う（VectorDrawable の <group> に写せる範囲）。"""
    t = []
    if tx or ty:
        t.append(f"translate({tx},{ty})")
    if rot:
        t.append(f"rotate({rot},{cx},{cy})")
    attr = f' transform="{" ".join(t)}"' if t else ""
    return f'<g{attr}>' + "".join(children) + "</g>"


# ---------------------------------------------------------------- 記号

def arrow(x1, y1, x2, y2, color=INK, sw=3, head=9, filled=True):
    """線分と矢尻。矢尻は marker が使えないので三角形を実体で描く。"""
    import math
    ang = math.atan2(y2 - y1, x2 - x1)
    # 線は矢尻の付け根で止める（先端が太らないように）
    bx, by = x2 - head * math.cos(ang), y2 - head * math.sin(ang)
    out = [line(round(x1, 1), round(y1, 1), round(bx, 1), round(by, 1), color, sw)]
    w = head * 0.45
    p = [(x2, y2),
         (bx - w * math.sin(ang), by + w * math.cos(ang)),
         (bx + w * math.sin(ang), by - w * math.cos(ang))]
    p = [(round(a, 1), round(b, 1)) for a, b in p]
    out.append(poly(p, fill=color if filled else "none", stroke=color, sw=sw * 0.8))
    return "".join(out)


def check(cx, cy, r=12, color=GOOD):
    """○ の中にチェック。"""
    return (circle(cx, cy, r, fill=color, alpha=0.18) +
            circle(cx, cy, r, stroke=color, sw=2) +
            path(f"M{cx - r * 0.45},{cy} L{cx - r * 0.1},{cy + r * 0.35} "
                 f"L{cx + r * 0.5},{cy - r * 0.4}", stroke=color, sw=2.6))


def cross(cx, cy, r=12, color=BAD):
    """○ の中にバツ。"""
    d = r * 0.42
    return (circle(cx, cy, r, fill=color, alpha=0.18) +
            circle(cx, cy, r, stroke=color, sw=2) +
            line(cx - d, cy - d, cx + d, cy + d, color, 2.6) +
            line(cx + d, cy - d, cx - d, cy + d, color, 2.6))


def phone(cx, cy, w=26, h=46, color=INK, sw=2.4, rot=None):
    """端末。背面カメラの丸を入れて向きが分かるようにする。"""
    body = (rect(cx - w / 2, cy - h / 2, w, h, fill=SURFACE, stroke=color, sw=sw, rx=5) +
            circle(cx, cy - h / 2 + 9, 3.4, fill=color) +
            line(cx - w * 0.22, cy + h / 2 - 7, cx + w * 0.22, cy + h / 2 - 7, color, 2))
    return group([body], rot=rot, cx=cx, cy=cy) if rot else body


def camera_mark(cx, cy, ang_deg, color=ACCENT, size=13, alpha=0.30):
    """撮影位置と視野。頂点が撮影位置、開いた側が視線方向。"""
    import math
    a = math.radians(ang_deg)
    half = math.radians(24)
    L = size * 2.1
    p = [(cx, cy),
         (cx + L * math.cos(a - half), cy + L * math.sin(a - half)),
         (cx + L * math.cos(a + half), cy + L * math.sin(a + half))]
    p = [(round(x, 1), round(y, 1)) for x, y in p]
    return (poly(p, fill=color, alpha=alpha) +
            poly(p, stroke=color, sw=1.6) +
            circle(round(cx, 1), round(cy, 1), 3.2, fill=color))


def photo_card(x, y, w=64, h=46, color=INK, sw=2.2, variant=0):
    """写真 1 枚。

    中身は特定の被写体を描かない抽象的な図形にする。variant で配置を少しずらし、
    「別の視点から撮った別の写真」であることを示す。
    """
    v = [(0.28, 0.32, 0.12, 0.45, 0.70, 0.52, 0.76, 0.94),
         (0.72, 0.28, 0.06, 0.30, 0.54, 0.40, 0.66, 0.88),
         (0.22, 0.26, 0.24, 0.58, 0.86, 0.04, 0.26, 0.48)][variant % 3]
    sx, sy, a1, a2, a3, b1, b2, b3 = v
    return (rect(x, y, w, h, fill=SURFACE, stroke=color, sw=sw, rx=4) +
            circle(x + w * sx, y + h * sy, 4.5, fill=DIM) +
            poly([(x + w * a1, y + h - 7), (x + w * a2, y + h * 0.48),
                  (x + w * a3, y + h - 7)], fill=DIM, alpha=0.85) +
            poly([(x + w * b1, y + h - 7), (x + w * b2, y + h * 0.62),
                  (x + w * b3, y + h - 7)], fill=DIM, alpha=0.55))


def dots(points, color=ACCENT, r=2.2, alpha=1.0):
    """特徴点。"""
    return "".join(circle(x, y, r, fill=color, alpha=alpha) for x, y in points)


def svg(body, bg=None):
    b = rect(0, 0, W, H, fill=bg) if bg else ""
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" '
            f'viewBox="0 0 {W} {H}">{b}{body}</svg>')


def ground_grid(x, y, w, h, rows=4, cols=5, color=DIM, alpha=0.22):
    """奥行きのある面。カメラが「空間に置かれている」ことを示す背景。"""
    out = []
    for i in range(rows + 1):
        t = i / rows
        inset = w * 0.18 * (1 - t)
        out.append(line(round(x + inset, 1), round(y + h * t, 1),
                        round(x + w - inset, 1), round(y + h * t, 1),
                        color, 1.1, alpha=alpha))
    for j in range(cols + 1):
        u = j / cols
        out.append(line(round(x + w * 0.18 + (w * 0.64) * u, 1), y,
                        round(x + w * u, 1), y + h, color, 1.1, alpha=alpha))
    return "".join(out)


def folder(x, y, w=46, h=36, color=INK, sw=2.2):
    """端末のフォルダ。"""
    return path(f"M{x},{y + 9} l0,{h - 9} l{w},0 l0,{-h + 15} l{-w * 0.46:.1f},0 "
                f"l-6,-6 l{-w * 0.42:.1f},0 z", fill=SURFACE, stroke=color, sw=sw)


def share_glyph(cx, cy, r=17, color=INK, sw=2.2):
    """共有アイコン。文字なしで意味が通る数少ない記号。"""
    a = (cx + r * 0.85, cy - r * 0.78)
    b = (cx - r * 0.78, cy)
    c = (cx + r * 0.85, cy + r * 0.78)
    return (line(b[0], b[1], a[0], a[1], color, sw) +
            line(b[0], b[1], c[0], c[1], color, sw) +
            circle(a[0], a[1], 5.6, fill=SURFACE, stroke=color, sw=sw) +
            circle(b[0], b[1], 5.6, fill=SURFACE, stroke=color, sw=sw) +
            circle(c[0], c[1], 5.6, fill=SURFACE, stroke=color, sw=sw))


def trash(cx, cy, w=40, h=44, color=BAD, sw=2.4):
    """ゴミ箱。"""
    x, y = cx - w / 2, cy - h / 2
    return (line(x - 4, y + 9, x + w + 4, y + 9, color, sw) +
            path(f"M{cx - 7},{y + 9} l2,-6 l10,0 l2,6", stroke=color, sw=sw) +
            path(f"M{x + 3},{y + 9} l4,{h - 9} l{w - 14},0 l4,{-h + 9} z",
                 fill=SURFACE, stroke=color, sw=sw) +
            line(cx - 7, y + 19, cx - 6, y + h - 8, color, 1.8) +
            line(cx + 7, y + 19, cx + 6, y + h - 8, color, 1.8))


def zip_box(cx, cy, w=58, h=54, color=INK, sw=2.4):
    """ZIP。留め具で「まとめたもの」を表す。"""
    x, y = cx - w / 2, cy - h / 2
    out = [rect(x, y, w, h, fill=SURFACE, stroke=color, sw=sw, rx=5)]
    for i in range(4):
        yy = y + 16 + i * 9
        out.append(line(cx - 5, yy, cx + 5, yy, color, 2.4))
    out.append(rect(cx - 8, y + 5, 16, 10, fill=SURFACE, stroke=color, sw=1.8, rx=2))
    return "".join(out)


def blob(cx, cy, r=30, color=DIM, sw=2.2, n=6, rot=0):
    """被写体の代わりに置く中立な立体。特定のカテゴリを示唆しない。"""
    import math
    p = [(round(cx + r * math.cos(math.radians(rot + i * 360 / n)), 1),
          round(cy + r * math.sin(math.radians(rot + i * 360 / n)), 1)) for i in range(n)]
    return poly(p, fill=color, stroke=color, sw=sw, alpha=0.30) + poly(p, stroke=color, sw=sw)

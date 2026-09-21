"""使い方ページの図 8 枚。

図に文字は入れない。説明はページ側の TextView に置く。
（画像に焼いた文言は翻訳もアクセシビリティ対応もできないため）
"""
import math
from draw import *


def fig1_what_is_recorded():
    """1 枚の写真と、それを撮ったカメラの位置・向きが 1 対 1 で紐づく。

    カメラの向きをあえてバラバラにしてある。1 点に集めると
    「物体を取り囲んで撮るアプリ」に見えてしまい、用途を狭める。
    """
    out = [ground_grid(146, 30, 168, 186)]
    ys = [26, 97, 168]
    cams = [(232, 48, 168), (262, 128, 200), (222, 196, 250)]
    for (cy, (mx, my, ang)) in zip(ys, cams):
        out.append(line(96, cy + 23, mx - 6, my, DIM, 1.6, alpha=0.75))
    for i, cy in enumerate(ys):
        out.append(photo_card(26, cy, variant=i))
    for (mx, my, ang) in cams:
        out.append(camera_mark(mx, my, ang, size=15))
    return "".join(out)


def fig2_start_tracking():
    """端末をゆっくり動かすと、まわりの面から特徴点が拾われていく。"""
    out = []
    # 床面
    out.append(poly([(18, 232), (302, 232), (250, 150), (70, 150)],
                    fill=SURFACE, alpha=0.85))
    out.append(poly([(18, 232), (302, 232), (250, 150), (70, 150)],
                    stroke=DIM, sw=1.6))
    pts = [(96, 176), (128, 168), (163, 160), (198, 166), (232, 176),
           (84, 200), (122, 192), (160, 186), (203, 194), (243, 204),
           (70, 222), (112, 216), (158, 212), (208, 218), (258, 226),
           (140, 176), (180, 200), (100, 188), (226, 190), (146, 222)]
    out.append(dots(pts, ACCENT, 2.6, 0.9))
    # 端末と往復の動き
    out.append(phone(160, 86))
    out.append(arrow(120, 86, 78, 86, INK, 3, 10))
    out.append(arrow(200, 86, 242, 86, INK, 3, 10))
    out.append(path("M84 108 Q160 134 236 108", stroke=DIM, sw=2))
    return "".join(out)


def fig3_wait_for_convergence():
    """収束前は、実際の向きと記録された向きが食い違う。

    角度差そのものが情報なので、2 本の矢印の開きを大きく取る。
    白＝実際に向いている方向、青＝記録された方向。
    矢印が枠からはみ出すと角度が読めなくなるので、長さは枠内に収める。
    """
    def panel(ox, recorded_ang, badge, show_gap):
        out = [rect(ox, 24, 142, 192, fill=SURFACE, stroke=DIM, sw=1.4, rx=8, alpha=0.5)]
        cx, cy = ox + 71, 185
        base_y = cy - 22
        L = 100
        actual = math.radians(-60)
        rec = math.radians(recorded_ang)
        if show_gap:
            # 2 本の開きを弧で示す
            r = 46
            out.append(path(
                f"M{cx + r * math.cos(actual):.1f},{base_y + r * math.sin(actual):.1f} "
                f"A{r},{r} 0 0,0 "
                f"{cx + r * math.cos(rec):.1f},{base_y + r * math.sin(rec):.1f}",
                stroke=BAD, sw=2, alpha=0.9))
        out.append(phone(cx, cy, 22, 38))
        out.append(arrow(cx, base_y, cx + L * math.cos(actual), base_y + L * math.sin(actual),
                         INK, 3.4, 11))
        out.append(arrow(cx, base_y, cx + L * math.cos(rec), base_y + L * math.sin(rec),
                         ACCENT, 3.4, 11, filled=False))
        out.append(badge(ox + 120, 44))
        return "".join(out)

    return panel(14, -130, cross, True) + panel(164, -66, check, False)


def fig4_keep_tracking():
    """トラッキングが外れる状況 3 つと、外れない状況 1 つ。

    4 コマとも「端末 ＋ 面 ＋ 特徴点」で構図をそろえる。違いを特徴点の量に
    集約すると、トラッキングが何に依存しているかが図から読める。
    """
    def cell(ox, oy, plane_alpha, pts, pt_alpha, extra, badge):
        out = [rect(ox, oy, 150, 108, fill=SURFACE, stroke=DIM, sw=1.4, rx=8, alpha=0.5)]
        out.append(poly([(ox + 16, oy + 96), (ox + 134, oy + 96),
                         (ox + 114, oy + 62), (ox + 36, oy + 62)],
                        fill=DIM, alpha=plane_alpha))
        out.append(dots([(ox + x, oy + y) for x, y in pts], ACCENT, 2.3, pt_alpha))
        out.extend(extra(ox, oy))
        out.append(badge(ox + 130, oy + 18, 11))
        return "".join(out)

    many = [(40, 74), (62, 68), (86, 72), (108, 68), (30, 86), (54, 82),
            (80, 88), (104, 84), (126, 80), (46, 92), (94, 92), (118, 90)]
    few = [(62, 76), (96, 86)]

    def dim_light(ox, oy):
        # 光量が足りないことを、暗い天体と短い光線で示す
        return [circle(ox + 30, oy + 26, 8, stroke=DIM, sw=1.8, alpha=0.55),
                line(ox + 30, oy + 12, ox + 30, oy + 16, DIM, 1.6, alpha=0.4),
                line(ox + 44, oy + 26, ox + 40, oy + 26, DIM, 1.6, alpha=0.4),
                phone(ox + 75, oy + 40, 20, 34, DIM, 1.8)]

    def blurred(ox, oy):
        return [phone(ox + 56, oy + 40, 20, 34, DIM, 1.6),
                phone(ox + 68, oy + 40, 20, 34, DIM, 2.0),
                phone(ox + 80, oy + 40, 20, 34, INK, 2.4),
                arrow(ox + 98, oy + 44, ox + 126, oy + 44, BAD, 2.8, 9)]

    def glossy(ox, oy):
        # 無地で光る面。特徴点が拾えない
        return [line(ox + 44, oy + 92, ox + 88, oy + 64, DIM, 6, alpha=0.5),
                line(ox + 62, oy + 94, ox + 100, oy + 70, DIM, 4, alpha=0.3),
                phone(ox + 75, oy + 36, 20, 34, INK, 2.2)]

    def slow(ox, oy):
        return [phone(ox + 62, oy + 36, 20, 34, INK, 2.2),
                arrow(ox + 80, oy + 36, ox + 104, oy + 36, GOOD, 2.6, 8)]

    return (cell(12, 8, 0.10, few, 0.22, dim_light, cross) +
            cell(158, 8, 0.20, many, 0.45, blurred, cross) +
            cell(12, 124, 0.26, few, 0.30, glossy, cross) +
            cell(158, 124, 0.24, many, 0.95, slow, check))


def fig5_depends_on_purpose():
    """撮り方は用途で決まる。3 通り並べて「これは一例」を図に言わせる。

    1 つの被写体を回り込む図だけを出すと、そういうアプリだと誤解される。
    """
    def panel(ox, inner):
        return (rect(ox, 34, 96, 172, fill=SURFACE, stroke=DIM, sw=1.4, rx=8, alpha=0.45)
                + "".join(inner(ox + 48, 120)))

    def around_object(cx, cy):
        out = [blob(cx, cy, 15, DIM, 2, 6, 10)]
        for i in range(8):
            a = i * 45 + 10
            r = 33
            out.append(camera_mark(cx + r * math.cos(math.radians(a)),
                                   cy + r * math.sin(math.radians(a)),
                                   a + 180, size=8, alpha=0.22))
        return out

    def along_wall(cx, cy):
        out = [rect(cx + 24, cy - 58, 11, 116, fill=DIM, stroke=DIM, sw=2, alpha=0.30)]
        for i in range(5):
            out.append(camera_mark(cx - 22, cy - 48 + i * 24, 0, size=8, alpha=0.22))
        return out

    def inside_room(cx, cy):
        out = [rect(cx - 36, cy - 58, 72, 116, stroke=DIM, sw=2, rx=4)]
        for i in range(6):
            a = i * 60 + 20
            out.append(camera_mark(cx + 9 * math.cos(math.radians(a)),
                                   cy + 9 * math.sin(math.radians(a)),
                                   a, size=8, alpha=0.22))
        return out

    return (panel(8, around_object) + panel(112, along_wall) + panel(216, inside_room))


def fig6_orbit_example():
    """例: ある物体を 3 次元再構成する場合。

    隣り合う画角が大きく重なることが要点なので、扇を被写体まで届かせて
    重なりを面で見せる。届かない長さだと図が「ただ囲んでいる」だけになる。
    """
    cx, cy = 160, 124
    out = []
    n, r = 10, 92
    # 手前の 2 台だけ扇を長くして、重なりを見せる
    for i in range(n):
        a = i * 360 / n - 90
        px = cx + r * math.cos(math.radians(a))
        py = cy + r * math.sin(math.radians(a))
        if i in (0, 1):
            continue
        out.append(camera_mark(px, py, a + 180, size=15, alpha=0.10, color=DIM))
    out.append(blob(cx, cy, 30, DIM, 2.6, 6, 12))
    for i in (0, 1):
        a = i * 360 / n - 90
        px = cx + r * math.cos(math.radians(a))
        py = cy + r * math.sin(math.radians(a))
        out.append(camera_mark(px, py, a + 180, size=44, alpha=0.16, color=ACCENT))
    # 回り込む向き
    out.append(path(f"M{cx - 20},{cy - 118} A124,124 0 0,1 {cx + 116},{cy - 40}",
                    stroke=GOOD, sw=2.6))
    out.append(arrow(cx + 108, cy - 54, cx + 118, cy - 32, GOOD, 2.6, 10))
    return "".join(out)


def fig7_export_and_take_out():
    """写真と JSON が ZIP にまとまり、端末に保存するか共有する。"""
    out = []
    for i in range(3):
        out.append(photo_card(14 + i * 7, 52 + i * 13, 52, 38, INK, 1.9, variant=i))
    # transforms.json
    out.append(path("M22,152 l0,44 l34,0 l0,-34 l-10,-10 z",
                    fill=SURFACE, stroke=ACCENT, sw=2.2))
    out.append(path("M46,152 l0,10 l10,0", stroke=ACCENT, sw=2.2))
    for i in range(3):
        out.append(line(29, 170 + i * 8, 49, 170 + i * 8, ACCENT, 1.8))
    # 写真と JSON をまとめて 1 本の流れにする
    out.append(path("M74,84 l12,0 l0,86 l-12,0", stroke=DIM, sw=2))
    out.append(arrow(92, 127, 122, 122, INK, 3, 10))
    out.append(zip_box(158, 120))
    out.append(arrow(192, 106, 228, 74, INK, 3, 10))
    out.append(arrow(192, 134, 228, 166, INK, 3, 10))
    out.append(folder(238, 44))
    out.append(share_glyph(262, 180))
    return "".join(out)


def fig8_storage():
    """1 回の撮影は 2 つ分の容量を使う。消すときは両方まとめて消える。"""
    out = []
    # 展開済みフォルダ（大）
    out.append(folder(26, 34, 84, 62))
    for i in range(6):
        out.append(rect(36 + (i % 3) * 22, 58 + (i // 3) * 18, 17, 13,
                        fill=DIM, stroke=DIM, sw=1.2, rx=2, alpha=0.5))
    # ZIP（小）
    out.append(zip_box(174, 66, 46, 44))
    # 2 つで 1 回分であることを括る
    out.append(path("M18,112 l0,10 l186,0 l0,-10", stroke=DIM, sw=2))
    out.append(line(111, 122, 111, 134, DIM, 2))
    out.append(arrow(111, 134, 111, 168, BAD, 3, 11))
    out.append(trash(111, 200))
    return "".join(out)


FIGURES = {
    "fig_what_is_recorded": fig1_what_is_recorded,
    "fig_start_tracking": fig2_start_tracking,
    "fig_wait_for_convergence": fig3_wait_for_convergence,
    "fig_keep_tracking": fig4_keep_tracking,
    "fig_depends_on_purpose": fig5_depends_on_purpose,
    "fig_orbit_example": fig6_orbit_example,
    "fig_export_and_take_out": fig7_export_and_take_out,
    "fig_storage": fig8_storage,
}

"""図を並べた 1 枚の SVG を書き出す（目視確認用）。"""
import sys
from draw import W, H, BG, rect, group, svg
import figures

names = sys.argv[2:] or list(figures.FIGURES)
cols = 2
rows = (len(names) + cols - 1) // cols
gap = 14
body = [rect(0, 0, cols * (W + gap) + gap, rows * (H + gap) + gap, fill="#0D1117")]
for i, n in enumerate(names):
    x = gap + (i % cols) * (W + gap)
    y = gap + (i // cols) * (H + gap)
    body.append(group([rect(0, 0, W, H, fill=BG, rx=10), figures.FIGURES[n]()], tx=x, ty=y))
sheet = (f'<svg xmlns="http://www.w3.org/2000/svg" width="{cols*(W+gap)+gap}" '
         f'height="{rows*(H+gap)+gap}" viewBox="0 0 {cols*(W+gap)+gap} {rows*(H+gap)+gap}">'
         + "".join(body) + "</svg>")
open(sys.argv[1], "w").write(sheet)

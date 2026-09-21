# 使い方ページの図

`app/src/main/res/drawable/fig_*.xml`（Android の VectorDrawable）を生成する。
図そのものを手で編集しないこと。編集するのは `figures.py`。

## 再生成

```
cd tools/figures
python3 convert.py ../../app/src/main/res/drawable
```

第 2 引数に出力先を渡すと、確認用の SVG も書き出す。
macOS なら `qlmanage -t -s 1000 -o <dir> <svg>` で PNG にして目視できる。

```
python3 convert.py ../../app/src/main/res/drawable /tmp/flat.svg
```

`preview.py` は図を並べた 1 枚の SVG を書き出す（引数で図名を絞れる）。

```
python3 preview.py /tmp/sheet.svg                      # 全部
python3 preview.py /tmp/sheet.svg fig_storage          # 一部だけ
```

## 決めごと

- **図に文字を入れない。** 画像に焼いた文言は翻訳もアクセシビリティ対応もできない。
  説明はページ側の `TextView` に置き、図は矢印・○×・記号だけで語らせる
- **被写体を描くのは 2 枚だけ。**
  `fig_depends_on_purpose` は撮り方が用途で変わることを示すために 3 通りを並べる図、
  `fig_orbit_example` はその 1 つを詳しく見せる図。後者の被写体は中立な多面体にしてある。
  特定の物（机の上の小物など）を描くと、アプリの用途が限定されているように読める
- **配色は `draw.py` の定数と `res/values/colors.xml` の `figure_*` を一致させる。**
  ダークテーマ上で読めることが前提

## VectorDrawable 側の制約

`convert.py` が `rect` / `circle` / `line` / `polygon` / `polyline` を `pathData` に落とす。
以下は使えないので、SVG の段階から使っていない。

| 使えないもの | 代わりに |
|---|---|
| `<text>` | 図に文字を入れない方針なので不要 |
| `stroke-dasharray` | 色と矢尻の塗り分けで区別する |
| `marker`（矢尻） | `draw.arrow()` が三角形を実体で描く |
| `<g>` の任意の transform | 座標を直接計算する |

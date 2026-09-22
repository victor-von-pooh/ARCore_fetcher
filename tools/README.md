# tools

## checks.py — ビルドせずにできる静的検査

```
python3 tools/checks.py
```

JDK が無い環境でもできる範囲の照合をまとめたもの。リソース参照、ViewBinding の
フィールド名、Activity の manifest 宣言、XML のパース、括弧の対応、関数呼び出しの
引数の数、定数の実在、未使用 string を見る。

定数の実在は、修飾なしで使った `SCREAMING_SNAKE` がそのファイルで定義も import も
されていないものを拾う。`val hole = radius * HOLE_RATIO` と書いて companion object に
`HOLE_RATIO` を足し忘れる類の書き忘れが、実機ビルドまで見つからないため。

**コンパイラの代わりにはならない。** 型・null 安全・API の実在・`when` の網羅性は
見ていない。定数も名前が同じファイルにあれば通すので、型違いやスコープ違いは拾えない。
変更を渡したら、最後は必ず実機でビルドを通すこと。

`callcheck.py` は呼び出しの引数だけを見る部分で、`checks.py` から呼ばれる。
単体でも走る。

## figures/ — 使い方ページの図

`figures/README.md` を参照。

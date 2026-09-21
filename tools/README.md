# tools

## checks.py — ビルドせずにできる静的検査

```
python3 tools/checks.py
```

JDK が無い環境でもできる範囲の照合をまとめたもの。リソース参照、ViewBinding の
フィールド名、Activity の manifest 宣言、XML のパース、括弧の対応、関数呼び出しの
引数の数、未使用 string を見る。

**コンパイラの代わりにはならない。** 型・null 安全・API の実在・`when` の網羅性は
見ていない。変更を渡したら、最後は必ず実機でビルドを通すこと。

`callcheck.py` は呼び出しの引数だけを見る部分で、`checks.py` から呼ばれる。
単体でも走る。

## figures/ — 使い方ページの図

`figures/README.md` を参照。

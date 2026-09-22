"""ビルドせずにできる範囲の静的検査をまとめて走らせる。

この環境では JDK を持たないことがあり、そのときコンパイラの目が使えない。
その代わりに「壊れていたら必ず落ちる」類の対応だけでも機械的に見ておく。

    python3 tools/checks.py

**コンパイラの代わりにはならない。** 型・null 安全・API の実在・when の網羅性は
見ていない。変更を渡したら、最後は必ず実機でビルドを通すこと。

見ているもの:
  1. リソース参照        R.string / R.layout / R.drawable / R.dimen と @string/... など
  2. ViewBinding         binding.<id> が対応するレイアウトに実在するか
  3. Activity の宣言     *Activity.kt が AndroidManifest に書かれているか
  4. XML                 res 配下すべてがパースできるか
  5. 括弧の対応          Kotlin の { } ( ) が閉じているか
  6. 呼び出しの引数      tools/callcheck.py に委譲
  7. 定数の実在          SCREAMING_SNAKE の参照先がそのファイルにあるか
  8. 未使用の string     （警告のみ。消し忘れの手がかり）
"""
import pathlib
import re
import subprocess
import sys
import xml.dom.minidom

ROOT = pathlib.Path(__file__).resolve().parent.parent
RES = ROOT / "app/src/main/res"
SRC = ROOT / "app/src/main/java"
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"

# 1 つの Kotlin ファイルが複数のレイアウトを束ねることがある
# （Activity 本体＋リスト行など）。binding.<id> はその和集合で照合する。
BINDINGS = {
    "MainActivity.kt": ["activity_main"],
    "TitleActivity.kt": ["activity_title"],
    "ManualActivity.kt": ["activity_manual", "item_manual_page"],
    "SavedCapturesActivity.kt": ["activity_saved_captures", "item_saved_capture"],
    "Dialogs.kt": ["dialog_export_done"],
}


def values(kind):
    out = set()
    for f in (RES / "values").glob("*.xml"):
        for n in xml.dom.minidom.parse(str(f)).getElementsByTagName(kind):
            out.add(n.getAttribute("name"))
    return out


def ids_of(layout):
    out = set()
    doc = xml.dom.minidom.parse(str(RES / "layout" / (layout + ".xml")))
    for n in doc.getElementsByTagName("*"):
        v = n.getAttribute("android:id")
        if v.startswith("@+id/"):
            out.add(v[5:])
    return out


def strip_noise(text):
    """コメントと文字列リテラルを落とす。識別子の検査を邪魔するため。"""
    text = re.sub(r"//[^\n]*", "", text)
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    text = re.sub(r'"""(?:.|\n)*?"""', '""', text)
    text = re.sub(r"'(?:\\.|[^'\\\n])'", "''", text)
    text = re.sub(r'"(?:\\.|[^"\\\n])*"', '""', text)
    return text


# SCREAMING_SNAKE の定数。3 文字以上に限って型名や 1 文字の変数を巻き込まない。
CONST_RE = re.compile(r"(?<![.\w@])([A-Z][A-Z0-9_]{2,})\b")
DECL_RE = re.compile(r"\b(?:const\s+)?va[lr]\s+([A-Z][A-Z0-9_]{2,})\b")


def undefined_constants(path):
    """
    修飾なしで使われている定数が、そのファイルで定義も import もされていない箇所。

    JDK が無い環境ではコンパイラが使えず、`HOLE_RATIO` のような書き忘れが
    実機ビルドまで見つからない。そこだけでも機械的に見る。

    **型検査ではない。** 同じファイルに名前があれば通すので、型違いや
    スコープ違いは拾えない。
    """
    text = path.read_text()
    imported = set(re.findall(r"^import\s+[\w.]*?([A-Z][A-Z0-9_]{2,})\s*$", text, re.M))
    body = strip_noise(text)
    declared = set(DECL_RE.findall(body))
    # enum の定義（`FOO,` だけの行）と when の分岐ラベルも定義側として扱う。
    declared |= set(re.findall(r"^\s*([A-Z][A-Z0-9_]{2,})\s*(?:,|\()", body, re.M))
    return sorted({n for n in CONST_RE.findall(body)
                   if n not in declared and n not in imported})


def main():
    problems, warnings = [], []
    kt = sorted(SRC.rglob("*.kt"))
    xmls = sorted((ROOT / "app/src/main").rglob("*.xml"))

    # 4. XML がパースできるか（以降の検査がこれに依存する）
    for f in xmls:
        try:
            xml.dom.minidom.parse(str(f))
        except Exception as e:
            problems.append(f"{f.name}: XML を読めない — {e}")
    if problems:
        print("\n".join(problems))
        return 1

    pools = {
        "string": values("string"), "color": values("color"),
        "style": values("style"), "dimen": values("dimen"),
        "layout": {f.stem for f in (RES / "layout").glob("*.xml")},
        "drawable": {f.stem for f in (RES / "drawable").glob("*.xml")}
                    | {f.stem for f in (RES / "mipmap").glob("*.xml")},
    }

    # 1. リソース参照
    for f in kt + xmls:
        t = f.read_text()
        for kind, pool in pools.items():
            for n in re.findall(rf"R\.{kind}\.(\w+)", t):
                if n not in pool:
                    problems.append(f"{f.name}: R.{kind}.{n} が無い")
            for n in re.findall(rf'"@{kind}/(\w+)"', t):
                # Widget.* / Theme.* はライブラリ側の style
                if n not in pool and not (kind == "style" and n.startswith(("Widget.", "Theme."))):
                    problems.append(f"{f.name}: @{kind}/{n} が無い")

    # 2. ViewBinding
    for name, layouts in BINDINGS.items():
        f = next(SRC.rglob(name))
        body = "\n".join(l for l in f.read_text().splitlines() if not l.startswith("import"))
        have = set().union(*[ids_of(l) for l in layouts]) | {"root"}
        for var in ("binding", "row"):
            for m in re.findall(rf"(?<![A-Za-z]){var}\.(\w+)", body):
                if m not in have:
                    problems.append(f"{name}: {var}.{m} が {layouts} に無い")

    # 3. Activity の宣言
    mani = MANIFEST.read_text()
    for f in kt:
        if f.stem.endswith("Activity") and f'.{f.stem}"' not in mani:
            problems.append(f"{f.stem} が AndroidManifest に無い")

    # 5. 括弧の対応
    for f in kt:
        t = f.read_text()
        t = re.sub(r"//[^\n]*", "", t)
        t = re.sub(r"/\*.*?\*/", "", t, flags=re.S)
        t = re.sub(r"'(?:\\.|[^'\\\n])'", "", t)
        t = re.sub(r'"(?:\\.|[^"\\\n])*"', "", t)
        for o, c in (("{", "}"), ("(", ")")):
            if t.count(o) != t.count(c):
                problems.append(f"{f.name}: {o}{c} の数が合わない ({t.count(o)} / {t.count(c)})")

    # 7. 定数の実在
    for f in kt:
        for name in undefined_constants(f):
            problems.append(f"{f.name}: {name} がこのファイルに無い（import も無い）")

    # 8. 未使用の string（警告）
    used = set()
    for f in kt + xmls:
        t = f.read_text()
        used |= set(re.findall(r"R\.string\.(\w+)", t))
        used |= set(re.findall(r'"@string/(\w+)"', t))
    for n in sorted(pools["string"] - used - {"app_name"}):
        warnings.append(f"string/{n} はどこからも参照されていない")

    for w in warnings:
        print(f"警告: {w}")
    if problems:
        print("\n".join(problems))
    else:
        print(f"Kotlin {len(kt)} / XML {len(xmls)} — "
              "リソース・binding・manifest・括弧・定数: 問題なし")

    # 6. 呼び出しの引数
    rc = subprocess.call([sys.executable, str(ROOT / "tools/callcheck.py")])
    return 1 if (problems or rc) else 0


if __name__ == "__main__":
    sys.exit(main())

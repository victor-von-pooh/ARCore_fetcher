"""プロジェクト内で宣言した関数の呼び出しが、引数の数を満たしているか検査する。

Kotlin を解析するわけではない。括弧の対応を見てトップレベルのカンマを数えるだけの
粗い検査で、コンパイラの代わりにはならない。狙いは 1 点だけ:

    「引数を足したのに呼び出し側を直し忘れた」を捕まえること。

実際に `Dialogs.showExportDone` に引数を足したとき、呼び出し側が trailing lambda
だったせいで別の引数に束縛され、コンパイルが通らなくなった。その再発検知用。

使い方: python3 tools/callcheck.py
"""
import pathlib
import re
import sys

SRC = pathlib.Path(__file__).resolve().parent.parent / "app/src/main/java"

# 引数の数だけ見るので、名前が衝突する標準 API は除外する
IGNORE = {"finish", "delete", "copyTo", "compressToJpeg", "close", "use", "toString"}


def mask(t):
    """コメントを空白に、文字列の中身を `_` に潰す。長さと行番号は変えない。"""
    out, i, n = list(t), 0, len(t)

    def fill(a, b, ch):
        for k in range(a, min(b, n)):
            if out[k] != "\n":
                out[k] = ch

    while i < n:
        if t.startswith("//", i):
            j = t.find("\n", i)
            j = n if j < 0 else j
            fill(i, j, " ")
            i = j
        elif t.startswith("/*", i):
            j = t.find("*/", i)
            j = n if j < 0 else j + 2
            fill(i, j, " ")
            i = j
        elif t[i] == '"':
            if t.startswith('"""', i):
                j = t.find('"""', i + 3)
                j = n if j < 0 else j + 3
            else:
                j = i + 1
                while j < n and t[j] != '"':
                    j += 2 if t[j] == "\\" else 1
                j = min(j + 1, n)
            fill(i, j, "_")   # 文字列は「1 個の引数」として残す
            i = j
        else:
            i += 1
    return "".join(out)


def match_paren(t, i):
    depth = 0
    while i < len(t):
        if t[i] in "([{":
            depth += 1
        elif t[i] in ")]}":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return -1


def split_args(s):
    depth, parts, cur = 0, [], []
    for c in s:
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        if c == "," and depth == 0:
            parts.append("".join(cur))
            cur = []
        else:
            cur.append(c)
    parts.append("".join(cur))
    return [p.strip() for p in parts if p.strip()]


def owner_at(t, pos):
    """pos の直前に現れる object / class の名前。呼び出しの受け側と突き合わせる。"""
    last = None
    for m in re.finditer(r"\b(?:object|class|interface)\s+(\w+)", t[:pos]):
        last = m.group(1)
    return last


def main():
    files = sorted(SRC.rglob("*.kt"))
    texts = {f: mask(f.read_text()) for f in files}

    decls = {}
    for f, t in texts.items():
        for m in re.finditer(r"\bfun\s+(?:<[^>]+>\s*)?(\w+)\s*\(", t):
            name = m.group(1)
            if name in IGNORE:
                continue
            op = m.end() - 1
            cl = match_paren(t, op)
            if cl < 0:
                continue
            args = split_args(t[op + 1:cl])
            req = sum(1 for a in args if "=" not in a.split(":")[-1])
            decls[name] = (req, len(args), f, owner_at(t, m.start()))

    problems = []
    for f, t in texts.items():
        for name, (req, total, dfile, owner) in decls.items():
            # 先読みは必ず先頭に置く。受け側の後ろ（= "." の直後）に置くと
            # 常に失敗し、Foo.bar(...) 形式の呼び出しが 1 件も検査されない。
            pat = r"(?<![\w.])(?:(\w+)\s*\.\s*)?" + re.escape(name) + r"\s*\("
            for m in re.finditer(pat, t):
                recv = m.group(1)
                # 受け側が宣言元と違うなら別物（標準 API との名前衝突）
                if recv is not None and recv != owner:
                    continue
                if recv is None and f != dfile:
                    continue
                if re.search(r"\bfun\s+(?:<[^>]+>\s*)?" + re.escape(name) + r"\s*$", t[:m.end() - 1]):
                    continue
                op = m.end() - 1
                cl = match_paren(t, op)
                if cl < 0:
                    continue
                args = split_args(t[op + 1:cl])
                trailing = bool(re.match(r"\s*\{", t[cl + 1:cl + 40]))
                n = len(args) + (1 if trailing else 0)
                named = any(re.match(r"\w+\s*=", a) for a in args)
                ln = t[:op].count("\n") + 1
                why = None
                if n > total:
                    why = f"引数 {n} 個 > 宣言 {total} 個"
                elif n < req and not named:
                    why = f"引数 {n} 個 < 必須 {req} 個"
                elif trailing and total > req:
                    why = (f"trailing lambda は最後の引数に付く。"
                           f"省略可能な引数が {total - req} 個あるので名前付きで渡すこと")
                if why:
                    problems.append(f"{f.name}:{ln} {name}() {why}")

    print(f"宣言 {len(decls)} 個 / ファイル {len(files)} 個を検査")
    print("\n".join(problems) if problems else "引数の数: 問題なし")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())

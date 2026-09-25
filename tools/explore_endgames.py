#!/usr/bin/env python3
import subprocess

def test_fen(fen):
    cmd = f"uci\nsetoption name EvalFile value third_party/Pikafish/src/pikafish.nnue\nisready\nposition fen {fen}\ngo movetime 1200\nquit\n"
    p = subprocess.Popen(["./third_party/Pikafish/src/pikafish"], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    out, _ = p.communicate(input=cmd)
    mate = None
    best = ""
    for line in out.splitlines():
        if "score mate" in line:
            parts = line.split()
            try:
                mate = int(parts[parts.index("mate") + 1])
            except:
                pass
        if line.startswith("bestmove"):
            parts = line.split()
            if len(parts) > 1:
                best = parts[1]
    return mate, best

puzzles = [
    # 1步杀
    ("白脸将", "4k4/4a4/9/9/9/9/9/9/R8/4K4 w - - 0 1"),
    ("闷宫杀", "2baka3/9/9/9/9/9/9/4C4/9/4K4 w - - 0 1"),
    ("挂角马1步", "3ak4/4a4/4N4/9/9/9/9/9/R8/3K5 w - - 0 1"),
    ("铁门栓1步", "3ak4/4a4/9/4C4/9/9/9/9/R8/3K5 w - - 0 1"),
    # 2步杀
    ("卧槽马2步", "3ak4/4a4/9/9/9/2N6/9/9/R8/3K5 w - - 0 1"),
    ("钓鱼马2步", "3ak4/4a4/9/9/9/3N5/9/9/R8/3K5 w - - 0 1"),
    ("夹车炮2步", "3ak4/4a4/9/9/9/9/9/9/R1C6/3K5 w - - 0 1"),
    ("马后炮2步", "3k5/4a4/9/9/9/9/3N5/9/3C5/3K5 w - - 0 1"),
    ("双车破士", "3aka3/9/9/9/9/9/9/R8/1R7/3K5 w - - 0 1"),
    # 3步杀
    ("大刀剜心3步", "3aka3/9/9/9/9/9/4R4/4C4/9/3K5 w - - 0 1"),
    ("车马炮3步", "3aka3/9/9/9/9/9/4N4/4C4/R8/3K5 w - - 0 1"),
    ("双马3步", "3ak4/4a4/9/9/9/9/9/3NN4/9/3K5 w - - 0 1"),
]

for name, fen in puzzles:
    m, b = test_fen(fen)
    print(f"{name:15s} mate={m} best={b}")

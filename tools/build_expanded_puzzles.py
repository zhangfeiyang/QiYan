#!/usr/bin/env python3
import subprocess

def test_fen(fen):
    cmd = f"uci\nisready\nposition fen {fen}\ngo movetime 400\nquit\n"
    p = subprocess.Popen(["./third_party/Pikafish/src/pikafish"],
                         stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
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

candidate_puzzles = [
    # --- 1-step candidates ---
    ("进兵绝杀", "3k5/4a4/9/4P4/9/9/9/9/9/3K5 w - - 0 1", 1),
    ("车底将", "4k4/9/9/9/9/9/9/9/4R4/4K4 w - - 0 1", 1),
    ("借帅杀", "3ak4/4a4/9/9/9/9/9/9/4R4/4K4 w - - 0 1", 1),
    ("侧翼马杀", "3ak4/4a4/3N5/9/9/9/9/9/9/3K5 w - - 0 1", 1),
    ("炮辗丹砂", "3ak4/4a4/9/9/9/9/9/9/2C6/3K5 w - - 0 1", 1),
    ("底线车杀", "3ak4/9/9/9/9/9/9/9/R8/3K5 w - - 0 1", 1),
    ("重炮点穴", "3aka3/9/9/4C4/9/9/9/9/4C4/3K5 w - - 0 1", 1),
    ("侧面虎", "3aka3/9/9/9/9/9/R8/9/1R7/3K5 w - - 0 1", 1),
    ("挂角定乾坤", "3ak4/4a4/4N4/9/9/9/9/9/R8/3K5 w - - 0 1", 1),
    ("拔簧抽杀", "3aka3/9/9/9/9/9/4N4/4R4/9/3K5 w - - 0 1", 1),
    ("车马合璧", "3aka3/9/9/9/9/9/4N4/9/R8/3K5 w - - 0 1", 1),
    ("老卒逼宫", "3aka3/9/9/4P4/9/9/9/9/4K4/9 w - - 0 1", 1),

    # --- 2-step candidates ---
    ("二鬼拍门", "3aka3/9/9/9/9/9/R8/9/2R6/3K5 w - - 0 1", 2),
    ("双车错", "4ka3/R7R/4b4/9/9/9/9/9/9/3K5 w - - 0 1", 2),
    ("双车破士", "3aka3/9/9/9/9/9/9/R8/1R7/3K5 w - - 0 1", 2),
    ("双车纵列", "3aka3/9/9/9/9/9/2R6/9/R8/3K5 w - - 0 1", 2),
    ("车马合攻", "3aka3/9/9/9/9/4N4/9/2C6/R8/3K5 w - - 0 1", 2),
    ("车炮进兵", "3aka3/9/9/9/9/4P4/9/2C6/R8/3K5 w - - 0 1", 2),
    ("双车单马", "3aka3/9/9/9/9/9/R8/2R6/4N4/3K5 w - - 0 1", 2),
    ("车后藏炮", "3aka3/9/9/9/9/9/9/2C6/2R6/3K5 w - - 0 1", 2),

    # --- 3-4 step candidates ---
    ("卧槽马", "3ak4/4a4/9/9/9/2N6/9/9/R8/3K5 w - - 0 1", 3),
    ("钓鱼马", "3ak4/4a4/9/2N6/9/9/9/9/R8/3K5 w - - 0 1", 3),
    ("挂角马3步", "3ak4/4a4/4N4/9/9/9/9/9/R8/3K5 w - - 0 1", 3),
    ("兵破敌阵", "3aka3/9/9/9/P8/9/9/4C4/R8/3K5 w - - 0 1", 3),
    ("车炮兵", "3aka3/9/9/9/9/P8/9/4C4/R8/3K5 w - - 0 1", 3),
    ("弃马穿肋", "3aka3/9/9/9/9/9/4N4/9/R8/3K5 w - - 0 1", 4),
    ("马炮辗转", "3aka3/9/9/9/4N4/9/9/2C6/9/3K5 w - - 0 1", 4),
    ("七星双车", "4ka3/R7R/9/9/9/9/9/9/9/3K5 w - - 0 1", 4),
]

valid = []
for name, fen, expected_moves in candidate_puzzles:
    mate, best = test_fen(fen)
    print(f"{name:15s} mate={mate} best={best}")
    if mate is not None and mate > 0:
        valid.append((name, fen, mate, best))

print(f"\nTotal verified forced mates: {len(valid)}")

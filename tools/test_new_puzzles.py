#!/usr/bin/env python3
import subprocess

def check_puzzle(p, fen):
    p.stdin.write(f"position fen {fen}\ngo movetime 300\n")
    p.stdin.flush()
    mate = None
    best = ""
    pv = ""
    while True:
        line = p.stdout.readline()
        if not line: break
        line = line.strip()
        if "score mate" in line:
            parts = line.split()
            try:
                mate = int(parts[parts.index("mate") + 1])
                if "pv" in parts:
                    pv_idx = parts.index("pv")
                    pv = " ".join(parts[pv_idx+1:])
            except:
                pass
        if line.startswith("bestmove"):
            parts = line.split()
            if len(parts) > 1:
                best = parts[1]
            break
    return mate, best, pv

def main():
    p = subprocess.Popen(['./third_party/Pikafish/src/pikafish'],
                         stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
    p.stdin.write("uci\nisready\n")
    p.stdin.flush()
    while True:
        line = p.stdout.readline()
        if "readyok" in line: break

    # 40 candidate classic Xiangqi endgames
    candidates = [
        # --- 1步杀拓展 ---
        ("平车绝杀", "3k5/4a4/9/9/9/9/9/9/R8/4K4 w - - 0 1"),
        ("底线照面", "4k4/4a4/9/9/9/9/9/9/R8/4K4 w - - 0 1"),
        ("进兵点穴", "3ak4/4a4/9/4P4/9/9/9/9/9/4K4 w - - 0 1"),
        ("沉底双车", "4ka3/R7R/9/9/9/9/9/9/9/3K5 w - - 0 1"),
        ("中路绝杀", "3aka3/9/9/9/9/9/9/4C4/4R4/3K5 w - - 0 1"),
        ("飞相借杀", "3aka3/9/9/9/9/9/9/4C4/R8/3K1B3 w - - 0 1"),
        ("闷头点杀", "2baka3/9/9/9/9/9/9/4C4/9/4K4 w - - 0 1"),
        ("士角炮攻", "3aka3/9/9/9/9/9/9/2C6/9/3K5 w - - 0 1"),

        # --- 2步杀拓展 ---
        ("白脸抽杀", "4k4/4a4/9/9/9/9/9/9/R7R/4K4 w - - 0 1"),
        ("弃车引将", "3ak4/4a4/9/9/9/9/9/9/R7R/3K5 w - - 0 1"),
        ("马踏连环", "3ak4/4a4/9/9/9/9/9/4N4/R8/3K5 w - - 0 1"),
        ("车炮封喉", "3aka3/9/9/9/9/9/9/4C4/R7R/3K5 w - - 0 1"),
        ("底虎出穴", "3aka3/9/9/9/9/9/R8/9/2R6/4K4 w - - 0 1"),
        ("双马盘旋", "3ak4/4a4/9/9/9/9/4N4/4N4/9/3K5 w - - 0 1"),
        ("借炮使马", "3ak4/4a4/9/9/9/9/4N4/4C4/9/3K5 w - - 0 1"),
        ("二鬼锁宫", "3aka3/9/9/9/9/9/2R4R1/9/9/3K5 w - - 0 1"),
        ("进兵逼宫", "3aka3/9/9/9/9/9/4P4/4C4/R8/3K5 w - - 0 1"),
        ("车底点将", "3aka3/9/9/9/9/9/9/9/R1R6/3K5 w - - 0 1"),
        ("连珠重炮", "3aka3/9/9/9/9/9/4C4/4C4/9/3K5 w - - 0 1"),
        ("侧翼破宫", "3aka3/9/9/9/9/9/9/R8/4R4/3K5 w - - 0 1"),

        # --- 3步杀拓展 ---
        ("破象攻心", "2b1ka3/9/4b4/9/9/9/9/9/R7R/3K5 w - - 0 1"),
        ("卧槽盘角", "3ak4/4a4/9/9/9/2N6/9/9/R7R/3K5 w - - 0 1"),
        ("弃马斩士", "3aka3/9/9/9/9/4N4/9/9/R7R/3K5 w - - 0 1"),
        ("天地合攻", "3aka3/9/9/9/9/9/4C4/9/R1C6/3K5 w - - 0 1"),
        ("老兵搜山", "3aka3/9/9/9/4P4/9/9/9/R7R/3K5 w - - 0 1"),
        ("夹车围困", "3ak4/4a4/9/9/9/9/9/R7R/9/3K5 w - - 0 1"),
        ("进炮逼士", "3aka3/9/9/9/9/9/9/4C4/2C6/3K5 w - - 0 1"),
        ("侧翼马炮", "3aka3/9/9/9/9/9/4N4/2C6/9/3K5 w - - 0 1"),
    ]

    verified = []
    for name, fen in candidates:
        mate, best, pv = check_puzzle(p, fen)
        if mate is not None and mate > 0:
            print(f"PASS: {name:15s} mate={mate} best={best:6s} pv={pv}")
            verified.append((name, fen, mate, pv))
        else:
            print(f"FAIL: {name:15s} mate={mate}")

    p.stdin.write("quit\n")
    p.stdin.flush()
    print(f"\nSuccessfully verified {len(verified)} puzzles!")

if __name__ == "__main__":
    main()

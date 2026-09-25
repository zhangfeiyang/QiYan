#!/usr/bin/env python3
import subprocess
import sys

def probe(fens):
    p = subprocess.Popen(["./third_party/Pikafish/src/pikafish"], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
    p.stdin.write("uci\nsetoption name EvalFile value third_party/Pikafish/src/pikafish.nnue\nisready\n")
    p.stdin.flush()
    for line in p.stdout:
        if "readyok" in line:
            break

    results = []
    for name, fen in fens:
        p.stdin.write(f"position fen {fen}\ngo depth 12\n")
        p.stdin.flush()

        mate = None
        pv = ""
        bestmove = ""
        for line in p.stdout:
            line_s = line.strip()
            if "score mate" in line_s:
                parts = line_s.split()
                try:
                    idx = parts.index("mate")
                    mate = int(parts[idx + 1])
                    if "pv" in parts:
                        pv_idx = parts.index("pv")
                        pv = " ".join(parts[pv_idx + 1:])
                except:
                    pass
            if line_s.startswith("bestmove"):
                bestmove = line_s.split()[1]
                break
        print(f"[{name}] mate={mate}, best={bestmove}, pv={pv}")
        results.append((name, mate, bestmove, fen))

    p.stdin.write("quit\n")
    p.stdin.flush()
    p.wait()
    return results

if __name__ == "__main__":
    test_list = [
        # 白脸将: 红帅中路, 红车在边路/底线, 黑将中路被将死
        ("白脸将1", "4k4/4a4/9/9/9/9/9/9/R8/4K4 w - - 0 1"),
        ("白脸将2", "4k4/3a5/9/9/9/9/9/9/4R4/4K4 w - - 0 1"),
        # 铁门栓: 红车中路或肋路, 沉底绝杀
        ("铁门栓1", "3ak4/4a4/9/4C4/9/9/9/9/3R5/4K4 w - - 0 1"),
        # 挂角马
        ("挂角马1", "3k5/4a4/4N4/9/9/9/9/9/3R5/4K4 w - - 0 1"),
        # 卧槽马
        ("卧槽马1", "3k5/4a4/9/9/9/2N6/9/9/3R5/4K4 w - - 0 1"),
        # 钓鱼马
        ("钓鱼马1", "3k5/4a4/3N5/9/9/9/9/9/3R5/4K4 w - - 0 1"),
        # 闷宫
        ("闷宫1", "3aka3/9/9/9/9/9/9/4C4/9/4K4 w - - 0 1"),
        # 马后炮
        ("马后炮1", "3k5/4a4/9/3N5/9/9/9/9/3C5/4K4 w - - 0 1"),
        # 夹车炮
        ("夹车炮1", "3aka3/9/9/9/9/9/9/4C4/4R4/4K4 w - - 0 1"),
        # 双车错
        ("双车错1", "3aka3/9/9/9/9/9/R8/9/1R7/4K4 w - - 0 1"),
    ]
    probe(test_list)

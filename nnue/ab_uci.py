#!/usr/bin/env python3
"""UCI 双引擎 A/B：两个自研引擎各自对 Pikafish，同开局配对统计。

用于验证"纯性能优化"（快速走法过滤 / qsearch SEE 预筛）是否提升棋力。
PikafishMatchRunner 只能加载单一 host_lib，无法同 JVM 跑新旧引擎；
这里用 UCI 协议实现等价配对：每个开局 baseline/candidate 各执红/黑
对 Pikafish 一次，逐盘断点，统计配对增量。

用法:
    python3 nnue/ab_uci.py \
        --engine-old /tmp/xq_uci_m0 --engine-new /tmp/xq_uci_seeskip \
        --net /home/zhangfy/nnue-nets/net_dagger_blend_v1.nnue \
        --pika third_party/Pikafish/src/pikafish \
        --pairs 100 --movetime 500 --seed 20260730 \
        --out ~/nnue-logs/ab_uci.tsv
"""
import argparse
import json
import math
import os
import random
import sys
import time

sys.path.insert(0, "/home/zhangfy/zhongguoxiangqi/nnue")
from mine_regret_pairs import UciClient, build_go_command  # noqa: E402
from audit_endgames import query_relaxed  # noqa: E402

START = "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1"
MAX_PLIES = 200


def play_game(engine, pika, fen_moves, our_color, movetime):
    """自研引擎执 our_color 对 Pikafish 一局。

    判定规则：
    - 轮到 Pikafish 走时它报 |score|>=9000 → Pikafish 看到杀棋。
      若 score<0（它被将死前兆）说明它认为**自己**将被杀 → 自研胜；
      若 score>0 说明它认为**自研**将被杀 → Pikafish 胜。
    - 轮到自研走时它返回 |score|>=9000 → 自研看到杀棋，同理判。
    - 200 半回合未分 → 和棋（粗判）。
    """
    moves = list(fen_moves)
    our_turn = (our_color == "red") ^ (len(moves) % 2 == 1)
    for ply in range(MAX_PLIES):
        fen_pos = (START + " moves " + " ".join(moves)) if moves else START
        if our_turn:
            mv, sc = query_relaxed(engine, fen_pos, movetime)
            if sc is not None and abs(sc) >= 9000:
                return 1.0 if sc > 0 else 0.0, "our-mate"
        else:
            mv, sc = pika.query(fen_pos, movetime_ms=movetime)
            if sc is not None and abs(sc) >= 9000:
                return 0.0 if sc < 0 else 1.0, "pika-mate"
        moves.append(mv)
        our_turn = not our_turn
    return 0.5, "draw-200"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--engine-old", required=True)
    ap.add_argument("--engine-new", required=True)
    ap.add_argument("--net", required=True)
    ap.add_argument("--pika", required=True)
    ap.add_argument("--pairs", type=int, default=100)
    ap.add_argument("--movetime", type=int, default=500)
    ap.add_argument("--seed", type=int, default=20260730)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    rng = random.Random(args.seed)
    # 生成开局：随机走 6 步（红黑交替）
    openings = []
    for _ in range(args.pairs):
        pos = START
        moves = []
        side = "red"
        tmp = UciClient([args.pika], need_score=True)
        try:
            for _ in range(6):
                mv, _ = tmp.query(pos + (" moves " + " ".join(moves)) if moves else pos,
                                  depth=1, movetime_ms=0)
                moves.append(mv)
                side = "black" if side == "red" else "red"
        finally:
            tmp.close()
        openings.append(moves)

    old = UciClient([args.engine_old, args.net], need_score=False)
    new = UciClient([args.engine_new, args.net], need_score=False)
    pika = UciClient([args.pika], need_score=True)

    rows = []
    checkpoint = args.out + ".checkpoint"
    if os.path.exists(checkpoint):
        with open(checkpoint) as f:
            rows = json.load(f)
        print(f"[ab] 从断点恢复 {len(rows)} 个开局")

    try:
        for i in range(len(rows), args.pairs):
            opening = openings[i]
            results = {}
            for label, engine in (("old", old), ("new", new)):
                for color in ("red", "black"):
                    # 自研引擎执 color 对 Pikafish
                    moves = list(opening)
                    # opening 已走 len(moves) 步：偶数=轮到红，奇数=轮到黑。
                    # 自研执红时轮到红则自研走，执黑时轮到黑则自研走。
                    side_now = "red" if len(moves) % 2 == 0 else "black"
                    our_turn = (color == side_now)
                    engine_result = None
                    for ply in range(MAX_PLIES):
                        fen_pos = (START + " moves " + " ".join(moves)) if moves else START
                        if our_turn:
                            mv, sc = query_relaxed(engine, fen_pos, args.movetime)
                            if sc is not None and abs(sc) >= 9000:
                                engine_result = 1.0 if sc > 0 else 0.0
                                break
                        else:
                            mv, sc = pika.query(fen_pos, movetime_ms=args.movetime)
                            if sc is not None and abs(sc) >= 9000:
                                # Pikafish 判杀棋：score>0 表示它看到**我们**
                                # 被杀（Pikafish 胜）；score<0 表示它看到**自己**
                                # 被杀（自研胜）。
                                engine_result = 0.0 if sc > 0 else 1.0
                                break
                        moves.append(mv)
                        our_turn = not our_turn
                    if engine_result is None:
                        engine_result = 0.5  # 粗判：200 半回合和棋
                    results[f"{label}_{color}"] = engine_result
            rows.append({
                "opening": opening,
                **results,
            })
            with open(checkpoint, "w") as f:
                json.dump(rows, f)
            if (i + 1) % 10 == 0:
                print(f"[ab] {i + 1}/{args.pairs}", flush=True)
    finally:
        old.close()
        new.close()
        pika.close()

    with open(args.out, "w") as f:
        f.write("# opening\told_red\told_black\tnew_red\tnew_black\n")
        for r in rows:
            f.write("\t".join([
                " ".join(r["opening"]),
                str(r["old_red"]), str(r["old_black"]),
                str(r["new_red"]), str(r["new_black"]),
            ]) + "\n")

    n = len(rows)
    old_score = sum(r["old_red"] + r["old_black"] for r in rows)
    new_score = sum(r["new_red"] + r["new_black"] for r in rows)
    deltas = [(r["new_red"] + r["new_black"]) - (r["old_red"] + r["old_black"]) for r in rows]
    dbar = sum(deltas) / n
    s = math.sqrt(sum((d - dbar) ** 2 for d in deltas) / (n - 1)) if n > 1 else 0
    se = s / math.sqrt(n)
    lo, hi = dbar - 1.96 * se, dbar + 1.96 * se
    print(f"[ab] {n} 开局")
    print(f"[ab] old 得分率 {old_score / (2 * n):.1%}  new 得分率 {new_score / (2 * n):.1%}")
    print(f"[ab] 配对增量 {dbar * 50:+.2f}pp  95% CI [{lo * 50:+.2f}, {hi * 50:+.2f}]")


if __name__ == "__main__":
    main()

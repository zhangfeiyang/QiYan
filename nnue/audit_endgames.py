#!/usr/bin/env python3
"""残局/特殊局面审计：自研引擎 vs PikaFish 限时搜索对比。

目标不是对战，而是定位搜索行为上的系统性差异：
1. bestmove 一致率（相同局面两边是否选同一着法）
2. 分数相关性（静态/搜索评估是否同向）
3. 极端差异局面（一边认为大幅领先、另一边大幅落后；或杀棋分不一致）

用法:
    python3 nnue/audit_endgames.py \
        --fens /tmp/eg_audit.fen \
        --ours /tmp/xq_uci --net /home/zhangfy/nnue-nets/net_dagger_blend_v1.nnue \
        --pika third_party/Pikafish/src/pikafish \
        --movetime 500 --limit 2000 --out /tmp/eg_audit.tsv
"""
import argparse
import math
import sys
import time

sys.path.insert(0, "/home/zhangfy/zhongguoxiangqi/nnue")
from mine_regret_pairs import UciClient, build_go_command, parse_score  # noqa: E402


def query_relaxed(client, fen, movetime_ms):
    """自研引擎不输出 'pv' 字段，只从 info 行提取 score + bestmove。"""
    client.send("position fen " + fen)
    client.send(build_go_command(0, movetime_ms, None))
    deadline = time.monotonic() + client.timeout
    score = None
    while True:
        line = client.read_line(deadline)
        if line.startswith("info"):
            parsed = parse_score(line)
            if parsed is not None:
                score = parsed
        if not line.startswith("bestmove"):
            continue
        move = line.split()[1] if len(line.split()) >= 2 else ""
        return move, score


def parse_args():
    ap = argparse.ArgumentParser()
    ap.add_argument("--fens", required=True)
    ap.add_argument("--ours", required=True, help="自研引擎 UCI 可执行")
    ap.add_argument("--net", required=True, help="自研引擎加载的 .nnue")
    ap.add_argument("--pika", required=True)
    ap.add_argument("--movetime", type=int, default=500)
    ap.add_argument("--limit", type=int, default=2000)
    ap.add_argument("--out", required=True)
    return ap.parse_args()


def main():
    args = parse_args()
    with open(args.fens) as f:
        fens = [line.strip() for line in f if line.strip()][: args.limit]
    print(f"[audit] {len(fens)} 个局面, movetime={args.movetime}ms")

    ours = UciClient([args.ours, args.net], need_score=False)
    pika = UciClient([args.pika], need_score=True)

    rows = []
    t0 = time.time()
    agree = disagree = 0
    mate_ours = mate_pika = mate_both = 0
    score_pairs = []
    flags = []
    try:
        for i, fen in enumerate(fens):
            om, os_ = query_relaxed(ours, fen, args.movetime)
            pm, ps_ = pika.query(fen, movetime_ms=args.movetime)
            same = om == pm
            if same:
                agree += 1
            else:
                disagree += 1
            rows.append((fen, om, os_, pm, ps_))
            score_pairs.append((os_, ps_))
            # 杀棋分不一致 / 方向相反是重点嫌疑
            if abs(os_) >= 9000 and abs(ps_) >= 9000:
                mate_both += 1
            elif abs(os_) >= 9000:
                mate_ours += 1
            elif abs(ps_) >= 9000:
                mate_pika += 1
            if (os_ > 0) != (ps_ > 0) and abs(os_) > 50 and abs(ps_) > 50:
                flags.append((fen, om, os_, pm, ps_, "SIGN_FLIP"))
            if abs(os_ - ps_) > 1500:
                flags.append((fen, om, os_, pm, ps_, "BIG_GAP"))
            if (i + 1) % 200 == 0:
                el = time.time() - t0
                print(f"[audit] {i + 1}/{len(fens)} 一致率={agree / (agree + disagree):.1%} "
                      f"耗时={el:.0f}s 预计剩余={el / (i + 1) * (len(fens) - i - 1):.0f}s",
                      flush=True)
    finally:
        ours.close()
        pika.close()

    n = len(rows)
    agree_rate = agree / max(n, 1)
    # 分数相关性（普通局面；杀棋哨兵单独数）
    normal = [(a, b) for a, b in score_pairs if abs(a) < 9000 and abs(b) < 9000]
    corr = None
    if len(normal) > 2:
        xs = [a for a, _ in normal]
        ys = [b for _, b in normal]
        mx, my = sum(xs) / len(xs), sum(ys) / len(ys)
        cov = sum((x - mx) * (y - my) for x, y in zip(xs, ys))
        sx = math.sqrt(sum((x - mx) ** 2 for x in xs))
        sy = math.sqrt(sum((y - my) ** 2 for y in ys))
        corr = cov / (sx * sy) if sx > 0 and sy > 0 else None
        mae = sum(abs(a - b) for a, b in normal) / len(normal)
    else:
        mae = None

    with open(args.out, "w") as f:
        f.write("# fen\tours_move\tours_score\tpika_move\tpika_score\tflag\n")
        for fen, om, os_, pm, ps_, fl in [
            (r[0], r[1], r[2], r[3], r[4], "")
            for r in rows
        ] + flags:
            f.write(f"{fen}\t{om}\t{os_}\t{pm}\t{ps_}\t{fl}\n")

    print(f"[audit] 完成 {n} 局面")
    print(f"[audit] bestmove 一致率: {agree_rate:.1%} ({agree}/{n})")
    print(f"[audit] 普通局面分数 corr={corr:.4f} MAE={mae:.1f}cp (n={len(normal)})" if corr else "")
    print(f"[audit] 双方都判杀棋: {mate_both}; 仅自研判杀棋: {mate_ours}; 仅 PikaFish 判杀棋: {mate_pika}")
    print(f"[audit] 方向相反/分差>1500 的嫌疑局面: {len(flags)} 个，见 {args.out}")


if __name__ == "__main__":
    main()

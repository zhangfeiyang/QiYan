#!/usr/bin/env python3
"""M0 测量基准：固定局面集上的 EBF 曲线 / 截断位置分布 / TT 指标。

这是排序改造各阶段的共用验收口径。每个局面独立跑一次搜索（go depth 12，
可换 movetime），解析引擎 --cutstats 输出，汇总为 TSV + 摘要。

用法:
    python3 nnue/m0_bench.py \
        --engine /tmp/xq_uci_m0 --net <net.nnue> --cutstats \
        --fens /tmp/midgame_sample.fen --out /tmp/m0_mid.tsv \
        --label midgame --depth 12
"""
import argparse
import re
import subprocess
import sys
import time

sys.path.insert(0, "/home/zhangfy/zhongguoxiangqi/nnue")
from mine_regret_pairs import UciClient, build_go_command  # noqa: E402


ITER_RE = re.compile(r"按迭代探针/命中/早退:(.*)$")
TT_RE = re.compile(r"TT: 内部节点=(\d+) 槽命中=(\d+) \(([\d.]+)%\) 深度达标早退=(\d+)")
CROSS_RE = re.compile(r"跨世代命中=(\d+)")
TYPE_RE = re.compile(r"着法类型: TT=([\d.]+)% 吃子=([\d.]+)% 杀手1=([\d.]+)% 杀手2=([\d.]+)% 安静历史=([\d.]+)%")
POS_RE = re.compile(r"截断手次: 第1手=([\d.]+)% 第2手=([\d.]+)% 第3手\+=([\d.]+)%")
POSFINE_RE = re.compile(r"截断位置细分: 3-4手=([\d.]+)% 5-8手=([\d.]+)% 9-16手=([\d.]+)% 17手\+=([\d.]+)%")


def parse_log(lines):
    """解析单次搜索的 cutstats 输出，返回指标 dict。"""
    out = {}
    for line in lines:
        m = ITER_RE.search(line)
        if m:
            iters = {}
            for tok in m.group(1).split():
                mm = re.match(r"d(\d+)=(\d+)/(\d+)/(\d+)", tok)
                if mm:
                    iters[int(mm.group(1))] = (int(mm.group(2)), int(mm.group(3)), int(mm.group(4)))
            out["iters"] = iters
        m = TT_RE.search(line)
        if m:
            out["probes"] = int(m.group(1))
            out["hits"] = int(m.group(2))
            out["hit_rate"] = float(m.group(3))
            out["early"] = int(m.group(4))
        m = CROSS_RE.search(line)
        if m:
            out["cross"] = int(m.group(1))
        m = TYPE_RE.search(line)
        if m:
            out["type"] = tuple(float(x) for x in m.groups())
        m = POS_RE.search(line)
        if m:
            out["pos"] = tuple(float(x) for x in m.groups())
        m = POSFINE_RE.search(line)
        if m:
            out["posfine"] = tuple(float(x) for x in m.groups())
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--engine", required=True)
    ap.add_argument("--net", required=True)
    ap.add_argument("--fens", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--label", default="")
    ap.add_argument("--depth", type=int, default=12)
    ap.add_argument("--movetime", type=int, default=0)
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--extra", nargs="*", default=[])
    args = ap.parse_args()

    with open(args.fens) as f:
        fens = [l.strip() for l in f if l.strip()]
    if args.limit > 0:
        fens = fens[: args.limit]

    cmd = [args.engine, args.net, "--cutstats=1"] + args.extra
    client = UciClient(cmd, need_score=False)
    rows = []
    t0 = time.time()
    try:
        for i, fen in enumerate(fens):
            client.send("position fen " + fen)
            client.send(build_go_command(args.depth, args.movetime, None))
            deadline = time.monotonic() + client.timeout
            lines = []
            while True:
                line = client.read_line(deadline)
                lines.append(line)
                if line.startswith("bestmove"):
                    break
            s = parse_log(lines)
            s["fen"] = fen
            rows.append(s)
            if (i + 1) % 50 == 0:
                el = time.time() - t0
                print(f"[m0] {i + 1}/{len(fens)} 耗时={el:.0f}s", flush=True)
    finally:
        client.close()

    with open(args.out, "w") as f:
        f.write("# label\tfen\tprobes\thits\thit_rate\tcross\t"
                "first_pct\tsecond_pct\tp34\tp58\tp916\tp17p\t"
                "tt_type\tcap_type\tk1_type\tk2_type\tquiet_type\t"
                "iter_chain\n")
        for s in rows:
            it = s.get("iters", {})
            chain = ";".join(f"{d}:{v[0]}" for d, v in sorted(it.items()))
            pos = s.get("pos", (0, 0, 0))
            pf = s.get("posfine", (0, 0, 0, 0))
            ty = s.get("type", (0, 0, 0, 0, 0))
            f.write("\t".join(str(x) for x in [
                args.label, s["fen"], s.get("probes", 0), s.get("hits", 0),
                s.get("hit_rate", 0), s.get("cross", 0),
                pos[0], pos[1], pf[0], pf[1], pf[2], pf[3],
                ty[0], ty[1], ty[2], ty[3], ty[4], chain,
            ]) + "\n")

    n = len(rows)
    def avg_scalar(key, default=0.0):
        vals = [s.get(key, default) for s in rows if isinstance(s.get(key), (int, float))]
        return sum(vals) / len(vals) if vals else 0.0

    first_cuts = [s["pos"][0] for s in rows if "pos" in s]
    avg_first = sum(first_cuts) / len(first_cuts) if first_cuts else 0.0
    print(f"[m0] {args.label}: n={n} 平均TT命中率={avg_scalar('hit_rate'):.1f}% "
          f"平均第1手截断={avg_first:.1f}%")
    # EBF 曲线：迭代链中位数的节点增长
    depth_avail = [s.get("iters", {}) for s in rows]
    if depth_avail:
        maxd = max(max(it) for it in depth_avail if it)
        for d in range(3, maxd + 1):
            pairs = [(it[d - 1][0], it[d][0]) for it in depth_avail if d in it and d - 1 in it and it[d - 1][0] > 0]
            if pairs:
                ratios = [b / a for a, b in pairs]
                print(f"[m0] EBF d{d-1}->{d}: {sum(ratios) / len(ratios):.3f} (n={len(pairs)})")


if __name__ == "__main__":
    main()

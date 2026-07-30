"""
生成跨语言校验用的黄金向量.

    python3 nnue/make_golden.py --net app/src/test/resources/nnue/test.nnue \
        --data ~/nnue-data --n 200 \
        --out app/src/test/resources/nnue/golden.txt

做这件事的原因: 量化推理是训练和端上唯一的交接面, 而它错了**不会崩溃**,
只会让棋力莫名其妙地差. 光看两边代码"长得一样"不算数 —— 整数除法的取整
方向、行主序、视角对调, 任何一处差一点都能悄悄改掉分数. 所以拿同一批真实
局面, 让 Python 参考实现算一遍, 把结果钉死成文件, 让 Kotlin 单测逐条比对
**完全相等**(不是"接近").

用真实自对弈记录而不是随机特征: 随机特征凑不出合法局面, 每方 32 个子随便
撒, 覆盖不到"两个王 + 稀疏残局"这类实际会遇到的分布.
"""
import argparse
import glob
import os
import struct

import numpy as np

from dataset import RECORD_BYTES, _OFF_COUNT, _OFF_STM, _OFF_PIECES
from export import int_infer
from model import FEATURES, L1, L2, L3

QA = 127
QW = 256


def load_net(path):
    """读回 export.py 写出的文件. 顺带验证那个格式确实是自洽的."""
    with open(path, "rb") as f:
        blob = f.read()
    assert blob[:4] == b"XQNN", "不是 NNUE 文件"
    ver, feats, l1, l2, l3, qa, qw = struct.unpack("<7i", blob[4:32])
    assert (feats, l1, l2, l3, qa, qw) == (FEATURES, L1, L2, L3, QA, QW), \
        f"结构不匹配: {(feats, l1, l2, l3, qa, qw)}"

    off = 32

    def take(count, dtype):
        nonlocal off
        n = count * np.dtype(dtype).itemsize
        a = np.frombuffer(blob[off:off + n], dtype=dtype)
        off += n
        return a

    ft_w = take(FEATURES * L1, "<i2").reshape(FEATURES, L1)
    ft_b = take(L1, "<i2")
    fc1_w = take(L2 * L1 * 2, "<i1").reshape(L2, L1 * 2)
    fc1_b = take(L2, "<i4")
    fc2_w = take(L3 * L2, "<i1").reshape(L3, L2)
    fc2_b = take(L3, "<i4")
    out_w = take(L3, "<i4").reshape(1, L3)
    out_b = take(1, "<i4")
    assert off == len(blob), f"读完还剩 {len(blob) - off} 字节"
    return ft_w, ft_b, fc1_w, fc1_b, fc2_w, fc2_b, out_w, out_b


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--net", required=True)
    ap.add_argument("--data", required=True)
    ap.add_argument("--n", type=int, default=200)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    w = load_net(args.net)
    print(f"[golden] 网络已读回: {args.net}")

    paths = sorted(glob.glob(os.path.join(
        os.path.expanduser(args.data), "shard-*.bin")))
    paths = [p for p in paths if os.path.getsize(p) >= RECORD_BYTES]
    if not paths:
        raise SystemExit(f"{args.data} 里没有可用的 shard-*.bin")

    raw = np.memmap(paths[0], dtype=np.uint8, mode="r")
    total = len(raw) // RECORD_BYTES
    recs = raw[:total * RECORD_BYTES].reshape(total, RECORD_BYTES)

    # 均匀取样而不是取前 N 条: 前 N 条都来自同一局的开头, 全是满盘 32 子的
    # 开局局面, 测不到残局.
    step = max(1, total // args.n)
    picked = list(range(0, total, step))[:args.n]
    print(f"[golden] 分片 {os.path.basename(paths[0])} 共 {total:,} 条, "
          f"每 {step} 条取一条, 取 {len(picked)} 条")

    lines = []
    for ri in picked:
        rec = recs[ri]
        count = int(rec[_OFF_COUNT])
        stm_black = int(rec[_OFF_STM]) == 1
        pieces = rec[_OFF_PIECES:_OFF_PIECES + count * 2].reshape(count, 2)
        idxs = pieces[:, 0].astype(np.int64)
        sqs = pieces[:, 1].astype(np.int64)

        # 视角约定与 dataset.py 完全一致
        if stm_black:
            own = np.array([(i + 7 if i < 7 else i - 7) * 90
                            + (9 - s // 9) * 9 + s % 9
                            for i, s in zip(idxs, sqs)], dtype=np.int64)
            opp = idxs * 90 + sqs
        else:
            own = idxs * 90 + sqs
            opp = np.array([(i + 7 if i < 7 else i - 7) * 90
                            + (9 - s // 9) * 9 + s % 9
                            for i, s in zip(idxs, sqs)], dtype=np.int64)

        score = int_infer(*w, own, opp)
        flat = " ".join(f"{int(i)},{int(s)}" for i, s in zip(idxs, sqs))
        lines.append(f"{1 if stm_black else 0}|{flat}|{score}")

    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w") as f:
        f.write("# NnueEvaluator 跨语言黄金向量, 由 nnue/make_golden.py 生成\n")
        f.write("# 每行: 走子方是否为黑(0/1) | pieceIdx,sq 列表 | 期望厘兵分\n")
        f.write("\n".join(lines) + "\n")
    print(f"[golden] 已写出 {args.out}  {len(lines)} 条")

    sc = np.array([int(l.rsplit("|", 1)[1]) for l in lines])
    print(f"[golden] 期望分数范围 [{sc.min()}, {sc.max()}], "
          f"不同取值 {len(set(sc.tolist()))} 个")
    if len(set(sc.tolist())) < 5:
        print("[golden] ?? 分数几乎全一样, 这批向量区分不出实现差异, "
              "换个练过的 checkpoint 再生成")


if __name__ == "__main__":
    main()

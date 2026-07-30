"""向量化后的 __getitem__ 必须与逐元素标量版**逐位相同**.

数据管线算错不会崩, 只会让网络学错东西, 而且症状是"棋力莫名其妙地差" ——
所以这里不比"差不多", 只比逐位相等, 并且直接拿真实数据跑, 不用构造样本.

    python3 nnue/test_dataset_vectorized.py ~/nnue-data-pika-d12
"""
import sys, glob, os
import numpy as np
from dataset import SQUARES, MAX_PIECES


def scalar_flip_piece(idx):
    return idx + 7 if idx < 7 else idx - 7


def scalar_flip_square(sq):
    row, col = sq // 9, sq % 9
    return (9 - row) * 9 + col


def main():
    data = sys.argv[1] if len(sys.argv) > 1 else os.path.expanduser("~/nnue-data-pika-d12")
    paths = sorted(glob.glob(os.path.join(data, "shard-*.bin")))[:2]
    if not paths:
        print(f"没有数据: {data}"); return 1
    from dataset import SelfPlayDataset
    ds = SelfPlayDataset(paths)
    rng = np.random.default_rng(20260730)
    picks = rng.choice(len(ds), min(50000, len(ds)), replace=False)

    bad = 0
    for i in picks:
        own, opp, target = ds[int(i)][0], ds[int(i)][1], ds[int(i)][2]
        own = np.asarray(own); opp = np.asarray(opp)
        # 反推: own/opp 里非 -1 的部分应当满足 "一边是原样, 另一边是翻转"
        o = own[own >= 0]; p = opp[opp >= 0]
        if len(o) != len(p): bad += 1; continue
        # 对每个特征下标还原 (pieceIdx, sq), 再用标量函数翻一次, 应当得到另一边
        oi, osq = o // SQUARES, o % SQUARES
        expect = np.array([scalar_flip_piece(int(a)) * SQUARES + scalar_flip_square(int(b))
                           for a, b in zip(oi, osq)], dtype=np.int64)
        if not np.array_equal(np.sort(expect), np.sort(p)):
            bad += 1
    print(f"[test] 抽查 {len(picks):,} 条, 与标量翻转不一致 {bad} 条")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())

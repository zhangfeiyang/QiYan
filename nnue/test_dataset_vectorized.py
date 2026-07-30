"""向量化的 [encode_perspectives] 必须与逐元素标量版**逐位相同**.

数据管线算错不会崩, 只会让网络学错东西, 而且症状是"棋力莫名其妙地差" ——
所以这里不比"差不多", 只比逐位相等, 并且直接拿真实数据跑, 不用构造样本.

    python3 nnue/test_dataset_vectorized.py ~/nnue-data-pika-d12

## 这个测试自己踩过的坑

它原来把特征下标当成 `pieceIdx * 90 + sq` 来反推。特征升到 v3 (加了将位桶,
1260 -> 11340 维) 之后这个反推就全错了, 但它是个独立脚本、不在 Gradle 套件
里, 于是**一直红着没人发现**, 直到有人手动跑了一次 —— 5 万条全部"不一致",
而真正错的是测试。同一次升级还漏改了 `dataset.FEATURES`(留在 1260)。

所以下面额外钉了三条: 两个模块的 FEATURES 必须相等; 下标不越界; 同一视角
内所有特征必须落在同一个将位桶里。
"""
import sys, glob, os
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from dataset import (SelfPlayDataset, encode_perspectives, FEATURES, SQUARES,
                     PIECE_SQ, KING_BUCKETS, _OFF_COUNT, _OFF_STM, _OFF_PIECES)
import model


def scalar_encode(idxs, sqs, stm_black):
    """朴素做法: 逐元素算, 不用任何 numpy 向量化"""
    def flip_piece(i):
        return i + 7 if i < 7 else i - 7

    def flip_square(sq):
        return (9 - sq // 9) * 9 + sq % 9

    own_parts, opp_parts = [], []
    for i, s in zip(idxs, sqs):
        i, s = int(i), int(s)
        if stm_black:
            own_parts.append((flip_piece(i), flip_square(s)))
            opp_parts.append((i, s))
        else:
            own_parts.append((i, s))
            opp_parts.append((flip_piece(i), flip_square(s)))

    def bucket(parts):
        for i, s in parts:
            if i == 0:
                return min(max((s // 9 - 7) * 3 + (s % 9 - 3), 0), KING_BUCKETS - 1)
        return 0

    ob, pb = bucket(own_parts), bucket(opp_parts)
    return ([ob * PIECE_SQ + i * SQUARES + s for i, s in own_parts],
            [pb * PIECE_SQ + i * SQUARES + s for i, s in opp_parts])


def main():
    assert FEATURES == model.FEATURES, (
        f"dataset.FEATURES={FEATURES} 与 model.FEATURES={model.FEATURES} 不一致 —— "
        "改特征维度时漏改了一处"
    )

    data = sys.argv[1] if len(sys.argv) > 1 else os.path.expanduser("~/nnue-data-pika-d12")
    paths = sorted(glob.glob(os.path.join(data, "shard-*.bin")))[:2]
    if not paths:
        print(f"没有数据: {data}")
        return 1
    ds = SelfPlayDataset(paths)
    rng = np.random.default_rng(20260730)
    picks = rng.choice(len(ds), min(50000, len(ds)), replace=False)

    bad = 0
    for i in picks:
        ci, ri = ds._locate(int(i))
        rec = ds.chunks[ci][ri]
        count = int(rec[_OFF_COUNT])
        stm_black = int(rec[_OFF_STM]) == 1
        pieces = rec[_OFF_PIECES:_OFF_PIECES + count * 2].reshape(count, 2)
        idxs = pieces[:, 0].astype(np.int64)
        sqs = pieces[:, 1].astype(np.int64)

        fast_own, fast_opp = encode_perspectives(idxs, sqs, stm_black)
        slow_own, slow_opp = scalar_encode(idxs, sqs, stm_black)
        if list(fast_own) != slow_own or list(fast_opp) != slow_opp:
            bad += 1
            if bad == 1:
                print(f"  首个不一致 @{i}: 向量化 {list(fast_own)[:4]} 标量 {slow_own[:4]}")
            continue
        if fast_own.max() >= FEATURES or fast_opp.max() >= FEATURES:
            print(f"  下标越界 @{i}")
            bad += 1
            continue
        # 同一视角内所有特征必须共用一个将位桶 —— 桶由己方将唯一决定
        if len(set((fast_own // PIECE_SQ).tolist())) != 1:
            print(f"  own 视角跨了多个将位桶 @{i}")
            bad += 1

    print(f"[test] 抽查 {len(picks):,} 条, 向量化与标量不一致 {bad} 条")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())

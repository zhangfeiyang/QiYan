"""整块解码 [decode_bulk] 必须与逐条 [SelfPlayDataset.__getitem__] **逐位相同**.

整块版本是训练缓存的唯一入口, 而它比逐条版本快两个数量级 —— 快归快, 算错一位
的症状仍然是"loss 正常下降但棋力莫名其妙地差"。所以这里不比"差不多"。

特别盯住补位区: 记录里 count 之后的字节是上一条留下的垃圾, 逐条版本切片到
count*2 天然看不见, 整块版本必须显式屏蔽。垃圾里偶然出现 idx==0 就会被当成
"将", 算出错误的九宫桶 —— 而错的只是那一条样本, 从 loss 上根本看不出来。

    python3 nnue/test_decode_bulk.py ~/nnue-data-pika-d8
"""
import sys, glob, os, time
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from dataset import SelfPlayDataset, decode_bulk, MAX_PIECES


def main():
    data = sys.argv[1] if len(sys.argv) > 1 else os.path.expanduser("~/nnue-data-pika-d8")
    paths = sorted(glob.glob(os.path.join(data, "shard-*.bin")))[:1]
    if not paths:
        print(f"没有数据: {data}")
        return 1
    ds = SelfPlayDataset(paths)
    n = min(200000, len(ds))
    recs = ds.chunks[0][:n]

    t0 = time.time()
    b_own, b_opp, b_y = decode_bulk(recs)
    t_bulk = time.time() - t0

    t0 = time.time()
    bad = 0
    for i in range(n):
        own, opp, y = ds[i]
        if not np.array_equal(own.numpy().astype(np.int16), b_own[i]):
            bad += 1
            if bad == 1:
                print(f"  own 不一致 @{i}\n    逐条 {own.numpy()[:8]}\n    整块 {b_own[i][:8]}")
        elif not np.array_equal(opp.numpy().astype(np.int16), b_opp[i]):
            bad += 1
            if bad == 1:
                print(f"  opp 不一致 @{i}")
        elif float(y) != float(b_y[i]):
            bad += 1
            if bad == 1:
                print(f"  y 不一致 @{i}: {float(y)} vs {float(b_y[i])}")
    t_slow = time.time() - t0

    print(f"[test] {n:,} 条: 不一致 {bad} 条")
    print(f"[test] 整块 {t_bulk:.2f}s   逐条 {t_slow:.1f}s   快 {t_slow/max(t_bulk,1e-9):.0f} 倍")
    # 补位区确实被屏蔽了: 每条样本的有效特征数必须等于 count
    counts = recs[:, 0].astype(np.int64)
    live = (b_own >= 0).sum(axis=1)
    assert np.array_equal(live, counts), "有效特征数与 count 对不上, 补位屏蔽有问题"
    print(f"[test] 补位屏蔽正确 (有效特征数处处等于 count)")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())

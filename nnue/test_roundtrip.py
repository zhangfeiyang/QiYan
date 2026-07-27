"""
编码交叉校验.

Kotlin 写、Python 读, 两边各写一遍编码逻辑就必然有对不上的风险。这里拿
真实生成的数据反推局面, 检查几条不变量 —— 全都通过才敢开始几小时的训练。
"""
import glob, os, sys
import numpy as np
sys.path.insert(0, os.path.dirname(__file__))
from dataset import (SelfPlayDataset, FEATURES, SQUARES, MAX_PIECES,
                     RECORD_BYTES, flip_square, flip_piece)

d = os.path.expanduser(sys.argv[1] if len(sys.argv) > 1 else "~/nnue-data")
paths = [p for p in sorted(glob.glob(os.path.join(d, "shard-*.bin"))) if os.path.getsize(p) > 0]
if not paths:
    print("!! 还没有非空分片, 等生成器落盘后再跑"); sys.exit(1)

ds = SelfPlayDataset(paths)
print(f"样本总数 {len(ds):,}")

bad = 0
n_check = min(len(ds), 5000)
for i in range(n_check):
    own, opp, y = ds[i]
    o, p = own.numpy(), opp.numpy()
    live_o, live_p = o[o >= 0], p[p >= 0]

    # 1. 下标必须落在特征空间内
    assert live_o.min() >= 0 and live_o.max() < FEATURES, f"own 越界 @{i}"
    assert live_p.min() >= 0 and live_p.max() < FEATURES, f"opp 越界 @{i}"
    # 2. 两个视角的子数必须一样 —— 同一个局面换个看法而已
    assert len(live_o) == len(live_p), f"两视角子数不一致 @{i}"
    # 3. 不能有重复: 一个格子上只能有一个子
    assert len(set(live_o.tolist())) == len(live_o), f"own 有重复特征 @{i}"
    # 4. 目标在 tanh 值域内
    assert -1.0 <= float(y) <= 1.0, f"目标越界 @{i}"
    # 5. 视角互换的自反性: 对 own 再做一次"翻转+换色"应当得到 opp 的集合
    back = set()
    for f in live_o.tolist():
        idx, sq = f // SQUARES, f % SQUARES
        back.add(flip_piece(idx) * SQUARES + flip_square(sq))
    if back != set(live_p.tolist()):
        bad += 1

print(f"检查 {n_check} 条: 视角自反性不符 {bad} 条")
assert bad == 0, "视角转换逻辑与 Kotlin 侧不一致!"
ys = np.array([float(ds[i][2]) for i in range(min(len(ds), 2000))])
print(f"目标分布: 均值={ys.mean():+.3f} 标准差={ys.std():.3f} "
      f"min={ys.min():+.3f} max={ys.max():+.3f}")
print("全部校验通过")

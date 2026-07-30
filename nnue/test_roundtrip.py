"""
编码交叉校验.

Kotlin 写、Python 读, 两边各写一遍编码逻辑就必然有对不上的风险。这里拿
真实生成的数据反推局面, 检查几条不变量 —— 全都通过才敢开始几小时的训练。

## 特征升到 v3 之后这里改了什么

加将位桶之前特征就是 `pieceIdx * 90 + sq`, 一除一模就能反推。加了桶之后是
`bucket * 1260 + pieceIdx * 90 + sq`, 而且**两个视角的桶不同**(各自看自己的
将), 所以第 5 条不变式不能再直接比整数下标, 得先剥掉桶。

这个脚本连同 test_dataset_vectorized.py 在那次升级里漏改了, 之后一直是红的
却没人发现 —— 它们是独立脚本, 不在 Gradle 套件里。默认数据目录也顺手改成
现在真正在用的语料。
"""
import glob, os, sys
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from dataset import (SelfPlayDataset, FEATURES, SQUARES, PIECE_SQ, MAX_PIECES,
                     RECORD_BYTES, SCORE_CLAMP, flip_square, flip_piece)
import model

assert FEATURES == model.FEATURES, (
    f"dataset.FEATURES={FEATURES} 与 model.FEATURES={model.FEATURES} 不一致 —— "
    "改特征维度时漏改了一处")

d = os.path.expanduser(sys.argv[1] if len(sys.argv) > 1 else "~/nnue-data-pika-d12")
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
    # 4. 目标是原始厘兵分, 应落在生成侧的截断范围内.
    #    (曾经这里断言 ±1, 因为目标一度是 tanh 压过的; 改成网络直接输出
    #     厘兵分之后这条断言就该跟着变 —— 它确实抓到了那次语义变更.)
    assert -SCORE_CLAMP <= float(y) <= SCORE_CLAMP, f"目标越界 @{i}: {float(y)}"
    # 5. 同一视角内所有特征共用一个将位桶 —— 桶由己方将唯一决定
    ob = set((live_o // PIECE_SQ).tolist())
    pb = set((live_p // PIECE_SQ).tolist())
    assert len(ob) == 1 and len(pb) == 1, f"视角内跨了多个将位桶 @{i}: {ob} {pb}"
    # 6. 视角互换的自反性: 剥掉桶之后, 对 own 再做一次"翻转+换色"应当得到 opp.
    #    桶本身不参与 —— 两个视角各看各的将, 桶号本来就该不同。
    back = set()
    for f in (live_o % PIECE_SQ).tolist():
        idx, sq = f // SQUARES, f % SQUARES
        back.add(flip_piece(idx) * SQUARES + flip_square(sq))
    if back != set((live_p % PIECE_SQ).tolist()):
        bad += 1

print(f"检查 {n_check} 条: 视角自反性不符 {bad} 条")
assert bad == 0, "视角转换逻辑与 Kotlin 侧不一致!"
ys = np.array([float(ds[i][2]) for i in range(min(len(ds), 2000))])
print(f"目标分布(厘兵分): 均值={ys.mean():+.0f} 标准差={ys.std():.0f} "
      f"min={ys.min():+.0f} max={ys.max():+.0f}")
import numpy as _np
sat = float((_np.abs(_np.tanh(ys / 400.0)) > 0.99).mean())
print(f"经 tanh(score/400) 后落在饱和区(|y|>0.99)的比例: {sat*100:.1f}%")
print("全部校验通过")

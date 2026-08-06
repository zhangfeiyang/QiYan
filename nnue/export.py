"""
把训练好的网络量化导出成端上能直接 mmap 的二进制.

    python3 nnue/export.py --ckpt nnue/net.pt --out app/src/main/assets/models/xiangqi.nnue

为什么必须量化: 手机上每秒要算几十万次评估, 浮点矩阵乘法根本跑不动.
int8/int16 配合整数累加, 才有可能在移动端达到可用的速度.

量化方案 (与 Kotlin 侧 NnueEvaluator 必须完全对应):

  激活值用定点表示, QA=127 代表 ClippedReLU 的上界 1.0
  权重按各自的尺度放大成整数, 推理时再除回去

  FeatureTransformer:  w_i16 = round(w * QA)     累加器直接是"激活值尺度"
  fc1 / fc2:           w_i8  = round(w * QW)     QW=256
  输出层:               w_i32 = round(w * QW)     见下
  偏置:                b_i32 = round(b * QA * QW) 便于和乘积直接相加

  隐藏层算完除以 QW 回到激活值尺度, 再 clamp(0, QA).
  输出层不 clamp (厘兵分有正有负没有上界), 且要除以 QA*QW 才回到厘兵分 ——
  因为输入 h2 本身带着 QA 的尺度, 隐藏层之间可以一直带着不用管, 但最后
  一步必须把它除掉.

输出层为什么不能用 int8: 它要从 32 个 [0,1] 的激活值凑出 ±2000 厘兵的
评估值, 权重量级必然在 100 上下; int8 配 QW 只能表示 |w| <= 127/QW,
差两个数量级. 这个坑在没训练好的网络上看不出来 —— 权重全是 0.2 量级,
怎么量化都不截断, 等网络真练出来才会突然全饱和.

## QW 为什么是 256 而不是原来的 64

原来 fc1/fc2 的权重实测最大只到 0.15~0.35, 乘 QW=64 之后量化值只占到
int8 的 9~22 (满量程 127), 也就是**只有二三十个可区分的档位**, 四舍五入把
大部分精度磨掉了。注意这不是溢出截断 —— 越界数一直是 0, 所以自检不会报错,
它是**分辨率浪费**, 症状只体现在"浮点 vs 整数"的误差上。

逐层消融(400 万条数据训出的网络, 300 个随机局面):
    只量化 ft        平均误差  7.4 厘兵
    只量化 fc1/fc2   平均误差 40.5 厘兵   <- 98% 的误差在这里
    只量化 out       平均误差  0.0 厘兵
    全部量化         平均误差 41.5 厘兵

QW 提到 256 让 fc1/fc2 用到 89/127 的量程, 分辨率翻两番。没取 512 是因为
W_CLIP = 127/QW 会降到 0.248, 而实测权重已经到 0.35, 会被训练时的钳位切掉。

输出层因此改成 int32: |out_w| 最大 233, 乘 256 是 59648, int16 装不下。
而它的量化误差本来就是 0.0, 加宽到 int32 只多 64 字节, 没有代价。
"""
import argparse
import os
import struct

import numpy as np
import torch

from model import (NnueNet, FEATURES, L1, L2, L3, OUT_SCALE, PSQT_Q,
                   PHASE_BUCKETS, PHASE_THRESHOLDS, PHASE_WEIGHTS,
                   PIECE_KINDS, SQUARES_, infer_phase_buckets, infer_psqt)

QA = 127      # 激活值尺度 (ClippedReLU 上界 1.0 对应 127)
QW = 256      # fc1/fc2 权重尺度. 从 64 提到 256 的理由见文件头
MAGIC = b"XQNN"
VERSION = 3          # 单输出栈，保留旧网络逐字节兼容
PHASE_VERSION = 4    # 共享 FT + 4 个按剩余子力选择的输出栈
PSQT_VERSION = 5     # v3/v4 主干 + int16 线性 PSQT 残差旁路


def q_weights(w, scale, dtype, name):
    """量化并报告截断情况 —— 截断多说明尺度选错了, 必须让人看见"""
    q = np.round(w * scale)
    lo, hi = np.iinfo(dtype).min, np.iinfo(dtype).max
    clipped = int(((q < lo) | (q > hi)).sum())
    if clipped:
        pct = clipped / q.size * 100
        print(f"  !! {name}: {clipped} 个权重被截断 ({pct:.2f}%) "
              f"—— 超过 1% 就该考虑降低尺度或在训练时加权重约束")
    return np.clip(q, lo, hi).astype(dtype)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ckpt", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--l1", type=int, default=L1,
                    help="FeatureTransformer 宽度, 必须与训练时一致")
    ap.add_argument("--phase-buckets", type=int, choices=(0, 1, PHASE_BUCKETS), default=0,
                    help=f"0=从 checkpoint 自动识别，或显式指定 1/{PHASE_BUCKETS}")
    ap.add_argument(
        "--stress", type=float, default=0.0,
        help="把输出层权重放大若干倍. 两个用途: (1) 在网络还没练出来时提前\n"
             "验证量化方案扛不扛得住训练好之后的权重量级; (2) 造有区分度的\n"
             "测试网络 —— 没练过的网络对所有局面都输出 0, 拿它做的黄金向量\n"
             "对着一个全错的实现也能通过. 试试 2000"
    )
    args = ap.parse_args()

    state = torch.load(args.ckpt, map_location="cpu", weights_only=True)
    checkpoint_buckets = infer_phase_buckets(state)
    checkpoint_psqt = infer_psqt(state)
    phase_buckets = args.phase_buckets or checkpoint_buckets
    net = NnueNet(
        l1=args.l1, phase_buckets=phase_buckets, psqt=checkpoint_psqt
    )
    net.load_compatible_state_dict(state)
    net.eval()
    print(f"[export] phase_buckets={phase_buckets} "
          f"(checkpoint={checkpoint_buckets}) psqt={checkpoint_psqt}")

    if args.stress > 0:
        # 刚开始训练的网络权重全在 0.2 量级, 怎么量化都不截断, 自检必然
        # 通过 —— 但那什么也没验证. 练成之后输出层要从 32 个 [0,1] 的激活
        # 凑出 ±2000 厘兵, 权重量级会涨到 100 上下. 这里人为放大到那个量级,
        # 把"将来才会暴露的截断/溢出"提前逼出来.
        # 只放大输出层: 中间层的输出被 ClippedReLU 钳在 [0,1], 放大它只会
        # 让激活饱和, 并不会把评估值的量级顶上去. 真正决定输出量级的只有
        # 最后一层的权重.
        print(f"[export] 压力模式: 输出层权重 ×{args.stress:.0f}")
        with torch.no_grad():
            for _, _, out in net.dense_stacks():
                out.weight.mul_(args.stress)
                out.bias.mul_(args.stress)

    def array(t):
        return t.detach().cpu().numpy()

    # EmbeddingBag 的权重是 (FEATURES+1, L1), 最后一行是 padding, 不导出
    ft_w = array(net.ft.weight)[:FEATURES]    # (11340, l1)
    ft_b = array(net.ft_bias)                 # (l1,)
    stacks = net.dense_stacks()
    fc1_w = np.stack([array(fc1.weight) for fc1, _, _ in stacks])
    fc1_b = np.stack([array(fc1.bias) for fc1, _, _ in stacks])
    fc2_w = np.stack([array(fc2.weight) for _, fc2, _ in stacks])
    fc2_b = np.stack([array(fc2.bias) for _, fc2, _ in stacks])
    # OUT_SCALE 是模型 forward 里的固定乘数, 这里直接乘进权重 ——
    # 端上不需要知道它存在, 也就不会因为两边常数不一致而出错.
    out_w = np.stack([array(out.weight)[0] for _, _, out in stacks]) * OUT_SCALE
    out_b = np.stack([array(out.bias)[0] for _, _, out in stacks]) * OUT_SCALE
    psqt_w = (
        array(net.psqt.weight)[:FEATURES, 0] * OUT_SCALE
        if net.psqt is not None else None
    )

    print("[export] 量化中...")
    ft_wq = q_weights(ft_w, QA, np.int16, "ft.weight")
    ft_bq = q_weights(ft_b, QA, np.int16, "ft_bias")
    fc1_wq = q_weights(fc1_w, QW, np.int8, "phase.fc1.weight")
    fc2_wq = q_weights(fc2_w, QW, np.int8, "phase.fc2.weight")
    out_wq = q_weights(out_w, QW, np.int32, "phase.out.weight")
    psqt_wq = (
        q_weights(psqt_w, PSQT_Q, np.int16, "psqt.weight")
        if psqt_w is not None else None
    )
    # 偏置放大到 QA*QW: 推理时它和"权重×激活"的乘积同尺度, 可以直接相加
    fc1_bq = np.round(fc1_b * QA * QW).astype(np.int32)
    fc2_bq = np.round(fc2_b * QA * QW).astype(np.int32)
    out_bq = np.round(out_b * QA * QW).astype(np.int32)

    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with open(args.out, "wb") as f:
        f.write(MAGIC)
        # 把结构写进文件头: 端上加载时校验, 尺寸对不上直接拒绝加载,
        # 而不是读出一堆噪声然后"能跑但棋力莫名其妙地差"
        if psqt_wq is not None:
            f.write(struct.pack("<9i", PSQT_VERSION, FEATURES, args.l1, L2, L3,
                                QA, QW, phase_buckets, PSQT_Q))
        elif phase_buckets == 1:
            f.write(struct.pack("<7i", VERSION, FEATURES, args.l1, L2, L3, QA, QW))
        else:
            f.write(struct.pack("<8i", PHASE_VERSION, FEATURES, args.l1, L2, L3,
                                QA, QW, phase_buckets))
        # v4 按层聚合所有阶段桶: [bucket, row, col]。v3 的 bucket 维为 1，
        # 去掉首维后字节序完全相同，因此旧格式仍能由同一段代码写出。
        for arr in (ft_wq, ft_bq, fc1_wq, fc1_bq,
                    fc2_wq, fc2_bq, out_wq, out_bq):
            f.write(arr.tobytes())
        if psqt_wq is not None:
            f.write(psqt_wq.tobytes())
    size = os.path.getsize(args.out)
    print(f"[export] 已写出 {args.out}  {size/1024:.0f} KB")
    if args.stress > 0:
        print("[export] !! 这是压力模式的产物, 权重被人为改过, 只能拿来做"
              "数值一致性测试, 绝不能当成棋力网络装进 assets")

    # 量化误差自检: 随机造几个局面, 比对浮点与整数推理的差距.
    # 这是导出环节唯一能自证正确的地方 —— 端上算出来和训练时不一样的话,
    # 网络再好也没用.
    print("[export] 量化误差自检...")
    rng = np.random.default_rng(0)
    refs, errs = [], []
    for _ in range(200):
        n = rng.integers(6, 32)
        own = rng.choice(FEATURES, size=n, replace=False)
        opp = rng.choice(FEATURES, size=n, replace=False)
        with torch.no_grad():
            o = torch.full((1, 32), FEATURES, dtype=torch.long)
            p = torch.full((1, 32), FEATURES, dtype=torch.long)
            o[0, :n] = torch.from_numpy(own)
            p[0, :n] = torch.from_numpy(opp)
            ref = float(net(o, p)[0])
        got = int_infer(ft_wq, ft_bq, fc1_wq, fc1_bq, fc2_wq, fc2_bq,
                        out_wq, out_bq, own, opp,
                        psqt_w=psqt_wq, psqt_q=PSQT_Q if psqt_wq is not None else 0)
        refs.append(ref)
        errs.append(abs(ref - got))
    refs, errs = np.array(refs), np.array(errs)

    # 必须连浮点输出的**跨度**一起报. 只报误差的话, 一个还没练出来、
    # 对所有局面都输出 0 的网络会显示"误差 0 厘兵", 看着完美, 其实什么
    # 都没验证到 —— 量化把 0 变成 0 是必然的.
    spread = refs.max() - refs.min()
    print(f"[export] 浮点输出范围 [{refs.min():.0f}, {refs.max():.0f}] 厘兵, "
          f"跨度 {spread:.0f}")
    print(f"[export] 浮点 vs 整数: 平均误差 {errs.mean():.1f} 厘兵, "
          f"最大 {errs.max():.1f} 厘兵")
    if spread < 50:
        print("[export] ?? 输出跨度太小, 这个 checkpoint 还没练出区分度, "
              "本次自检只能证明流水线没有数量级错误, 不能证明精度够用")
    elif errs.mean() > spread * 0.02:
        print(f"[export] !! 误差超过跨度的 2%, 量化尺度不合适")


def int_infer(ft_w, ft_b, fc1_w, fc1_b, fc2_w, fc2_b, out_w, out_b, own, opp,
              psqt_w=None, psqt_q=0):
    """
    整数推理参考实现. Kotlin 侧 NnueEvaluator 必须与之逐步对应.

    除法一律截断向零 (np.fix), 因为 Kotlin 的 Int `/` 就是这个语义;
    用 Python 的 `//` (向下取整) 会在负数上差 1, 端上和训练端对不上.
    隐藏层因为紧接着 clamp 到 [0,QA], 两种语义等价, 但输出层不是.
    """
    def trunc_div(v, d):
        return np.fix(v / d).astype(np.int64)

    def acc(idx):
        a = ft_b.astype(np.int32).copy()
        for i in idx:
            a += ft_w[i]
        return np.clip(a, 0, QA)

    # 兼容历史调用方传入的无 bucket 维数组。
    if fc1_w.ndim == 2:
        bucket = 0
    elif fc1_w.shape[0] == 1:
        bucket = 0
        fc1_w, fc1_b = fc1_w[0], fc1_b[0]
        fc2_w, fc2_b = fc2_w[0], fc2_b[0]
        out_w, out_b = out_w[0], out_b[0]
    else:
        safe = np.asarray(own, dtype=np.int64)
        piece = (safe % (PIECE_KINDS * SQUARES_)) // SQUARES_
        phase = int(np.asarray(PHASE_WEIGHTS, dtype=np.int64)[piece].sum())
        bucket = sum(phase >= t for t in PHASE_THRESHOLDS)
        fc1_w, fc1_b = fc1_w[bucket], fc1_b[bucket]
        fc2_w, fc2_b = fc2_w[bucket], fc2_b[bucket]
        out_w, out_b = out_w[bucket], out_b[bucket]

    x = np.concatenate([acc(own), acc(opp)]).astype(np.int64)   # 512, 尺度 QA
    h1 = np.clip(trunc_div(fc1_w.astype(np.int64) @ x + fc1_b, QW), 0, QA)
    h2 = np.clip(trunc_div(fc2_w.astype(np.int64) @ h1 + fc2_b, QW), 0, QA)
    # h2 带着 QA 的尺度, out_w 带着 QW 的尺度, 所以要除掉 QA*QW 才是厘兵分
    out_value = np.asarray(out_w, dtype=np.int64) @ h2 + np.asarray(out_b)
    result = int(trunc_div(np.asarray(out_value).reshape(-1)[0], QA * QW))
    if psqt_w is not None:
        if psqt_q <= 0:
            raise ValueError("提供 psqt_w 时 psqt_q 必须为正")
        psqt = np.asarray(psqt_w, dtype=np.int64)
        delta = psqt[np.asarray(own, dtype=np.int64)].sum()
        delta -= psqt[np.asarray(opp, dtype=np.int64)].sum()
        result += int(trunc_div(np.asarray(delta), psqt_q))
    return result


if __name__ == "__main__":
    main()

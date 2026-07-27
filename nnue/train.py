"""
训练入口.

    python3 nnue/train.py --data ~/nnue-data --epochs 10 --out nnue/net.pt

数据还在生成时也能跑: 每个 epoch 重新扫描目录, 新写进来的分片会被自动纳入.
"""
import argparse
import glob
import os
import time

import torch
import torch.nn as nn
from torch.utils.data import DataLoader

from dataset import SelfPlayDataset
from model import NnueNet, OUT_SCALE


def build_loader(data_dir, batch, workers):
    paths = sorted(glob.glob(os.path.join(data_dir, "shard-*.bin")))
    paths = [p for p in paths if os.path.getsize(p) > 0]
    if not paths:
        raise RuntimeError(f"{data_dir} 里没有非空的 shard-*.bin")
    ds = SelfPlayDataset(paths)
    print(f"[data] {len(paths)} 个分片, 共 {len(ds):,} 条样本")
    return DataLoader(
        ds, batch_size=batch, shuffle=True, num_workers=workers,
        pin_memory=True, drop_last=True, persistent_workers=workers > 0,
    )


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", required=True)
    ap.add_argument("--out", default="nnue/net.pt")
    ap.add_argument("--epochs", type=int, default=10)
    ap.add_argument("--batch", type=int, default=8192)
    ap.add_argument("--lr", type=float, default=1e-3)
    ap.add_argument("--workers", type=int, default=4)
    ap.add_argument("--l1", type=int, default=256,
                    help="FeatureTransformer 宽度. 端上求值耗时基本正比于它,\n"
                         "导出时要用同样的值")
    args = ap.parse_args()

    dev = "cuda" if torch.cuda.is_available() else "cpu"
    print(f"[dev] {dev}")

    net = NnueNet(l1=args.l1).to(dev)
    print(f"[net] l1={args.l1}")
    opt = torch.optim.AdamW(net.parameters(), lr=args.lr, weight_decay=1e-5)
    # 损失在"胜率空间"里算: 两边都过 tanh 再比.
    #
    # 直接对厘兵分做 MSE 的话, 一个 ±10000 的杀棋样本贡献的损失是普通
    # ±100 局面的一万倍, 训练会被极少数极端分数带着走, 而那些局面恰恰是
    # 搜索最容易解决、最不需要评估函数操心的. 压到 (-1,1) 之后每个样本的
    # 权重才是可比的.
    # 800 是量出来的, 不是拍的. 76k 样本实测 |分数| 中位数 301、90 分位 1295,
    # 各尺度的饱和率(|tanh|>0.99):
    #   400 -> 12.7%    600 -> 6.2%    800 -> 2.5%    1000 -> 1.5%
    # 1.3% 是 ±10000 杀棋分带来的不可约下限. 选 800: 饱和率已接近下限, 而
    # 中位数落在 tanh(301/800)=0.36 的线性区, 梯度信息保留得最好. 再大只是
    # 把最常见的 ±600 区间压平, 换不来什么.
    SCORE_SCALE = 800.0

    def lossf(pred_cp, target_cp):
        return torch.mean(
            (torch.tanh(pred_cp / SCORE_SCALE) - torch.tanh(target_cp / SCORE_SCALE)) ** 2
        )
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, T_max=args.epochs)

    # fc1/fc2 端上是 int8, 尺度 QW=64, 能表示的范围只有 |w| <= 127/64 = 1.98.
    # 训练时不管, 等导出才发现权重跑到 5 了, 那时只能截断 —— 网络就废了,
    # 而且是在跑完几天训练之后才发现. 所以每步之后直接钳住, 让优化器从一开始
    # 就在"端上表示得出来"的范围内找解. Stockfish 也是这么做的.
    W_CLIP = 127.0 / 64.0

    # 输出层是 int16, 尺度 QW=64, 上限 |w*OUT_SCALE| <= 32767/64 = 512.
    # 它会一路往上爬(实测 30 轮从 100 涨到 350+), 不钳住的话训练久了就会
    # 撞上限, 而那时只能在导出时截断 —— 又是"练完才发现白练"。
    OUT_CLIP = 32767.0 / 64.0 / OUT_SCALE

    def clamp_quantizable():
        with torch.no_grad():
            net.fc1.weight.clamp_(-W_CLIP, W_CLIP)
            net.fc2.weight.clamp_(-W_CLIP, W_CLIP)
            net.out.weight.clamp_(-OUT_CLIP, OUT_CLIP)

    for ep in range(args.epochs):
        # 每个 epoch 重扫: 数据还在后台生成, 新分片要能被吃进来
        loader = build_loader(args.data, args.batch, args.workers)
        net.train()
        total, seen, t0 = 0.0, 0, time.time()
        for own, opp, y in loader:
            own, opp, y = own.to(dev, non_blocking=True), opp.to(dev, non_blocking=True), y.to(dev, non_blocking=True)
            pred = net(own, opp)
            loss = lossf(pred, y)
            opt.zero_grad(set_to_none=True)
            loss.backward()
            # 特征层梯度稀疏且量级差异大, 不裁剪偶尔会炸
            nn.utils.clip_grad_norm_(net.parameters(), 5.0)
            opt.step()
            clamp_quantizable()
            total += loss.item() * y.size(0)
            seen += y.size(0)
        sched.step()
        secs = time.time() - t0
        # 一并报出量化敏感的权重量级: 输出层没有钳位 (它是 int16, 范围宽),
        # 但一旦 |w| 逼近 512 导出就会截断, 得能提前看见.
        # 报"乘进 OUT_SCALE 之后"的等效权重, 因为导出时截断的是它
        ow = net.out.weight.abs().max().item() * OUT_SCALE
        print(f"[ep {ep+1}/{args.epochs}] loss={total/max(seen,1):.5f} "
              f"样本={seen:,} 耗时={secs:.0f}s  |out.w|max={ow:.1f}/512")
        torch.save(net.state_dict(), args.out)
        print(f"[ckpt] 已保存 {args.out}")


if __name__ == "__main__":
    main()

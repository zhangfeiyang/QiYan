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
from model import NnueNet


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
    args = ap.parse_args()

    dev = "cuda" if torch.cuda.is_available() else "cpu"
    print(f"[dev] {dev}")

    net = NnueNet().to(dev)
    opt = torch.optim.AdamW(net.parameters(), lr=args.lr, weight_decay=1e-5)
    # 目标已经过 tanh 压到 (-1,1), 用 MSE 就够; Huber 对这个尺度没有额外好处
    lossf = nn.MSELoss()
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, T_max=args.epochs)

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
            total += loss.item() * y.size(0)
            seen += y.size(0)
        sched.step()
        secs = time.time() - t0
        print(f"[ep {ep+1}/{args.epochs}] loss={total/max(seen,1):.5f} "
              f"样本={seen:,} 耗时={secs:.0f}s")
        torch.save(net.state_dict(), args.out)
        print(f"[ckpt] 已保存 {args.out}")


if __name__ == "__main__":
    main()

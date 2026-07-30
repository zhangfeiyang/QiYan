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
from model import NnueNet, OUT_SCALE, PIECE_KINDS, SQUARES_

PIECE_SQ = PIECE_KINDS * SQUARES_


def _mirror_feat(t, flip):
    """特征下标的左右镜像. t 里的 -1 是补位, 必须原样保留。

    f = bucket*1260 + pieceIdx*90 + row*9 + col
    镜像后:  col -> 8-col, 且 bucket 按将的新列重算。
    桶 b = (kr-7)*3 + (kc-3), 镜像只改 kc -> 8-kc, 即 b 的列分量 (b%3) -> 2-(b%3)。
    """
    valid = t >= 0
    safe = torch.where(valid, t, torch.zeros_like(t))
    bucket = safe // PIECE_SQ
    rest = safe % PIECE_SQ                 # pieceIdx*90 + sq
    piece = rest // 90
    sq = rest % 90
    new_sq = sq + 8 - 2 * (sq % 9)         # 只翻列
    new_bucket = bucket - (bucket % 3) + (2 - bucket % 3)
    out = new_bucket * PIECE_SQ + piece * 90 + new_sq
    return torch.where(valid & flip, out, t)


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
    # 这两个都**默认开启**: 实测纯收益零代价, 忘了加就白亏。
    # 用 --no-mirror / --no-cache 关掉。
    ap.add_argument("--mirror", action=argparse.BooleanOptionalAction, default=True,
                    help="左右镜像增强(默认开). 象棋盘左右对称(九宫/象位/兵过河/\n"
                         "白脸将全对称), 列翻转得到的是合法局面且分数不变 ——\n"
                         "实测 +29.1 Elo (t=3.69), 零额外算力")
    ap.add_argument("--cache", action=argparse.BooleanOptionalAction, default=True,
                    help="解码一次存成张量, 之后每轮直接切片(默认开). 同一份数据\n"
                         "反复解码 N 遍是纯浪费 —— 实测每轮 80 秒降到 3 秒,\n"
                         "GPU 利用率 1%% -> 78%%。数据还在后台生成时要用\n"
                         "--no-cache, 否则新分片进不来")
    ap.add_argument("--init", default=None,
                    help="从已有 .pt checkpoint 初始化权重做微调. 不设则随机初始化")
    args = ap.parse_args()

    dev = "cuda" if torch.cuda.is_available() else "cpu"
    print(f"[dev] {dev}")

    net = NnueNet(l1=args.l1).to(dev)
    if args.init:
        state = torch.load(args.init, map_location=dev)
        net.load_state_dict(state)
        print(f"[init] 从 {args.init} 加载预训练权重, 继续微调")
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
    W_CLIP = 127.0 / 256.0   # = 127/QW, QW 见 export.py

    # 输出层是 int16, 尺度 QW=64, 上限 |w*OUT_SCALE| <= 32767/64 = 512.
    # 它会一路往上爬(实测 30 轮从 100 涨到 350+), 不钳住的话训练久了就会
    # 撞上限, 而那时只能在导出时截断 —— 又是"练完才发现白练"。
    # 导出时 out_w 会被量化成 round(w * OUT_SCALE * QW) 存进 int32
    OUT_W_LIMIT = 2147483647.0 / 256.0          # |w*OUT_SCALE| 的硬上限
    OUT_CLIP = OUT_W_LIMIT / OUT_SCALE

    def clamp_quantizable():
        with torch.no_grad():
            net.fc1.weight.clamp_(-W_CLIP, W_CLIP)
            net.fc2.weight.clamp_(-W_CLIP, W_CLIP)
            net.out.weight.clamp_(-OUT_CLIP, OUT_CLIP)

    # ---- 可选: 一次性解码进张量 ----
    #
    # 每轮重建 loader 是为了让"还在后台生成的新分片"能被吃进来。但数据生成
    # 结束之后这就是纯浪费: 同一份数据解码 40 遍, 而解码正是瓶颈 —— 实测
    # 552 万条时每轮 80 秒、GPU 利用率 1%。解码一次存成张量, 之后每轮只是
    # 显存里的切片, 每轮从 80 秒降到几秒。
    #
    # 存 int16: 特征下标最大 1260, 补位用 -1, 都在 int16 范围内。
    # 552 万条 × 32 × 2 字节 × 2 份 = 约 700MB, 显存装得下; 装不下就退回内存。
    cache = None
    if args.cache:
        t0 = time.time()
        loader = build_loader(args.data, args.batch, args.workers)
        owns, opps, ys = [], [], []
        for own, opp, y in loader:
            owns.append(own.to(torch.int16)); opps.append(opp.to(torch.int16)); ys.append(y)
        c_own = torch.cat(owns); c_opp = torch.cat(opps); c_y = torch.cat(ys)
        del owns, opps, ys
        nbytes = (c_own.numel() + c_opp.numel()) * 2 + c_y.numel() * 4
        try:
            # 三个必须**一起**成功: 只搬成一半的话后面会拿 GPU 张量去索引 CPU
            # 张量, 报的是莫名其妙的设备不匹配错误
            g_own, g_opp, g_y = c_own.to(dev), c_opp.to(dev), c_y.to(dev)
            c_own, c_opp, c_y = g_own, g_opp, g_y
            where = "显存"
        except RuntimeError:
            torch.cuda.empty_cache() if dev == "cuda" else None
            where = "内存(显存不够, 每批再搬)"
        cache = (c_own, c_opp, c_y)
        print(f"[cache] {c_y.numel():,} 条已解码进{where}, "
              f"{nbytes/1e6:.0f}MB, 耗时 {time.time()-t0:.0f}s")

    for ep in range(args.epochs):
        net.train()
        total, seen, t0 = 0.0, 0, time.time()
        if cache is not None:
            c_own, c_opp, c_y = cache
            perm = torch.randperm(c_y.numel(), device=c_y.device)
            batches = ((c_own[perm[i:i+args.batch]], c_opp[perm[i:i+args.batch]],
                        c_y[perm[i:i+args.batch]])
                       for i in range(0, c_y.numel() - args.batch + 1, args.batch))
        else:
            # 每个 epoch 重扫: 数据还在后台生成, 新分片要能被吃进来
            batches = build_loader(args.data, args.batch, args.workers)
        for own, opp, y in batches:
            own = own.to(dev, non_blocking=True).long()
            opp = opp.to(dev, non_blocking=True).long()
            y = y.to(dev, non_blocking=True)

            if args.mirror:
                # 列翻转 col -> 8-col。象棋盘左右对称, 得到的是合法局面且分数不变。
                #
                # 加入将位特征之后**不能**再用旧的 f + 8 - 2*(f%9) 快捷公式:
                # 将本身也在盘上, 镜像之后它的九宫桶跟着变(桶0<->桶2), 而那个
                # 公式只翻子的列、不动桶, 会把"将在左边"的局面标成"将还在左边"。
                # 必须拆成 桶/子类型/格子 三个分量分别处理。
                #
                # 每批随机翻一半而不是把数据集拷成两份: 40 轮下来每条样本正反
                # 两面都见过多次, 而显存占用不变。
                flip = torch.rand(own.size(0), 1, device=own.device) < 0.5
                own = _mirror_feat(own, flip)
                opp = _mirror_feat(opp, flip)
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
        # 一并报出量化敏感的权重量级. 报"乘进 OUT_SCALE 之后"的等效权重,
        # 因为导出时截断的就是它。上限随格式走: out_w 现在是 int32, 尺度 QW,
        # 所以是 2^31/QW —— 以前写死 512 那个数是 int16/QW=64 的遗留, 改了格式
        # 之后它会让人误以为权重爆了。
        ow = net.out.weight.abs().max().item() * OUT_SCALE
        print(f"[ep {ep+1}/{args.epochs}] loss={total/max(seen,1):.5f} "
              f"样本={seen:,} 耗时={secs:.0f}s  |out.w|max={ow:.0f}/{OUT_W_LIMIT:.0f}")
        torch.save(net.state_dict(), args.out)
        print(f"[ckpt] 已保存 {args.out}")


if __name__ == "__main__":
    main()

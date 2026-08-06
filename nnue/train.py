"""
训练入口.

    python3 nnue/train.py --data ~/nnue-data --epochs 10 --out nnue/net.pt

数据还在生成时也能跑: 每个 epoch 重新扫描目录, 新写进来的分片会被自动纳入.
"""
import argparse
import glob
import os
import time

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F
from torch.utils.data import DataLoader

from dataset import SelfPlayDataset, decode_bulk, RECORD_BYTES, MAX_PIECES
from model import (NnueNet, OUT_SCALE, PHASE_BUCKETS, PIECE_KINDS, SQUARES_,
                   FEATURES, PSQT_Q)

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


def build_loader(data_dirs, batch, workers):
    """
    [data_dirs] 可以给多个目录, 合成一个数据集.

    数据是分几批生成的(老师深度不同 -> 目录不同), 而"数据量"是目前实测
    最大的杠杆, 所以要能把它们并起来一起训。分片顺序按目录再按文件名排,
    保证同一组目录每次构造出的顺序一致 —— 否则同一 seed 训不出同一个网络。
    """
    if isinstance(data_dirs, str):
        data_dirs = [data_dirs]
    paths = []
    for d in data_dirs:
        got = sorted(glob.glob(os.path.join(d, "shard-*.bin")))
        got = [p for p in got if os.path.getsize(p) > 0]
        if not got:
            raise RuntimeError(f"{d} 里没有非空的 shard-*.bin")
        paths.extend(got)
    ds = SelfPlayDataset(paths)
    if len(data_dirs) > 1:
        for d in data_dirs:
            n = sum(os.path.getsize(p) for p in sorted(glob.glob(os.path.join(d, "shard-*.bin"))))
            print(f"[data]   {d}: {n // 72:,} 条")
    print(f"[data] {len(paths)} 个分片, 共 {len(ds):,} 条样本")
    return DataLoader(
        ds, batch_size=batch, shuffle=True, num_workers=workers,
        pin_memory=True, drop_last=True, persistent_workers=workers > 0,
    )


def _host_available_bytes():
    with open("/proc/meminfo") as f:
        for line in f:
            if line.startswith("MemAvailable:"):
                return int(line.split()[1]) * 1024
    return 0


def _decode_cache(data_dirs, dev="cpu", disk_cache_dir="/data/nnue/train_cache"):
    """把所有分片整块解码成三个张量, 按数据量自动落在显存/主机内存/磁盘三档之一.

    预先按总条数一次性开好数组、按分片就地填, 而不是攒一堆小数组最后 concat ——
    concat 那一下要同时拿着新旧两份, 五千万条就是凭空多占 7GB。

    ## 为什么按数据量"先估后分配", 而不是分配了再 catch 异常

    原来只有"显存不够 retry 主机内存"这一级, 靠 try/except 抓 CUDA OOM 来切换
    ——这招在显存上管用, 因为 CUDA 分配失败会干净地抛异常。但主机内存这边
    Linux 默认 overcommit, torch.empty(43GB) 本身几乎总是"成功"(只是虚拟地址
    空间), 真正撑爆是在后面逐块填充、物理页被逐步触达的时候 —— 3.28 亿条
    (43GB) 那次撑到系统内存只剩 3GB、swap 疯涨才被手动杀掉, 一亿条时更早的
    一次是直接被 systemd 的 oom-kill 连终端 scope 一起端了(训练、同步、等待
    器全没)。try/except 在这条路上抓不住, 因为分配那一刻并不失败。

    所以改成分配前先问一句"这份数据装得下装不下": 显存用 mem_get_info() 查
    剩余量, 主机内存用 /proc/meminfo 的 MemAvailable, 两边都不够就退到磁盘
    memmap(存在 --data 之外单独一个 disk_cache_dir, 不会跟正在生成的数据混)。
    memmap 随机访问比全量重解码(逐条 Python, 3 亿条要几十小时)快得多,
    换来的是能训, 不是最快。
    """
    if isinstance(data_dirs, str):
        data_dirs = [data_dirs]
    paths = []
    for d in data_dirs:
        got = sorted(glob.glob(os.path.join(d, "shard-*.bin")))
        paths.extend(p for p in got if os.path.getsize(p) > 0)
    if not paths:
        raise RuntimeError(f"{data_dirs} 里没有非空的 shard-*.bin")

    sizes = [os.path.getsize(p) // RECORD_BYTES for p in paths]
    total = sum(sizes)
    nbytes = total * (MAX_PIECES * 2 * 2 + 4)
    where = None

    if dev != "cpu":
        free_b, _ = torch.cuda.mem_get_info()
        if nbytes * 1.15 < free_b:          # 留 15% 余量给激活值/临时张量
            try:
                own = torch.empty((total, MAX_PIECES), dtype=torch.int16, device=dev)
                opp = torch.empty((total, MAX_PIECES), dtype=torch.int16, device=dev)
                y = torch.empty(total, dtype=torch.float32, device=dev)
                where = dev
            except (RuntimeError, torch.cuda.OutOfMemoryError):
                torch.cuda.empty_cache()

    if where is None:
        avail = _host_available_bytes()
        if nbytes * 1.3 < avail:             # 留 30% 余量: 主机内存还要扛 OS/其它进程
            own = torch.empty((total, MAX_PIECES), dtype=torch.int16)
            opp = torch.empty((total, MAX_PIECES), dtype=torch.int16)
            y = torch.empty(total, dtype=torch.float32)
            where = "内存(显存不够)"
        else:
            os.makedirs(disk_cache_dir, exist_ok=True)
            own = torch.from_numpy(np.memmap(
                os.path.join(disk_cache_dir, "own.bin"), dtype=np.int16, mode="w+",
                shape=(total, MAX_PIECES)))
            opp = torch.from_numpy(np.memmap(
                os.path.join(disk_cache_dir, "opp.bin"), dtype=np.int16, mode="w+",
                shape=(total, MAX_PIECES)))
            y = torch.from_numpy(np.memmap(
                os.path.join(disk_cache_dir, "y.bin"), dtype=np.float32, mode="w+",
                shape=(total,)))
            where = f"磁盘memmap({disk_cache_dir}, 显存/内存都不够)"

    at = 0
    for p, n in zip(paths, sizes):
        if n == 0:
            continue
        raw = np.memmap(p, dtype=np.uint8, mode="r")
        recs = raw[: n * RECORD_BYTES].reshape(n, RECORD_BYTES)
        # 分块处理: 一个分片可能有几百万条, 中间量按 int64 展开会很占内存
        for lo in range(0, n, 1_000_000):
            hi = min(lo + 1_000_000, n)
            o, q, sc = decode_bulk(recs[lo:hi])
            k = hi - lo
            own[at:at + k] = torch.from_numpy(o).to(own.device, non_blocking=False)
            opp[at:at + k] = torch.from_numpy(q).to(own.device, non_blocking=False)
            y[at:at + k] = torch.from_numpy(sc).to(own.device, non_blocking=False)
            at += k
        del raw, recs
    print(f"[data] {len(paths)} 个分片, 共 {total:,} 条样本 -> {where} ({nbytes/1e9:.1f}GB)")
    disk_backed = isinstance(where, str) and where.startswith("磁盘")
    return (own, opp, y, disk_backed)


def _load_validation(data_dirs, limit=500_000, label="val"):
    """把**独立目录**里的样本解码到 CPU，供每轮验证。

    不从训练 shard 的尾部临时切：那些数据只要在任意一次训练里被扫到过，
    就已经不是留出集。验证集应用新 seed 生成到单独目录。
    """
    if isinstance(data_dirs, str):
        data_dirs = [data_dirs]
    paths = []
    for d in data_dirs:
        paths.extend(p for p in sorted(glob.glob(os.path.join(d, "shard-*.bin")))
                     if os.path.getsize(p) >= RECORD_BYTES)
    if not paths:
        raise RuntimeError(f"{data_dirs} 里没有可用的 shard-*.bin")

    # 按分片均匀取，避免 limit 全被第一个线程/种子占满。
    per_path = max(1, (limit + len(paths) - 1) // len(paths)) if limit > 0 else None
    own_parts, opp_parts, y_parts = [], [], []
    remaining = limit
    for p in paths:
        raw = np.memmap(p, dtype=np.uint8, mode="r")
        n = len(raw) // RECORD_BYTES
        if n == 0:
            continue
        take = n if per_path is None else min(n, per_path, remaining)
        recs = raw[: take * RECORD_BYTES].reshape(take, RECORD_BYTES)
        own_np, opp_np, y_np = decode_bulk(recs)
        own_parts.append(torch.from_numpy(own_np.copy()))
        opp_parts.append(torch.from_numpy(opp_np.copy()))
        y_parts.append(torch.from_numpy(y_np.copy()))
        if limit > 0:
            remaining -= take
            if remaining <= 0:
                break
    own = torch.cat(own_parts)
    opp = torch.cat(opp_parts)
    y = torch.cat(y_parts)
    print(f"[{label}] {len(paths)} 个分片, 独立留出 {y.numel():,} 条 (CPU)")
    return own, opp, y


def _validate(net, validation, dev, batch, score_scale, forced_head=-1):
    own, opp, target = validation
    net.eval()
    se = abs_err = normal_abs_err = 0.0
    normal_n = 0
    sign_ok = sign_n = 0
    mate_sign_ok = mate_n = 0
    pred_sum = target_sum = pred_sq = target_sq = cross = 0.0
    phase_se = [0.0] * net.phase_buckets
    phase_abs = [0.0] * net.phase_buckets
    phase_n = [0] * net.phase_buckets
    phase_normal_n = [0] * net.phase_buckets
    n = target.numel()
    with torch.no_grad():
        for lo in range(0, n, batch):
            hi = min(lo + batch, n)
            o = own[lo:hi].to(dev, non_blocking=True).long()
            p = opp[lo:hi].to(dev, non_blocking=True).long()
            y = target[lo:hi].to(dev, non_blocking=True)
            pred = (
                net.forward_head(o, p, forced_head)
                if forced_head >= 0 else net(o, p)
            )
            sample_se = (torch.tanh(pred / score_scale) -
                         torch.tanh(y / score_scale)) ** 2
            se += torch.sum(sample_se).item()
            abs_err += torch.sum(torch.abs(pred - y)).item()
            # ±9999/10000 是“已看到杀”的哨兵，不是可按厘兵距离解释的连续值。
            # 把它混进 MAE/corr 会让一个符号正确的 +800 被算成 9200 厘兵误差，
            # 掩盖普通局面的实际拟合质量。
            normal = y.abs() < 9000
            yn, pn = y[normal], pred[normal]
            normal_abs_err += torch.sum(torch.abs(pn - yn)).item()
            normal_n += int(normal.sum().item())
            decisive = y.abs() > 50
            sign_ok += torch.sum(torch.sign(pred[decisive]) == torch.sign(y[decisive])).item()
            sign_n += int(decisive.sum().item())
            mate = ~normal
            mate_sign_ok += torch.sum(
                torch.sign(pred[mate]) == torch.sign(y[mate])
            ).item()
            mate_n += int(mate.sum().item())
            if net.phase_buckets > 1:
                buckets = net.phase_bucket(o)
                for b in range(net.phase_buckets):
                    mask = buckets == b
                    phase_se[b] += sample_se[mask].sum().item()
                    phase_n[b] += int(mask.sum().item())
                    normal_mask = mask & normal
                    phase_abs[b] += torch.abs(pred[normal_mask] - y[normal_mask]).sum().item()
                    phase_normal_n[b] += int(normal_mask.sum().item())
            pred_sum += pn.sum().item(); target_sum += yn.sum().item()
            pred_sq += torch.sum(pn * pn).item(); target_sq += torch.sum(yn * yn).item()
            cross += torch.sum(pn * yn).item()
    cov = cross - pred_sum * target_sum / max(normal_n, 1)
    pv = max(pred_sq - pred_sum * pred_sum / max(normal_n, 1), 0.0)
    tv = max(target_sq - target_sum * target_sum / max(normal_n, 1), 0.0)
    corr = cov / max((pv * tv) ** 0.5, 1e-12)
    metrics = {
        "loss": se / n,
        "mae_cp": normal_abs_err / max(normal_n, 1),
        "mae_all_cp": abs_err / n,
        "sign": sign_ok / max(sign_n, 1),
        "corr": corr,
        "mate_sign": mate_sign_ok / max(mate_n, 1),
        "mate_n": mate_n,
    }
    if net.phase_buckets > 1:
        metrics["phase"] = [
            {
                "n": phase_n[b],
                "loss": phase_se[b] / max(phase_n[b], 1),
                "mae_cp": phase_abs[b] / max(phase_normal_n[b], 1),
            }
            for b in range(net.phase_buckets)
        ]
    return metrics


def _print_validation(prefix, metrics):
    print(f"[{prefix}] loss={metrics['loss']:.5f} "
          f"MAE(non-mate)={metrics['mae_cp']:.1f}cp "
          f"符号={metrics['sign']*100:.1f}% corr(non-mate)={metrics['corr']:.4f} "
          f"杀棋符号={metrics['mate_sign']*100:.1f}%({metrics['mate_n']})")
    if "phase" in metrics:
        detail = "  ".join(
            f"b{i}:n={m['n']:,},loss={m['loss']:.5f},MAE={m['mae_cp']:.1f}cp"
            for i, m in enumerate(metrics["phase"])
        )
        print(f"[{prefix} phase] {detail}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", required=True, nargs="+",
                    help="一个或多个数据目录, 合并成一个数据集")
    ap.add_argument("--data-repeat", nargs="+", type=int, default=None,
                    help="与 --data 一一对应的重复次数，用于提高小型 DAgger 数据源权重；"
                         "缓存会实际复制这些样本，使用前估算内存")
    ap.add_argument("--out", default="nnue/net.pt")
    ap.add_argument("--epochs", type=int, default=10)
    ap.add_argument("--batch", type=int, default=8192)
    ap.add_argument("--lr", type=float, default=1e-3)
    ap.add_argument("--workers", type=int, default=4)
    ap.add_argument("--l1", type=int, default=256,
                    help="FeatureTransformer 宽度. 端上求值耗时基本正比于它,\n"
                         "导出时要用同样的值")
    ap.add_argument("--phase-buckets", type=int, choices=(1, PHASE_BUCKETS), default=1,
                    help=f"输出阶段桶数量: 1=历史 v3 单头, {PHASE_BUCKETS}=按剩余子力分桶")
    ap.add_argument("--psqt", action=argparse.BooleanOptionalAction, default=False,
                    help="启用零初始化的线性 PSQT 残差旁路；旧 checkpoint 可等值升级")
    ap.add_argument("--freeze-trunk", action=argparse.BooleanOptionalAction, default=False,
                    help="冻结旧 NNUE 主干，只训练 PSQT；用于函数保持的结构消融")
    ap.add_argument("--train-head", type=int, default=-1,
                    help="强制所有训练/主验证样本走指定输出头，并冻结除此头以外的"
                         "全部参数；-1 保持按阶段桶训练。用于独立叶评估头")
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
    ap.add_argument("--val-data", nargs="+", default=None,
                    help="独立留出集目录(不得与 --data 重叠). 设置后按 val loss 保存最佳轮")
    ap.add_argument("--val-limit", type=int, default=500_000,
                    help="最多加载多少条验证样本; <=0 表示全部")
    ap.add_argument("--val-batch", type=int, default=32768)
    ap.add_argument("--select-metric", choices=("loss", "mae_cp"), default="loss",
                    help="有验证集时保存 checkpoint 的指标；loss 保持历史口径，"
                         "mae_cp 只看非杀棋厘兵 MAE，适合避免深标签的杀棋样本"
                         "掩盖常规局面退化")
    ap.add_argument("--guard-val-data", nargs="+", default=None,
                    help="第二套独立验证集。候选只有在主 --val-data 指标改善，且这套"
                         "防退化验证集没有越过 loss/MAE 上限时才保存")
    ap.add_argument("--guard-val-limit", type=int, default=500_000,
                    help="最多加载多少条防退化验证样本；<=0 表示全部")
    ap.add_argument("--guard-max-loss-regression", type=float, default=0.005,
                    help="防退化集 loss 相对初始网络允许上升的比例（默认 0.5%%）")
    ap.add_argument("--guard-max-mae-regression", type=float, default=0.01,
                    help="防退化集非杀棋 MAE 相对初始网络允许上升的比例（默认 1%%）")
    ap.add_argument("--loss-mode", choices=("tanh", "hybrid_mae"), default="tanh",
                    help="tanh 为历史胜率空间损失；hybrid_mae 额外直接约束非杀棋厘兵误差")
    ap.add_argument("--hybrid-mae-weight", type=float, default=0.10,
                    help="hybrid_mae 中归一化非杀棋 L1 的权重")
    ap.add_argument("--seed", type=int, default=20260802,
                    help="训练随机种子(样本顺序/镜像增强)，用于复现实验")
    args = ap.parse_args()

    if args.guard_val_data and not args.val_data:
        raise ValueError("--guard-val-data 必须和主 --val-data 一起使用")
    if args.guard_max_loss_regression < 0 or args.guard_max_mae_regression < 0:
        raise ValueError("防退化允许比例不能为负数")

    if args.data_repeat is not None:
        if len(args.data_repeat) != len(args.data):
            raise ValueError("--data-repeat 的个数必须与 --data 相同")
        if any(n <= 0 for n in args.data_repeat):
            raise ValueError("--data-repeat 全部必须为正整数")
        original_data = list(args.data)
        args.data = [d for d, repeat in zip(original_data, args.data_repeat)
                     for _ in range(repeat)]
        print("[data] source repeats: " + ", ".join(
            f"{d} x{repeat}" for d, repeat in zip(original_data, args.data_repeat)
        ))
    if args.freeze_trunk and not args.psqt:
        raise ValueError("--freeze-trunk 当前只用于 --psqt 结构消融")
    if args.train_head >= 0:
        if args.phase_buckets != PHASE_BUCKETS:
            raise ValueError(f"--train-head 需要 --phase-buckets={PHASE_BUCKETS}")
        if args.train_head >= args.phase_buckets:
            raise ValueError("--train-head 超出输出头范围")
        if args.freeze_trunk:
            raise ValueError("--train-head 与 --freeze-trunk 不能同时使用")

    train_files = {
        os.path.realpath(p)
        for d in args.data
        for p in glob.glob(os.path.join(d, "shard-*.bin"))
    }
    for option, dirs in (("--val-data", args.val_data),
                         ("--guard-val-data", args.guard_val_data)):
        if not dirs:
            continue
        held_out_files = {
            os.path.realpath(p)
            for d in dirs
            for p in glob.glob(os.path.join(d, "shard-*.bin"))
        }
        overlap = train_files & held_out_files
        if overlap:
            preview = "\n  ".join(sorted(overlap)[:5])
            raise RuntimeError(
                f"{option} 与 --data 共享训练分片，验证结果会泄漏:\n  " + preview
            )

    np.random.seed(args.seed)
    torch.manual_seed(args.seed)
    if torch.cuda.is_available():
        torch.cuda.manual_seed_all(args.seed)

    dev = "cuda" if torch.cuda.is_available() else "cpu"
    print(f"[dev] {dev} seed={args.seed}")

    net = NnueNet(
        l1=args.l1, phase_buckets=args.phase_buckets, psqt=args.psqt
    ).to(dev)
    if args.init:
        state = torch.load(args.init, map_location=dev, weights_only=True)
        source_buckets = net.load_compatible_state_dict(state)
        if source_buckets == args.phase_buckets:
            detail = "同结构继续微调"
        else:
            detail = f"{source_buckets} 头等值复制到 {args.phase_buckets} 个阶段头"
        print(f"[init] 从 {args.init} 加载预训练权重, {detail}")
    if args.freeze_trunk:
        for name, parameter in net.named_parameters():
            parameter.requires_grad_(name == "psqt.weight")
        print("[net] 主干冻结，只训练 psqt.weight")
    if args.train_head >= 0:
        for parameter in net.parameters():
            parameter.requires_grad_(False)
        for layer in net.dense_stacks()[args.train_head]:
            for parameter in layer.parameters():
                parameter.requires_grad_(True)
        print(f"[net] 主干与其余输出头冻结，只训练 head={args.train_head}")
    print(f"[net] l1={args.l1} phase_buckets={args.phase_buckets} psqt={args.psqt}")
    trainable = [p for p in net.parameters() if p.requires_grad]
    opt = torch.optim.AdamW(trainable, lr=args.lr, weight_decay=1e-5)
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
        tanh_loss = torch.mean(
            (torch.tanh(pred_cp / SCORE_SCALE) - torch.tanh(target_cp / SCORE_SCALE)) ** 2
        )
        if args.loss_mode == "tanh":
            return tanh_loss
        normal = target_cp.abs() < 9000
        # 杀棋哨兵 ±10000 只有符号语义，不能以厘兵距离回传梯度；常规局面则
        # 直接最小化归一化 L1，使 checkpoint 选择的 MAE 有对应的训练信号。
        mae_loss = F.l1_loss(pred_cp[normal], target_cp[normal]) / SCORE_SCALE
        return tanh_loss + args.hybrid_mae_weight * mae_loss
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, T_max=args.epochs)

    # fc1/fc2 端上是 int8, 尺度 QW=256, 能表示的范围只有 |w| <= 127/256.
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
    PSQT_CLIP = 32767.0 / (PSQT_Q * OUT_SCALE)

    def clamp_quantizable():
        with torch.no_grad():
            for fc1, fc2, out in net.dense_stacks():
                fc1.weight.clamp_(-W_CLIP, W_CLIP)
                fc2.weight.clamp_(-W_CLIP, W_CLIP)
                out.weight.clamp_(-OUT_CLIP, OUT_CLIP)
            if net.psqt is not None:
                net.psqt.weight.clamp_(-PSQT_CLIP, PSQT_CLIP)
                net.psqt.weight[FEATURES].zero_()

    # ---- 可选: 一次性解码进张量 ----
    #
    # 每轮重建 loader 是为了让"还在后台生成的新分片"能被吃进来。但数据生成
    # 结束之后这就是纯浪费: 同一份数据解码 40 遍, 而解码正是瓶颈 —— 实测
    # 552 万条时每轮 80 秒、GPU 利用率 1%。解码一次存成张量, 之后每轮只是
    # 显存里的切片, 每轮从 80 秒降到几秒。
    #
    # 存 int16: 特征下标最大 11339, 补位用 -1, 都在 int16 范围内。
    # 5563 万条 × 32 × 2 字节 × 2 份 = 约 7.1GB, 显存装得下; 装不下就退回内存。
    #
    # 解码走 decode_bulk 整块做, 不走 DataLoader 逐条:
    # 逐条要为每条记录跑一遍 Python + 十来个 32 元素的 numpy 调用, 再 pickle
    # 回主进程, 实测 5563 万条要一小时; 整块做纯粹是逐行独立的算术, 实测同样
    # 的数据快 59 倍(单进程口径), 结果由 test_decode_bulk.py 锁住逐位相同。
    cache = None
    if args.cache:
        t0 = time.time()
        # 目标张量直接开在 dev 上, 解码一块拷一块 —— 不再"主机建满再整体搬",
        # 那种做法一亿条要 13.2GB 主机内存, 实测被 oom-kill 连锅端。
        c_own, c_opp, c_y, disk_backed = _decode_cache(args.data, dev)
        cache = (c_own, c_opp, c_y, disk_backed)
        print(f"[cache] {c_y.numel():,} 条已就绪于 {c_y.device}"
              f"{'(磁盘, 分块顺序读)' if disk_backed else ''}, 耗时 {time.time()-t0:.0f}s")

    validation = (_load_validation(args.val_data, args.val_limit, "val")
                  if args.val_data else None)
    guard_validation = (_load_validation(
        args.guard_val_data, args.guard_val_limit, "guard"
    ) if args.guard_val_data else None)
    best_val = float("inf")
    guard_baseline = None
    if validation is not None:
        # 微调时把初始网络也纳入候选；否则第一轮即使退化也会被误当成“最佳”。
        metrics = _validate(
            net, validation, dev, args.val_batch, SCORE_SCALE, args.train_head
        )
        best_val = metrics[args.select_metric]
        _print_validation(f"val 0/{args.epochs}", metrics)
        print(f"[ckpt] 选择指标={args.select_metric} 初值={best_val:.5f}")
        torch.save(net.state_dict(), args.out)
        print(f"[ckpt] 初始验证基线已保存 {args.out}")
    if guard_validation is not None:
        guard_baseline = _validate(
            net, guard_validation, dev, args.val_batch, SCORE_SCALE,
            0 if args.train_head >= 0 else -1,
        )
        _print_validation(f"guard 0/{args.epochs}", guard_baseline)
        loss_limit = guard_baseline["loss"] * (1.0 + args.guard_max_loss_regression)
        mae_limit = guard_baseline["mae_cp"] * (1.0 + args.guard_max_mae_regression)
        print(f"[guard] 保存上限 loss<={loss_limit:.5f} "
              f"MAE(non-mate)<={mae_limit:.2f}cp")

    for ep in range(args.epochs):
        net.train()
        total, seen, t0 = 0.0, 0, time.time()
        if cache is not None and disk_backed:
            c_own, c_opp, c_y, _ = cache
            # 磁盘 memmap 不能像内存/显存那样"随手全局 randperm 再逐元素 gather"——
            # 那样每批 8192 条要在 3.5 亿行里随机点 8192 次, 每次都是一次独立的
            # 磁盘寻址(实测这条路 IOPS 3万+, NVMe 花在寻址上, %util 只有 67%,
            # GPU 利用率被拖到 0%, 训一轮的时间全花在等磁盘上)。
            #
            # 改成分块: 先把"块的顺序"打乱(块间随机), 每块用一次顺序读整体
            # 搬进主机内存(这才是 NVMe 真正快的模式), 到内存之后块内再 randperm
            # (内存里怎么打乱都不占磁盘 IO)。丢的是"跨块的样本混合度", 换来的
            # 是 IO 从"3.5亿次随机寻址"降到"几十次顺序大块读"。
            n = c_y.numel()
            BLOCK = 4_000_000                              # 约 4M 条 ≈ 528MB/块
            nblocks = (n + BLOCK - 1) // BLOCK
            block_order = torch.randperm(nblocks).tolist()

            def _disk_batches():
                for bi in block_order:
                    lo, hi = bi * BLOCK, min(bi * BLOCK + BLOCK, n)
                    # .clone() 才会真正触发拷贝(顺序读进新分配的主机内存);
                    # 光包一层 torch.from_numpy/np.asarray 只是重解释, 底下存储
                    # 还是 memmap, 后面 fancy-index 一样会退回逐行随机寻址。
                    bo = c_own[lo:hi].clone()
                    bq = c_opp[lo:hi].clone()
                    by = c_y[lo:hi].clone()
                    lp = torch.randperm(hi - lo)
                    bo, bq, by = bo[lp], bq[lp], by[lp]
                    for i in range(0, (hi - lo) - args.batch + 1, args.batch):
                        yield bo[i:i + args.batch], bq[i:i + args.batch], by[i:i + args.batch]
            batches = _disk_batches()
        elif cache is not None:
            c_own, c_opp, c_y, _ = cache
            # 排列表**在主机内存里生成**, 每批只把那几千个下标搬到显存。
            #
            # 一亿条的 randperm 是 int64、足足 808MB, 而数据本身已经吃掉
            # 14.6GB, 显存只剩 300MB —— 实测就是在这一行 CUDA OOM。放主机
            # 内存则毫无压力(有 23GB), 每批多搬 8192×8=64KB, 可以忽略。
            # 采样语义和原来完全一样, 不影响与之前几版的可比性。
            n = c_y.numel()
            perm = torch.randperm(n)                      # CPU
            dv = c_y.device
            batches = ((lambda ix: (c_own[ix], c_opp[ix], c_y[ix]))(
                            perm[i:i + args.batch].to(dv))
                       for i in range(0, n - args.batch + 1, args.batch))
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
            pred = (
                net.forward_head(own, opp, args.train_head)
                if args.train_head >= 0 else net(own, opp)
            )
            loss = lossf(pred, y)
            opt.zero_grad(set_to_none=True)
            loss.backward()
            # 特征层梯度稀疏且量级差异大, 不裁剪偶尔会炸
            nn.utils.clip_grad_norm_(trainable, 5.0)
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
        ow = max(out.weight.abs().max().item()
                 for _, _, out in net.dense_stacks()) * OUT_SCALE
        print(f"[ep {ep+1}/{args.epochs}] loss={total/max(seen,1):.5f} "
              f"样本={seen:,} 耗时={secs:.0f}s  |out.w|max={ow:.0f}/{OUT_W_LIMIT:.0f}")
        if validation is not None:
            metrics = _validate(
                net, validation, dev, args.val_batch, SCORE_SCALE, args.train_head
            )
            _print_validation(f"val {ep+1}/{args.epochs}", metrics)
            guard_metrics = None
            guard_ok = True
            if guard_validation is not None:
                guard_metrics = _validate(
                    net, guard_validation, dev, args.val_batch, SCORE_SCALE,
                    0 if args.train_head >= 0 else -1,
                )
                _print_validation(f"guard {ep+1}/{args.epochs}", guard_metrics)
                guard_ok = (
                    guard_metrics["loss"] <= loss_limit and
                    guard_metrics["mae_cp"] <= mae_limit
                )
            candidate_val = metrics[args.select_metric]
            if candidate_val < best_val and guard_ok:
                best_val = candidate_val
                torch.save(net.state_dict(), args.out)
                print(f"[ckpt] 新最佳 {args.select_metric}={best_val:.5f}, 已保存 {args.out}")
            elif candidate_val < best_val and not guard_ok:
                print(f"[ckpt] 主指标改善到 {candidate_val:.5f}，但防退化门禁未通过，跳过")
        else:
            torch.save(net.state_dict(), args.out)
            print(f"[ckpt] 已保存 {args.out}")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""在普通价值留出集约束下，用高后悔值着法对微调 DAgger NNUE。"""
import argparse
import glob
import os
import time

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F

from dataset import PAIR_RECORD_BYTES, decode_pair_bulk
from model import NnueNet, OUT_SCALE
from train import (
    _decode_cache,
    _load_validation,
    _mirror_feat,
    _print_validation,
    _validate,
)

SCORE_SCALE = 800.0
W_CLIP = 127.0 / 256.0
OUT_W_LIMIT = 2147483647.0 / 256.0
OUT_CLIP = OUT_W_LIMIT / OUT_SCALE


def discover_pair_files(values):
    paths = []
    for value in values:
        if os.path.isdir(value):
            paths.extend(sorted(glob.glob(os.path.join(value, "*pair*.bin"))))
        elif os.path.isfile(value):
            paths.append(value)
        else:
            raise FileNotFoundError(value)
    paths = [p for p in paths if os.path.getsize(p) >= PAIR_RECORD_BYTES]
    if not paths:
        raise RuntimeError("没有可用的后悔值配对 .bin")
    return paths


def load_pairs(values, max_abs_target=None):
    parts = [[] for _ in range(6)]
    paths = discover_pair_files(values)
    total = 0
    for path in paths:
        raw = np.memmap(path, dtype=np.uint8, mode="r")
        n = len(raw) // PAIR_RECORD_BYTES
        if n == 0:
            continue
        decoded = decode_pair_bulk(raw[:n * PAIR_RECORD_BYTES].reshape(n, PAIR_RECORD_BYTES))
        for dest, array in zip(parts, decoded):
            dest.append(torch.from_numpy(array.copy()))
        total += n
    tensors = tuple(torch.cat(part) for part in parts)
    before_filter = tensors[2].numel()
    if max_abs_target is not None:
        keep = (tensors[2].abs() <= max_abs_target) & (tensors[5].abs() <= max_abs_target)
        tensors = tuple(tensor[keep] for tensor in tensors)
        print(
            f"[pairs] 目标绝对值 <= {max_abs_target:.0f}cp: "
            f"保留 {int(keep.sum().item()):,}/{before_filter:,} 对"
        )
        if tensors[2].numel() == 0:
            raise RuntimeError("配对过滤后为空")
    teacher_margin = tensors[5] - tensors[2]
    if torch.any(teacher_margin <= 0):
        bad = int(torch.sum(teacher_margin <= 0).item())
        raise RuntimeError(f"配对中有 {bad} 条教师分差非正，疑似视角或顺序写反")
    print(
        f"[pairs] {len(paths)} 个文件，共 {total:,} 对，"
        f"后悔值 mean={teacher_margin.mean().item():.1f}cp "
        f"p50={teacher_margin.median().item():.0f}cp "
        f"max={teacher_margin.max().item():.0f}cp"
    )
    return tensors


def pair_metrics(net, pairs, dev, batch, margin_cap, rank_margin):
    good_o, good_p, good_y, bad_o, bad_p, bad_y = pairs
    n = good_y.numel()
    value_se = rank_sum = hinge_sum = margin_abs = order_ok = 0.0
    pred_margin_sum = teacher_margin_sum = 0.0
    normal_order_ok = normal_n = tactical_order_ok = tactical_n = 0.0
    normal_hinge_sum = tactical_hinge_sum = 0.0
    net.eval()
    with torch.no_grad():
        for lo in range(0, n, batch):
            hi = min(lo + batch, n)
            go = good_o[lo:hi].to(dev).long()
            gp = good_p[lo:hi].to(dev).long()
            bo = bad_o[lo:hi].to(dev).long()
            bp = bad_p[lo:hi].to(dev).long()
            gy = good_y[lo:hi].to(dev)
            by = bad_y[lo:hi].to(dev)
            good_pred = net(go, gp)
            bad_pred = net(bo, bp)
            pred_margin = bad_pred - good_pred
            teacher_margin = (by - gy).clamp(0, margin_cap)
            value_se += 0.5 * torch.sum(
                (torch.tanh(good_pred / SCORE_SCALE) - torch.tanh(gy / SCORE_SCALE)) ** 2
                + (torch.tanh(bad_pred / SCORE_SCALE) - torch.tanh(by / SCORE_SCALE)) ** 2
            ).item()
            rank_sum += F.smooth_l1_loss(
                pred_margin / SCORE_SCALE,
                teacher_margin / SCORE_SCALE,
                reduction="sum",
            ).item()
            sample_hinge = F.relu((rank_margin - pred_margin) / SCORE_SCALE)
            hinge_sum += sample_hinge.sum().item()
            margin_abs += torch.sum(torch.abs(pred_margin - teacher_margin)).item()
            order_ok += torch.sum(pred_margin > 0).item()
            normal = (gy.abs() < 9000) & (by.abs() < 9000)
            tactical = ~normal
            normal_order_ok += torch.sum((pred_margin > 0) & normal).item()
            normal_n += normal.sum().item()
            normal_hinge_sum += sample_hinge[normal].sum().item()
            tactical_order_ok += torch.sum((pred_margin > 0) & tactical).item()
            tactical_n += tactical.sum().item()
            tactical_hinge_sum += sample_hinge[tactical].sum().item()
            pred_margin_sum += pred_margin.sum().item()
            teacher_margin_sum += teacher_margin.sum().item()
    return {
        "value_loss": value_se / n,
        "rank_loss": rank_sum / n,
        "hinge_loss": hinge_sum / n,
        "rank_acc": order_ok / n,
        "margin_mae": margin_abs / n,
        "pred_margin": pred_margin_sum / n,
        "teacher_margin": teacher_margin_sum / n,
        "normal_rank_acc": normal_order_ok / max(normal_n, 1),
        "normal_hinge_loss": normal_hinge_sum / max(normal_n, 1),
        "normal_n": int(normal_n),
        "tactical_rank_acc": tactical_order_ok / max(tactical_n, 1),
        "tactical_hinge_loss": tactical_hinge_sum / max(tactical_n, 1),
        "tactical_n": int(tactical_n),
    }


def print_pair_metrics(prefix, metrics):
    print(
        f"[{prefix}] rank_loss={metrics['rank_loss']:.5f} "
        f"hinge={metrics['hinge_loss']:.5f} "
        f"排序={metrics['rank_acc'] * 100:.1f}% "
        f"margin_MAE={metrics['margin_mae']:.1f}cp "
        f"预测/教师margin={metrics['pred_margin']:.1f}/{metrics['teacher_margin']:.1f}cp "
        f"pair_value_loss={metrics['value_loss']:.5f} "
        f"常规={metrics['normal_rank_acc'] * 100:.1f}%/h{metrics['normal_hinge_loss']:.5f}"
        f"({metrics['normal_n']}) "
        f"杀棋/极值={metrics['tactical_rank_acc'] * 100:.1f}%/"
        f"h{metrics['tactical_hinge_loss']:.5f}({metrics['tactical_n']})"
    )


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pair-data", nargs="+", required=True)
    ap.add_argument("--pair-val-data", nargs="+", required=True)
    ap.add_argument("--anchor-data", nargs="+", required=True,
                    help="普通 DAgger 价值样本，用于防止只记住分歧局面")
    ap.add_argument("--val-data", nargs="+", required=True,
                    help="独立普通价值留出集")
    ap.add_argument("--init", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--epochs", type=int, default=12)
    ap.add_argument("--batch", type=int, default=2048)
    ap.add_argument("--anchor-ratio", type=float, default=2.0)
    ap.add_argument("--lr", type=float, default=2e-5)
    ap.add_argument("--pair-value-weight", type=float, default=0.5)
    ap.add_argument("--rank-weight", type=float, default=1.0)
    ap.add_argument("--rank-mode", choices=("regression", "hinge"), default="hinge",
                    help="regression 拟合完整教师分差；hinge 只要求优选着领先小间隔")
    ap.add_argument("--rank-margin", type=float, default=30.0,
                    help="hinge 模式要求的最小预测分差（厘兵）")
    ap.add_argument("--margin-cap", type=float, default=800.0)
    ap.add_argument("--max-pair-abs-target", type=float, default=10_000.0,
                    help="训练配对两端标签绝对值上限；8999 可排除 mate/极值哨兵")
    ap.add_argument("--select-pair-metric",
                    choices=("hinge_loss", "normal_hinge_loss", "rank_loss"),
                    default=None,
                    help="checkpoint 的配对选择指标；默认随 rank-mode 选择")
    ap.add_argument("--max-val-regression", type=float, default=0.005,
                    help="普通留出 loss 最多允许比初始网络回退的比例")
    ap.add_argument("--val-limit", type=int, default=500_000)
    ap.add_argument("--seed", type=int, default=20260803)
    ap.add_argument("--cache-dir", default="/data/nnue/regret_anchor_cache")
    args = ap.parse_args()

    if args.epochs <= 0 or args.batch <= 0 or args.anchor_ratio <= 0:
        raise ValueError("epochs/batch/anchor-ratio 必须为正")
    if not 0 <= args.max_val_regression < 1:
        raise ValueError("max-val-regression 必须在 [0,1) 内")
    if os.path.exists(args.out):
        raise FileExistsError(f"拒绝覆盖已有 checkpoint: {args.out}")

    np.random.seed(args.seed)
    torch.manual_seed(args.seed)
    if torch.cuda.is_available():
        torch.cuda.manual_seed_all(args.seed)
    dev = "cuda" if torch.cuda.is_available() else "cpu"
    print(f"[dev] {dev} seed={args.seed}")

    train_pairs = load_pairs(args.pair_data, args.max_pair_abs_target)
    val_pairs = load_pairs(args.pair_val_data)
    anchor_o, anchor_p, anchor_y, disk_backed = _decode_cache(
        args.anchor_data, dev, args.cache_dir
    )
    if disk_backed:
        raise RuntimeError("anchor 数据落到磁盘 memmap；本微调器要求能装入内存或显存")
    validation = _load_validation(args.val_data, args.val_limit)

    net = NnueNet(l1=256, phase_buckets=1).to(dev)
    net.load_compatible_state_dict(torch.load(args.init, map_location=dev, weights_only=True))
    opt = torch.optim.AdamW(net.parameters(), lr=args.lr, weight_decay=1e-5)
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, T_max=args.epochs)

    def clamp_quantizable():
        with torch.no_grad():
            for fc1, fc2, out in net.dense_stacks():
                fc1.weight.clamp_(-W_CLIP, W_CLIP)
                fc2.weight.clamp_(-W_CLIP, W_CLIP)
                out.weight.clamp_(-OUT_CLIP, OUT_CLIP)

    def value_loss(pred, target):
        return torch.mean(
            (torch.tanh(pred / SCORE_SCALE) - torch.tanh(target / SCORE_SCALE)) ** 2
        )

    base_value = _validate(net, validation, dev, 32768, SCORE_SCALE)
    _print_validation(f"value val 0/{args.epochs}", base_value)
    base_pair = pair_metrics(
        net, val_pairs, dev, 32768, args.margin_cap, args.rank_margin
    )
    print_pair_metrics(f"pair val 0/{args.epochs}", base_pair)
    select_key = args.select_pair_metric or (
        "hinge_loss" if args.rank_mode == "hinge" else "rank_loss"
    )
    best_rank = base_pair[select_key]
    torch.save(net.state_dict(), args.out)
    print(f"[ckpt] 初始 DAgger 已保存；普通 val loss 门槛 "
          f"{base_value['loss'] * (1 + args.max_val_regression):.5f}")

    pair_n = train_pairs[2].numel()
    anchor_n = anchor_y.numel()
    for epoch in range(args.epochs):
        net.train()
        order = torch.randperm(pair_n)
        totals = {"all": 0.0, "anchor": 0.0, "pair": 0.0, "rank": 0.0}
        seen = 0
        started = time.time()
        for lo in range(0, pair_n, args.batch):
            ix = order[lo:min(lo + args.batch, pair_n)]
            if ix.numel() == 0:
                continue
            go, gp, gy, bo, bp, by = (
                tensor[ix].to(dev, non_blocking=True) for tensor in train_pairs
            )
            go = go.long(); gp = gp.long(); bo = bo.long(); bp = bp.long()

            anchor_count = max(1, int(ix.numel() * args.anchor_ratio))
            anchor_ix = torch.randint(anchor_n, (anchor_count,), device=anchor_y.device)
            ao = anchor_o[anchor_ix].to(dev, non_blocking=True).long()
            ap_ = anchor_p[anchor_ix].to(dev, non_blocking=True).long()
            ay = anchor_y[anchor_ix].to(dev, non_blocking=True)

            pair_flip = torch.rand(ix.numel(), 1, device=dev) < 0.5
            go = _mirror_feat(go, pair_flip); gp = _mirror_feat(gp, pair_flip)
            bo = _mirror_feat(bo, pair_flip); bp = _mirror_feat(bp, pair_flip)
            anchor_flip = torch.rand(anchor_count, 1, device=dev) < 0.5
            ao = _mirror_feat(ao, anchor_flip); ap_ = _mirror_feat(ap_, anchor_flip)

            anchor_pred = net(ao, ap_)
            good_pred = net(go, gp)
            bad_pred = net(bo, bp)
            anchor_loss = value_loss(anchor_pred, ay)
            pair_value = 0.5 * (value_loss(good_pred, gy) + value_loss(bad_pred, by))
            teacher_margin = (by - gy).clamp(0, args.margin_cap)
            pred_margin = bad_pred - good_pred
            if args.rank_mode == "hinge":
                rank_loss = torch.mean(
                    F.relu((args.rank_margin - pred_margin) / SCORE_SCALE)
                )
            else:
                rank_loss = F.smooth_l1_loss(
                    pred_margin / SCORE_SCALE, teacher_margin / SCORE_SCALE
                )
            loss = (
                anchor_loss
                + args.pair_value_weight * pair_value
                + args.rank_weight * rank_loss
            )

            opt.zero_grad(set_to_none=True)
            loss.backward()
            nn.utils.clip_grad_norm_(net.parameters(), 5.0)
            opt.step()
            clamp_quantizable()

            n = ix.numel()
            totals["all"] += loss.item() * n
            totals["anchor"] += anchor_loss.item() * n
            totals["pair"] += pair_value.item() * n
            totals["rank"] += rank_loss.item() * n
            seen += n
        sched.step()
        print(
            f"[ep {epoch + 1}/{args.epochs}] loss={totals['all']/seen:.5f} "
            f"anchor={totals['anchor']/seen:.5f} pair={totals['pair']/seen:.5f} "
            f"rank={totals['rank']/seen:.5f} pairs={seen:,} "
            f"耗时={time.time()-started:.1f}s"
        )

        value_metrics = _validate(net, validation, dev, 32768, SCORE_SCALE)
        _print_validation(f"value val {epoch + 1}/{args.epochs}", value_metrics)
        pair_result = pair_metrics(
            net, val_pairs, dev, 32768, args.margin_cap, args.rank_margin
        )
        print_pair_metrics(f"pair val {epoch + 1}/{args.epochs}", pair_result)
        value_ok = value_metrics["loss"] <= base_value["loss"] * (1 + args.max_val_regression)
        if pair_result[select_key] < best_rank and value_ok:
            best_rank = pair_result[select_key]
            torch.save(net.state_dict(), args.out)
            print(f"[ckpt] 新最佳 pair {select_key}={best_rank:.5f}，"
                  "普通 value val 通过，已保存")
        elif not value_ok:
            print("[ckpt] 普通 value val 超过回退门槛，不保存")

    print(f"[done] 最佳 checkpoint: {args.out} pair {select_key}={best_rank:.5f}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

"""
在**留出集**上直接比两个网络的评估准确度。

    python3 nnue/compare.py --a nnue/net_gen0.pt --b nnue/net_gen1.pt \
        --data ~/nnue-data --holdout 1500

为什么要有它: 实战对局是唯一的最终判据, 但一局要几秒, 跑够局数拿到能用的
置信区间要十几分钟。而"评估准不准"是纯批量前向, GPU 上几秒就完事 —— 拿它
做**前置筛选**: 连留出集都赢不了的网络, 不值得再花十几分钟去打棋。

**但它不能替代对局。** 评估更准不等于棋更强: 误差可能集中在搜索本来就能
解决的局面上, 也可能在关键局面上系统性偏一边。所以这里只回答"要不要继续
测", 不回答"要不要发版"。

留出集怎么来的 (关键, 不然这个对比毫无意义):
训练脚本每个 epoch 重扫目录, 日志里的最后一条 `[data] 共 N 条` 就是训练
见过的上界。之后数据生成还在追加, 那部分谁都没见过。分片是追加写, 所以
取每个分片的**尾部**即可 —— 只要取的条数小于"训练结束后新增的量/分片数"。
"""
import argparse
import glob
import os

import numpy as np
import torch

from dataset import RECORD_BYTES, SelfPlayDataset
from model import NnueNet


def tail_dataset(data_dir, per_shard):
    """取每个分片尾部 per_shard 条, 拼成留出集"""
    paths = sorted(glob.glob(os.path.join(os.path.expanduser(data_dir), "shard-[0-9]*.bin")))
    if not paths:
        raise SystemExit(f"{data_dir} 里没有 shard-N.bin")
    chunks = []
    for p in paths:
        raw = np.memmap(p, dtype=np.uint8, mode="r")
        n = len(raw) // RECORD_BYTES
        take = min(per_shard, n)
        tail = np.array(raw[(n - take) * RECORD_BYTES: n * RECORD_BYTES])
        chunks.append(tail)
        print(f"  {os.path.basename(p)}: 共 {n:,} 条, 取尾部 {take:,} 条")
    blob = np.concatenate(chunks)
    tmp = os.path.join("/tmp", "nnue_holdout.bin")
    blob.tofile(tmp)
    return SelfPlayDataset([tmp])


def evaluate(net, loader, dev, scale):
    """返回 (胜率空间损失, 厘兵平均绝对误差, 正负号一致率, 相关系数)"""
    net.eval()
    se = n = 0.0
    abs_err = 0.0
    sign_ok = 0
    preds, targets = [], []
    with torch.no_grad():
        for own, opp, y in loader:
            own, opp, y = own.to(dev), opp.to(dev), y.to(dev)
            p = net(own, opp)
            se += torch.sum((torch.tanh(p / scale) - torch.tanh(y / scale)) ** 2).item()
            abs_err += torch.sum(torch.abs(p - y)).item()
            # 只在双方都明确有优劣时算符号一致 (±50 厘兵内算均势, 符号没意义)
            m = (y.abs() > 50) & (p.abs() > 50)
            sign_ok += torch.sum((torch.sign(p[m]) == torch.sign(y[m]))).item()
            n += y.numel()
            preds.append(p.cpu())
            targets.append(y.cpu())
            del own, opp, y, p
    pr = torch.cat(preds).numpy()
    tg = torch.cat(targets).numpy()
    m = (np.abs(tg) > 50) & (np.abs(pr) > 50)
    corr = float(np.corrcoef(pr, tg)[0, 1])
    return se / n, abs_err / n, sign_ok / max(m.sum(), 1), corr


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--a", required=True, help="网络 A 的 .pt")
    ap.add_argument("--b", required=True, help="网络 B 的 .pt")
    ap.add_argument("--data", default="~/nnue-data")
    ap.add_argument("--holdout", type=int, default=1500,
                    help="每个分片取尾部多少条; 必须小于训练结束后新增的量/分片数")
    ap.add_argument("--l1", type=int, default=256)
    ap.add_argument("--batch", type=int, default=16384)
    ap.add_argument("--scale", type=float, default=800.0)
    args = ap.parse_args()

    print(f"[holdout] 每分片取尾部 {args.holdout:,} 条:")
    ds = tail_dataset(args.data, args.holdout)
    print(f"[holdout] 留出集共 {len(ds):,} 条")

    dev = "cuda" if torch.cuda.is_available() else "cpu"
    print(f"[dev] {dev}")
    loader = torch.utils.data.DataLoader(ds, batch_size=args.batch, num_workers=4)

    rows = []
    for tag, path in (("A", args.a), ("B", args.b)):
        net = NnueNet(l1=args.l1).to(dev)
        net.load_state_dict(torch.load(path, map_location=dev))
        rows.append((tag, os.path.basename(path)) + evaluate(net, loader, dev, args.scale))
        del net
        torch.cuda.empty_cache() if dev == "cuda" else None

    # 基线: 恒定输出标签均值。没有它就看不出"损失 0.02"到底算好还是算差。
    ys = torch.cat([y for _, _, y in loader])
    t = torch.tanh(ys / args.scale)
    base = float(torch.mean((t - t.mean()) ** 2))

    print()
    print(f"{'':2} {'网络':22} {'损失':>9} {'厘兵MAE':>9} {'符号一致':>9} {'相关系数':>9}")
    for tag, name, loss, mae, sign, corr in rows:
        print(f"{tag:2} {name:22} {loss:9.5f} {mae:9.1f} {sign*100:8.1f}% {corr:9.4f}")
    print(f"{'--':2} {'恒定输出均值(基线)':22} {base:9.5f}")

    la, lb = rows[0][2], rows[1][2]
    print()
    if lb < la:
        print(f"[结论] B 的留出损失比 A 低 {(la-lb)/la*100:.1f}% —— 值得再跑实战对局验证")
    elif lb > la:
        print(f"[结论] B 反而比 A 高 {(lb-la)/la*100:.1f}% —— 别浪费时间打棋了")
    else:
        print("[结论] 两者持平")
    print("[提醒] 评估更准 != 棋更强。最终判据仍是 NnueGauntletRunner 的实战 Elo。")


if __name__ == "__main__":
    main()

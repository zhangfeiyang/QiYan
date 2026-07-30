"""左右镜像增强的正确性.

象棋盘左右对称, 列翻转得到的是合法局面且分数不变。这里验证特征下标上的
快捷公式 f + 8 - 2*(f%9) 确实等价于"解码成 (子类型,格子) 再翻列再编码",
并且补位 -1 不会被误伤。

    python3 nnue/test_mirror.py
"""
import sys
import numpy as np
import torch

SQUARES = 90


def slow_mirror(f):
    """朴素做法: 解码、翻列、再编码"""
    if f < 0:
        return f
    pi, sq = f // SQUARES, f % SQUARES
    row, col = sq // 9, sq % 9
    return pi * SQUARES + row * 9 + (8 - col)


def fast_mirror(t):
    """训练里用的向量化形式"""
    return torch.where(t >= 0, t + 8 - 2 * (t % 9), t)


def main():
    # 全部合法下标 + 补位
    feats = list(range(14 * SQUARES)) + [-1] * 20
    t = torch.tensor(feats, dtype=torch.long)

    fast = fast_mirror(t).tolist()
    slow = [slow_mirror(f) for f in feats]
    if fast != slow:
        n = sum(1 for a, b in zip(fast, slow) if a != b)
        print(f"[test] !! 快捷公式与朴素做法不一致 {n} 处")
        return 1

    # 镜像两次必须还原
    twice = fast_mirror(fast_mirror(t)).tolist()
    if twice != feats:
        n = sum(1 for a, b in zip(twice, feats) if a != b)
        print(f"[test] !! 镜像两次没还原, {n} 处不一致")
        return 1

    # 补位必须原样保留 (torch 的 (-1)%9 是 8, 套公式会算成 -9)
    if any(x != -1 for x in fast[-20:]):
        print("[test] !! 补位 -1 被公式改坏了")
        return 1

    print(f"[test] {len(feats)} 个下标: 快捷公式 == 朴素做法, 镜像两次还原, 补位未受影响")
    return 0


if __name__ == "__main__":
    sys.exit(main())

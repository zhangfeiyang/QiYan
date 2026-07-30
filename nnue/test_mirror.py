"""左右镜像增强的正确性 (带将位特征).

象棋盘左右对称, 列翻转得到的是合法局面且分数不变。但加入将位特征之后,
镜像不能再对特征下标直接做算术 —— 将本身也在盘上, 镜像后它的九宫桶跟着变。
这里用"解码 → 分量镜像 → 重编码"的朴素做法当参照, 验证训练里那个向量化
实现与它逐位相同, 并且镜像两次能还原。

    python3 nnue/test_mirror.py
"""
import sys
import torch

sys.path.insert(0, __file__.rsplit("/", 1)[0])
from model import FEATURES, PIECE_KINDS, SQUARES_          # noqa: E402
from train import _mirror_feat, PIECE_SQ                    # noqa: E402


def slow_mirror(f):
    """朴素做法: 拆成 桶/子类型/格子, 各自镜像, 再编回去"""
    if f < 0:
        return f
    bucket, rest = f // PIECE_SQ, f % PIECE_SQ
    piece, sq = rest // SQUARES_, rest % SQUARES_
    # 格子: 只翻列
    new_sq = (sq // 9) * 9 + (8 - sq % 9)
    # 桶: b = (kr-7)*3 + (kc-3), 镜像 kc -> 8-kc 即列分量 -> 2-列分量
    kr, kc = bucket // 3, bucket % 3
    new_bucket = kr * 3 + (2 - kc)
    return new_bucket * PIECE_SQ + piece * SQUARES_ + new_sq


def main():
    feats = list(range(FEATURES)) + [-1] * 32
    # 形状必须和训练时一致: (batch, MAX_PIECES) 配 (batch, 1) 的掩码。
    # 写成一维会让 flip 广播成 (N,N) 的矩阵 —— 测试自己先错了。
    t = torch.tensor(feats, dtype=torch.long).reshape(-1, 1)
    always = torch.ones(t.size(0), 1, dtype=torch.bool)

    fast = _mirror_feat(t, always).flatten().tolist()
    slow = [slow_mirror(f) for f in feats]
    if fast != slow:
        n = sum(1 for a, b in zip(fast, slow) if a != b)
        bad = [(f, a, b) for f, a, b in zip(feats, fast, slow) if a != b][:5]
        print(f"[test] !! 向量化与朴素做法不一致 {n} 处, 例: {bad}")
        return 1

    twice = _mirror_feat(_mirror_feat(t, always), always).flatten().tolist()
    if twice != feats:
        n = sum(1 for a, b in zip(twice, feats) if a != b)
        print(f"[test] !! 镜像两次没还原, {n} 处不一致")
        return 1

    if any(x != -1 for x in fast[-32:]):
        print("[test] !! 补位 -1 被改坏了")
        return 1

    # 镜像后必须仍落在合法特征范围内
    if any(not (0 <= x < FEATURES) for x in fast[:FEATURES]):
        print("[test] !! 镜像结果越界")
        return 1

    print(f"[test] {FEATURES} 个特征 + 32 个补位: 向量化 == 朴素做法, "
          f"镜像两次还原, 补位未受影响, 无越界")
    return 0


if __name__ == "__main__":
    sys.exit(main())

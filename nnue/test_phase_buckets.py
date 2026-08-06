"""四阶段输出头的边界与旧 checkpoint 等值初始化回归测试。

运行：python3 nnue/test_phase_buckets.py
"""
import sys

import torch

sys.path.insert(0, __file__.rsplit("/", 1)[0])
from model import NnueNet, PHASE_BUCKETS, SQUARES_  # noqa: E402


def feature(piece, square=0, king_bucket=4):
    return king_bucket * 14 * SQUARES_ + piece * SQUARES_ + square


def sample(piece_indices):
    values = [feature(piece, i) for i, piece in enumerate(piece_indices)]
    return torch.tensor([values + [-1] * (32 - len(values))], dtype=torch.long)


def bucket_for(piece_indices):
    net = NnueNet(l1=32, phase_buckets=PHASE_BUCKETS)
    return int(net.phase_bucket(sample(piece_indices)).item())


def main():
    # 车=4、马=2、士=1，覆盖 18/26/36 三个边界的左右两侧。
    cases = [
        ([4, 4, 4, 4, 1], 0),
        ([4, 4, 4, 4, 3], 1),
        ([4, 4, 4, 4, 4, 4, 1], 1),
        ([4, 4, 4, 4, 4, 4, 3], 2),
        ([4, 4, 4, 4, 4, 4, 4, 4, 3, 1], 2),
        ([4] * 9, 3),
    ]
    for pieces, expected in cases:
        got = bucket_for(pieces)
        assert got == expected, (pieces, expected, got)

    torch.manual_seed(20260802)
    old = NnueNet(l1=32, phase_buckets=1).eval()
    phase4 = NnueNet(l1=32, phase_buckets=PHASE_BUCKETS).eval()
    source_buckets = phase4.load_compatible_state_dict(old.state_dict())
    assert source_buckets == 1

    own = torch.cat([sample(pieces) for pieces, _ in cases], dim=0)
    opp = own.clone()
    with torch.no_grad():
        expected = old(own, opp)
        got = phase4(own, opp)
    # phase4 会按桶拆成不同大小的 GEMM，浮点加法次序可能带来极小尾差；
    # 导出的整数网络仍由跨语言黄金向量要求逐分完全相等。
    torch.testing.assert_close(got, expected, rtol=1e-6, atol=5e-4)

    # 叶头训练会绕过子力阶段选择。四个头从单头等值复制后都必须与旧网络
    # 一致；改变 head1 后，head0 仍须逐样本保持原输出。
    with torch.no_grad():
        for head in range(PHASE_BUCKETS):
            torch.testing.assert_close(
                phase4.forward_head(own, opp, head), expected,
                rtol=1e-6, atol=5e-4,
            )
        phase4.extra_out[0].bias.add_(0.25)
        root_after = phase4.forward_head(own, opp, 0)
        leaf_after = phase4.forward_head(own, opp, 1)
    torch.testing.assert_close(root_after, expected, rtol=1e-6, atol=5e-4)
    assert torch.max(torch.abs(leaf_after - expected)).item() > 100.0

    print("[test] 阶段边界、v3→v4 等值初始化与强制输出头隔离正确")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

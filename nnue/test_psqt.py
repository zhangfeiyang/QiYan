#!/usr/bin/env python3
"""PSQT 残差旁路的函数保持、反对称和整数取整测试。"""
import numpy as np
import torch

from export import int_infer
from model import FEATURES, NnueNet, OUT_SCALE, PSQT_Q


def sample(batch=8, count=12):
    own = torch.full((batch, 32), FEATURES, dtype=torch.long)
    opp = torch.full((batch, 32), FEATURES, dtype=torch.long)
    rng = np.random.default_rng(7)
    for row in range(batch):
        own[row, :count] = torch.from_numpy(
            rng.choice(FEATURES, size=count, replace=False)
        )
        opp[row, :count] = torch.from_numpy(
            rng.choice(FEATURES, size=count, replace=False)
        )
    return own, opp


def test_zero_upgrade():
    torch.manual_seed(3)
    old = NnueNet(l1=32).eval()
    upgraded = NnueNet(l1=32, psqt=True).eval()
    upgraded.load_compatible_state_dict(old.state_dict())
    own, opp = sample()
    with torch.no_grad():
        before = old(own, opp)
        after = upgraded(own, opp)
    assert torch.equal(before, after), "零初始化 PSQT 必须与旧 checkpoint 逐位等价"


def test_psqt_is_antisymmetric():
    net = NnueNet(l1=32, psqt=True).eval()
    with torch.no_grad():
        for p in net.parameters():
            p.zero_()
        net.psqt.weight[:FEATURES, 0] = torch.linspace(-0.2, 0.2, FEATURES)
    own, opp = sample(batch=5)
    with torch.no_grad():
        direct = net(own, opp)
        swapped = net(opp, own)
    assert torch.allclose(direct, -swapped, atol=1e-5)


def test_integer_psqt_truncates_toward_zero():
    l1 = 32
    ft_w = np.zeros((FEATURES, l1), dtype=np.int16)
    ft_b = np.zeros(l1, dtype=np.int16)
    fc1_w = np.zeros((1, 32, l1 * 2), dtype=np.int8)
    fc1_b = np.zeros((1, 32), dtype=np.int32)
    fc2_w = np.zeros((1, 32, 32), dtype=np.int8)
    fc2_b = np.zeros((1, 32), dtype=np.int32)
    out_w = np.zeros((1, 32), dtype=np.int32)
    out_b = np.zeros(1, dtype=np.int32)
    psqt = np.zeros(FEATURES, dtype=np.int16)
    psqt[1], psqt[2], psqt[3] = 63, -32, 17

    got = int_infer(
        ft_w, ft_b, fc1_w, fc1_b, fc2_w, fc2_b, out_w, out_b,
        np.array([1, 3]), np.array([2]), psqt_w=psqt, psqt_q=PSQT_Q,
    )
    # (63 + 17 - (-32)) / 32 = 112/32，截断后为 3。
    assert got == 3


if __name__ == "__main__":
    test_zero_upgrade()
    test_psqt_is_antisymmetric()
    test_integer_psqt_truncates_toward_zero()
    print(f"[test] PSQT 旁路通过：零升级等价、换边反号、Q={PSQT_Q} 整数推理一致")

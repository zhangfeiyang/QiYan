"""
NNUE 网络结构. 与 FORMAT.md 一致.

设计取舍都围绕一件事: **端上要能用整数算, 而且要快**.
手机上每秒要算几十万次评估, 浮点矩阵乘法根本跑不动, 所以:

  · 隐藏层用 ClippedReLU (clamp 到 [0,1]) 而不是普通 ReLU
    —— 输出有界才能安全量化成 int8, 普通 ReLU 的值域无上限, 量化尺度没法定.
  · FeatureTransformer 输出维度取 256, 是"表达力"和"端上累加器开销"的折中.
  · 双视角共享同一套 FeatureTransformer 权重: 参数量减半, 且天然获得红黑对称
    的先验 —— 网络学的是"我方/敌方"而不是"红方/黑方".
"""
import torch
import torch.nn as nn

FEATURES = 1260
L1 = 256          # FeatureTransformer 输出 (每个视角)
L2 = 32
L3 = 32


class ClippedReLU(nn.Module):
    """clamp(x, 0, 1). 量化友好的激活."""

    def forward(self, x):
        return torch.clamp(x, 0.0, 1.0)


class NnueNet(nn.Module):
    """
    [l1] 可调. 端上求值耗时基本正比于 l1 (FeatureTransformer 的累加和 fc1
    的乘加都是 l1 量级), 而棋力随 l1 递增 —— 甜点只能实测, 所以留成参数而
    不是常量. 样本格式与 l1 无关, 换宽度只需重训, 不必重新生成数据.
    """

    def __init__(self, l1=L1):
        super().__init__()
        self.l1 = l1
        # padding_idx=FEATURES 用来吃掉补位: 数据里空位是 -1, 前向时会被
        # 映射到这个下标, 它的 embedding 恒为 0 且不参与梯度.
        self.ft = nn.EmbeddingBag(
            FEATURES + 1, l1, mode="sum", padding_idx=FEATURES
        )
        self.ft_bias = nn.Parameter(torch.zeros(l1))

        self.act = ClippedReLU()
        self.fc1 = nn.Linear(l1 * 2, L2)
        self.fc2 = nn.Linear(L2, L3)
        self.out = nn.Linear(L3, 1)

        # FeatureTransformer 初值要小: 一个局面有 30 多个特征同时激活, 求和
        # 之后很容易冲出 ClippedReLU 的上界, 一开始就全饱和, 梯度直接消失.
        nn.init.uniform_(self.ft.weight, -0.01, 0.01)
        with torch.no_grad():
            self.ft.weight[FEATURES].zero_()

    def forward(self, own, opp):
        # -1 是补位标记, 换成 padding_idx
        own = torch.where(own < 0, torch.full_like(own, FEATURES), own)
        opp = torch.where(opp < 0, torch.full_like(opp, FEATURES), opp)

        acc_own = self.act(self.ft(own) + self.ft_bias)
        acc_opp = self.act(self.ft(opp) + self.ft_bias)

        x = torch.cat([acc_own, acc_opp], dim=1)
        x = self.act(self.fc1(x))
        x = self.act(self.fc2(x))
        return self.out(x).squeeze(1)

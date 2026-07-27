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

# 最后一层的输出乘上这个常数才是厘兵分.
#
# 不这么做的话, 网络要直接吐出 ±2000 的数, 而它的输入是 32 个 [0,1] 的激活,
# 于是输出层权重必须长到 100 上下. Adam 每步最多挪 lr(1e-3), 十轮训练才三百
# 多步 —— 实测权重从 0.2 爬到 0.4 就没了, 网络输出 std 只有 2.65 厘兵, 损失
# 还不如"恒定输出均值". 那不是学不会, 是根本走不到.
# 乘个固定常数之后, 权重只需要 0.1 量级就能覆盖整个分数范围, 几百步就够。
# 顺带把损失的梯度也放正常了: tanh(cp/800) 对网络原始输出的导数原本带着
# 1/800, 现在是 600/800, 回到 1 的量级.
#
# 常数是固定的, 不参与训练, 导出时直接乘进权重 —— 端上不需要知道它存在.
OUT_SCALE = 600.0


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

        # 初始化必须让累加器落在 ClippedReLU 的**中间**, 不是两头.
        #
        # 一开始只顾着躲上界: 权重取 ±0.01, 偏置取 0. 结果一个局面 32 个特征
        # 求和之后累加器是 0 附近的对称分布, 有一半直接被下界钳成 0 —— 那些
        # 单元梯度恒为 0, 永远学不动; 活下来的另一半均值只有 0.02, 等于整个
        # 网络只在 [0,1] 的 2% 里工作. 实测练 8 轮后预测值 std 只有 2.65 厘兵,
        # 而标签 std 是 1172, 损失甚至不如"恒定输出均值".
        #
        # 所以偏置给 0.5 把工作点顶到量程中央, 权重的尺度按"32 个特征求和后
        # std 约 0.2"反推: 0.2/sqrt(32) ≈ 0.035, 均匀分布取 ±0.06.
        nn.init.uniform_(self.ft.weight, -0.06, 0.06)
        nn.init.constant_(self.ft_bias, 0.5)
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
        return self.out(x).squeeze(1) * OUT_SCALE

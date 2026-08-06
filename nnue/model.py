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

# 特征 = (己方将所在九宫格位, 子类型, 格子)
#
# 只按 (子类型, 格子) 编码时, 网络无法表达"这个子的价值取决于将在哪" ——
# 而象棋的评估极度依赖将位(士象是否成形、车炮是否对着将门)。标准 NNUE
# (Stockfish 的 HalfKP/HalfKA) 就是把己方将的位置编进特征; 国象里这会让特征
# 爆到几十万维, 但象棋的将被困在九宫只有 9 个位置, 所以只到 9*14*90=11340。
KING_BUCKETS = 9
PIECE_KINDS = 14
SQUARES_ = 90
FEATURES = KING_BUCKETS * PIECE_KINDS * SQUARES_   # 11340
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
# PSQT 旁路导出成 int16，PSQT_Q 个整数档代表 1 厘兵。32 倍精度让最多 64 项
# 求和后的舍入误差仍远小于主干量化误差，同时单项可表示约 ±1024 厘兵。
PSQT_Q = 32

# 阶段值只看盘上剩余子力, 与走子方/红黑视角无关。权重按
#   将/兵=0, 士/象=1, 马/炮=2, 车=4
# 计算, 初始局面两边合计 40。边界来自当前 DAgger 训练集的分位数:
#   0..17 / 18..25 / 26..35 / 36..40
# 四桶各约占 1/4, 避免等宽切分让残局头几乎没有训练样本。
PHASE_BUCKETS = 4
PHASE_THRESHOLDS = (18, 26, 36)
PHASE_WEIGHTS = (0, 1, 1, 2, 4, 2, 0,
                 0, 1, 1, 2, 4, 2, 0)


def infer_phase_buckets(state_dict):
    """从 checkpoint 的键名推断输出栈数量；旧 v3 checkpoint 返回 1。"""
    prefixes = ("extra_fc1.", "extra_fc2.", "extra_out.")
    seen = []
    for prefix in prefixes:
        indices = {
            int(k[len(prefix):].split(".", 1)[0])
            for k in state_dict if k.startswith(prefix)
        }
        seen.append(indices)
    if not any(seen):
        return 1
    if seen[0] != seen[1] or seen[0] != seen[2]:
        raise ValueError("checkpoint 的阶段输出栈不完整")
    expected = set(range(max(seen[0]) + 1))
    if seen[0] != expected:
        raise ValueError("checkpoint 的阶段输出栈编号不连续")
    return len(expected) + 1


def infer_psqt(state_dict):
    """checkpoint 是否包含线性 PSQT 残差旁路。"""
    return "psqt.weight" in state_dict


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

    def __init__(self, l1=L1, phase_buckets=1, psqt=False):
        super().__init__()
        if phase_buckets not in (1, PHASE_BUCKETS):
            raise ValueError(f"phase_buckets 只支持 1 或 {PHASE_BUCKETS}")
        self.l1 = l1
        self.phase_buckets = phase_buckets
        self.has_psqt = psqt
        # padding_idx=FEATURES 用来吃掉补位: 数据里空位是 -1, 前向时会被
        # 映射到这个下标, 它的 embedding 恒为 0 且不参与梯度.
        self.ft = nn.EmbeddingBag(
            FEATURES + 1, l1, mode="sum", padding_idx=FEATURES
        )
        self.ft_bias = nn.Parameter(torch.zeros(l1))

        # 线性 PSQT 残差旁路。
        #
        # 主干的 FeatureTransformer 累加后立刻经过 ClippedReLU，一阶的子力/
        # 位置值也必须挤过这个非线性瓶颈。PSQT 直接对同一套 HalfKP 特征求和，
        # 用 own-opp 保证换边时严格反号，让主干把容量留给真正的组合关系。
        # 权重从 0 初始化，因此给旧 checkpoint 加上该旁路时第 0 轮逐样本等价；
        # 推理侧只多几个可增量维护的整数加减。
        self.psqt = (
            nn.EmbeddingBag(FEATURES + 1, 1, mode="sum", padding_idx=FEATURES)
            if psqt else None
        )
        if self.psqt is not None:
            nn.init.zeros_(self.psqt.weight)

        self.act = ClippedReLU()
        self.fc1 = nn.Linear(l1 * 2, L2)
        self.fc2 = nn.Linear(L2, L3)
        self.out = nn.Linear(L3, 1)

        # 第一套沿用历史键名 fc1/fc2/out, 让旧 checkpoint 可以无损加载；其余
        # 三套只增加约 55KB 浮点参数，FeatureTransformer 仍然完全共享。
        self.extra_fc1 = nn.ModuleList(
            nn.Linear(l1 * 2, L2) for _ in range(phase_buckets - 1)
        )
        self.extra_fc2 = nn.ModuleList(
            nn.Linear(L2, L3) for _ in range(phase_buckets - 1)
        )
        self.extra_out = nn.ModuleList(
            nn.Linear(L3, 1) for _ in range(phase_buckets - 1)
        )
        self.register_buffer(
            "_phase_weights", torch.tensor(PHASE_WEIGHTS, dtype=torch.long),
            persistent=False,
        )

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

    def dense_stacks(self):
        """按阶段桶顺序返回 (fc1, fc2, out)，供训练钳位和导出共用。"""
        return list(zip(
            [self.fc1, *self.extra_fc1],
            [self.fc2, *self.extra_fc2],
            [self.out, *self.extra_out],
        ))

    def load_compatible_state_dict(self, state_dict):
        """加载同结构 checkpoint，或把旧单头 checkpoint 等值复制到四个桶。"""
        source_buckets = infer_phase_buckets(state_dict)
        source_psqt = infer_psqt(state_dict)
        if source_psqt and not self.has_psqt:
            raise ValueError("checkpoint 含 PSQT 旁路，但目标网络未启用 PSQT")
        psqt_upgrade = self.has_psqt and not source_psqt
        if source_buckets == self.phase_buckets:
            incompatible = self.load_state_dict(state_dict, strict=not psqt_upgrade)
            if psqt_upgrade and (
                set(incompatible.missing_keys) != {"psqt.weight"}
                or incompatible.unexpected_keys
            ):
                raise ValueError(
                    f"checkpoint 兼容加载失败: missing={incompatible.missing_keys}, "
                    f"unexpected={incompatible.unexpected_keys}"
                )
            return source_buckets
        if source_buckets != 1 or self.phase_buckets != PHASE_BUCKETS:
            raise ValueError(
                f"checkpoint 是 {source_buckets} 阶段桶，目标网络是 {self.phase_buckets} 桶"
            )

        incompatible = self.load_state_dict(state_dict, strict=False)
        if incompatible.unexpected_keys:
            raise ValueError(f"checkpoint 有未知参数: {incompatible.unexpected_keys}")
        expected_missing = {
            k for k in self.state_dict()
            if k.startswith(("extra_fc1.", "extra_fc2.", "extra_out."))
        }
        if psqt_upgrade:
            expected_missing.add("psqt.weight")
        if set(incompatible.missing_keys) != expected_missing:
            raise ValueError(f"checkpoint 缺少基础参数: {incompatible.missing_keys}")

        # 每个阶段头从原 DAgger 头的完全相同副本开始。于是 epoch 0 的浮点输出
        # 与旧模型逐样本相同，后续胜负变化才能归因于阶段专门化，而非随机初始化。
        with torch.no_grad():
            for fc1, fc2, out in self.dense_stacks()[1:]:
                fc1.load_state_dict(self.fc1.state_dict())
                fc2.load_state_dict(self.fc2.state_dict())
                out.load_state_dict(self.out.state_dict())
        return source_buckets

    def phase_bucket(self, own):
        """从任一视角的稀疏特征恢复子类型并计算 0..3 阶段桶。"""
        valid = (own >= 0) & (own < FEATURES)
        safe = torch.where(valid, own, torch.zeros_like(own))
        piece = (safe % (PIECE_KINDS * SQUARES_)) // SQUARES_
        phase = torch.where(valid, self._phase_weights[piece], 0).sum(dim=1)
        return ((phase >= PHASE_THRESHOLDS[0]).long()
                + (phase >= PHASE_THRESHOLDS[1]).long()
                + (phase >= PHASE_THRESHOLDS[2]).long())

    def _run_stack(self, x, bucket):
        fc1, fc2, out = self.dense_stacks()[bucket]
        x = self.act(fc1(x))
        x = self.act(fc2(x))
        return out(x).squeeze(1)

    def _forward(self, own, opp, forced_head=None):
        if forced_head is not None and not 0 <= forced_head < self.phase_buckets:
            raise ValueError(
                f"forced_head={forced_head} 超出 0..{self.phase_buckets - 1}"
            )
        buckets = (
            self.phase_bucket(own)
            if self.phase_buckets > 1 and forced_head is None else None
        )
        # -1 是补位标记, 换成 padding_idx
        own = torch.where(own < 0, torch.full_like(own, FEATURES), own)
        opp = torch.where(opp < 0, torch.full_like(opp, FEATURES), opp)

        acc_own = self.act(self.ft(own) + self.ft_bias)
        acc_opp = self.act(self.ft(opp) + self.ft_bias)

        x = torch.cat([acc_own, acc_opp], dim=1)
        if forced_head is not None:
            result = self._run_stack(x, forced_head)
        elif self.phase_buckets == 1:
            result = self._run_stack(x, 0)
        else:
            # 每条样本只跑自己所属的一套小输出栈，推理计算量与单头相同。训练时
            # 用 index_copy 保留 autograd 图，同时避免把四个头全算一遍。
            result = torch.empty(x.size(0), dtype=x.dtype, device=x.device)
            for bucket in range(self.phase_buckets):
                indices = torch.nonzero(buckets == bucket, as_tuple=False).squeeze(1)
                if indices.numel() == 0:
                    continue
                values = self._run_stack(x.index_select(0, indices), bucket)
                result = result.index_copy(0, indices, values)

        if self.psqt is not None:
            # 与主输出共用 OUT_SCALE，让 PSQT 参数仍在约 0.1 的易优化量级；
            # 导出时再把常数乘进定点权重，端上完全不知道这个浮点尺度。
            result = result + (
                self.psqt(own).squeeze(1) - self.psqt(opp).squeeze(1)
            )
        return result * OUT_SCALE

    def forward(self, own, opp):
        return self._forward(own, opp)

    def forward_head(self, own, opp, head):
        """绕过子力阶段选择，强制走指定输出头。

        训练“搜索头/叶评估头”时共享冻结的 FeatureTransformer，却让两套互相
        冲突的教师目标各自拥有输出栈。运行时也用相同入口语义做逐样本对拍。
        """
        return self._forward(own, opp, forced_head=head)

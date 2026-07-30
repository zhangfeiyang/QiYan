"""
训练数据读取. 格式见 FORMAT.md, 必须与 Kotlin 侧 NnueFeatures/SelfPlayGenerator 逐位一致.

编码错一位的症状是"训练 loss 正常下降, 但端上棋力莫名其妙地差" —— 因为
网络确实学会了某种规律, 只是那个规律和引擎实际喂给它的特征对不上. 极难排查.
所以这里的常量全部从 FORMAT.md 抄写, 并且有 test_roundtrip.py 做交叉校验.
"""
import numpy as np
import torch
from torch.utils.data import Dataset

# ---- 必须与 Kotlin 侧一致 ----
PIECE_KINDS = 14
SQUARES = 90
FEATURES = PIECE_KINDS * SQUARES      # 1260
MAX_PIECES = 32
RECORD_BYTES = 72
SCORE_CLAMP = 10000

# 每条记录的字段偏移 (小端)
_OFF_COUNT = 0        # u8
_OFF_STM = 1          # u8  0=红 1=黑
_OFF_SCORE = 2        # i16 走子方视角厘兵分
_OFF_PIECES = 4       # u8[64]  (pieceIdx, sq) x 32
_OFF_DEPTH = 68       # i16


def flip_square(sq):
    """上下翻转, 列不动. 与 Kotlin NnueFeatures.flipSquare 一致."""
    row, col = sq // 9, sq % 9
    return (9 - row) * 9 + col


def flip_piece(idx):
    """红黑互换: 0..6 <-> 7..13"""
    return idx + 7 if idx < 7 else idx - 7


class SelfPlayDataset(Dataset):
    """
    把若干 shard-*.bin 当成一个数据集.

    用 np.memmap 而不是一次读进内存: 千万级样本是 700MB+, 而且训练时是随机
    访问, memmap 让操作系统按页调度比我们自己管理缓存更合适.
    """

    def __init__(self, paths):
        self.chunks = []
        self.offsets = [0]
        total = 0
        for p in paths:
            raw = np.memmap(p, dtype=np.uint8, mode="r")
            n = len(raw) // RECORD_BYTES
            if n == 0:
                continue
            # 截掉末尾不完整的记录: 生成任务被中断时最后一条可能只写了一半
            self.chunks.append(raw[: n * RECORD_BYTES].reshape(n, RECORD_BYTES))
            total += n
            self.offsets.append(total)
        if not self.chunks:
            raise RuntimeError("没有可用样本, 检查路径与文件是否为空")
        self.total = total

    def __len__(self):
        return self.total

    def _locate(self, i):
        # 二分找到 i 落在哪个 shard
        lo, hi = 0, len(self.offsets) - 1
        while lo < hi - 1:
            mid = (lo + hi) // 2
            if self.offsets[mid] <= i:
                lo = mid
            else:
                hi = mid
        return lo, i - self.offsets[lo]

    def __getitem__(self, i):
        ci, ri = self._locate(i)
        rec = self.chunks[ci][ri]

        count = int(rec[_OFF_COUNT])
        stm_black = int(rec[_OFF_STM]) == 1
        score = int(np.frombuffer(rec[_OFF_SCORE:_OFF_SCORE + 2].tobytes(), dtype="<i2")[0])

        pieces = rec[_OFF_PIECES:_OFF_PIECES + count * 2].reshape(count, 2)
        idxs = pieces[:, 0].astype(np.int64)
        sqs = pieces[:, 1].astype(np.int64)

        # 视角转换在读数据时做, 不在生成时做 —— 这样同一份数据将来换个
        # 视角约定还能重用, 不必重新生成几天的数据.
        # 整体向量化, 不逐元素调 flip_piece/flip_square. 那两个是纯算术, 但写成
        # 列表推导之后每条样本要跑几十次 Python 函数调用加 int() 转换 ——
        # 552 万条 × 40 轮全压在 Python 层, 实测第一轮就要 11 分钟, 而 GPU
        # 利用率只有 5%, 8 个 dataloader 进程各占满一个核。语义与那两个标量
        # 函数逐位一致, 由 test_dataset_vectorized.py 锁住。
        flip_idx = np.where(idxs < 7, idxs + 7, idxs - 7)
        flip_sq = (9 - sqs // 9) * 9 + sqs % 9
        if stm_black:
            # 走子方是黑: "我方"要把棋盘翻过来看
            own_idx, own_sq = flip_idx, flip_sq
            opp_idx, opp_sq = idxs, sqs
        else:
            own_idx, own_sq = idxs, sqs
            opp_idx, opp_sq = flip_idx, flip_sq

        own = own_idx * SQUARES + own_sq
        opp = opp_idx * SQUARES + opp_sq

        # 定长补齐到 MAX_PIECES, 用 -1 表示空位, 训练时 mask 掉.
        # 变长会让 DataLoader 没法 batch, 而补 0 会被当成"第 0 号特征存在".
        own_pad = np.full(MAX_PIECES, -1, dtype=np.int64)
        opp_pad = np.full(MAX_PIECES, -1, dtype=np.int64)
        own_pad[:count] = own
        opp_pad[:count] = opp

        # 返回**原始厘兵分**, 不在这里做 tanh.
        #
        # 之前这里直接返回 tanh(score/400), 等于让网络去预测 tanh 空间的值 ——
        # 那样端上拿到输出还得做 atanh 才能还原成厘兵分, 运算贵且在 ±1 附近
        # 数值爆炸. 正确做法是网络输出原始评估值, 把 tanh 放进损失函数里两边
        # 同时压 (Stockfish 就是这么做的): 既避免极端分数主导损失, 又让推理
        # 端可以直接把输出当分数用.
        target = np.float32(score)

        return (
            torch.from_numpy(own_pad),
            torch.from_numpy(opp_pad),
            torch.tensor(target),
        )

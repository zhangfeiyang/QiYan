"""后悔值配对的着法、视角、二进制布局回归测试。"""
import sys

import numpy as np

sys.path.insert(0, __file__.rsplit("/", 1)[0])
from dataset import PAIR_RECORD_BYTES, decode_pair_bulk  # noqa: E402
from mine_regret_pairs import (  # noqa: E402
    apply_uci_move,
    board_to_fen,
    build_go_command,
    pack_record,
    parse_record,
    parse_score,
)


def main():
    assert build_go_command(3) == "go depth 3"
    assert build_go_command(3, 100) == "go movetime 100"
    assert build_go_command(10, 100, "a0a1") == "go movetime 100 searchmoves a0a1"

    board = [-1] * 90
    board[9 * 9 + 4] = 0       # 红帅 e0
    board[0 * 9 + 4] = 7       # 黑将 e9
    board[9 * 9 + 0] = 4       # 红车 a0
    board[8 * 9 + 0] = 13      # 黑卒 a1（供布局/吃子测试）

    root = pack_record(board, 0, 321, 8)
    decoded_board, stm = parse_record(root)
    assert decoded_board == board and stm == 0
    assert board_to_fen(board, 0).startswith("4k4/9/9/9/9/9/9/9/p8/R3K4 w")

    preferred, child_stm = apply_uci_move(board, 0, "a0a1")
    rejected, rejected_stm = apply_uci_move(board, 0, "a0b0")
    assert child_stm == rejected_stm == 1
    assert preferred[8 * 9] == 4 and preferred[9 * 9] == -1

    # 根视角教师分 300/100，写到子局面后必须翻成 -300/-100；解码后
    # rejected_y - preferred_y = 200，正好还原根局面的后悔值。
    blob = pack_record(preferred, 1, -300, 8) + pack_record(rejected, 1, -100, 8)
    assert len(blob) == PAIR_RECORD_BYTES
    decoded = decode_pair_bulk(np.frombuffer(blob, dtype=np.uint8).reshape(1, -1))
    preferred_y, rejected_y = decoded[2], decoded[5]
    assert preferred_y.tolist() == [-300.0]
    assert rejected_y.tolist() == [-100.0]
    assert (rejected_y - preferred_y).tolist() == [200.0]

    assert parse_score("info depth 8 score cp -37 nodes 1 pv a0a1") == -37
    assert parse_score("info depth 8 score mate 3 nodes 1 pv a0a1") == 9997
    assert parse_score("info depth 8 score mate -3 nodes 1 pv a0a1") == -9997
    print("[test] 后悔值着法应用、子局面视角、144 字节布局均正确")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

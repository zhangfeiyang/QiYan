#!/usr/bin/env python3
import struct
import unittest

from label_fens import fen_to_pieces, pack_record, parse_static_eval


class StaticEvalLabelTest(unittest.TestCase):
    def test_white_score_is_converted_to_side_to_move(self):
        line = "Final evaluation      +0.51 (white side) [with scaled NNUE, ...]"
        self.assertEqual(parse_static_eval(line, 0), 51)
        self.assertEqual(parse_static_eval(line, 1), -51)

        line = "Final evaluation      -1.23 (white side) [with scaled NNUE, ...]"
        self.assertEqual(parse_static_eval(line, 0), -123)
        self.assertEqual(parse_static_eval(line, 1), 123)

    def test_in_check_trace_has_no_static_label(self):
        self.assertIsNone(parse_static_eval("Final evaluation: none (in check)", 0))

    def test_static_record_uses_depth_zero(self):
        fen = "4k4/9/9/9/9/9/9/9/9/4K4 b - - 0 1"
        pieces, stm = fen_to_pieces(fen)
        record = pack_record(pieces, stm, -51, 0)
        self.assertEqual(len(record), 72)
        self.assertEqual(record[1], 1)
        self.assertEqual(struct.unpack_from("<h", record, 2)[0], -51)
        self.assertEqual(struct.unpack_from("<h", record, 68)[0], 0)


if __name__ == "__main__":
    unittest.main()

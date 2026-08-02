// 校验 endgame_gen 产出的局面。**故意不复用生成器里的判定函数** —— 复用的话
// 生成逻辑错了校验也会跟着错, 等于没校验。这里从 FEN 重新解析, 独立重算每条
// 约束。
//
//   g++ -O2 -std=c++17 board.cpp movegen.cpp see.cpp endgame_check.cpp -o /tmp/endgame_check
//   /tmp/endgame_gen 5000 | /tmp/endgame_check
#include <cstdio>
#include <iostream>
#include <map>
#include <string>
#include <vector>

#include "board.h"
#include "movegen.h"

using namespace xq;

int main() {
    std::string line;
    long n = 0, bad = 0;
    std::map<std::string, long> reasons;
    auto fail = [&](const char* why, const std::string& fen) {
        ++bad;
        if (reasons[why]++ < 2) std::printf("  [%s] %s\n", why, fen.c_str());
    };

    while (std::getline(std::cin, line)) {
        if (line.empty()) continue;
        ++n;
        Position p;
        if (!p.set_from_fen(line)) { fail("FEN 解析失败", line); continue; }

        // 1) FEN 往返必须逐字符一致 —— 不一致说明编解码有歧义, 那会让
        //    训练数据和端上看到的局面对不上。
        if (p.to_fen() != line) { fail("FEN 往返不一致", line); continue; }

        int cnt[16] = {0};
        int rk = -1, bk = -1;
        bool geom_ok = true;
        for (int sq = 0; sq < kSquareCount; ++sq) {
            const int pc = p.squares[sq];
            if (pc == kPieceNone) continue;
            ++cnt[pc];
            const int r = sq_row(sq), c = sq_col(sq);
            const int t = piece_type(pc);
            const bool red = piece_is_red(pc);
            switch (t) {
                case kTypeKing:
                    (red ? rk : bk) = sq;
                    if (c < 3 || c > 5) geom_ok = false;
                    if (red ? (r < 7 || r > 9) : (r < 0 || r > 2)) geom_ok = false;
                    break;
                case kTypeAdvisor:
                    if (c < 3 || c > 5) geom_ok = false;
                    if (red ? (r < 7 || r > 9) : (r < 0 || r > 2)) geom_ok = false;
                    // 士只能在九宫的四角或中心
                    if (c == 4) { if (red ? (r != 8) : (r != 1)) geom_ok = false; }
                    else       { if (red ? (r != 7 && r != 9) : (r != 0 && r != 2)) geom_ok = false; }
                    break;
                case kTypeElephant:
                    if (red ? (r < 5) : (r > 4)) geom_ok = false;   // 象不过河
                    if ((c % 2) != 0) geom_ok = false;               // 七个点的纵线都是偶数
                    if (red ? (r != 5 && r != 7 && r != 9) : (r != 0 && r != 2 && r != 4))
                        geom_ok = false;
                    // 田字格点: (行,列) 必须同为"偶数格"
                    if (red) { if (!((r == 9 && (c == 2 || c == 6)) || (r == 7 && (c == 0 || c == 4 || c == 8)) || (r == 5 && (c == 2 || c == 6)))) geom_ok = false; }
                    else     { if (!((r == 0 && (c == 2 || c == 6)) || (r == 2 && (c == 0 || c == 4 || c == 8)) || (r == 4 && (c == 2 || c == 6)))) geom_ok = false; }
                    break;
                case kTypePawn:
                    if (red) {
                        if (r >= 7) geom_ok = false;                       // 兵不能后退
                        if ((r == 6 || r == 5) && (c % 2) != 0) geom_ok = false;  // 未过河不能横走
                    } else {
                        if (r <= 2) geom_ok = false;
                        if ((r == 3 || r == 4) && (c % 2) != 0) geom_ok = false;
                    }
                    break;
                default: break;                                    // 车/马/炮无位置约束
            }
        }
        if (!geom_ok) { fail("子力位置违规", line); continue; }

        if (cnt[kRKing] != 1 || cnt[kBKing] != 1) { fail("将/帅数量不为1", line); continue; }
        if (cnt[kRRook] > 2 || cnt[kBRook] > 2 || cnt[kRHorse] > 2 || cnt[kBHorse] > 2 ||
            cnt[kRCannon] > 2 || cnt[kBCannon] > 2 || cnt[kRAdvisor] > 2 || cnt[kBAdvisor] > 2 ||
            cnt[kRElephant] > 2 || cnt[kBElephant] > 2 || cnt[kRPawn] > 5 || cnt[kBPawn] > 5) {
            fail("子力数量超限", line); continue;
        }

        // 2) 将帅照面
        if (sq_col(rk) == sq_col(bk)) {
            bool blocked = false;
            const int c = sq_col(rk);
            for (int r = std::min(sq_row(rk), sq_row(bk)) + 1; r < std::max(sq_row(rk), sq_row(bk)); ++r)
                if (p.squares[sq_make(r, c)] != kPieceNone) { blocked = true; break; }
            if (!blocked) { fail("将帅照面", line); continue; }
        }

        // 3) 未轮到的一方不能被将
        const int opp = (p.side_to_move == kRed) ? kBlack : kRed;
        if (is_in_check(p, opp)) { fail("非行棋方被将", line); continue; }

        // 4) 行棋方必须有棋可走
        std::vector<Move> mv;
        generate_all_legal(p, mv);
        if (mv.empty()) { fail("行棋方无着可走", line); continue; }
    }

    std::printf("[check] 共 %ld 条, 违规 %ld 条\n", n, bad);
    for (auto& kv : reasons) std::printf("  %-16s x%ld\n", kv.first.c_str(), kv.second);
    return bad == 0 ? 0 : 1;
}

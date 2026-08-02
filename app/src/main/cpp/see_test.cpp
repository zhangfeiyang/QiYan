// 宿主机上验证 C++ SEE 与 Kotlin 版逐条一致。**不参与 Android 构建**。
//
//   g++ -O2 -std=c++17 board.cpp movegen.cpp see.cpp see_test.cpp -o /tmp/see_test
//   /tmp/see_test /tmp/see_golden.txt
//
// golden 文件格式(由 SeeGoldenGenerator.kt 生成):
//   [SEE] fen=<FEN>
//   [SEE] move <from> <to> see=<value>
//   ... (可重复多条 move, 直到下一个 fen 行)
//   [SEE] total=<N>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <sstream>
#include <string>

#include "board.h"
#include "see.h"

int main(int argc, char** argv) {
    if (argc < 2) {
        std::fprintf(stderr, "用法: %s <see_golden.txt>\n", argv[0]);
        return 2;
    }
    std::ifstream f(argv[1]);
    if (!f) { std::fprintf(stderr, "打不开 %s\n", argv[1]); return 1; }

    xq::Position pos;
    bool have_pos = false;
    int checked = 0, bad = 0;
    std::string line;
    while (std::getline(f, line)) {
        // 去掉 gradle -i 加的前导空白
        size_t start = line.find("[SEE]");
        if (start == std::string::npos) continue;
        line = line.substr(start);

        if (line.rfind("[SEE] fen=", 0) == 0) {
            const std::string fen = line.substr(std::strlen("[SEE] fen="));
            if (!pos.set_from_fen(fen)) {
                std::fprintf(stderr, "[see] !! FEN 解析失败: %s\n", fen.c_str());
                ++bad;
                have_pos = false;
                continue;
            }
            have_pos = true;
        } else if (line.rfind("[SEE] move ", 0) == 0) {
            if (!have_pos) continue;
            int from, to, expect;
            if (std::sscanf(line.c_str(), "[SEE] move %d %d see=%d", &from, &to, &expect) != 3) {
                continue;
            }
            const xq::Move m(from, to);
            const int got = xq::see_evaluate(pos, m);
            ++checked;
            if (got != expect) {
                if (++bad <= 10) {
                    std::fprintf(stderr,
                                 "[see] !! 不一致 fen=%s move=%d->%d 期望=%d 实得=%d\n",
                                 pos.to_fen().c_str(), from, to, expect, got);
                }
            }
        }
        // total= 行不用管, 只是 Kotlin 侧留的计数, 不影响比对
    }

    std::printf("[see] 比对 %d 条, 不一致 %d 条\n", checked, bad);
    if (checked < 100) {
        std::fprintf(stderr, "[see] !! 素材太少, 没验证到什么\n");
        return 1;
    }
    if (bad) {
        std::fprintf(stderr, "[see] !! C++ 与 Kotlin SEE 实现对不上\n");
        return 1;
    }
    std::printf("[see] 全部一致: C++ SEE 与 Kotlin See.evaluate 逐条相同\n");
    return 0;
}

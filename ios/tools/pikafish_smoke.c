// macOS smoke test for the PikaFish bridge: load the net, search the start position,
// then a mate-in-one, and print what came back.
//   clang tools/pikafish_smoke.c -IEngine Vendor/pikafish/macosx/libpikafish.a -lc++ -o /tmp/pf_smoke
//   /tmp/pf_smoke path/to/pikafish.nnue
#include <stdio.h>
#include <string.h>
#include "pikafish_bridge.h"

static void search(const char *fen, long long ms) {
    if (pf_set_position(fen, NULL, 0) != 0) { printf("bad fen %s\n", fen); return; }
    pf_go(ms, 0, 0);
    pf_wait_for_search();
    char best[16] = {0};
    pf_bestmove(best, sizeof best - 1);
    long long info[6];
    pf_last_info(info);
    printf("fen=%s\n  best=%s depth=%lld score=%lld mate=%lld nodes=%lld nps=%lld\n",
           fen, best, info[0], info[1], info[2], info[4], info[5]);
}

int main(int argc, char **argv) {
    if (argc < 2) { fprintf(stderr, "usage: %s pikafish.nnue\n", argv[0]); return 2; }
    if (pf_init(argv[1]) != 0) { puts("init failed"); return 1; }
    pf_set_option("Threads", "4");
    search("rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1", 1000);
    search("3aka3/4n4/9/9/2C6/9/9/9/9/3K5 w - - 0 1", 500);
    pf_set_option("MultiPV", "3");
    search("rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1", 800);
    char lines[512] = {0};
    pf_multipv(lines, sizeof lines - 1);
    printf("multipv=%s\n", lines);
    pf_shutdown();
    return 0;
}

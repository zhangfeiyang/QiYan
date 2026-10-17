// C interface to the in-process PikaFish engine (GPL-3), mirroring the Android
// PikafishNative JNI functions. Every call is serialized behind one mutex inside
// the bridge; only pf_wait_for_search runs without it so the search can finish.
#ifndef PIKAFISH_BRIDGE_H
#define PIKAFISH_BRIDGE_H

#ifdef __cplusplus
extern "C" {
#endif

/// Builds the engine and loads the network from `nnue_path`. Returns 0 on success.
int pf_init(const char *nnue_path);
void pf_set_option(const char *name, const char *value);
/// Returns 0 on success, -1 if the FEN or a move is rejected.
int pf_set_position(const char *fen, const char *const *moves, int count);
/// Starts an asynchronous search. 0 means "no limit" for movetime and depth.
void pf_go(long long movetime_ms, int depth, int infinite);
void pf_stop(void);
void pf_wait_for_search(void);
int pf_is_searching(void);
/// Copies the best move (UCI) into buf; returns the byte count.
int pf_bestmove(char *buf, int len);
int pf_pondermove(char *buf, int len);
/// out[0]=depth out[1]=score(internal units, side to move) out[2]=mate plies
/// out[3]=time ms out[4]=nodes out[5]=nps
void pf_last_info(long long out[6]);
/// "move:cp:mate;move:cp:mate;..." for each MultiPV line of the last search.
int pf_multipv(char *buf, int len);
void pf_search_clear(void);
void pf_shutdown(void);

#ifdef __cplusplus
}
#endif

#endif

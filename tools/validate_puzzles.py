#!/usr/bin/env python3
import subprocess
import sys

def check_puzzle(engine_path, fen, max_moves):
    p = subprocess.Popen([engine_path], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
    p.stdin.write("uci\nsetoption name EvalFile value pikafish.nnue\nisready\n")
    p.stdin.flush()
    while True:
        line = p.stdout.readline()
        if "readyok" in line:
            break

    moves_played = []
    # Play moves until mate
    step = 0
    solved = False
    mate_announced = None

    while step < max_moves * 2:
        movelist_str = (" moves " + " ".join(moves_played)) if moves_played else ""
        cmd = f"position fen {fen}{movelist_str}\ngo movetime 1500\n"
        p.stdin.write(cmd)
        p.stdin.flush()

        bestmove = None
        score_mate = None
        while True:
            line = p.stdout.readline().strip()
            if "score mate" in line:
                parts = line.split()
                try:
                    idx = parts.index("mate")
                    score_mate = int(parts[idx + 1])
                except:
                    pass
            if line.startswith("bestmove"):
                parts = line.split()
                bestmove = parts[1]
                break

        if mate_announced is None and score_mate is not None:
            mate_announced = score_mate

        if not bestmove or bestmove == "(none)" or bestmove == "0000":
            # No moves: check if game over
            break

        moves_played.append(bestmove)
        step += 1

        # If red announced mate in N and we finished
        if step % 2 == 1:
            # Red just moved. Check if mate was reached (score mate 0 from black perspective? or black has no move)
            pass

    p.kill()
    red_moves = (len(moves_played) + 1) // 2
    return mate_announced, moves_played, red_moves

if __name__ == "__main__":
    print("Puzzle validator ready")

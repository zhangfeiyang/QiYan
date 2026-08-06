#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
out=${1:-/tmp/xq_eval_leaf_sampler}

g++ -O3 -std=c++17 -mavx2 -DXQ_ENABLE_EVAL_TRACE \
  "$script_dir/board.cpp" \
  "$script_dir/movegen.cpp" \
  "$script_dir/see.cpp" \
  "$script_dir/nnue_core.cpp" \
  "$script_dir/search.cpp" \
  "$script_dir/eval_leaf_sampler.cpp" \
  -o "$out"
echo "→ $out"

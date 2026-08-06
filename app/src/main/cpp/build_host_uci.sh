#!/usr/bin/env bash
set -euo pipefail

cpp_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
out=${1:-/tmp/xq_uci}
[[ -n "$out" ]] || { echo "输出路径不能为空" >&2; exit 2; }
mkdir -p "$(dirname "$out")"

g++ -O3 -DNDEBUG -std=c++17 -pthread \
  "$cpp_dir/board.cpp" \
  "$cpp_dir/movegen.cpp" \
  "$cpp_dir/see.cpp" \
  "$cpp_dir/nnue_core.cpp" \
  "$cpp_dir/search.cpp" \
  "$cpp_dir/uci_engine.cpp" \
  -o "$out"

echo "→ $out"

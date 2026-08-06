#!/usr/bin/env bash
set -euo pipefail

# MAE 约束强度消融：weight=0.3（对照 hybrid_mae_v1 的 0.10）。
# 数据配比、起点、选轮口径与 net_dagger_d10_hybrid_mae_v1 完全一致，
# 只改 --hybrid-mae-weight，验证"把普通局面 MAE 压得更狠"能否带来实战提升。
repo_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
d10_v1=${D10_V1:-/data/nnue/dagger-train-blend-d10-v1}
d10_v2=${D10_V2:-/data/nnue/dagger-train-blend-d10-v2}
d8_replay=${D8_REPLAY:-/data/nnue/dagger-train-net328m-d8-v1}
val_dir=${VAL_DIR:-/data/nnue/dagger-val-blend-d10-v1}
init_pt=${INIT_PT:-"$repo_dir/nnue/net_dagger_blend_v1.pt"}
out_pt=${OUT_PT:-"$repo_dir/nnue/net_dagger_d10_hybrid_mae_w03_v1.pt"}
out_nnue=${OUT_NNUE:-"${HOME}/nnue-nets/net_dagger_d10_hybrid_mae_w03_v1.nnue"}
epochs=${EPOCHS:-20}
lr=${LR:-1e-5}
seed=${SEED:-26080301}

for d in "$d10_v1" "$d10_v2" "$d8_replay" "$val_dir"; do
  [[ -d "$d" ]] || { echo "数据目录不存在: $d" >&2; exit 2; }
done
[[ -f "$init_pt" ]] || { echo "初始化 checkpoint 不存在: $init_pt" >&2; exit 2; }
[[ ! -e "$out_pt" ]] || { echo "拒绝覆盖已有 checkpoint: $out_pt" >&2; exit 2; }
[[ ! -e "$out_nnue" ]] || { echo "拒绝覆盖已有网络: $out_nnue" >&2; exit 2; }

cd "$repo_dir"
python3 -u nnue/train.py \
  --data "$d10_v1" "$d10_v2" "$d8_replay" \
  --data-repeat 1 1 3 \
  --val-data "$val_dir" \
  --init "$init_pt" \
  --out "$out_pt" \
  --epochs "$epochs" \
  --lr "$lr" \
  --batch 8192 \
  --workers 4 \
  --select-metric mae_cp \
  --loss-mode hybrid_mae \
  --hybrid-mae-weight 0.30 \
  --seed "$seed"
python3 nnue/export.py --ckpt "$out_pt" --out "$out_nnue"

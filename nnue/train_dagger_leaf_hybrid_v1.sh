#!/usr/bin/env bash
set -euo pipefail

# leaf-v1 的 tanh-only 目标能明显改善叶节点，但会让普通根局面厘兵标尺漂移。
# 本版维持相同数据配比，额外约束非杀棋 MAE，并继续使用双验证集门禁。
repo_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
d10_v1=${D10_V1:-/data/nnue/dagger-train-blend-d10-v1}
d10_v2=${D10_V2:-/data/nnue/dagger-train-blend-d10-v2}
replay_dir=${REPLAY_DIR:-/data/nnue/dagger-train-net328m-d8-v1}
leaf_train=${LEAF_TRAIN:-/data/nnue/eval-leaves-d6-train-v1}
leaf_val=${LEAF_VAL:-/data/nnue/eval-leaves-d6-val-v1}
root_val=${ROOT_VAL:-/data/nnue/dagger-val-blend-d10-v1}
init_pt=${INIT_PT:-"$repo_dir/nnue/net_dagger_blend_v1.pt"}
out_pt=${OUT_PT:-"$repo_dir/nnue/net_dagger_leaf_hybrid_v1.pt"}
out_nnue=${OUT_NNUE:-"${HOME}/nnue-nets/net_dagger_leaf_hybrid_v1.nnue"}
epochs=${EPOCHS:-20}
lr=${LR:-1e-5}
seed=${SEED:-26080225}
leaf_repeat=${LEAF_REPEAT:-8}
guard_max_loss_regression=${GUARD_MAX_LOSS_REGRESSION:-0.005}
guard_max_mae_regression=${GUARD_MAX_MAE_REGRESSION:-0.01}

for d in "$d10_v1" "$d10_v2" "$replay_dir" "$leaf_train" "$leaf_val" "$root_val"; do
  [[ -d "$d" ]] || { echo "数据目录不存在: $d" >&2; exit 2; }
done
[[ -f "$init_pt" ]] || { echo "初始化 checkpoint 不存在: $init_pt" >&2; exit 2; }
[[ ! -e "$out_pt" ]] || { echo "拒绝覆盖已有 checkpoint: $out_pt" >&2; exit 2; }
[[ ! -e "$out_nnue" ]] || { echo "拒绝覆盖已有网络: $out_nnue" >&2; exit 2; }

cd "$repo_dir"
python3 -u nnue/train.py \
  --data "$d10_v1" "$d10_v2" "$replay_dir" "$leaf_train" \
  --data-repeat 1 1 3 "$leaf_repeat" \
  --val-data "$leaf_val" \
  --guard-val-data "$root_val" \
  --guard-max-loss-regression "$guard_max_loss_regression" \
  --guard-max-mae-regression "$guard_max_mae_regression" \
  --init "$init_pt" \
  --out "$out_pt" \
  --epochs "$epochs" \
  --lr "$lr" \
  --batch 8192 \
  --workers 4 \
  --select-metric loss \
  --loss-mode hybrid_mae \
  --hybrid-mae-weight 0.10 \
  --seed "$seed"
python3 nnue/export.py --ckpt "$out_pt" --out "$out_nnue"

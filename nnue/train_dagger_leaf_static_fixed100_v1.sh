#!/usr/bin/env bash
set -euo pipefail

# 用 DAgger 在固定 100ms 搜索中真实访问到的多样叶节点，蒸馏 Pikafish 的
# 静态 eval。新叶数据和原 DAgger 数据各回放一次；固定用时叶留出集负责选模，
# 原 DAgger 留出集负责阻止已有根局面能力退化。
repo_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
focus_data=${FOCUS_DATA:-/data/nnue/eval-leaves-fixed100-static-train-v1}
anchor_data=${ANCHOR_DATA:-/data/nnue/dagger-train-blend-d10-v1}
focus_val=${FOCUS_VAL:-/data/nnue/eval-leaves-fixed100-static-val-v1}
guard_val=${GUARD_VAL:-/data/nnue/dagger-val-blend-d10-v1}
init_pt=${INIT_PT:-"$repo_dir/nnue/net_dagger_blend_v1.pt"}
out_pt=${OUT_PT:-"$repo_dir/nnue/net_dagger_leaf_static_fixed100_v1.pt"}
out_nnue=${OUT_NNUE:-/home/zhangfy/nnue-nets/net_dagger_leaf_static_fixed100_v1.nnue}
epochs=${EPOCHS:-20}
lr=${LR:-1e-5}
seed=${SEED:-26080366}
focus_repeat=${FOCUS_REPEAT:-1}
anchor_repeat=${ANCHOR_REPEAT:-1}
guard_max_loss_regression=${GUARD_MAX_LOSS_REGRESSION:-0.003}
guard_max_mae_regression=${GUARD_MAX_MAE_REGRESSION:-0.003}

for dir in "$focus_data" "$anchor_data" "$focus_val" "$guard_val"; do
  [[ -d "$dir" ]] || { echo "数据目录不存在: $dir" >&2; exit 2; }
done
[[ -f "$init_pt" ]] || { echo "初始化 checkpoint 不存在: $init_pt" >&2; exit 2; }
[[ ! -e "$out_pt" ]] || { echo "拒绝覆盖已有 checkpoint: $out_pt" >&2; exit 2; }
[[ ! -e "$out_nnue" ]] || { echo "拒绝覆盖已有网络: $out_nnue" >&2; exit 2; }

cd "$repo_dir"
python3 -u nnue/train.py \
  --data "$focus_data" "$anchor_data" \
  --data-repeat "$focus_repeat" "$anchor_repeat" \
  --val-data "$focus_val" \
  --guard-val-data "$guard_val" \
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

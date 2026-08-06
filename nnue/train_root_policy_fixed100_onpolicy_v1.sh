#!/usr/bin/env bash
set -euo pipefail

# 独立根着法排序网络：输出仍沿用 NNUE 值格式，但只作为 child-position policy
# 分使用，不参与 alpha-beta 叶估值。因而可以大幅提高 rank loss 权重，不再受
# “必须保持全局价值几乎不动”的候选网络门禁束缚；少量 anchor 只稳定输出尺度。
repo_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
pair_train=${PAIR_TRAIN:-/data/nnue/regret-fixed100-onpolicy-train-v1}
pair_val=${PAIR_VAL:-/data/nnue/regret-fixed100-onpolicy-val-v1}
anchor_data=${ANCHOR_DATA:-/data/nnue/dagger-train-blend-d10-v1}
val_data=${VAL_DATA:-/data/nnue/dagger-val-blend-d10-v1}
init_pt=${INIT_PT:-"$repo_dir/nnue/net_dagger_blend_v1.pt"}
out_pt=${OUT_PT:-"$repo_dir/nnue/net_root_policy_fixed100_onpolicy_v1.pt"}
out_nnue=${OUT_NNUE:-/home/zhangfy/nnue-nets/net_root_policy_fixed100_onpolicy_v1.nnue}
epochs=${EPOCHS:-50}
lr=${LR:-5e-5}
rank_weight=${RANK_WEIGHT:-4}
seed=${SEED:-2026080363}

for dir in "$pair_train" "$pair_val" "$anchor_data" "$val_data"; do
  [[ -d "$dir" ]] || { echo "数据目录不存在: $dir" >&2; exit 2; }
done
[[ -f "$init_pt" ]] || { echo "初始化 checkpoint 不存在: $init_pt" >&2; exit 2; }
[[ ! -e "$out_pt" ]] || { echo "拒绝覆盖已有 checkpoint: $out_pt" >&2; exit 2; }
[[ ! -e "$out_nnue" ]] || { echo "拒绝覆盖已有网络: $out_nnue" >&2; exit 2; }

cd "$repo_dir"
python3 -u nnue/train_regret.py \
  --pair-data "$pair_train" \
  --pair-val-data "$pair_val" \
  --anchor-data "$anchor_data" \
  --val-data "$val_data" \
  --init "$init_pt" \
  --out "$out_pt" \
  --epochs "$epochs" \
  --batch 2048 \
  --anchor-ratio 1 \
  --lr "$lr" \
  --pair-value-weight 0 \
  --rank-weight "$rank_weight" \
  --rank-mode hinge \
  --rank-margin 20 \
  --max-pair-abs-target 8999 \
  --select-pair-metric normal_hinge_loss \
  --max-val-regression 0.999 \
  --cache-dir /data/nnue/regret_fixed100_anchor_cache \
  --seed "$seed"
python3 nnue/export.py --ckpt "$out_pt" --out "$out_nnue"

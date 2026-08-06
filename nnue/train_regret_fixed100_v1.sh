#!/usr/bin/env bash
set -euo pipefail

# 只纠正当前 DAgger 在最终 100ms 口径下真实选错的局面。相比旧 regret_v2：
# actor 从 depth=3 改为 movetime=100，且只学习相对排序，不再把 Pika 深搜索
# 绝对分数硬塞给静态 NNUE；同时提高普通 DAgger anchor 比例、收紧回退门禁。
repo_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
pair_train=${PAIR_TRAIN:-/data/nnue/regret-fixed100-train-v1}
pair_val=${PAIR_VAL:-/data/nnue/regret-fixed100-val-v1}
anchor_data=${ANCHOR_DATA:-/data/nnue/dagger-train-blend-d10-v1}
val_data=${VAL_DATA:-/data/nnue/dagger-val-blend-d10-v1}
init_pt=${INIT_PT:-"$repo_dir/nnue/net_dagger_blend_v1.pt"}
out_pt=${OUT_PT:-"$repo_dir/nnue/net_dagger_regret_fixed100_v1.pt"}
out_nnue=${OUT_NNUE:-/home/zhangfy/nnue-nets/net_dagger_regret_fixed100_v1.nnue}
epochs=${EPOCHS:-30}
lr=${LR:-1e-5}
seed=${SEED:-2026080335}

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
  --anchor-ratio 4 \
  --lr "$lr" \
  --pair-value-weight 0 \
  --rank-weight 0.25 \
  --rank-mode hinge \
  --rank-margin 20 \
  --max-pair-abs-target 8999 \
  --select-pair-metric normal_hinge_loss \
  --max-val-regression 0.003 \
  --cache-dir /data/nnue/regret_fixed100_anchor_cache \
  --seed "$seed"
python3 nnue/export.py --ckpt "$out_pt" --out "$out_nnue"

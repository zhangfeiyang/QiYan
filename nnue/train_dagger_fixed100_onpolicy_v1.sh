#!/usr/bin/env bash
set -euo pipefail

# 先做最便宜的消融：只把“固定 100ms 学生真实到达的状态”加入 DAgger 价值
# 训练。新状态按 8 倍回放，与旧 DAgger 主数据大致等权；checkpoint 由新分布
# 留出集选择，同时用旧分布留出集限制回退。若实战无信号，再进入动作后悔值训练。
repo_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
focus_data=${FOCUS_DATA:-/data/nnue/dagger-fixed100-train-d10-v1}
anchor_data=${ANCHOR_DATA:-/data/nnue/dagger-train-blend-d10-v1}
focus_val=${FOCUS_VAL:-/data/nnue/dagger-fixed100-val-d10-v1}
guard_val=${GUARD_VAL:-/data/nnue/dagger-val-blend-d10-v1}
init_pt=${INIT_PT:-"$repo_dir/nnue/net_dagger_blend_v1.pt"}
out_pt=${OUT_PT:-"$repo_dir/nnue/net_dagger_fixed100_onpolicy_v1.pt"}
out_nnue=${OUT_NNUE:-/home/zhangfy/nnue-nets/net_dagger_fixed100_onpolicy_v1.nnue}
epochs=${EPOCHS:-20}
lr=${LR:-2e-5}
seed=${SEED:-26080361}
focus_repeat=${FOCUS_REPEAT:-8}
anchor_repeat=${ANCHOR_REPEAT:-1}

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
  --guard-max-loss-regression 0.003 \
  --guard-max-mae-regression 0.003 \
  --init "$init_pt" \
  --out "$out_pt" \
  --epochs "$epochs" \
  --lr "$lr" \
  --batch 8192 \
  --workers 4 \
  --select-metric loss \
  --seed "$seed"
python3 nnue/export.py --ckpt "$out_pt" --out "$out_nnue"

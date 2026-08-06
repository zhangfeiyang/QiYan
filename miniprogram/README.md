# 棋眼微信小程序

用微信开发者工具导入仓库中的 `miniprogram/` 目录。`project.config.json` 里已经是正式 AppID。

## 与 App 的关系

小程序不是"简化版", 目标是**同一个产品的另一块屏**。因此:

- 界面 token (颜色/间距/字号/圆角) 从 `app/src/main/res/values*/colors.xml`、`dimens.xml`
  逐项移植到 `app.wxss`, 两边同名。
- 文案不手抄: `tools/sync-shared-config.mjs` 从 `values/strings.xml` 与
  `values-zh/strings.xml` 生成 `generated/strings.js`, 小程序按同一批 key 取词,
  连"业余五段"这种段位标签都保证逐字一致。
- 图标不换套: 同一个脚本把 `res/drawable/ic_*.xml` 的 pathData 抽成
  `generated/icons.js`, 小程序用同样的 24×24 图形内联渲染。
- 难度档位、段位切分点、能力矩阵以 `shared/config/` 为唯一事实来源,
  `tools/check-platform-sync.mjs` 会逐条比对它和 Android 字符串表。

## 页面对应

| 小程序 | Android |
| --- | --- |
| `pages/index` | `MainMenuActivity` |
| `pages/game` | `GameActivity` + `GameViewModel` |
| `pages/replay` | `ReplayActivity` |
| `pages/replay-viewer` | `ReplayViewerActivity` |
| `pages/settings` | `SettingsActivity` |
| `components/xiangqi-board` | `BoardView` |
| `components/win-rate-bar` | `WinRateBarView` |
| `components/difficulty-picker` | `dialog_difficulty` + `DifficultyAdapter` |
| `core/xiangqi.js` | `MoveGenerator` / `Rules` / `Position` |
| `core/notation.js` | `Move.toChineseString` |
| `core/rating.js` | `RatingSystem` (Glicko-2) + `UserRank` |
| `core/feedback.js` | `GameFeedback` + `BgmPlayer` |

棋盘是 canvas 2d 重画的, 绘制顺序、格距、棋子渐变、高亮、提示箭头、七个兵种的
吃子特效都按 `BoardView.onDraw` 一比一移植 —— 棋子画在**交点**上, 不是格子里。

## 平台边界

Android 的 Pikafish 通过 NDK/JNI 本地运行, 小程序加载不了这个原生库。

- **Lv.1-9**: 端侧 JS 引擎 (alpha-beta + 静搜 + MVV-LVA + 杀手着法 + 迭代加深),
  档位参数全部来自 `shared/config/engine-profile.json`。
- **Lv.10-15**: 需要后端 Pikafish 服务。难度选择器里仍然列出这些档位并标为
  App 端, 而不是直接藏掉。在设置页填好引擎服务地址后, 小程序会走云端。
- **拍照识局 / 实时支招 / 语音走子**: 需要把 ONNX 推理或语音识别迁到云端,
  能力矩阵里标为迁移中并写明 roadmap。

### 云服务接口

- `POST /bestmove`: `{ fen, level }` → `{ bestmove: "b2e2" }` 或 `{ from: {row,col}, to: {row,col} }`
- `POST /analyze`: `{ fen, message, history }` → `{ text }`

后端必须使用与 Android 相同的 1-15 级映射。域名要加入微信公众平台的 request 合法域名。

## 上传前自检

```bash
node tools/sync-shared-config.mjs        # 重新生成跨端配置 / 文案 / 图标
node tools/check-platform-sync.mjs       # 跨端一致性
node miniprogram/tests/xiangqi.test.mjs  # 规则 / 记谱 / FEN / 终局
node miniprogram/tests/rating.test.mjs   # Glicko-2 / 段位 / 存储
```

## 发布

微信开发者工具打开 `miniprogram/` → 右上角「上传」, 或走命令行:

```bash
cd /snap/wechat-devtools/current
echo y | node package.nw/js/common/cli/index.js islogin      # 输出里 grep "listening on" 拿端口
node package.nw/js/common/cli/index.js open   --project <repo>/miniprogram --port <端口>
node package.nw/js/common/cli/index.js upload --project <repo>/miniprogram --port <端口> \
  --version 1.4.0 --desc "..."
```

后台「版本管理 → 开发版本」不再提供 zip 上传入口, `tools/package-miniprogram.mjs`
打出来的包只用于存档和体检。

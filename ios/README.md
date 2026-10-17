# 棋眼 iOS 版

SwiftUI 移植，功能对齐安卓 1.4.0，**不含云端 AI 助手**（AI 分析/对话）；对弈、提示、拍照识局、实时支招、
语音走子全部在设备上运行，不联网。App Store：bundle id `com.zhangfeiyang.qiyan`。

本目录与仓库其余部分一样按 GNU GPL v3 授权。下面是从源码构建出与 App Store 版相同程序所需的全部材料
（GPL-3 所说的 Corresponding Source）。

## 组成

| 部分 | 位置 / 来源 |
|---|---|
| 纯逻辑（走法、规则、杀法、复盘评估、识局数学） | `Core/`，SwiftPM 包，`swift test` |
| App（SwiftUI 界面、引擎/识局桥接） | `QiYan/`，工程由 `tools/gen_xcodeproj.py` 生成 |
| PikaFish 引擎（GPL-3） | 上游 https://github.com/official-pikafish/Pikafish ，提交 `97133ee`，源码未改动；放到 `../third_party/Pikafish` |
| PikaFish 评估网络 `pikafish.nnue`（GPL-3） | sha256 `3cd15292bf8c979884262f57fc723959fc0dea43b4d8d544f88db5ceb2479e24`；本仓库提交 `266d7e1` 的 `app/src/main/assets/engine/pikafish.nnue` |
| 识局模型 `board_pose.onnx` / `board_layout.onnx`（MIT） | https://huggingface.co/spaces/yolo12138/Chinese_Chess_Recognition/tree/main/onnx ，与 `app/src/main/assets/models/` 相同 |
| ONNX Runtime 1.22.0（MIT） | `tools/fetch_onnxruntime.sh` 下载 onnxruntime-c 静态 xcframework |
| 字符串、颜色、图标、音效、字体 | 由 `tools/make_strings.py`、`tools/make_assets.py` 从安卓 `app/src/main/res` 生成；iOS 专有文案在 `tools/ios_strings.json` |

## 构建

需要 macOS + Xcode（iOS 17 SDK 以上）。

```bash
git clone https://github.com/official-pikafish/Pikafish.git third_party/Pikafish
git -C third_party/Pikafish checkout 97133ee
# 把 pikafish.nnue 与两个 onnx 模型放进 app/src/main/assets/models/

python3 ios/tools/make_strings.py && python3 ios/tools/make_assets.py && python3 ios/tools/gen_xcodeproj.py
# 在 Mac 上: 复制 Pikafish 源码到 ios/../Pikafish/src、模型到 ios/QiYan/Resources/Models/ (见 ios/sync.sh)
bash ios/tools/mac_build.sh sim        # 模拟器 Debug；会顺带编 libpikafish.a、下载 ONNX Runtime
```

`tools/mac_build.sh archive` 是作者发布 App Store 版用的签名流程（需要自己的证书与描述文件）。
`store/` 是 App Store Connect 的上架脚本与 16 种语言的商店文案。

# 棋眼微信小程序

用微信开发者工具导入仓库中的 `miniprogram/` 目录。首次开发可使用测试号；发布前把 `project.config.json` 的 `appid` 替换为正式 AppID。

## 已完成

- 首页、棋力档案与 15 档难度
- 本地完整象棋走法合法性、对局、提示、悔棋、翻转和认输
- AI 全屏扫描动效、透明分析抽屉、快捷提问和可发送输入框
- 最近棋谱逐步复盘
- 跨端能力矩阵与共享配置自动校验

## 云服务接口

- `POST /bestmove`: `{ fen, level }`，返回 `{ from: {row,col}, to: {row,col} }`
- `POST /analyze`: `{ fen, message, history }`，返回 `{ text }`

Android 的 Pikafish 是 NDK 原生库，小程序不能直接加载；正式版应部署同版本 Pikafish HTTP 服务。拍照识局和实时支招需要云端 ONNX 推理，迁移状态在设置页明确展示。

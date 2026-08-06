# 棋眼微信小程序

用微信开发者工具导入仓库中的 `miniprogram/` 目录。首次开发可使用测试号；发布前把 `project.config.json` 的 `appid` 替换为正式 AppID。

## 已完成

- 首页、棋力档案与 15 档难度（端侧 Lv.1-9，10-15 需 App 端体验云端引擎）
- 本地完整象棋走法合法性、对局、提示、悔棋、翻转和认输
- AI 全屏扫描动效、透明分析抽屉、快捷提问和可发送输入框
- 最近棋谱逐步复盘
- 跨端能力矩阵与共享配置自动校验
- 端侧 alpha-beta + 静态搜索 + MVV-LVA + 杀手着法 + 迭代加深，按 engineProfile 调度

## 云服务接口

- `POST /bestmove`: `{ fen, level }`，返回 `{ from: {row,col}, to: {row,col} }`
- `POST /analyze`: `{ fen, message, history }`，返回 `{ text }`

Android 的 Pikafish 是 NDK 原生库，小程序不能直接加载；正式版应部署同版本 Pikafish HTTP 服务。拍照识局和实时支招需要云端 ONNX 推理，迁移状态在设置页明确展示。

## 发布到微信公众平台

正式 AppID（`wx...`）在 mp.weixin.qq.com 注册后填入 `miniprogram/project.config.json` 的 `appid` 字段。

### 方式 A：微信开发者工具上传（推荐）

1. 打开微信开发者工具，导入 `miniprogram/` 目录
2. 右上角点击"上传"，填写版本号与项目备注
3. 工具自动打包并提交到 mp.weixin.qq.com 版本管理的"开发版本"

### 方式 B：手动打 zip 在后台"版本管理"上传

```bash
# 1. 把 project.config.json 的 appid 替换为正式 AppID
# 2. 一键打包（脚本会校验 AppID 格式、自动排除 tests/、.md、.git/）
node tools/package-miniprogram.mjs
# 产物：dist/qiyan-miniprogram-<appid>-<时间戳>.zip

# 3. 在 mp.weixin.qq.com → 版本管理 → 开发版本 → 上传代码包，选这个 zip
```

### 上传前自检

```bash
node tools/check-platform-sync.mjs   # 跨端配置一致性
node miniprogram/tests/xiangqi.test.mjs   # 引擎单元测试
```

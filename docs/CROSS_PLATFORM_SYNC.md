# App / 微信小程序同步规范

## 原则

- 小程序不是 App 的简化版, 是同一个产品的另一块屏。用户在两端应该认得出这是同一个东西:
  同样的信息架构、同样的说法、同样的图形、同样的规则。
- 以下四类东西必须**同源**, 不允许在小程序侧另写一份:
  | 内容 | 唯一事实来源 | 生成物 |
  | --- | --- | --- |
  | 产品能力 / 难度档位 / 段位切分点 / 引擎档位 | `shared/config/*.json` | `miniprogram/generated/product-config.js` |
  | 全部界面文案 (中英) | `app/src/main/res/values*/strings.xml` | `miniprogram/generated/strings.js` |
  | 图标 | `app/src/main/res/drawable/ic_*.xml` | `miniprogram/generated/icons.js` |
  | 颜色 / 间距 / 字号 / 圆角 | `values*/colors.xml`, `dimens.xml` | 手工对齐到 `miniprogram/app.wxss` 与 `core/palette.js`, 同名 token |
- UI 实现和平台 API 可以各写各的, 但用户可见的规则、文案含义、功能状态必须一致。
- 修改跨端功能时, 同一提交必须更新 `shared/config/features.json`、Android 实现和小程序实现;
  平台受限功能必须填写明确的 `roadmap`。
- 提交前运行 `node tools/sync-shared-config.mjs` 和 `node tools/check-platform-sync.mjs`。

## 页面对应

小程序每个页面都对着一个 Activity 做, 对应关系见 `miniprogram/README.md`。
新增页面时同步补进那张表, 并在 `check-platform-sync.mjs` 里加上对应的槽位检查 ——
表格会过期, 检查不会。

## 平台边界

Android 的 Pikafish 通过 NDK/JNI 本地运行, 微信小程序无法直接加载该原生库。

- 小程序 Lv.1-9 走端侧 JS 引擎, Lv.10-15 走后端 Pikafish 服务, 后端必须使用与
  Android 相同的 1-15 级映射。
- 走法合法性、棋盘交互、对局状态、记谱、评分全部在小程序本地完成, 与 App 同一套规则。
- 拍照识局、实时支招需要把 ONNX 推理迁到服务器或微信云托管; 语音走子需要接入
  微信同声传译插件。完成前能力矩阵明确显示为待同步, 入口带 App 徽章并给出提示,
  不用不可运行的假入口冒充已完成。

## 不该做的事

- 不要为了"小程序也有点东西"而加 App 没有的功能。App 里 `BoardEditorActivity`
  只在 Manifest 里注册、没有任何入口, 那是死代码, 不是待移植清单上的一项。
- 不要在小程序里另起一套说法。凡是 App 里已有的句子, 用 `strings.xml` 的 key;
  只有 App 确实没有对应界面的才写进 `shared/config/miniprogram-strings.json`,
  且 key 一律 `mp_` 前缀, 撞名会被检查脚本拦下。

## 更新流程

1. 先改共享规范和能力矩阵。
2. 同一分支分别完成 Android 与小程序实现。
3. 跑 `node tools/sync-shared-config.mjs` 生成小程序侧配置/文案/图标。
4. 跑双端测试: `./gradlew :app:testDebugUnitTest` 与 `miniprogram/tests/*.test.mjs`。
5. 仅当能力矩阵和 `check-platform-sync.mjs` 通过时提交。

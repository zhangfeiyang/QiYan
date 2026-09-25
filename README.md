# 棋眼 QiYan · 中国象棋 Android App

> 一个完整的中国象棋 Android 应用, 对弈引擎基于 **PikaFish**（GPL-3 开源），
> 经 JNI 进程内集成；整套 App 以 GPL-3 发布。
>
> **拍一张棋盘照片, 它就能看懂局面并告诉你这一手该怎么走。**

<sub>「棋眼」既指一个局面的要害点 —— 正是支招要找的东西; 也指这个 App 用摄像头把棋盘看懂的能力。</sub>

![Build Status](https://img.shields.io/badge/build-passing-brightgreen)
![Min SDK](https://img.shields.io/badge/min%20SDK-26-blue)
![Target SDK](https://img.shields.io/badge/target%20SDK-36-blue)
![Kotlin](https://img.shields.io/badge/Kotlin-1.9.22-purple)
![Play](https://img.shields.io/badge/Google%20Play-内部测试-orange)

---

## 📑 目录

- [功能特性](#-功能特性)
- [项目结构](#-项目结构)
- [技术栈与架构](#-技术栈与架构)
- [AI 引擎实现](#-ai-引擎实现)
- [拍照识别棋局](#-拍照识别棋局)
- [实时胜率分析](#-实时胜率分析)
- [提示功能实现](#-提示功能实现)
- [棋力评定系统](#-棋力评定系统)
- [中国风 UI](#-中国风-ui)
- [数据持久化](#-数据持久化)
- [编译与运行](#-编译与运行)
- [NNUE 网络的训练与更新](#-nnue-网络的训练与更新)
- [已知限制](#-已知限制)
- [许可](#-许可)
- [致谢](#-致谢)

---

## ✨ 功能特性

### 🎯 5 个难度等级

| 等级 | 名称 | 对手行为 |
|------|------|----------|
| 1 | 轻松陪练 | 以随机合法走法为主，常漏看吃子；练习不计积分，保留复盘 |
| 2 | 入门 | 只看一步吃子和眼前威胁，仍会漏算 |
| 3 | 进阶 | PikaFish，约 1.2 秒搜索 |
| 4 | 高手 | PikaFish，约 3.2 秒搜索 |
| 5 | 大师 | PikaFish，约 6 秒搜索 |

首次安装默认轻松陪练。前两档有棋子规则提示和合法落点；提示与主动分析始终使用专业引擎。
旧版 15 档的偏好按每三档归并为一档，已完成的闯关进度同步迁移。
难度标称积分仅用于相对换算，未经外部 Elo 标定；高手档实际响应取决于设备与资源设置。

### 🌿 每日练习与视听

- 首页以轻松开局为主入口，包含每日一题、连续练习天数和闯关进度。
- 每日一题按本地日期轮换现有题库，当天成功完成才计入；同一天重复完成不重复增加天数。
  新手的前 7 次每日一题只出一两步杀，打满一周再进全库轮换。
- 纸感水墨背景、墨绿暖金主视觉、立体棋子插画；首页和对手选择支持滚动。
- 原创 64 秒器乐《松风入局》，可调音量、试听，失去音频焦点或离开前台暂停。
- 落子涟漪、将军光环与吃子反馈，支持关闭并尊重系统动画设置。

### 📊 实时胜率分析
- 棋盘顶部动态显示双方胜率百分比
- 引擎评估值、深度、NPS 实时显示
- 颜色强度随优势变化 (淡红 → 暗红 / 淡黑 → 墨黑)
- 支持杀棋检测与显示

### 💡 对战中提示
- 点击"提示"按钮,引擎立即开始分析
- 主变例 + 备选方案
- 显示中文着法 (如"炮二平五")
- 显示评分与杀棋步数

### 🏆 棋力评定 (Glicko-2)
- 10 个段位 (初学者 → 棋王)
- 新档案前 3 盘计分对局为定级赛：只定分不授印，下满 3 盘揭晓首枚段位印
- 计分局中途换边转为练习（先确认）；中途改档按本局出现过的最低档结算
- 评分偏差 (RD) 反映评分不确定度
- 评分波动率反映近期表现稳定性
- 实时显示胜负场数与胜率

### 🎨 经典中国风 UI
- 木纹棋盘 + 红黑圆形棋子
- 楷体中文字符 (帅仕相马车炮兵)
- 楚河汉界文字 + 九宫对角线
- 走子动画 (60fps)
- 翻转棋盘 (执黑视角)

### 📷 拍照识别棋局
拍一张棋盘照片(或从相册选图), 自动识别出局面, 确认后让 AI 支招。
支持斜角拍摄 —— 先检测棋盘四角做透视校正, 再识别棋子。

进入这一屏**不会自动弹相机**: 先给一屏引导, 拍照与从相册选并列摆着 (用截图找棋局和
拍实体棋盘一样常见), 并附三条拍摄要点 —— 识别准不准基本取决于拍得正不正。

### 🎥 实时支招
摄像头对准真实棋盘, 局面一变就重算并在预览上画出建议着法, 不用拍照也不用点按钮。
首页独立入口。

单帧识别约 300 ms; 连续两帧识别出同一局面才认为"稳定"并触发搜索, 避免手抖时反复重算。

### 📜 其他特性
- 自动保存所有对局 (Room SQLite)
- 对局回放 (前/后步/自动播放)，结算页一键「复盘本局」
- 悔棋（不扣分，轻松陪练整局不计积分）；结算后可「继续对局」，续下部分不计分
- 认输/求和
- 多线程数与哈希表可调
- 主题切换 (浅色/深色/跟随系统)
- 音效与振动反馈

---

## 📁 项目结构

```
zhongguoxiangqi/
├── app/                                 # Android 应用模块
│   ├── src/main/
│   │   ├── java/com/zhongguoxiangqi/    # Kotlin 源码 (32 个文件)
│   │   │   ├── game/                    # 棋盘核心 (7 文件)
│   │   │   │   ├── Piece.kt             # 棋子定义与编码
│   │   │   │   ├── Square.kt            # 坐标系统 (10×9)
│   │   │   │   ├── Move.kt              # 走法编码 (16-bit)
│   │   │   │   ├── Position.kt          # 局面管理与 FEN
│   │   │   │   ├── Zobrist.kt           # 哈希键 (置换表/检测重复)
│   │   │   │   ├── MoveGenerator.kt     # 所有棋子走法生成
│   │   │   │   └── Rules.kt             # 将军/将死/困毙/飞行将军判定
│   │   │   ├── engine/                  # AI 引擎集成 (6 文件)
│   │   │   │   ├── ChessEngine.kt       # 引擎接口抽象
│   │   │   │   ├── nnue/                # 自研 NNUE: 特征编码/权重加载/求值
│   │   │   │   ├── SimpleEngine.kt      # 内置 Alpha-Beta 引擎
│   │   │   │   ├── EngineManager.kt     # 难度映射与故障降级
│   │   │   │   └── OpeningBook.kt       # 开局库查询
│   │   │   ├── analysis/                # 胜率分析
│   │   │   │   └── WinRateAnalyzer.kt   # 厘兵 → 胜率转换
│   │   │   ├── rating/                  # 棋力评定
│   │   │   │   ├── RatingSystem.kt      # Glicko-2 + Elo
│   │   │   │   └── UserProfile.kt       # 用户档案持久化
│   │   │   ├── data/                    # 数据持久化
│   │   │   │   ├── GameRecord.kt        # Room 实体 + DAO
│   │   │   │   └── AppDatabase.kt       # Room 数据库单例
│   │   │   ├── ui/                      # 用户界面
│   │   │   │   ├── board/
│   │   │   │   │   ├── BoardView.kt          # 棋盘自定义 View (Canvas)
│   │   │   │   │   ├── WinRateBarView.kt     # 实时胜率条
│   │   │   │   │   └── BoardEditorActivity.kt
│   │   │   │   ├── menu/                # 主菜单
│   │   │   │   ├── game/                # 主对局 (Activity + ViewModel)
│   │   │   │   ├── replay/              # 对局回放
│   │   │   │   └── settings/            # 设置
│   │   │   └── ZhongguoXiangqiApp.kt    # Application 类
│   │   ├── res/                         # Android 资源
│   │   │   ├── layout/                  # 9 个 XML 布局
│   │   │   ├── values/                  # 颜色/字符串/尺寸/主题
│   │   │   ├── drawable/                # 矢量图标
│   │   │   ├── mipmap-*/                # 启动图标
│   │   │   └── xml/                     # 备份规则
│   │   ├── assets/
│   │   │   ├── models/xiangqi.nnue     # 自研 NNUE 网络 (648 KB)
│   │   │   └── book/                    # 开局库
│   │   ├── jniLibs/arm64-v8a/
│   │   ├── cpp/                         # 求值核心 (C++/NEON) + JNI 桥
│   │   └── AndroidManifest.xml
│   ├── src/test/                        # 单元测试
│   └── build.gradle.kts
├── gradle/                              # Gradle Wrapper
├── ndk-build/
│   ├── NDK_BUILD.md                     # 引擎编译指南
│   └── nnue/                            # 训练流水线 (Python): 数据/训练/量化导出
├── build.gradle.kts
├── settings.gradle.kts
└── README.md
```

**代码量**: ~6,700 行 Kotlin, 36 个 XML 资源, ~80 个文件。

---

## 🏛️ 技术栈与架构

### 核心栈
- **语言**: Kotlin 1.9.22
- **构建**: Gradle 8.9 + AGP 8.2.2
- **JDK**: 17
- **minSdk**: 26 (Android 8.0) / **targetSdk**: 34 (Android 14)
- **UI**: 传统 View + ViewBinding (Material Components 1.11)
- **数据库**: Room 2.6.1 (KSP 处理注解)
- **协程**: Kotlin Coroutines 1.7.3
- **设置**: DataStore Preferences 1.0.0

### 架构模式
- **MVVM**: ViewModel + StateFlow + LiveData
- **JNI 进程内求值**: 自研 NNUE 的 C++/NEON 核心通过 `System.loadLibrary("xqnnue")` 加载
- **降级容错**: native 不可用或自检不过 → 自动退回 Kotlin 实现, 再不行退回手写评估

---

## 🤖 AI 引擎实现

App 内的对弈引擎采用 **PikaFish**（https://github.com/initdc/Pikafish），
走法生成、搜索与 NNUE 评估全部由 PikaFish 完成，经 JNI 进程内加载
（`PikafishNative` ↔ `pikafish_jni.cpp`）。

### 为什么重新采用 Pikafish

经过多轮自研搜索与网络优化（见 [`nnue/RESULTS.md`](nnue/RESULTS.md)），
自研引擎在 500ms 固定用时门槛下始终无法追上 PikaFish 的棋力（残局深度差
46 层、中局 EBF 2.90 vs 2.21，且所有参数/排序/LMR 微调均未转化为实战
提升）。决定整体切换到 PikaFish 并让整个应用以 GPL-3 开源。

### 集成方式

- C++ 侧：`third_party/Pikafish/src/pikafish_jni.cpp` 提供 JNI 入口，
  CMake 直接编译 PikaFish 源码为 `libpikafish.so`。
- Kotlin 侧：`PikafishNative` 对接 12 个 JNI 函数，`PikafishEngine`
  实现 `ChessEngine` 接口，`EngineManager` 统一使用 PikaFish。
- 权重：`pikafish.nnue` 构建时从 `third_party/Pikafish/src/` 复制进 assets。

```
输入 own[11340] opp[11340]        稀疏, 每边最多 32 个 1
        |          |
   FeatureTransformer (共享权重 11340->256)
        |          |
      acc_own    acc_opp          各 256 维
        +----+-----+
          concat 512
             | ClippedReLU
            32
             | ClippedReLU
            32
             |
             1  ->  厘兵分
```

- **双视角共享权重**: 网络学的是"我方/敌方"而不是"红方/黑方", 参数量减半, 天然带上
  红黑对称的先验。
- **轨迹与标签解耦**: 本项目引擎负责产生局面，可显式让外部 UCI 教师只打数值标签。
  当前采用学生轨迹 + Pikafish 标签的 DAgger 流程，并记录教师二进制、教师 NNUE、
  学生网络及参数的 SHA-256。[GNU GPL FAQ](https://www.gnu.org/licenses/gpl-faq.html#WhatCaseIsOutputGPL)
  说明程序输出通常不自动受 GPL 约束，但具体模型发行仍需结合训练方式、输出内容与
  法域复核；[Pikafish 本体为 GPLv3](https://github.com/official-pikafish/Pikafish)，
  不随 App 分发。
- 数据/网络格式见 [`nnue/FORMAT.md`](nnue/FORMAT.md), 各代实测结果见
  [`nnue/RESULTS.md`](nnue/RESULTS.md)。

### 端上推理: C++/NEON

求值核心在 `app/src/main/cpp/`, 全整数运算:

| 层 | 量化 | 说明 |
|---|---|---|
| FeatureTransformer | i16, 尺度 QA=127 | 累加器 i16 够用, 实测最坏情况有 55 倍余量 |
| fc1 / fc2 | **i8**, 尺度 QW=256 | 激活经 ClippedReLU 落在 [0,127] |
| 输出层 | **i32**, 尺度 QW=256 | 避免成熟网络的百量级输出权重饱和 |

fc1 是热点 (`l2 x l1 x 2` 次乘加), NEON 一条 `smull` 顶 8 路 i8 乘加。

**真机实测** (V2502DA, arm64-v8a, 满盘 32 子, 5 次取值):

| 评估 | 次/秒 | 相对手写 |
|---|---:|---:|
| 手写 (子力+PST) | ~810k | 1x |
| **NNUE native (C++/NEON)** | **~363k** | 慢 2.2x (约 0.7 层) |
| NNUE Kotlin (退路) | 23k | 慢 35x |

纯 Kotlin 标量实现是慢 59 倍、少搜 3.7 层 —— NEON 把"装上网络"的门槛从基本没戏
降到了够得着。

### 正确性怎么保证

NNUE 算错**不会崩溃**, 只会让棋力莫名其妙地差, 是最难查的那类问题。所以有四道:

1. **跨语言黄金向量**: 200 个真实局面, Python 参考实现 `int_infer` 的输出钉成文件,
   Kotlin 与 C++ 两侧逐条比对**完全相等** —— 整数推理是确定性的, 没有"误差范围"可言。
2. **NEON vs 标量**: 真机上 20000 条随机局面逐位比对。
3. **运行时自检**: 每次加载网络跑 256 条 NEON vs 标量, 对不上就退回 Kotlin 实现,
   宁可慢也不能带着可疑实现跑。
4. **加载即校验**: 层宽、量化尺度全写在文件头, 对不上直接拒绝加载 —— 尺寸错位不会
   崩溃, 只会让网络输出垃圾分数而引擎照样下棋。

### 引擎参数映射

| 难度 | 引擎 | 线程数 | 哈希(MB) | 搜索/回应时间 |
|------|------|--------|----------|----------|
| 1 | 新手策略 | 1 | 不需要 | 0.35s |
| 2 | 新手策略 | 1 | 不需要 | 0.55s |
| 3 | PikaFish | 2 | 64 | 1.2s |
| 4 | PikaFish | 4 | 128 | 3.2s |
| 5 | PikaFish | 4 | 256 | 6s |

线程数与哈希最终受用户设置和设备内存上限约束。

> 实时支招走 `strictMovetime`: 时限是硬约束, 不被难度抬高。否则支招传 600ms 会被
> 悄悄抬成几秒, "实时"就名存实亡了。

---

## 📷 拍照识别棋局

从一张照片还原局面, 再交给引擎支招。模型来自
[TheOne1006/chinese-chess-recognition](https://github.com/TheOne1006/chinese-chess-recognition) (MIT),
预训练 ONNX 取自 [HuggingFace Space](https://huggingface.co/spaces/yolo12138/Chinese_Chess_Recognition)。

### 两阶段流水线

`vision/BoardRecognizer.kt` —— 纯 Kotlin + ONNX Runtime, **不依赖 OpenCV**
(透视变换用 `android.graphics.Matrix.setPolyToPoly`, 4 个点对即完整透视)。

| 阶段 | 模型 | 输入 | 输出 |
|---|---|---|---|
| 1. 棋盘四角关键点 | RTMPose (10.7 MB) | 256×256 | SimCC x/y 各 512 bin → A0/A8/J0/J8 |
| 2. 棋子分类 | SwinV2-tiny (31 MB) | 280×315 | `(1, 90, 16)`, 已 softmax |

先按四角做**透视校正**拉成正视图再分类, 这是能识别"斜着拍"的关键 ——
分类模型只需要面对正视图。两个模型共用 ImageNet 归一化。

16 个类别为 `".xKABNRCPkabnrcp"`, 与本项目的 `Piece.fen` 完全对应;
`x` 表示"不在棋盘上", 出现说明四角没框准。

### 坐标约定

模型输出的 10×9、App 的 `Square`、标准 FEN **三者行序完全一致**
(row 0 = 黑方底线), 因此不需要任何翻转 —— 注意这与 UCI 相反 (UCI 的
rank 0 是红方底线, 见 `game/UciCoords.kt`)。
`BoardLayoutTest` 用"起始排布转出的 FEN == `Position().toFen()`" 锁死了方向。

### 为什么必须人工确认

作者验证集: 整盘全对 **83%**, 错 ≤1 格 90%, 错 ≤3 格 96%。
也就是**每 6 张图就有 1 张至少错一个子, 而错一个子引擎的建议就完全错误**。

所以 `RecognizeActivity` 的流程是"识别 → 人工确认 → 才允许求招":

- 置信度低于 0.75 的格子画**黄圈**, 点一下即可改子
- `BoardLayout.validate` 校验将帅数量/九宫/相象过河/子力超限, 以及
  "该走方的对手正在被将"(这种局面立不住); 有阻断级问题时求招按钮直接禁用
- 四角没框准(出现 `x` 格或关键点得分过低)会提示重拍

已知弱点: 黑车(車)/黑卒易混; 非俯视角度掉点。

### 与参考实现对拍

`app/src/androidTest/.../BoardRecognizerTest.kt` 在真机上跑三张参考图,
与 Python 参考实现的输出**逐格比对**:

```
demo003.png        与参考实现差 0 格, 最低置信度 0.81
demo004.png        与参考实现差 0 格, 最低置信度 0.75
video010-0004.jpg  与参考实现差 0 格, 最低置信度 0.81   ← 真实斜角照片
单张识别耗时 278ms
```

容差卡死 0 格 —— 仿射/SimCC 解码/透视映射一旦改坏立刻会红。

```bash
./gradlew connectedDebugAndroidTest
```

> 参考实现 `chessboard_detector.py` 里 `width, height = image_bgr.shape[:2]`
> 把宽高写反了(shape 是 `(h, w)`), 非方形图片的仿射中心会偏。本项目用的是
> 正确写法(实测平均角点得分 0.267 → 0.271, 差异在噪声范围内)。

---

## 📊 实时胜率分析

**文件**: `app/src/main/java/com/zhongguoxiangqi/analysis/WinRateAnalyzer.kt`

### 胜率公式

从 Elo 期望胜率公式反推:

```
W = 1 / (1 + 10^(-Δ/400))
```

将厘兵转换为胜率 (红方视角):

```kotlin
fun redWinRate(scoreCp: Int, scoreMate: Int = 0): Double {
    if (scoreMate != 0) {
        return if (scoreMate > 0) 99.9 else 0.1
    }
    return 100.0 / (1.0 + Math.pow(10.0, -scoreCp / 600.0))
}
```

**参数解释**:
- `SCALING = 600`: 每 600 厘兵优势 = 100% 胜率
- 杀棋局面直接返回 99.9% / 0.1%

### 实时显示

- **位置**: 棋盘上方,横向条形图 (`WinRateBarView`)
- **数据源**: 引擎 `onInfo()` 回调 → `WinRateSnapshot`
- **更新频率**: 引擎每个搜索节点完成后 (通常每秒数十次)
- **平滑动画**: 旧/新胜率线性插值 1 秒

---

## 💡 提示功能实现

**文件**: `app/src/main/java/com/zhongguoxiangqi/ui/game/GameViewModel.kt`

### 流程

1. 用户点击"提示"按钮
2. `GameViewModel.requestHint()`:
   - 设置 `isHintSearchActive = true`
   - 启动引擎搜索 (2.5 秒思考时间, MultiPV=3)
3. 引擎返回 `onBestMove()` 时:
   - 检测 `isHintSearchActive` 标志
   - 走法保存到 `_hintState` 而非实际执行
4. UI 显示提示行:
   - 主推荐 + 备选着法 (中文格式)
   - 评估值 (厘兵或杀棋步数)

### 着法格式化

```kotlin
fun toChineseString(move: Move, side: Int, board: Position): String {
    // "炮二平五" 格式
    // 前/后/中 区分同列同种棋子
    // 进/退/平 区分移动方向
}
```

---

## 🏆 棋力评定系统

**文件**: `app/src/main/java/com/zhongguoxiangqi/rating/`

### Glicko-2 算法

相比传统 Elo,Glicko-2 增加了两个维度:
- **RD (Rating Deviation)**: 评分偏差,反映不确定度 (新用户 RD=350, 常比赛用户 RD<100)
- **σ (Volatility)**: 波动率,反映近期表现稳定性

```
新玩家: rating=1500, RD=350, σ=0.06
经过 5 胜: rating=1585, RD=164
```

### 段位映射

| 段位 | 评分范围 | 颜色 |
|------|----------|------|
| 1. 初学者 | 0-999 | 铜 |
| 2. 入门棋友 | 1000-1199 | 铜 |
| 3. 业余初段 | 1200-1399 | 银 |
| 4. 业余三段 | 1400-1599 | 银 |
| 5. 业余五段 | 1600-1799 | 金 |
| 6. 业余七段 | 1800-1999 | 金 |
| 7. 地方大师 | 2000-2199 | 白金 |
| 8. 国家大师 | 2200-2399 | 钻石 |
| 9. 特级大师 | 2400-2599 | 大师 |
| 10. **棋王** | 2600+ | 宗师 |

### 对局结果影响

轻松陪练与闯关不计入排位战绩。其他对局的内部对手估值为入门 800、进阶 1450、
高手 2000、大师 2500，仅用于相对积分变化，不代表实测真人棋力或胜率。
中途改成轻松陪练后，该局仍按不计分练习结算，避免通过切换难度刷分。

---

## 🎨 中国风 UI

**文件**: `app/src/main/java/com/zhongguoxiangqi/ui/board/BoardView.kt`

### 棋盘绘制
- **背景**: LinearGradient 木纹 (深褐→浅褐)
- **边框**: 圆角 12dp + 深色描边
- **网格**: 9×10 黑色细线 (1-2px)
- **楚河汉界**: 七号楷体粗体,棋盘中部
- **九宫**: 上方/下方 X 形对角线

### 棋子绘制
- **外圈**: 1-2px 红/黑描边
- **内圈**: RadialGradient 木纹底色 (#FFF8DC → #DEB887)
- **木纹细节**: 横线纹理 (透明度 30%)
- **字符**: 中文楷体,32px, 红/黑着色
- **阴影**: 3-4px 偏移深色阴影

### 交互
- **触摸**: 点击选格 → 拖动 → 落子
- **高亮**: 选中(蓝)/可走(绿点)/吃子(红框)/最后走(橙框)/提示(紫)/将军(红闪)
- **走子动画**: ValueAnimator 220ms 缓动
- **翻转**: 镜像行列坐标

### 颜色调色板

```xml
<!-- 主色 -->
<color name="primary">#8B4513</color>       <!-- 木纹主色 -->
<color name="accent">#C9302C</color>        <!-- 中国红 -->

<!-- 棋盘 -->
<color name="board_light">#F5DEB3</color>
<color name="board_dark">#DEB887</color>
<color name="board_wood">#8B5A2B</color>

<!-- 棋子 -->
<color name="piece_red">#C8102E</color>
<color name="piece_black">#1A1A1A</color>

<!-- 段位 -->
<color name="rank_master">#FF4500</color>
<color name="rank_grandmaster">#9400D3</color>
<color name="rank_king">#FFD700</color>
```

---

## 💾 数据持久化

### 1. Room SQLite (对局记录)

**位置**: `app/src/main/java/com/zhongguoxiangqi/data/`

```kotlin
@Entity(tableName = "games")
data class GameRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startTime: Long,
    val endTime: Long,
    val playerSide: Int,    // 0=红 1=黑
    val difficulty: Int,    // 1-5（旧记录保留历史档位）
    val engineElo: Int,
    val result: Int,        // 1=胜 -1=负 0=和
    val ratingDelta: Int,
    val playerRating: Int,
    val moves: List<String>, // UCI 格式
    val initialFen: String,
    val endReason: String
)
```

### 2. SharedPreferences (用户档案 & 设置)

存储路径: `/data/data/com.zhongguoxiangqi/shared_prefs/`
- `user_profile.xml`: Glicko-2 评分、战绩、昵称
- `settings.xml`: 线程数、哈希、音效、振动、主题等

### 3. Internal Storage (引擎二进制)

`/data/data/com.zhangfy.zhongguoxiangqi/files/engine/`
> 自研 NNUE 网络直接从 assets 读取 (`models/xiangqi.nnue`), 不再往内部存储解压任何引擎文件。

---

## 🔨 编译与运行

### 前置要求
- JDK 17 (`/usr/lib/jvm/java-17-openjdk-amd64`)
- Android SDK 36 (`compileSdk`/`targetSdk` 均为 36)
- Gradle 8.x

### 编译

```bash
# 配置环境
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=/path/to/android-sdk

# 编译 Debug APK
cd /home/zhangfy/zhongguoxiangqi
./gradlew assembleDebug

# 输出位置
ls app/build/outputs/apk/debug/app-debug.apk
```

### 已验证的编译

```bash
$ ./gradlew assembleDebug
> Task :app:assembleDebug
BUILD SUCCESSFUL in 1m 7s
38 actionable tasks: 11 executed, 27 up-to-date
```

**产物大小**: release AAB 约 **60.8 MB** (大头是 ONNX 识别模型 ~40 MB + ONNX Runtime ~17 MB; 自研 NNUE 仅 648 KB)

### 安装

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Android Studio

直接用 Android Studio (Hedgehog+) 打开根目录的 `build.gradle.kts`。

---

## 🚀 NNUE 网络的训练与更新

自研网络的完整流水线在 [`nnue/`](nnue/) 下, 全部是 Python:

| 文件 | 作用 |
|---|---|
| `FORMAT.md` | 样本格式 + 网络文件格式规范 (Kotlin/C++/Python 三方共同遵守) |
| `dataset.py` | 读 `.bin` 样本, 做视角转换 |
| `model.py` | 网络结构 |
| `train.py` | 训练 |
| `export.py` | 量化导出 `.nnue` + 误差自检 |
| `make_golden.py` | 生成跨语言黄金向量 |
| `RESULTS.md` | 各代实测 Elo 与求值速度 |

### 数据从哪来

`SelfPlayGenerator` 负责产生轨迹。没有外部教师时可顺手把本地搜索分当标签；使用
Pikafish 蒸馏时，必须让当前学生负责走棋、Pikafish 只给学生实际到达的局面评分。
开局随机 8 步, 中局按 5% 概率乱走 —— 后者是必要的: 开局随机之后引擎是确定性的,
一局棋会一路走在同一条最佳线上, 那一百多条样本彼此只差一步棋, 条数好看但有效信息不多。
加了中局随机化之后标签 std 从 814 涨到 1024 厘兵。

```bash
./gradlew :app:testDebugUnitTest --tests "*DataGenRunner*" --info \
    -Dnnue.run=1 -Dnnue.games=400000 -Dnnue.threads=8 -Dnnue.depth=3 \
    -Dnnue.actor=student -Dnnue.teacherNet=/path/to/current.nnue \
    -Dnnue.uciTeacher=/path/to/pikafish -Dnnue.uciDepth=8 \
    -Dnnue.out=$HOME/nnue-data
```

`nnue.actor=student` 是关键；旧实现一旦启用 UCI 教师就直接采用教师 bestmove，
导致传入的学生网络没有参与轨迹生成。每个 seed 现在使用独立分片名，同一 seed
重复运行会被拒绝，避免把完全相同的棋局静默追加多次。

### 更新网络 = 换一个文件

层宽和量化尺度全写在 `.nnue` 文件头里, 端上按文件说的来。**换更宽的网络、换训练更久的
权重, 都不用改一行代码**:

```bash
python3 nnue/train.py --data ~/nnue-data --epochs 30 --l1 256 --out net.pt \
        --val-data ~/nnue-val --seed 20260802
python3 nnue/export.py --ckpt net.pt --l1 256 \
        --out app/src/main/assets/models/xiangqi.nnue
# 把 app/build.gradle.kts 的 versionCode 加一
./gradlew :app:bundleRelease
```

换完跑这个验收 (它验的是"发布路径通不通": assets 里文件在不在、走没走上 native):

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.zhongguoxiangqi.engine.nnue.NnueNativeTest
```

### 判断新一代到底有没有变强

先用**独立目录**的 PikaFish 留出集检查导出后的整数网络，再看实战 Elo：

```bash
./gradlew :app:testDebugUnitTest --tests "*NnueValidationRunner*" \
    -Dnnue.validate=1 -Dnnue.net=/path/to/new.nnue \
    -Dnnue.valData=/path/to/independent-val-data

./gradlew :app:testDebugUnitTest --tests "*NnueGauntletRunner*" --info \
    -Dnnue.match=1 -Dnnue.net=/path/to/new.nnue -Dmatch.pairs=100 -Dmatch.depth=4
```

结果带 95% 置信区间。**分差没盖过误差就是"没测出差别", 不是"略强"** —— 自举训练最容易
出的事就是"感觉变强了", loss 降了就接着练, 几代之后才发现早就走偏。

### 发布签名

上传密钥库在仓库外: `~/.android/qiyan-keystore.properties` 指向 `~/.android/qiyan-upload.jks`。
文件不存在时 release 构建会产出未签名产物而不是报错。

> ⚠️ **这个 keystore 必须自行备份 —— 丢了就再也无法更新这个应用。**
>
> ⚠️ **`versionCode` 必须递增。** Play 撞号会拒, 而那个错误只在 bundle 那一行显示为
> 红字, 整个草稿版本会静默失效。

---

## 🧪 单元测试

**位置**: `app/src/test/java/com/zhongguoxiangqi/`

### 测试覆盖

| 模块 | 文件 | 测试 |
|------|------|------|
| `game/` | `PositionTest.kt` | 32 棋 / 起始 44 走法 / Hash 确定性 / 走子悔棋 |
| `game/` | `MoveGeneratorTest.kt` | 车/马/炮/兵/仕/帅走法 / 蹩马腿 / 塞象眼 / 炮架 |
| `analysis/` | `WinRateAnalyzerTest.kt` | 均势 / 优势 / 杀棋胜率 |
| `rating/` | `RatingSystemTest.kt` | Glicko-2 升降分 / Elo 期望值 |
| `engine/nnue/` | `NnueEvaluatorTest.kt` | **与 Python 参考实现逐条比对 200 条黄金向量** / 加载器防呆 |
| `engine/nnue/` | `SelfPlayDiversityTest.kt` | 中局随机化是否真的摊开了标签分布 |
| `engine/` | `QuiescenceInCheckTest.kt` | 静止搜索将死分 / 安静应将不能 stand-pat |
| `engine/` | `SearchParamsAdaptTest.kt` | 硬时限不被抬高 / 难度不倒挂 / 越界夹紧 |
| `engine/eval/` | `MatchRunnerTest.kt` | Elo 换算与置信区间 / 配对开局可复现 |

**仪器测试** (需真机或模拟器, `app/src/androidTest/`):

| 文件 | 测试 |
|---|---|
| `NnueNativeTest.kt` | NEON vs 标量 20000 条逐位相同 / 对黄金向量 / 随包网络能加载并走 native / 求值速度 |

### 运行测试

```bash
./gradlew test
```

核心单元测试与 NNUE 跨语言黄金测试均应通过。

> NNUE 的跨语言一致性做过变异验证: 故意把视角对调、或让输出层少除一个 QA, 两种错都会
> 被黄金向量测试抓到 —— 一个从没失败过的测试不能证明它有用。

---

## 📝 已知限制

1. **仅 arm64-v8a**: NNUE 求值核心与 ONNX Runtime 都只编了这一个 ABI。x86_64 模拟器上
   native 库加载失败, 会自动退回 Kotlin 求值 (慢 35 倍, 但能跑)。
2. **固定用时棋力仍落后 Pikafish**: 当前发布网络为 DAgger blend v1。其 2 秒/步
   预验收相对 net328m 显示出明显改善，但仍未达到 PikaFish 的 50% 持平线，且完整
   固定用时置信区间尚未收敛；后续优化以该网络的学生轨迹 + PikaFish depth 10 标签为
   基线。进展见 [`nnue/RESULTS.md`](nnue/RESULTS.md)。
3. **绝对 Elo 无法标定**: 没有已知等级分的对手, 所以界面上不显示分数, 只给档位名与描述。
4. **识别不是 100% 可靠**: 整盘全对约 83%, 即每 6 张图就有 1 张至少错一个子 —— 所以
   求招前强制人工确认。
5. **包体积偏大**: 60.8 MB, 大头是 ONNX 识别模型 (~40 MB) 与 ONNX Runtime (~17 MB)。
6. **复盘界面**: 走法列表以数字格式显示, 未做完整中文棋谱格式化。
7. **多语言**: 当前仅简体中文。
8. **残局库**: 未集成残局表。

---

## 📦 依赖清单

```kotlin
// AndroidX 核心
implementation("androidx.core:core-ktx:1.12.0")
implementation("androidx.appcompat:appcompat:1.6.1")
implementation("androidx.constraintlayout:constraintlayout:2.1.4")
implementation("androidx.fragment:fragment-ktx:1.6.2")
implementation("androidx.activity:activity-ktx:1.8.2")

// Material Design
implementation("com.google.android.material:material:1.11.0")

// Lifecycle & ViewModel
implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

// Coroutines
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

// Room
implementation("androidx.room:room-runtime:2.6.1")
implementation("androidx.room:room-ktx:2.6.1")
ksp("androidx.room:room-compiler:2.6.1")

// DataStore
implementation("androidx.datastore:datastore-preferences:1.0.0")

// RecyclerView
implementation("androidx.recyclerview:recyclerview:1.3.2")
```

---

## 📄 许可

本项目以 **GNU General Public License v3** 发布（见 [LICENSE](LICENSE) 与
[NOTICE](NOTICE)）。

App 内置 **PikaFish** 引擎（GPL-3，[源码](third_party/Pikafish)），
经 JNI 进程内加载。依据 GPL-3 的要求，本应用完整源代码随发布提供，任何人
可按 GPL-3 条款使用、修改与再分发。

---

## 🙏 致谢

- **[Stockfish](https://stockfishchess.org/)** - NNUE 技术与量化方案的思路来源
- **[PikaFish](https://github.com/initdc/Pikafish)** - 对局引擎（GPL-3）
- **[ONNX Runtime](https://onnxruntime.ai/)** - 端上棋盘识别推理
- **[Material Components](https://material.io/)** - UI 组件
- **[Glicko-2](http://www.glicko.net/glicko/glicko2.pdf)** - 评分算法
- **[AndroidX](https://developer.android.com/jetpack/androidx)** - 应用框架

---

## 📞 联系

如有问题或建议,欢迎提交 Issue 或 PR。

**项目地址**: <https://github.com/zhangfeiyang/QiYan>
**隐私政策**: <https://zhangfeiyang.github.io/qiyan-site/privacy-policy.html>
**当前状态**: v1.0.0 已发布至 Google Play 内部测试轨道

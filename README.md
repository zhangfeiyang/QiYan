# 棋眼 QiYan · 中国象棋 Android App

> 一个完整的中国象棋 Android 应用, 对弈 AI **完全自研**: alpha-beta 搜索 + 自训练 NNUE 神经网络评估, 求值核心走 C++/NEON。
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

### 🎯 6 个难度等级

| 等级 | 名称 | 思考时间 | 对手大概什么水平 |
|------|------|----------|------------------|
| 1 | 入门 | 0.1s | 会走棋, 但经常白送子 |
| 2 | 初级 | 0.3s | 能吃明摆着的子, 看不出陷阱 |
| 3 | 中级 | 0.8s | 有基本战术, 会抓双叫将 |
| 4 | 高级 | 1.5s | 开局成套路, 中局能算三五步 |
| 5 | 大师 | 2.5s | 很少走软手, 残局功底扎实 |
| 6 | 特级大师 | 4s | 本机最强档, 算得深, 抓得住明显失误 |

> **界面上不显示具体 Elo 分数。** 绝对 Elo 需要有已知等级分的对手才能标定, 而我们没有 ——
> 摆一个精确到十位的数字, 是给用户一个并不存在的精确度。代码里保留了估计值
> (600/900/1200/1450/1600/1700), 仅用于玩家自己积分涨落的相对换算。
>
> 早先版本有 10 档、最高标 Elo 3300, 那是 Pikafish 的棋力。Pikafish 因 GPL-3 已移除,
> 那些数字随之删掉 —— 留着就是虚假宣传。

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
- 对局回放 (前/后步/自动播放)
- 悔棋 (评分轻微损失)
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

对弈 AI **完全自研**, 不依赖任何外部引擎。

### 为什么移除了 Pikafish

早期版本在难度 6-10 走 Pikafish (JNI 进程内加载)。它棋力很强, 但:

- **GPL-3**: `libpikafish.so` 经 JNI 加载构成结合作品, 一旦分发 (发给内测者也算),
  整个应用都要按 GPL-3 开源并提供对应源码。
- **体积**: `pikafish.nnue` 单个文件 49 MB, 占了 AAB 的一半。

移除之后 AAB 从 **116.7 MB 降到 60.8 MB**, 同时不再有 GPL 义务。代价是高难度档位
明显变弱 —— 自研网络目前的水平见 [`nnue/RESULTS.md`](nnue/RESULTS.md)。

### 搜索: SimpleEngine

`engine/SimpleEngine.kt` — alpha-beta + 迭代加深, 外加置换表 (Zobrist, 深度优先替换)、
静态搜索、MVV-LVA 走法排序、杀手着法、历史启发、主变例搜索 (PVS)、后期走法削减 (LMR)。

这套增强实测把**同深度的节点数降到原来的 1/7**。

### 评估: 自研 NNUE

```
输入  own[1260]  opp[1260]        稀疏, 每边最多 32 个 1
        |          |
   FeatureTransformer (共享权重 1260->256)
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
- **训练数据全部来自本项目引擎的自对弈**, 不接触 Pikafish 或任何 GPL 引擎的输出 ——
  蒸馏别人的网络会让权重成为衍生作品 (参见 Fat Fritz 2 案例)。
- 数据/网络格式见 [`nnue/FORMAT.md`](nnue/FORMAT.md), 各代实测结果见
  [`nnue/RESULTS.md`](nnue/RESULTS.md)。

### 端上推理: C++/NEON

求值核心在 `app/src/main/cpp/`, 全整数运算:

| 层 | 量化 | 说明 |
|---|---|---|
| FeatureTransformer | i16, 尺度 QA=127 | 累加器 i16 够用, 实测最坏情况有 55 倍余量 |
| fc1 / fc2 | **i8**, 尺度 QW=64 | 激活经 ClippedReLU 落在 [0,127], 正好装进 i8 |
| 输出层 | **i16**, 尺度 QW=64 | 要从 32 个 [0,1] 凑出 ±2000 厘兵, i8 差两个数量级 |

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

| 难度 | 线程数 | 哈希(MB) | 思考时间 |
|------|--------|----------|----------|
| 1 | 1 | 16 | 0.1s |
| 2 | 1 | 16 | 0.3s |
| 3 | 1 | 16 | 0.8s |
| 4 | 1 | 16 | 1.5s |
| 5 | 1 | 16 | 2.5s |
| 6 | 1 | 16 | 4s |

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

| 玩家对 AI (内部估计分) | 期望胜率 (玩家 1500 分) |
|----------------------|------------------------|
| 入门 (600) | ~98% |
| 初级 (900) | ~93% |
| 中级 (1200) | ~77% |
| 高级 (1450) | ~57% |
| 大师 (1600) | ~36% |
| 特级大师 (1700) | ~24% |

> 括号里的分数只用于积分换算, 界面上不显示 —— 见 [6 个难度等级](#-6-个难度等级)。

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
    val difficulty: Int,    // 1-10
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

引擎自对弈。`SelfPlayGenerator` 在自对弈时**顺手把每步的搜索分当标签**, 不必再单独深搜
一遍。开局随机 8 步, 中局按 5% 概率乱走 —— 后者是必要的: 开局随机之后引擎是确定性的,
一局棋会一路走在同一条最佳线上, 那一百多条样本彼此只差一步棋, 条数好看但有效信息不多。
加了中局随机化之后标签 std 从 814 涨到 1024 厘兵。

```bash
./gradlew :app:testDebugUnitTest --tests "*DataGenRunner*" --info \
    -Dnnue.run=1 -Dnnue.games=400000 -Dnnue.threads=8 -Dnnue.depth=4 \
    -Dnnue.out=$HOME/nnue-data
```

### 更新网络 = 换一个文件

层宽和量化尺度全写在 `.nnue` 文件头里, 端上按文件说的来。**换更宽的网络、换训练更久的
权重, 都不用改一行代码**:

```bash
python3 nnue/train.py  --data ~/nnue-data --epochs 30 --l1 256 --out net.pt
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

只认实战 Elo, 不认 loss:

```bash
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

单元测试 **163 个用例**全部通过。

> NNUE 的跨语言一致性做过变异验证: 故意把视角对调、或让输出层少除一个 QA, 两种错都会
> 被黄金向量测试抓到 —— 一个从没失败过的测试不能证明它有用。

---

## 📝 已知限制

1. **仅 arm64-v8a**: NNUE 求值核心与 ONNX Runtime 都只编了这一个 ABI。x86_64 模拟器上
   native 库加载失败, 会自动退回 Kotlin 求值 (慢 35 倍, 但能跑)。
2. **AI 棋力仍在爬坡**: 移除 Pikafish 后对手只有自研引擎。第 0 代网络在固定深度下与手写
   评估**打平** (Elo +6 ± 31, 不显著), 还需要更多自对弈数据才能真正拉开。进展见
   [`nnue/RESULTS.md`](nnue/RESULTS.md)。
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

本项目代码采用 **MIT License**。

对弈 AI (搜索、评估网络、训练流水线) 全部自研, 训练数据来自本项目引擎的自对弈,
不含任何 GPL 代码或权重。早期版本曾内置 Pikafish (GPL-3), 已在 v1.0.0 移除。

---

## 🙏 致谢

- **[Stockfish](https://stockfishchess.org/)** - NNUE 技术与量化方案的思路来源
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

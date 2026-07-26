# 棋眼 QiYan · 中国象棋 Android App

> 一个完整的中国象棋 Android 应用, 集成 **Pikafish NNUE** 引擎 (JNI 进程内加载), 最高棋力 ≥3300 Elo, 可轻松击败最顶级人类棋手。
>
> **拍一张棋盘照片, 它就能看懂局面并告诉你这一手该怎么走。**

<sub>「棋眼」既指一个局面的要害点 —— 正是支招要找的东西; 也指这个 App 用摄像头把棋盘看懂的能力。</sub>

![Build Status](https://img.shields.io/badge/build-passing-brightgreen)
![Min SDK](https://img.shields.io/badge/min%20SDK-26-blue)
![Target SDK](https://img.shields.io/badge/target%20SDK-34-blue)
![Kotlin](https://img.shields.io/badge/Kotlin-1.9.22-purple)

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
- [引擎部署](#-引擎部署)
- [已知限制](#-已知限制)
- [许可与致谢](#-许可与致谢)

---

## ✨ 功能特性

### 🎯 10 个难度等级
| 等级 | 名称 | 引擎 | 估算 Elo | 段位建议 |
|------|------|------|----------|----------|
| 1 | 入门 | 内置 Java 引擎 | 600 | 初学者 |
| 2 | 初级 | 内置 Java 引擎 | 900 | 入门棋友 |
| 3 | 中级 | 内置 Java 引擎 | 1200 | 业余初段 |
| 4 | 高级 | 内置 Java 引擎 | 1500 | 业余三段 |
| 5 | 大师 | 内置 Java 引擎 | 1700 | 业余五段 |
| 6 | 特级大师 | Pikafish NNUE (JNI) | 1900 | 业余七段 |
| 7 | 冠军 | Pikafish NNUE (JNI) | 2200 | 地方大师 |
| 8 | 精英 | Pikafish NNUE (JNI) | 2500 | 国家大师 |
| 9 | 传说 | Pikafish NNUE (JNI) | 2900 | 特级大师 |
| 10 | **超凡入圣** | Pikafish NNUE (JNI) | 3300+ | **可轻松击败最顶级人类棋手** |

> 第 6 级起自动启用 Pikafish JNI 引擎。引擎通过 `System.loadLibrary("pikafish")` 在进程内加载, 不依赖子进程。如因任何原因启动失败, 自动降级到内置引擎。

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
拍一张棋盘照片(或从相册选图), 自动识别出局面, 确认后让 Pikafish 支招。
支持斜角拍摄 —— 先检测棋盘四角做透视校正, 再识别棋子。

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
│   │   │   │   ├── PikafishNative.kt    # JNI 桥 (11 个 external fun)
│   │   │   │   ├── PikafishNativeEngine.kt  # ChessEngine 实现 (JNI 包装)
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
│   │   │   ├── engine/pikafish.nnue    # NNUE 网络 (52 MB)
│   │   │   └── book/                    # 开局库
│   │   ├── jniLibs/arm64-v8a/
│   │   │   │   └── libpikafish.so          # Pikafish JNI 共享库 (2.3 MB)
│   │   └── AndroidManifest.xml
│   ├── src/test/                        # 单元测试
│   └── build.gradle.kts
├── gradle/                              # Gradle Wrapper
├── ndk-build/
│   ├── NDK_BUILD.md                     # 引擎编译指南
│   └── pikafish_jni.cpp                 # JNI Wrapper 源码 (~250 行 C++)
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
- **JNI 进程内引擎**: Pikafish 通过 `System.loadLibrary` 加载, C++ JNI wrapper 直调 Stockfish API
- **降级容错**: Pikafish 启动失败 → 自动降级到 SimpleEngine

---

## 🤖 AI 引擎实现

### 引擎分层策略

`EngineManager` 根据难度自动选择引擎:

```kotlin
class EngineManager(context: Context) {
    fun start(listener: EngineListener?): Boolean {
        if (difficultyLevel >= 6) {
            // 尝试 Pikafish JNI — 引擎在进程内, 不依赖子进程
            val nnue = PikafishNativeInstaller.ensureNnueInstalled(context)
            if (nnue != null) {
                val engine = PikafishNativeEngine(nnue, listener)
                if (engine.start()) { currentEngine = engine; return true }
            }
        }
        // 降级到 SimpleEngine
        ...
    }
}
```

### 1. Pikafish JNI 集成 (难度 6-10)

**Pikafish** 是基于 Stockfish 的中国象棋引擎分支, 使用 NNUE 神经网络评估, 棋力 ≥3300 Elo。

**集成方式**: JNI 进程内加载。`libpikafish.so` 是真正的共享库 (`ET_DYN`), 由 `System.loadLibrary("pikafish")` 加载到 App 进程, ~250 行的 C++ JNI wrapper 将 Kotlin 调用直接映射到 Stockfish C++ API。

```
Kotlin (UI 线程)              JNI Wrapper (C++)             Pikafish Engine (C++)
     │                              │                              │
     ├─ initGlobal(nnuePath) ──────>├─ Engine(path) ──────────────>│  构造引擎 + 加载 NNUE
     │                              │  set_option(EvalFile, ...)    │
     │                              │  set_on_bestmove(cb)          │
     │<─ return 0 ──────────────────┤                              │
     │                              │                              │
     ├─ setposition(fen, moves) ───>├─ Engine::set_position(...) ─>│  设置局面
     │                              │                              │
     ├─ go(movetime, depth, ...) ──>├─ Engine::go(limits) ────────>│  异步启动搜索
     │                              │  (搜索在线程池中运行)           │
     ├─ waitForSearch() ───────────>├─ Engine::wait... ───────────>│  阻塞等待
     │                              │  (bestmove 回调触发)          │
     ├─ getBestmove(buf) ──────────>├─ copy g_bestmove ───────────>│
     │<─ "b2e2" ────────────────────┤                              │
```

**为什么不用子进程**: Android 10+ SELinux 禁止从 `app_data_file` 上下文 exec。曾试图走 `nativeLibraryDir` exec 路线, 但上游 Pikafish Makefile 对 `COMP=ndk` 产出的是 `ET_EXEC` (不可被 nativeloader 接受)。JNI 进程内加载绕开了整个 SELinux exec 限制。

**关键文件**: `engine/PikafishNative.kt` (11 个 `external fun`), `engine/PikafishNativeEngine.kt` (ChessEngine 实现), `ndk-build/pikafish_jni.cpp` (~250 行 C++ JNI wrapper).

**构建方式**: 将 `pikafish_jni.cpp` 放入 Pikafish 源码目录, `EXTRACXXFLAGS=-fPIC make build ARCH=armv8 COMP=ndk`, 产出的 `libpikafish.so` 放入 `app/src/main/jniLibs/arm64-v8a/`. 详见 `ndk-build/NDK_BUILD.md`.
│   │   │   │   └── libpikafish.so          # Pikafish JNI 共享库 (2.3 MB)

### 2. 内置 SimpleEngine (难度 1-5)

**位置**: `engine/SimpleEngine.kt`

**算法**: Alpha-Beta + 迭代加深, 子力价值 + 位置分评估, MVV-LVA 走法排序, Zobrist 键置换表. 强度上限 ~1700 Elo (业余 5-7 段).

### 3. 引擎参数映射

| 难度 | 引擎 | 线程数 | 哈希(MB) | 思考时间 |
|------|------|--------|----------|----------|
| 1-5  | SimpleEngine | 1 | 16 | 0.5-2s |
| 6    | Pikafish JNI | 1-2 | 32-64 | 2s |
| 7    | Pikafish JNI | 2 | 64 | 3s |
| 8    | Pikafish JNI | 2 | 128 | 4s |
| 9    | Pikafish JNI | 4 | 256 | 6s |
| 10   | Pikafish JNI | 4 | 512 | 8s |

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

| 玩家对引擎 (引擎 Elo) | 期望胜率 (玩家 1500 分) |
|----------------------|------------------------|
| 入门 (600) | ~98% |
| 高级 (1500) | ~50% |
| 特级大师 (1900) | ~9% |
| 冠军 (2200) | ~2% |
| 传说 (2900) | ~0.01% |
| 超凡入圣 (3300+) | ~0% |

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
- `pikafish-{arch}`: 二进制 (从 assets 提取)
- `pikafish.nnue`: NNUE 网络文件

---

## 🔨 编译与运行

### 前置要求
- JDK 17 (`/usr/lib/jvm/java-17-openjdk-amd64`)
- Android SDK 34
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

**APK 大小**: ~62 MB (包含 52 MB NNUE 网络 + 2.3 MB Pikafish .so)

### 安装

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Android Studio

直接用 Android Studio (Hedgehog+) 打开根目录的 `build.gradle.kts`。

---

## 🚀 引擎部署

> ⚠️ **当前版本已内置 Pikafish 引擎** — 无需额外部署。`libpikafish.so` (2.3 MB) 和 `pikafish.nnue` (52 MB) 已直接打包在 APK 中。

### 当前引擎状态

| 组件 | 路径 | 大小 |
|------|------|------|
| Pikafish 共享库 | `app/src/main/jniLibs/arm64-v8a/libpikafish.so` | ~2.3 MB |
│   │   │   │   └── libpikafish.so          # Pikafish JNI 共享库 (2.3 MB)
| NNUE 网络文件 | `app/src/main/assets/engine/pikafish.nnue` | ~52 MB |
| JNI Wrapper 源码 | `ndk-build/pikafish_jni.cpp` | ~250 行 C++ |

### 更新/重建 Pikafish

如果上游 Pikafish 有重大更新, 需要重建引擎:

1. 克隆最新 Pikafish 源码并放入 JNI wrapper
2. `make net` 拉取匹配的 NNUE 网络
3. 交叉编译 `libpikafish.so`
4. 替换 `jniLibs/` 和 `assets/engine/` 中的文件

详细步骤见 `ndk-build/NDK_BUILD.md`.

### 降级容错

APK 仍然包含 SimpleEngine 作为后备。如果 `System.loadLibrary("pikafish")` 或 `initGlobal()` 失败, App 会自动降级到内置引擎 (上限 ~1700 Elo), 不会崩溃。

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

### 运行测试

```bash
./gradlew test
```

所有测试已验证通过 (54 个用例), 包括新增的将军检测回归测试 (炮架/马腿/白脸将)。

---

## 📝 已知限制

1. **x86_64 模拟器不支持**: Pikafish `.so` 仅编译了 `arm64-v8a`, 模拟器上自动降级到 SimpleEngine
2. **APK 体积较大**: 包含 ~52 MB NNUE 网络文件, 完整 APK 约 60+ MB
3. **内置简易引擎强度有限**: 业余 5-7 段水平 (~1700 Elo), 适合低难度或 Pikafish 降级场景
4. **复盘界面**: 走法列表以数字格式显示, 未做完整中文棋谱格式化
5. **多语言**: 当前仅简体中文
6. **残局库**: 未集成 Syzygy 残局表

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

Pikafish 引擎本身采用 **GPL v3**。

---

## 🙏 致谢

- **[Pikafish](https://github.com/official-pikafish/Pikafish)** - 中国象棋引擎
- **[Stockfish](https://stockfishchess.org/)** - 引擎框架与 NNUE 技术
- **[Material Components](https://material.io/)** - UI 组件
- **[Glicko-2](http://www.glicko.net/glicko/glicko2.pdf)** - 评分算法
- **[AndroidX](https://developer.android.com/jetpack/androidx)** - 应用框架

---

## 📞 联系

如有问题或建议,欢迎提交 Issue 或 PR。

**项目地址**: `/home/zhangfy/zhongguoxiangqi/`
**最后构建**: BUILD SUCCESSFUL
**APK 输出**: `app/build/outputs/apk/debug/app-debug.apk` (~6.8 MB)
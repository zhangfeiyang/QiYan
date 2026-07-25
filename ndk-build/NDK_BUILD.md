# Pikafish NDK 编译指南

本文档介绍如何为 **中国象棋 Android App** 编译 Pikafish 引擎的 Android 版本.

## 什么是 Pikafish

Pikafish 是基于 Stockfish 的中国象棋引擎分支,由 Stockfish 团队官方维护.
- NNUE 神经网络驱动(与传统 alpha-beta 引擎相比,棋力大幅提升)
- 世界冠军级别,轻松击败最顶级人类棋手
- 兼容 UCI 协议,易于集成
- 完全开源(GPL v3)

官方仓库: https://github.com/official-pikafish/Pikafish

## 编译步骤

### 1. 环境准备

- Linux / macOS (推荐 Ubuntu 22.04+)
- Android NDK r25c+ ([下载地址](https://developer.android.com/ndk/downloads))
- CMake 3.22+
- Git, Python 3

```bash
# Ubuntu
sudo apt install -y git python3 cmake build-essential

# 下载 NDK
wget https://dl.google.com/android/repository/android-ndk-r25c-linux.zip
unzip android-ndk-r25c-linux.zip
export ANDROID_NDK_HOME=$PWD/android-ndk-r25c
```

### 2. 克隆 Pikafish 源码

```bash
git clone --depth=1 https://github.com/official-pikafish/Pikafish.git
cd Pikafish/src
```

### 3. 编译 arm64-v8a (推荐 ABI)

```bash
# 设置交叉编译
export PATH=$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH

# 编译 (使用 Pikafish 提供的 Makefile,需要修改)
# 或者使用 src/Makefile 中的 android 目标
cd ../src
make -j4 build ARCH=x86-64-avx2 COMP=clang \
    CXX=clang++ \
    CXXFLAGS="-O3 -static -fPIC" \
    LDFLAGS="-static"

# 提示: 实际 Pikafish Android 编译需要使用 NDK 工具链
# 推荐使用 docker 镜像或第三方交叉编译脚本
```

### 4. 简化方案: 使用预编译二进制

如果你不想自己编译,可以使用社区维护的预编译版本:

1. 从 https://github.com/official-pikafish/Pikafish/releases 下载
2. 或者从其他象棋 App 项目(如 PikaChess,天天象棋开源版)获取

### 5. 准备引擎资产

将编译/下载的 Pikafish 可执行文件和 NNUE 网络文件放置到项目的正确位置:

```
app/src/main/assets/engine/
├── pikafish-arm64-v8a        # ARM64 设备 (大部分现代 Android 手机)
├── pikafish-armeabi-v7a      # ARMv7 设备 (老设备)
├── pikafish-x86_64           # x86_64 (模拟器)
└── pikafish.nnue             # NNUE 网络文件 (约 40MB)
```

NNUE 网络文件可以从以下地址下载:
- https://github.com/official-pikafish/Pikafish/tree/main/src

### 6. 设置可执行权限

Pikafish 二进制在 Android 上需要执行权限.
App 首次启动时会自动从 assets 复制到内部存储并设置权限(见 `PikafishEngine.kt`).

## 故障排除

### 引擎启动失败 (Permission denied)

确认二进制可执行权限:
```bash
adb shell run-as com.zhongguoxiangqi ls -la files/engine/
# 应显示 -rwxr-xr-x
```

### 引擎崩溃 (Native crash)

1. 检查 NNUE 文件是否完整 (~40MB)
2. 验证 ABI 兼容性 (arm64-v8a 是主流)
3. 查看 logcat:
   ```bash
   adb logcat | grep -i pikafish
   ```

### 引擎过慢

1. 在设置中增加线程数(Threads) 和 哈希表(Hash) 大小
2. 高端设备(8 核)可设置 Threads=4, Hash=512

## 替代引擎

如果 Pikafish 不可用,App 会自动降级到内置的 **SimpleEngine**:
- 1-5 级难度: Java/Kotlin 实现(业余 5-7 段水平)
- 6-10 级难度: 需要 Pikafish 才能达到

## 进阶: 自定义引擎

可以替换为其他 UCI 兼容的中国象棋引擎:
- **ELEEYE**: 早期开源中国象棋引擎(GPL)
- **Fairy-Stockfish**: 国际象棋,但有中国象棋变体支持
- **CcBrain**: 商业级神经网络引擎

只需保证:
1. 二进制名称符合 `pikafish-{abi}` 格式(或修改 `PikafishEngine.kt`)
2. NNUE 文件在相同位置
3. 支持 UCI 协议
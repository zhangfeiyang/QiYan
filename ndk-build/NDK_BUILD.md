# NDK 构建指南 (历史)

**当前不再使用 NDK。** 项目所有难度都走内置 SimpleEngine, 不需要外部引擎.

## 背景

最初计划集成 Pikafish (基于 Stockfish 的中国象棋引擎) 作为高难度对手. 试过两条路线都失败了:

1. **JNI 路线** — 把 Pikafish 编成 `libpikafish.so` 通过 `System.loadLibrary` 加载. 需要私有 JNI bridge 源码 (`pikafish_android.cpp` 等), 仓库里没有, 上游 Pikafish 也没有, 无法重建.
2. **子进程 + UCI 路线** — 把 Pikafish 编成 ELF 可执行文件, 通过 `ProcessBuilder` 启动. Android 10+ 的 SELinux 阻止 `app_data_file` 上下文 exec (即便 chmod +x 也无效), 必须把 .so 放在 `jniLibs/` 让系统 extract 到 `nativeLibraryDir` 才能 exec. 但 Pikafish `Makefile` 给 `COMP=ndk` 不加 `-fPIE -pie`, 编出来是 `ET_EXEC` 而非 `ET_DYN`, nativeloader 拒绝加载.

## 如果以后想重新引入 Pikafish

可走的几条路, 按推荐度排序:

- **Fairy-Stockfish-NNUE** (网络内嵌, 无 `.nnue` 资产, GPL-3.0) — NDK 编译即可, 没有架构哈希匹配问题. 棋力比 Pikafish 弱 ~200–400 Elo.
- **Pikafish-HCE** / **pikafish-free** (去掉 NNUE 的 Pikafish fork) — 不需要 .nnue, NDK 编译即可.
- **Pikafish 原始分支** — 必须修改上游 Makefile 给 `COMP=ndk` 加 `-fPIE -pie`, 配 `official-pikafish/Networks` `master-net` 一起 build. 参考 `walekr12/PikafishAndroidShell/.github/workflows/android-apk.yml` (但它的产物是给 subprocess exec 用的, 不能直接被 nativeloader 接受).

无论选哪条, 集成时引擎部分都得是 `ChessEngine` 接口实现, 与 SimpleEngine 平行注册到 `EngineManager`.
package com.zhongguoxiangqi.ui.settings

import android.content.Context

/**
 * 应用设置的读取入口.
 *
 * [SettingsActivity] 负责写, 其余界面通过这里读 —— 否则设置项只是存进
 * SharedPreferences 就没人用了(开关拨了也不起作用).
 */
class AppSettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val appContext = context.applicationContext

    /**
     * 线程数/置换表.
     *
     * 这两项是**上限**: EngineManager.applyUserLimits 用 coerceAtMost 把难度
     * 要的参数往下夹. 所以默认值定低了会悄悄把高难度废掉 —— 之前写死 2 线程
     * 64MB, 而难度梯子在 Lv.9 以上要的是 4 线程 128~256MB, 等于顶档棋力从来
     * 没兑现过, 界面上还照样写着"传奇". 默认值必须至少够得着梯子的顶.
     */
    val threads: Int get() = prefs.getInt(SettingsActivity.KEY_THREADS, defaultThreads()).coerceIn(1, 8)
    val hashMb: Int get() = prefs.getInt(SettingsActivity.KEY_HASH, defaultHashMb()).coerceIn(16, 1024)

    /** 一半核心, 夹在 2..4 —— 就是设置页那句"不超过设备核心数的一半" */
    private fun defaultThreads(): Int =
        (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)

    /**
     * 置换表默认值按整机内存分档.
     *
     * 这块是 native 分配, 不占 Java 堆, 但也不能不管设备就要 256MB ——
     * NNUE 本身已经吃掉 50MB 上下.
     */
    private fun defaultHashMb(): Int {
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            ?: return 128
        val info = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val totalGb = info.totalMem / (1024.0 * 1024 * 1024)
        return when {
            totalGb >= 6 -> 256
            totalGb >= 3 -> 128
            else -> 64
        }
    }
    val soundEnabled: Boolean get() = prefs.getBoolean(SettingsActivity.KEY_SOUND, true)
    val vibrateEnabled: Boolean get() = prefs.getBoolean(SettingsActivity.KEY_VIBRATE, true)
    val showCoordinates: Boolean get() = prefs.getBoolean(SettingsActivity.KEY_SHOW_COORDS, true)
    val showWinRate: Boolean get() = prefs.getBoolean(SettingsActivity.KEY_SHOW_WINRATE, true)
    val animationEnabled: Boolean get() = prefs.getBoolean(SettingsActivity.KEY_ANIMATION, true)
    val boardFlipped: Boolean get() = prefs.getBoolean(SettingsActivity.KEY_FLIPPED, false)

    /**
     * 吃子的战场厮杀特效(刀光/火花/溅墨).
     *
     * 默认开 —— 这是用户明确要的戏份; 但连吃几子时确实闹, 得给人关的余地.
     * 关掉后棋盘仍会弹"吃车"文字标签: 那是信息, 不属于特效.
     */
    val captureFxEnabled: Boolean get() = prefs.getBoolean(SettingsActivity.KEY_CAPTURE_FX, true)

    /** 背景音乐. 默认开 —— 氛围是这次特意加的, 但随时能关 */
    val bgmEnabled: Boolean get() = prefs.getBoolean(SettingsActivity.KEY_BGM, true)

    /** Music stays behind the board feedback; users can adjust or mute it independently. */
    val bgmVolume: Int get() = prefs.getInt(SettingsActivity.KEY_BGM_VOLUME, 30).coerceIn(0, 100)

    /**
     * 主题模式: 0=跟随系统 1=深色 2=浅色.
     *
     * 默认 2 而不是 0 —— 这个 App 的配色是照着浅色暖木设计的 (棋盘木色、
     * 红黑双方、胜率条的对比度都按米白底调过), 深色是备选. 跟随系统会让
     * 一部分人第一次打开时看到的是备选方案.
     */
    val themeMode: Int get() = prefs.getInt(SettingsActivity.KEY_THEME, 2)

    /**
     * 棋子字符集: 0=跟随界面语言 1=汉字 2=拉丁字母.
     *
     * 默认跟随语言而不是写死汉字: 把界面翻成英文却在盘上摆七种汉字, 等于
     * 只翻译了外壳 —— 用户读得懂"Hint"却认不出被提示的那枚子是什么。
     */
    val pieceSetMode: Int get() = prefs.getInt(SettingsActivity.KEY_PIECE_SET, 0)

    /** 棋盘材质, 见 [com.zhongguoxiangqi.ui.board.BoardSkin]. 默认黄花梨. */
    val boardSkin: Int get() = prefs.getInt(SettingsActivity.KEY_BOARD_SKIN, 0)

    /**
     * 记谱法: 0=跟随界面语言 1=中文记谱 2=WXF.
     *
     * 同上. "炮二平五"对不读汉字的人不是"另一种写法", 而是没有信息。
     */
    val notationMode: Int get() = prefs.getInt(SettingsActivity.KEY_NOTATION, 0)

    companion object {
        const val PREFS_NAME = "settings"
    }
}

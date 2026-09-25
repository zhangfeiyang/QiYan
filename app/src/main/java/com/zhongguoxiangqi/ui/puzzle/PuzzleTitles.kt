package com.zhongguoxiangqi.ui.puzzle

/**
 * 题名在卡片上的写法.
 *
 * 题名形如"一步杀·闷宫" / "Mate in 1 · Smothered". 残局研习的卡片里, 卷名
 * 标题和卡片底部的"红先 · 一步杀"已经把步数说了两遍, 标题再以"Mate in 1 ·"
 * 开头, 半宽卡片里就只剩"Mate in 1 · Smother…" —— 真正区分各题的题眼被
 * 省略号吃掉了. 卡片上只写题眼.
 */
internal object PuzzleTitles {
    fun theme(name: String): String {
        val cut = name.indexOf('·')
        if (cut < 0) return name
        return name.substring(cut + 1).trim().ifEmpty { name }
    }
}

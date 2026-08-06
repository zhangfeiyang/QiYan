package com.zhongguoxiangqi.game

/**
 * 中文着法语音解析.
 *
 * 把用户说出的着法(如 "车九进四"、"前炮平五"、"马8退7")转成具体走法.
 *
 * 策略: 不自己重写一套数字/进退解析, 而是复用 [Move.toChineseString] 作为
 * 标准答案 —— 对当前局面生成全部合法走法, 逐一转成中文记谱, 与语音文本
 * 归一化后做精确匹配. 这样"同列多子加前后缀""红汉黑数""马象进退记列"
 * 这些规则只有一份实现, 不会出现两边各写一套然后对不上.
 */
object VoiceCommands {

    /**
     * 解析语音文本, 返回所有匹配的合法走法(通常 0 或 1 个).
     *
     * 调用方应保持当前局面不变: 匹配用的是"走这一步之前"的棋盘.
     */
    fun parse(position: Position, raw: String): List<Move> {
        val spoken = normalize(raw) ?: return emptyList()
        val moves = ArrayList<Move>(64)
        MoveGenerator.generateAll(position, moves)
        return moves.filter { normalize(Move.toChineseString(it, position)) == spoken }
    }

    /**
     * 归一化语音文本:
     * - 去掉所有空白(含全角空格)
     * - 全角数字转半角
     * - 异体字统一(傌/砲/俥/帥/將 等)
     * - 去掉结尾的口头后缀: "吃子/吃X"、"将军/绝杀/将死/将"
     *
     * 无法形成着法文本时返回 null.
     */
    fun normalize(raw: String): String? {
        var t = raw.trim()
        if (t.isEmpty()) return null
        t = t.replace(Regex("[\\s　]+"), "")
        t = t.replace('１', '1').replace('２', '2').replace('３', '3')
            .replace('４', '4').replace('５', '5').replace('６', '6')
            .replace('７', '7').replace('８', '8').replace('９', '9').replace('０', '0')
        t = t.replace('傌', '马').replace('砲', '炮').replace('俥', '车')
            .replace('帥', '帅').replace('將', '将').replace('進', '进')
        // "车九进四吃马" -> "车九进四"; "车九进四将军" -> "车九进四"
        t = t.replace(Regex("吃[将士象相仕帅马炮车兵卒]?$"), "")
        t = t.replace(Regex("(将军|绝杀|将死|将)$"), "")
        return t.ifEmpty { null }
    }
}

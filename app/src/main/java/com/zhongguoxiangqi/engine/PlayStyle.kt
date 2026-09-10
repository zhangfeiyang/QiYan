package com.zhongguoxiangqi.engine

import androidx.annotation.StringRes
import com.zhongguoxiangqi.R

/**
 * 棋手风格.
 *
 * 难度决定引擎"想多久"(棋力), 风格决定"想好了之后挑哪步"(棋路)。
 * 引擎搜索时按 [multiPv] 展开几条候选线, 本枚举的 [pick] 在与最佳着法
 * 相差 [SELECTION_MARGIN_CP] 以内的候选里按各风格的口味挑一步:
 *
 *   均衡 —— 永远走引擎最佳, 就是纯粹的最强着法
 *   攻杀 —— 优先吃子/将军/弃子强攻, 愿意用小分差换进攻机会
 *   稳健 —— 拒绝亏子力的冒险(SEE<0 的弃子), 偏好平静的展开
 *   灵动 —— 在不亏分的候选里随机挑, 同一个对手每盘下得都不一样
 *
 * 风格只在候选分数接近时起作用(≤40cp): 便宜不会大到把赢棋下输,
 * 但足以让"攻杀型"真的更爱弃子强攻、"稳健型"真的更少冒险。
 */
enum class PlayStyle(
    val id: Int,
    @StringRes val nameRes: Int,
    @StringRes val descRes: Int,
    /** 引擎走子时展开的候选线数; 1 = 不需要候选, 直接走最佳 */
    val multiPv: Int,
) {
    BALANCED(0, R.string.style_balanced, R.string.style_balanced_desc, 1),
    AGGRESSIVE(1, R.string.style_aggressive, R.string.style_aggressive_desc, 4),
    SOLID(2, R.string.style_solid, R.string.style_solid_desc, 4),
    SWARM(3, R.string.style_swarm, R.string.style_swarm_desc, 5);

    /** 引擎一条 PV 线的候选着法: 首着 UCI + 引擎对它的评分(走子方视角) */
    data class Candidate(
        val moveUci: String,
        val scoreCp: Int,
        val scoreMate: Int,
    )

    /**
     * 着法特征, 由调用方在真实局面上算好后传入 —— 本文件保持纯逻辑可单测.
     *
     * @param captureGain 兵力交换净收益(See.evaluate, 非吃子为 0)
     * @param givesCheck 走完这步是否将军对方
     */
    data class Features(
        val isCapture: Boolean = false,
        val captureGain: Int = 0,
        val givesCheck: Boolean = false,
    )

    /**
     * 按风格从候选中挑一步.
     *
     * @return 选中的候选; 候选为空时返回 null, 调用方应退回引擎 bestmove
     */
    fun pick(candidates: List<Candidate>, featuresOf: (Candidate) -> Features): Candidate? {
        val best = candidates.firstOrNull() ?: return null
        if (this == BALANCED || candidates.size == 1) return best

        // 统一可比的"强度分": 杀棋步数折算成远离 0 的大数, 与 cp 同一量纲
        fun strength(c: Candidate): Int = when {
            c.scoreMate > 0 -> MATE_STRENGTH - c.scoreMate
            c.scoreMate < 0 -> -MATE_STRENGTH - c.scoreMate
            else -> c.scoreCp
        }

        // 已有成杀路线就不再谈风格: 任何风格的"下一步赢"都比"风格化的一步"强
        if (best.scoreMate > 0) return best

        val bestStrength = strength(best)
        val playable = candidates.filter { bestStrength - strength(it) <= SELECTION_MARGIN_CP }
        if (playable.size <= 1) return best

        return when (this) {
            AGGRESSIVE -> playable.maxWithOrNull(
                compareBy(
                    // 主口味: 兑子收益(兵/车不是一回事), 其次将军, 再其次纯吃子
                    { (featuresOf(it).captureGain.takeIf { g -> g > 0 } ?: 0) / CAPTURE_TASTE_UNIT },
                    { if (featuresOf(it).givesCheck) 1 else 0 },
                    { if (featuresOf(it).isCapture) 1 else 0 },
                    // 同口味时引擎评分高者优先 —— 风格是偏好, 不是瞎子
                    { strength(it) },
                )
            ) ?: best

            SOLID -> {
                // 稳健的第一条纪律: 不做亏子力的交换(弃子强攻是攻杀型的事)
                val safe = playable.filter { featuresOf(it).captureGain >= 0 }
                    .ifEmpty { listOf(best) }
                safe.maxWithOrNull(
                    compareBy(
                        { strength(it) },
                        // 同分时偏好不吃子、不惹事的平静着法
                        { if (!featuresOf(it).isCapture) 1 else 0 },
                        { if (!featuresOf(it).givesCheck) 1 else 0 },
                    )
                ) ?: best
            }

            SWARM -> {
                // 随机归随机, 容差内的成杀路线绝不能放过 —— 那是棋力漏洞不是风格
                playable.filter { it.scoreMate > 0 }.minByOrNull { it.scoreMate }
                    ?: playable.random()
            }

            else -> best
        }
    }

    companion object {
        fun fromId(id: Int): PlayStyle = entries.firstOrNull { it.id == id } ?: BALANCED

        /** 候选与最佳着法的最大允许分差(厘兵) */
        const val SELECTION_MARGIN_CP = 40

        /** 杀棋在强度分里的基准值, 远超任何 cp 评分 */
        private const val MATE_STRENGTH = 1_000_000

        /** 攻杀口味里, 多少 cp 的兑子收益算"高一档" */
        private const val CAPTURE_TASTE_UNIT = 90
    }
}

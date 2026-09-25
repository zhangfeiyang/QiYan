package com.zhongguoxiangqi.game

import androidx.annotation.StringRes
import com.zhongguoxiangqi.R
import com.zhongguoxiangqi.engine.DifficultySettings

/**
 * 登峰棋路的八关.
 *
 * 关卡号就是对手强度。名字和一句说明只描述这一关实际会遇到的对手,
 * 不另编一套剧情。
 */
data class CampaignStage(
    val level: Int,
    @StringRes val nameRes: Int,
    @StringRes val briefRes: Int,
)

object CampaignStages {
    val all: List<CampaignStage> = listOf(
        CampaignStage(1, R.string.stage_1_name, R.string.stage_1_brief),
        CampaignStage(2, R.string.stage_2_name, R.string.stage_2_brief),
        CampaignStage(3, R.string.stage_3_name, R.string.stage_3_brief),
        CampaignStage(4, R.string.stage_4_name, R.string.stage_4_brief),
        CampaignStage(5, R.string.stage_5_name, R.string.stage_5_brief),
        CampaignStage(6, R.string.stage_6_name, R.string.stage_6_brief),
        CampaignStage(7, R.string.stage_7_name, R.string.stage_7_brief),
        CampaignStage(8, R.string.stage_8_name, R.string.stage_8_brief),
    )

    fun of(level: Int): CampaignStage {
        val safe = level.coerceIn(1, DifficultySettings.MAX_LEVEL)
        return all[safe - 1]
    }
}

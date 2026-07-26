package com.zhongguoxiangqi.ui.menu

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.zhongguoxiangqi.R
import com.zhongguoxiangqi.databinding.ActivityMainMenuBinding
import com.zhongguoxiangqi.game.Color
import com.zhongguoxiangqi.rating.UserProfile
import com.zhongguoxiangqi.rating.UserRank
import com.zhongguoxiangqi.ui.game.GameActivity
import com.zhongguoxiangqi.ui.replay.ReplayActivity
import com.zhongguoxiangqi.ui.settings.SettingsActivity

/**
 * 主菜单.
 *
 * 显示:
 * - 用户档案(评分、段位、战绩)
 * - 入口按钮(对局、复盘、设置、关于)
 */
class MainMenuActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainMenuBinding
    private lateinit var userProfile: UserProfile

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainMenuBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        userProfile = UserProfile(this)
        updateProfile()

        binding.btnPlay.setOnClickListener {
            showGameStartDialog()
        }
        binding.btnReplay.setOnClickListener {
            startActivity(Intent(this, ReplayActivity::class.java))
        }
        binding.btnRecognize.setOnClickListener {
            startActivity(Intent(this, com.zhongguoxiangqi.ui.recognize.RecognizeActivity::class.java))
        }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnAbout.setOnClickListener {
            showAbout()
        }
    }

    override fun onResume() {
        super.onResume()
        updateProfile()
    }

    private fun updateProfile() {
        binding.nickname.text = userProfile.nickname
        binding.ratingValue.text = userProfile.displayedRating.toString()
        val rank = userProfile.rank()
        binding.rankValue.text = getString(rank.nameRes)
        binding.gamesValue.text = userProfile.totalGames.toString()
        binding.winsValue.text = "胜 ${userProfile.wins}"
        binding.lossesValue.text = "负 ${userProfile.losses}"
        binding.drawsValue.text = "和 ${userProfile.draws}"
    }

    /** 显示开始游戏对话框(选难度 + 选执子) */
    private fun showGameStartDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_difficulty, null)
        val rv = dialogView.findViewById<RecyclerView>(R.id.difficulty_list)
        rv.layoutManager = LinearLayoutManager(this)

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.menu_difficulty)
            // 标题在 dialog 顶部, RecyclerView 自己负责把难度列表居中显示,
            // 不再额外加一道内部标题 —— 否则会出现两道「选择难度」
            .setView(dialogView)
            .setNegativeButton(R.string.dialog_cancel, null)
            .create()

        rv.adapter = DifficultyAdapter(DifficultyLevel.entries.toList()) { level ->
            // 选完难度先关掉本对话框,否则它会一直压在后面
            dialog.dismiss()
            showSideSelectionDialog(level)
        }
        dialog.show()
    }

    private fun showSideSelectionDialog(level: DifficultyLevel) {
        val sides = arrayOf(
            getString(R.string.action_play_red),
            getString(R.string.action_play_black)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_side)
            .setItems(sides) { _, which ->
                val side = if (which == 0) Color.RED else Color.BLACK
                startGame(level, side)
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun startGame(level: DifficultyLevel, side: Int) {
        val intent = Intent(this, GameActivity::class.java).apply {
            putExtra(GameActivity.EXTRA_DIFFICULTY, level.id)
            putExtra(GameActivity.EXTRA_PLAYER_SIDE, side)
        }
        startActivity(intent)
    }

    private fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_about)
            .setMessage(R.string.settings_about_text)
            .setPositiveButton(R.string.dialog_ok, null)
            .show()
    }
}

/**
 * 难度等级定义.
 */
enum class DifficultyLevel(
    val id: Int,
    val nameRes: Int,
    val descRes: Int,
    val elo: Int,
    val color: Int
) {
    EASY(1, R.string.difficulty_easy, R.string.difficulty_desc_easy, 600, R.color.rank_bronze),
    BEGINNER(2, R.string.difficulty_beginner, R.string.difficulty_desc_easy, 900, R.color.rank_bronze),
    INTERMEDIATE(3, R.string.difficulty_intermediate, R.string.difficulty_desc_easy, 1200, R.color.rank_silver),
    ADVANCED(4, R.string.difficulty_advanced, R.string.difficulty_desc_easy, 1500, R.color.rank_silver),
    EXPERT(5, R.string.difficulty_expert, R.string.difficulty_desc_easy, 1700, R.color.rank_gold),
    MASTER(6, R.string.difficulty_master, R.string.difficulty_desc_easy, 1900, R.color.rank_gold),
    CHAMPION(7, R.string.difficulty_champion, R.string.difficulty_desc_easy, 2200, R.color.rank_platinum),
    ELITE(8, R.string.difficulty_elite, R.string.difficulty_desc_easy, 2500, R.color.rank_diamond),
    LEGEND(9, R.string.difficulty_legend, R.string.difficulty_desc_easy, 2900, R.color.rank_master),
    IMMORTAL(10, R.string.difficulty_immortal, R.string.difficulty_desc_legend, 3300, R.color.rank_grandmaster);
}
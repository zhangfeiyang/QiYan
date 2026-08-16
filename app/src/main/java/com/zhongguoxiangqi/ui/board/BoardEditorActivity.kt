package com.zhongguoxiangqi.ui.board

import com.zhongguoxiangqi.ui.common.Insets

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.zhongguoxiangqi.R
import com.zhongguoxiangqi.databinding.ActivityBoardEditorBinding
import com.zhongguoxiangqi.game.Position

/**
 * 棋盘编辑器(高级功能).
 *
 * 允许玩家设置自定义局面(用于残局练习、研究等).
 * 由于时间限制,本版本提供基础框架.
 */
class BoardEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBoardEditorBinding
    private val position = Position()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBoardEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        Insets.apply(binding.root, top = binding.toolbar, bottom = binding.editorActions, extraBottomDp = 8f)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        // 简化版: 显示一个静态棋盘,实际编辑器需要更复杂的 UI
        binding.boardView.setPosition(position)

        binding.btnReset.setOnClickListener {
            position.setupStartPosition()
            binding.boardView.setPosition(position)
        }
        binding.btnClear.setOnClickListener {
            for (i in 0 until com.zhongguoxiangqi.game.Square.COUNT) {
                position.squares[i] = 0
            }
            binding.boardView.setPosition(position)
        }
    }
}
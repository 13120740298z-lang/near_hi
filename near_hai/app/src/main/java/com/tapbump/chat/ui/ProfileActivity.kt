package com.tapbump.chat.ui

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tapbump.chat.R
import com.tapbump.chat.TapBumpApplication
import com.tapbump.chat.databinding.ActivityProfileBinding

class ProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfileBinding
    private lateinit var prefs: com.tapbump.chat.util.PreferenceManager

    private val avatars = listOf("😊", "😎", "🤗", "🦊", "🐱", "🐶", "🐼", "🐨")
    private var selectedAvatarIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = (application as TapBumpApplication).preferenceManager

        setupToolbar()
        loadProfile()
        setupAvatarGrid()
        setupSaveButton()
    }

    private fun setupToolbar() {
        binding.toolbarProfile.setNavigationOnClickListener { finish() }
    }

    private fun loadProfile() {
        val nickname = prefs.getNickname()
        selectedAvatarIndex = prefs.getAvatarIndex()

        binding.etNickname.setText(nickname)
        binding.tvCurrentAvatar.text = getAvatarEmoji(selectedAvatarIndex)
        binding.tvDeviceId.text = "设备ID: ${prefs.getDeviceId()}"
    }

    private fun setupAvatarGrid() {
        val grid = binding.avatarGrid
        grid.removeAllViews()

        for ((index, emoji) in avatars.withIndex()) {
            val avatarView = TextView(this).apply {
                text = emoji
                textSize = 28f
                gravity = Gravity.CENTER
                setPadding(12, 12, 12, 12)
                setOnClickListener {
                    selectedAvatarIndex = index
                    binding.tvCurrentAvatar.text = emoji
                    updateAvatarSelection(grid, index)
                }
            }
            grid.addView(avatarView)

            // 默认选中
            if (index == selectedAvatarIndex) {
                updateAvatarSelection(grid, index)
            }
        }
    }

    private fun updateAvatarSelection(grid: View, selectedIndex: Int) {
        for (i in 0 until grid.childCount) {
            val child = grid.getChildAt(i)
            if (i == selectedIndex) {
                child.setBackgroundResource(R.drawable.bg_bubble_sent)
            } else {
                child.background = null
            }
        }
    }

    private fun setupSaveButton() {
        binding.btnSaveProfile.setOnClickListener {
            val nickname = binding.etNickname.text.toString().trim()
            if (nickname.isEmpty()) {
                Toast.makeText(this, "昵称不能为空", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            prefs.setNickname(nickname)
            prefs.setAvatarIndex(selectedAvatarIndex)

            Toast.makeText(this, "保存成功", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun getAvatarEmoji(index: Int): String {
        return avatars.getOrElse(index) { "😊" }
    }
}

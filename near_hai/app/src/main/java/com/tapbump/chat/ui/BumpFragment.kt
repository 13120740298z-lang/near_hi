package com.tapbump.chat.ui

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.tapbump.chat.R
import com.tapbump.chat.ble.BleDeviceInfo
import com.tapbump.chat.databinding.FragmentBumpBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class BumpFragment : Fragment() {

    private var _binding: FragmentBumpBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: MainViewModel
    private var isBumpActive = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBumpBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(requireActivity())[MainViewModel::class.java]

        setupBumpButton()
        observeData()
        updateRecentFriend()
    }

    private fun setupBumpButton() {
        binding.btnBump.setOnClickListener {
            if (!viewModel.isBluetoothEnabled()) {
                Toast.makeText(requireContext(), R.string.bluetooth_required, Toast.LENGTH_SHORT).show()
                val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                startActivityForResult(enableBtIntent, 1)
                return@setOnClickListener
            }

            if (!isBumpActive) {
                startBumpMode()
            } else {
                stopBumpMode()
            }
        }
    }

    private fun startBumpMode() {
        isBumpActive = true
        viewModel.startBumpMode()

        // 更新UI状态
        binding.btnBump.apply {
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), android.R.color.holo_green_dark)
            )
        }
        binding.tvBumpStatus.text = getString(R.string.bump_listening)
        binding.containerDiscovered.removeAllViews()

        // 监听碰撞
        lifecycleScope.launch {
            viewModel.bleProtocol.collisionDetected.collectLatest { detected ->
                if (detected) {
                    binding.tvBumpStatus.text = getString(R.string.bump_scanning)
                    binding.progressScan.visibility = View.VISIBLE

                    // 5秒倒计时
                    object : CountDownTimer(5000, 50) {
                        override fun onTick(millisUntilFinished: Long) {
                            binding.progressScan.progress =
                                ((5000 - millisUntilFinished) * 100 / 5000).toInt()
                        }

                        override fun onFinish() {
                            binding.progressScan.visibility = View.GONE
                            binding.tvBumpStatus.text = getString(R.string.bump_listening)
                            viewModel.bleProtocol.resetCollision()
                        }
                    }.start()
                }
            }
        }

        // 监听好友添加结果
        lifecycleScope.launch {
            viewModel.addFriendResult.collectLatest { result ->
                Toast.makeText(requireContext(), result, Toast.LENGTH_SHORT).show()
                updateRecentFriend()
            }
        }
    }

    private fun stopBumpMode() {
        isBumpActive = false
        viewModel.stopBumpMode()

        binding.btnBump.apply {
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), R.color.bump_button)
            )
        }
        binding.tvBumpStatus.text = getString(R.string.bump_hint)
        binding.progressScan.visibility = View.GONE
    }

    private fun observeData() {
        // 监听发现的设备
        lifecycleScope.launch {
            viewModel.discoveredDevices.collectLatest { devices ->
                if (devices.isNotEmpty()) {
                    binding.tvScanTitle.visibility = View.VISIBLE
                    binding.containerDiscovered.removeAllViews()
                    for (device in devices) {
                        addDeviceCard(device)
                    }
                }
            }
        }
    }

    private fun addDeviceCard(deviceInfo: BleDeviceInfo) {
        val card = MaterialCardView(requireContext()).apply {
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.MarginLayoutParams.MATCH_PARENT,
                ViewGroup.MarginLayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 4, 0, 4)
            }
            radius = resources.getDimension(R.dimen.card_corner_radius)
            cardElevation = 2f
            setContentPadding(16, 12, 16, 12)

            val textView = TextView(context).apply {
                text = "${deviceInfo.nickname} (信号: ${deviceInfo.rssi}dBm)"
                textSize = 14f
                setTextColor(ContextCompat.getColor(requireContext(), R.color.on_surface))
            }
            addView(textView)

            setOnClickListener {
                viewModel.connectToDevice(deviceInfo)
                Toast.makeText(
                    requireContext(),
                    "正在与 ${deviceInfo.nickname} 交换资料...",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        binding.containerDiscovered.addView(card)
    }

    private fun updateRecentFriend() {
        val friends = viewModel.friends.value
        if (friends.isNotEmpty()) {
            binding.tvRecentFriend.text = friends.first().nickname
        } else {
            binding.tvRecentFriend.text = "暂无"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (isBumpActive) {
            stopBumpMode()
        }
        _binding = null
    }
}

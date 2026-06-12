package com.tapbump.chat.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tapbump.chat.R
import com.tapbump.chat.data.Friend
import com.tapbump.chat.databinding.FragmentFriendsBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class FriendsFragment : Fragment() {

    private var _binding: FragmentFriendsBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: MainViewModel
    private lateinit var adapter: FriendAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFriendsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(requireActivity())[MainViewModel::class.java]

        setupRecyclerView()
        observeData()
    }

    private fun setupRecyclerView() {
        adapter = FriendAdapter { friend ->
            // 点击进入聊天
            val intent = Intent(requireContext(), ChatActivity::class.java).apply {
                putExtra("friend_device_id", friend.deviceId)
                putExtra("friend_nickname", friend.nickname)
                putExtra("friend_avatar", friend.avatarIndex)
            }
            startActivity(intent)
        }
        binding.recyclerFriends.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerFriends.adapter = adapter
    }

    private fun observeData() {
        lifecycleScope.launch {
            viewModel.friends.collectLatest { friends ->
                adapter.submitList(friends)
                if (friends.isEmpty()) {
                    binding.tvEmptyFriends.visibility = View.VISIBLE
                    binding.recyclerFriends.visibility = View.GONE
                } else {
                    binding.tvEmptyFriends.visibility = View.GONE
                    binding.recyclerFriends.visibility = View.VISIBLE
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ========== Adapter ==========

    inner class FriendAdapter(
        private val onItemClick: (Friend) -> Unit
    ) : RecyclerView.Adapter<FriendAdapter.ViewHolder>() {

        private var items: List<Friend> = emptyList()
        private val dateFormat = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())

        fun submitList(list: List<Friend>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_friend, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val friend = items[position]
            holder.bind(friend)
        }

        override fun getItemCount(): Int = items.size

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvAvatar: TextView = itemView.findViewById(R.id.tvAvatar)
            private val tvNickname: TextView = itemView.findViewById(R.id.tvNickname)
            private val tvLastMessage: TextView = itemView.findViewById(R.id.tvLastMessage)
            private val tvTime: TextView = itemView.findViewById(R.id.tvTime)

            fun bind(friend: Friend) {
                tvNickname.text = friend.nickname
                tvAvatar.text = getAvatarEmoji(friend.avatarIndex)

                val lastTime = viewModel.getLastMessageTime(friend.deviceId)
                if (lastTime > 0) {
                    tvLastMessage.text = "点击查看聊天记录"
                    tvTime.text = dateFormat.format(Date(lastTime))
                } else {
                    tvLastMessage.text = "还没有消息"
                    tvTime.text = dateFormat.format(Date(friend.addedTime))
                }

                itemView.setOnClickListener { onItemClick(friend) }

                itemView.setOnLongClickListener {
                    showDeleteDialog(friend)
                    true
                }
            }

            private fun showDeleteDialog(friend: Friend) {
                AlertDialog.Builder(itemView.context)
                    .setTitle(R.string.delete_friend)
                    .setMessage(R.string.delete_confirm)
                    .setPositiveButton("确定") { _, _ ->
                        viewModel.deleteFriend(friend)
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
    }

    private fun getAvatarEmoji(index: Int): String {
        val emojis = listOf("😊", "😎", "🤗", "🦊", "🐱", "🐶", "🐼", "🐨")
        return emojis.getOrElse(index) { "😊" }
    }
}

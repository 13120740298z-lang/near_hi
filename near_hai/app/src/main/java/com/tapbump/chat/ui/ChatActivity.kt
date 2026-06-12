package com.tapbump.chat.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tapbump.chat.R
import com.tapbump.chat.data.Message
import com.tapbump.chat.databinding.ActivityChatBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class ChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatBinding
    private lateinit var viewModel: ChatViewModel
    private lateinit var adapter: MessageAdapter

    private var friendDeviceId: String = ""
    private var friendNickname: String = ""
    private var friendAvatarIndex: Int = 0

    private val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(this)[ChatViewModel::class.java]

        friendDeviceId = intent.getStringExtra("friend_device_id") ?: ""
        friendNickname = intent.getStringExtra("friend_nickname") ?: "好友"
        friendAvatarIndex = intent.getIntExtra("friend_avatar", 0)

        setupToolbar()
        setupRecyclerView()
        setupSendButton()
        loadMessages()
    }

    private fun setupToolbar() {
        binding.toolbarChat.title = friendNickname
        binding.toolbarChat.setNavigationOnClickListener { finish() }
    }

    private fun setupRecyclerView() {
        adapter = MessageAdapter()
        binding.recyclerMessages.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.recyclerMessages.adapter = adapter
    }

    private fun setupSendButton() {
        binding.btnSend.setOnClickListener {
            val content = binding.etMessage.text.toString().trim()
            if (content.isEmpty()) return@setOnClickListener

            viewModel.sendMessage(content)
            binding.etMessage.text?.clear()
        }
    }

    private fun loadMessages() {
        viewModel.loadMessages(friendDeviceId)

        lifecycleScope.launch {
            viewModel.messages.collectLatest { messages ->
                adapter.submitList(messages)
                if (messages.isEmpty()) {
                    binding.tvEmptyChat.visibility = View.VISIBLE
                    binding.recyclerMessages.visibility = View.GONE
                } else {
                    binding.tvEmptyChat.visibility = View.GONE
                    binding.recyclerMessages.visibility = View.VISIBLE
                    binding.recyclerMessages.scrollToPosition(messages.size - 1)
                }
            }
        }

        lifecycleScope.launch {
            viewModel.sendStatus.collectLatest { status ->
                when (status) {
                    is SendStatus.Sent -> {
                        viewModel.refreshMessages()
                    }
                    is SendStatus.Error -> {
                        android.widget.Toast.makeText(
                            this@ChatActivity,
                            status.message,
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }

    // ========== MessageAdapter ==========

    inner class MessageAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        companion object {
            private const val TYPE_SENT = 0
            private const val TYPE_RECEIVED = 1
        }

        private var items: List<Message> = emptyList()

        fun submitList(list: List<Message>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int): Int {
            return if (items[position].senderId == viewModel.getMyDeviceId()) {
                TYPE_SENT
            } else {
                TYPE_RECEIVED
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return when (viewType) {
                TYPE_SENT -> {
                    val view = LayoutInflater.from(parent.context)
                        .inflate(R.layout.item_message_sent, parent, false)
                    SentViewHolder(view)
                }
                else -> {
                    val view = LayoutInflater.from(parent.context)
                        .inflate(R.layout.item_message_received, parent, false)
                    ReceivedViewHolder(view)
                }
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val message = items[position]
            when (holder) {
                is SentViewHolder -> holder.bind(message)
                is ReceivedViewHolder -> holder.bind(message)
            }
        }

        override fun getItemCount(): Int = items.size

        inner class SentViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvContent: TextView = itemView.findViewById(R.id.tvContent)
            private val tvTime: TextView = itemView.findViewById(R.id.tvTime)
            private val tvStatus: TextView = itemView.findViewById(R.id.tvStatus)

            fun bind(message: Message) {
                tvContent.text = message.content
                tvTime.text = dateFormat.format(Date(message.timestamp))
                if (message.isDelivered) {
                    tvStatus.text = "已送达"
                    tvStatus.setTextColor(
                        ContextCompat.getColor(itemView.context, R.color.delivered_green)
                    )
                } else {
                    tvStatus.text = "未送达"
                    tvStatus.setTextColor(
                        ContextCompat.getColor(itemView.context, R.color.not_delivered_red)
                    )
                }
            }
        }

        inner class ReceivedViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvContent: TextView = itemView.findViewById(R.id.tvContent)
            private val tvTime: TextView = itemView.findViewById(R.id.tvTime)

            fun bind(message: Message) {
                tvContent.text = message.content
                tvTime.text = dateFormat.format(Date(message.timestamp))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshMessages()
    }
}

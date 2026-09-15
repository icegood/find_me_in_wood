package com.icegood.findmeinwood.core.chat

data class ChatMessage(
    val id: String,
    val channelId: String,
    val senderId: String,
    val senderName: String,
    val text: String,
    val photoRef: String? = null,
    val timestamp: Long,
    val isOwn: Boolean = false,
)

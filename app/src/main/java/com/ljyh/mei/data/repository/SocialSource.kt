package com.ljyh.mei.data.repository

import com.ljyh.mei.data.model.melox.MessageContact
import com.ljyh.mei.data.model.melox.PrivateConversation
import com.ljyh.mei.data.model.melox.PrivateMessage
import com.ljyh.mei.data.model.melox.ShareResource
import com.ljyh.mei.data.session.SessionStamp

interface SocialSource {
    suspend fun privateConversations(session: SessionStamp, offset: Int = 0, limit: Int = 50): List<PrivateConversation>
    suspend fun privateMessages(session: SessionStamp, userId: Long, before: Long = -1, limit: Int = 100): List<PrivateMessage>
    suspend fun messageContacts(session: SessionStamp, pageSize: Int = 100, maximumCount: Int = 1_000): List<MessageContact>
    suspend fun sendPrivateText(session: SessionStamp, message: String, userIds: List<Long>)
    suspend fun sendPrivateResource(session: SessionStamp, resource: ShareResource, userIds: List<Long>, message: String = "")
    suspend fun shareToTimeline(session: SessionStamp, resource: ShareResource, message: String = "")
}

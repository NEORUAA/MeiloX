package com.ljyh.mei.data.model.weapi

import com.google.gson.Gson
import org.junit.Assert.assertNull
import org.junit.Test

class CommentModelTest {
    @Test
    fun replyOptionalMetadataCanBeAbsentDuringStateEquality() {
        val reply = Gson().fromJson(
            """{"commentId":1,"content":"Reply","timeStr":"Today","user":{"userId":1,"nickname":"Reader","avatarUrl":""}}""",
            FComment::class.java,
        )
        assertNull(reply.ipLocation)
        assertNull(reply.decoration)
        assertNull(reply.beReplied)
        reply.hashCode()
        org.junit.Assert.assertEquals(reply, reply.copy())
    }

    @Test
    fun allowsNullIdentityIconUrlFromNeteaseComments() {
        val avatarDetail = Gson().fromJson(
            """{"identityIconUrl":null,"identityLevel":0,"userType":0}""",
            AvatarDetail::class.java,
        )

        assertNull(avatarDetail.identityIconUrl)
        avatarDetail.hashCode()
    }
}

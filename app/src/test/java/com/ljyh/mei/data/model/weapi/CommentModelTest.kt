package com.ljyh.mei.data.model.weapi

import com.google.gson.Gson
import org.junit.Assert.assertNull
import org.junit.Test

class CommentModelTest {
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

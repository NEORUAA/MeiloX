package com.ljyh.mei.standalone

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.ljyh.mei.constants.CookieKey
import com.ljyh.mei.constants.UserAvatarUrlKey
import com.ljyh.mei.constants.UserIdKey
import com.ljyh.mei.constants.UserNicknameKey
import com.ljyh.mei.di.ApplicationContext
import com.ljyh.mei.utils.dataStore
import javax.inject.Inject
import kotlinx.coroutines.flow.first

internal class DataStoreAccountPersistence @Inject constructor(
    @ApplicationContext private val context: Context,
) : StandaloneAccountPersistence {
    override suspend fun read(): StoredAccount {
        val saved = context.dataStore.data.first()
        return StoredAccount(
            musicU = saved[CookieKey].orEmpty(),
            userId = saved[UserIdKey]?.toLongOrNull() ?: 0,
            nickname = saved[UserNicknameKey].orEmpty(),
            avatarUrl = saved[UserAvatarUrlKey],
        )
    }

    override suspend fun write(account: StoredAccount) {
        context.dataStore.edit { saved ->
            if (account.musicU.isEmpty()) {
                saved.remove(CookieKey)
                saved.remove(UserIdKey)
                saved.remove(UserNicknameKey)
                saved.remove(UserAvatarUrlKey)
            } else {
                saved[CookieKey] = account.musicU
                saved[UserIdKey] = account.userId.toString()
                saved[UserNicknameKey] = account.nickname
                if (account.avatarUrl == null) saved.remove(UserAvatarUrlKey)
                else saved[UserAvatarUrlKey] = account.avatarUrl
            }
        }
    }
}

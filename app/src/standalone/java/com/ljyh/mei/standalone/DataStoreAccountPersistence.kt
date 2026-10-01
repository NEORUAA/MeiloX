package com.ljyh.mei.standalone

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.ljyh.mei.constants.CookieKey
import com.ljyh.mei.constants.UserAvatarUrlKey
import com.ljyh.mei.constants.UserIdKey
import com.ljyh.mei.constants.UserNicknameKey
import com.ljyh.mei.constants.UserPhotoKey
import com.ljyh.mei.di.ApplicationContext
import com.ljyh.mei.utils.dataStore
import javax.inject.Inject
import kotlinx.coroutines.flow.first

internal class DataStoreAccountPersistence internal constructor(
    private val store: DataStore<Preferences>,
) : StandaloneAccountPersistence {
    @Inject constructor(@ApplicationContext context: Context) : this(context.dataStore)

    override suspend fun read(): StoredAccount {
        val saved = store.data.first()
        return StoredAccount(
            musicU = saved[CookieKey].orEmpty(),
            userId = saved[UserIdKey]?.toLongOrNull() ?: 0,
            nickname = saved[UserNicknameKey].orEmpty(),
            avatarUrl = saved[UserAvatarUrlKey],
        )
    }

    override suspend fun write(account: StoredAccount) {
        store.edit { saved ->
            val legacyPhotoOwner = longPreferencesKey("standalone_legacy_library_photo_owner_v1")
            // Freeze affinity before replacing/removing the legacy public account ID.
            if (legacyPhotoOwner !in saved) {
                saved[legacyPhotoOwner] = saved[UserIdKey]?.toLongOrNull()?.coerceAtLeast(0) ?: 0
            }
            if (account.userId > 0 && account.musicU.isNotEmpty() && saved[legacyPhotoOwner] == account.userId) {
                val photoKey = stringPreferencesKey("official_user_photo_${account.userId}")
                val legacyPhoto = saved[UserPhotoKey]
                if (photoKey !in saved && !legacyPhoto.isNullOrBlank()) saved[photoKey] = legacyPhoto
            }
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

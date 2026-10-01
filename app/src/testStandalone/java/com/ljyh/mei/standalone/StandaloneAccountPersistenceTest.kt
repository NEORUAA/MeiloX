package com.ljyh.mei.standalone

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.ljyh.mei.constants.CookieKey
import com.ljyh.mei.constants.UserAvatarUrlKey
import com.ljyh.mei.constants.UserIdKey
import com.ljyh.mei.constants.UserNicknameKey
import com.ljyh.mei.constants.UserPhotoKey
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real disk-backed preferences; all account, Cookie and URI values are synthetic. */
class StandaloneAccountPersistenceTest {
    @get:Rule val files = TemporaryFolder()

    private fun photoKey(id: Long) = stringPreferencesKey("official_user_photo_$id")
    private val selectedPhoto = "content://invalid.test/selected/old-photo"
    private val unrelated = stringPreferencesKey("fixture_unrelated_setting")

    @Test fun verifiedOriginalAccountRestoresLegacySelectionWithoutChangingOtherPreferences() = runBlocking {
        withStore { store, persistence ->
            store.edit {
                seedLegacy(it)
                it[unrelated] = "preserved"
            }
            persistence.write(StoredAccount("renewed-synthetic", 11, "Restored", "https://invalid.test/avatar"))
            val saved = store.data.first()
            assertEquals(selectedPhoto, saved[photoKey(11)])
            assertEquals(selectedPhoto, saved[UserPhotoKey])
            assertEquals("preserved", saved[unrelated])
            val account = persistence.read()
            assertEquals("renewed-synthetic", account.musicU)
            assertEquals(11L, account.userId)
            assertEquals("Restored", account.nickname)
            assertEquals("https://invalid.test/avatar", account.avatarUrl)
        }
    }

    @Test fun replacementAccountCannotInheritSelectionEvenAfterAnotherVerification() = runBlocking {
        withStore { store, persistence ->
            store.edit(::seedLegacy)
            repeat(2) { persistence.write(StoredAccount("replacement-synthetic", 22)) }
            assertNull(store.data.first()[photoKey(22)])
            persistence.write(StoredAccount("original-synthetic", 11))
            assertEquals(selectedPhoto, store.data.first()[photoKey(11)])
            persistence.write(StoredAccount("replacement-synthetic", 22))
            assertNull(store.data.first()[photoKey(22)])
        }
    }

    @Test fun existingScopedSelectionIncludingEmptyValueWinsOverLegacyPhoto() = runBlocking {
        for (current in listOf("https://invalid.test/new-photo", "")) {
            withStore { store, persistence ->
                store.edit {
                    seedLegacy(it)
                    it[photoKey(11)] = current
                }
                persistence.write(StoredAccount("verified-synthetic", 11))
                assertEquals(current, store.data.first()[photoKey(11)])
                assertEquals(selectedPhoto, store.data.first()[UserPhotoKey])
            }
        }
    }

    @Test fun logoutFreezesLegacyAffinityBeforeRemovingAccountFields() = runBlocking {
        withStore { store, persistence ->
            store.edit(::seedLegacy)
            persistence.write(StoredAccount("", 0))
            val anonymous = store.data.first()
            assertNull(anonymous[CookieKey])
            assertNull(anonymous[UserIdKey])
            assertNull(anonymous[UserNicknameKey])
            assertNull(anonymous[UserAvatarUrlKey])
            assertEquals(selectedPhoto, anonymous[UserPhotoKey])
            repeat(2) { persistence.write(StoredAccount("replacement-synthetic", 22)) }
            assertNull(store.data.first()[photoKey(22)])
            persistence.write(StoredAccount("original-synthetic", 11))
            assertEquals(selectedPhoto, store.data.first()[photoKey(11)])
        }
    }

    @Test fun missingOrInvalidLegacyIdentityNeverAcquiresTheNextAccount() = runBlocking {
        for (id in listOf(null, "", "0", "-1", "invalid")) {
            withStore { store, persistence ->
                store.edit {
                    it[UserPhotoKey] = selectedPhoto
                    if (id != null) it[UserIdKey] = id
                }
                repeat(2) { persistence.write(StoredAccount("verified-synthetic", 11)) }
                assertNull(store.data.first()[photoKey(11)])
                assertEquals(selectedPhoto, store.data.first()[UserPhotoKey])
            }
        }
    }

    @Test fun missingOrBlankLegacyPhotoDoesNotInventAScopedSelection() = runBlocking {
        for (photo in listOf(null, "", " ")) {
            withStore { store, persistence ->
                store.edit {
                    it[UserIdKey] = "11"
                    if (photo != null) it[UserPhotoKey] = photo
                }
                persistence.write(StoredAccount("verified-synthetic", 11))
                assertNull(store.data.first()[photoKey(11)])
                assertEquals(photo, store.data.first()[UserPhotoKey])
            }
        }
    }

    @Test fun frozenOwnerAndSelectionSurviveClosingAndReopeningTheSameFile() = runBlocking {
        val file = files.newFile("reopened.preferences_pb")
        withStore(file) { store, persistence ->
            store.edit(::seedLegacy)
            persistence.write(StoredAccount("replacement-synthetic", 22))
            assertNull(store.data.first()[photoKey(22)])
        }
        withStore(file) { store, persistence ->
            assertEquals(22L, persistence.read().userId)
            persistence.write(StoredAccount("replacement-synthetic", 22))
            assertNull(store.data.first()[photoKey(22)])
            persistence.write(StoredAccount("original-synthetic", 11))
            assertEquals(selectedPhoto, store.data.first()[photoKey(11)])
        }
    }

    @Test fun rejectedLoginLeavesLegacyPreferencesUntouchedUntilVerifiedPublication() = runBlocking {
        withStore { store, persistence ->
            store.edit(::seedLegacy)
            val sessions = StandaloneSessionStore(persistence).also { it.initialize() }
            val before = store.data.first()
            val rejected = StandaloneAccountController(sessions) { _, _ -> throw java.io.IOException("synthetic rejection") }
            assertFalse(rejected.login("rejected-synthetic"))
            assertEquals(before, store.data.first())
            val accepted = StandaloneAccountController(sessions) { value, _ -> StoredAccount(value, 11) }
            assertTrue(accepted.login("verified-synthetic"))
            assertEquals(selectedPhoto, store.data.first()[photoKey(11)])
            assertEquals(11L, sessions.snapshot().identity.userId)
        }
    }

    private fun seedLegacy(saved: MutablePreferences) {
        saved[CookieKey] = "legacy-synthetic"
        saved[UserIdKey] = "11"
        saved[UserNicknameKey] = "Legacy"
        saved[UserAvatarUrlKey] = "https://invalid.test/legacy-avatar"
        saved[UserPhotoKey] = selectedPhoto
    }

    private suspend fun withStore(
        file: File = files.newFile(),
        block: suspend (DataStore<Preferences>, DataStoreAccountPersistence) -> Unit,
    ) {
        val path = if (file.extension == "preferences_pb") file else File(file.parentFile, "${file.name}.preferences_pb")
        val job = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO)) { path }
        try { block(store, DataStoreAccountPersistence(store)) }
        finally { job.cancelAndJoin() }
    }
}

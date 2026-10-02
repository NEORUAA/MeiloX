package com.ljyh.mei.standalone

import com.ljyh.mei.data.session.SessionChangedException
import com.ljyh.mei.data.session.SessionIdentity
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionStore
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class StoredAccount(
    val musicU: String,
    val userId: Long,
    val nickname: String = "",
    val avatarUrl: String? = null,
) {
    override fun toString() = "StoredAccount(redacted)"
}

internal interface StandaloneAccountPersistence {
    suspend fun read(): StoredAccount
    suspend fun write(account: StoredAccount)
}

internal class StandaloneCredentials(val musicU: String) {
    override fun toString() = "StandaloneCredentials(redacted)"
}

/** The only live owner of standalone credentials; shared consumers see public stamps. */
@Singleton
class StandaloneSessionStore @Inject internal constructor(
    private val persistence: StandaloneAccountPersistence,
) : SessionStore() {
    private val mutations = Mutex()
    private val attempts = AtomicLong()
    @Volatile private var current: StoredAccount? = null

    init {
        bind {
            val account = current ?: throw IOException("Standalone session is not ready")
            SessionIdentity(account.userId, account.userId > 0 && account.musicU.isNotEmpty(), false)
        }
    }

    /** Saved IDs are not proof that a persisted Cookie still belongs to that account. */
    suspend fun initialize(): String? = mutations.withLock {
        if (current != null) return@withLock null
        beginTransition().use {
            val stored = persistence.read()
            val validCookie = isValidMusicU(stored.musicU)
            current = anonymous()
            setRecoveryRequired(validCookie)
            stored.musicU.takeIf { validCookie }
        }
    }

    internal fun credentials(owner: SessionStamp): StandaloneCredentials = withCurrent(owner) {
        StandaloneCredentials(requireNotNull(current).musicU)
    }

    internal fun requireAuthenticated(owner: SessionStamp) {
        requireCurrent(owner)
        if (!owner.identity.authenticated || owner.identity.anonymous || recoveryRequired.value) {
            throw SessionChangedException()
        }
    }

    internal class LoginAttempt(val number: Long, val owner: SessionStamp)

    internal fun beginLogin(): LoginAttempt = LoginAttempt(attempts.incrementAndGet(), snapshot())

    internal fun beginLogin(expected: SessionStamp): LoginAttempt = withCurrent(expected) {
        LoginAttempt(attempts.incrementAndGet(), expected)
    }

    internal suspend fun commitLogin(attempt: LoginAttempt, account: StoredAccount) = mutations.withLock {
        require(account.userId > 0 && isValidMusicU(account.musicU))
        currentCoroutineContext().ensureActive()
        if (attempt.number != attempts.get()) throw SessionChangedException()
        requireCurrent(attempt.owner)
        beginTransition().use {
            withContext(NonCancellable) {
                persistence.write(account)
                current = account
                setRecoveryRequired(false)
            }
        }
    }

    suspend fun logout(expected: SessionStamp = snapshot(), clearWebSession: suspend () -> Unit) = mutations.withLock {
        beginTransition(expected).use {
            attempts.incrementAndGet()
            withContext(NonCancellable) {
                persistence.write(anonymous())
                current = anonymous()
                try {
                    clearWebSession()
                    setRecoveryRequired(false)
                } catch (error: Exception) {
                    setRecoveryRequired(true)
                    throw error
                }
            }
        }
    }

    private fun anonymous() = StoredAccount("", 0)
}

internal fun isValidMusicU(value: String): Boolean = value.isNotBlank() &&
    value.all { it.code in 33..126 && it != '=' && it != ';' }

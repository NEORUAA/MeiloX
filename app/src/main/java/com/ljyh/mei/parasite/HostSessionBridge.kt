package com.ljyh.mei.parasite

import android.graphics.Bitmap
import com.ljyh.mei.data.session.SessionStore
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Official login adapter; credential-free session lifetime is shared with standalone. */
@Singleton
class HostSessionBridge @Inject constructor() : SessionStore() {
    @Volatile private var loginBackend: HostLoginBackend<Bitmap>? = null

    @Synchronized
    internal fun bindLogin(backend: HostLoginBackend<Bitmap>) {
        check(loginBackend == null) { "Official login is already bound" }
        loginBackend = backend
    }

    fun newLogin(): HostLoginController<Bitmap> = HostLoginController(
        open = { emit -> (loginBackend ?: throw IOException("Official login is not ready")).open(emit) },
        authenticated = { runCatching { snapshot().identity.authenticated }.getOrDefault(false) },
    )

    fun logout() {
        val backend = loginBackend ?: throw IOException("Official login is not ready")
        beginTransition().use { backend.logout() }
    }
}

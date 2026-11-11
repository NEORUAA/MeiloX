package com.ljyh.mei.source.platform

import android.os.Looper
import androidx.webkit.Profile
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewFeature
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.ljyh.mei.source.api.SourceAccount
import com.ljyh.mei.source.api.SourceEffectScope
import com.ljyh.mei.source.api.SourceErrorCode
import com.ljyh.mei.source.api.SourceException
import com.ljyh.mei.source.api.SourceHostEffect
import com.ljyh.mei.source.api.SourceHostEffectResult
import com.ljyh.mei.source.script.ScriptNetworkPolicy
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private val sourceWebSessionProfileName = Regex("meilox-source-session-[0-9a-f]{64}")
private val sourceWebTemporaryProfileName = Regex("meilox-source-tmp-[0-9a-f]{64}")
private var sourceWebProfilesPruned = false
private val sourceWebCleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** Delete only one-shot temporary records before the first source profile is loaded this process. */
internal fun createSourceWebProfile(name: String): Profile {
    check(Looper.myLooper() == Looper.getMainLooper())
    require(sourceWebSessionProfileName.matches(name) || sourceWebTemporaryProfileName.matches(name))
    val store = ProfileStore.getInstance()
    if (!sourceWebProfilesPruned) {
        sourceWebProfilesPruned = true
        try {
            store.allProfileNames.filter(sourceWebTemporaryProfileName::matches).forEach { previous ->
                try { store.deleteProfile(previous) }
                catch (error: Exception) {
                    // An already loaded record stays isolated and can be removed next process.
                    android.util.Log.w("SourceWebProfiles", "Profile retirement failed kind=${error.javaClass.name}")
                }
            }
        } catch (error: Exception) {
            android.util.Log.w("SourceWebProfiles", "Profile enumeration failed kind=${error.javaClass.name}")
        }
    }
    return store.getOrCreateProfile(name)
}

/** Run after the WebView and transport close. Persistent session data is intentionally retained. */
internal fun clearSourceWebProfile(profile: Profile, onCleared: (Boolean) -> Unit = {}) {
    check(Looper.myLooper() == Looper.getMainLooper())
    require(sourceWebSessionProfileName.matches(profile.name) || sourceWebTemporaryProfileName.matches(profile.name))
    val cookies = profile.cookieManager
    fun complete() {
        // Disposal cancels the page's coroutine scope; cleanup belongs to the host instead.
        sourceWebCleanupScope.launch {
            val flushed = try { cookies.flush(); true }
                catch (error: Exception) {
                    android.util.Log.w("SourceWebProfiles", "Cookie flush failed kind=${error.javaClass.name}")
                    false
                }
            withContext(Dispatchers.Main.immediate) { onCleared(flushed) }
        }
    }
    try {
        complete()
    } catch (error: Exception) {
        android.util.Log.w("SourceWebProfiles", "Profile close failed kind=${error.javaClass.name}")
        onCleared(false)
    }
}

/** Trusted ownership data supplied by the calling source screen, never by page JavaScript. */
class SourceWebOwner(
    val approvedDomains: Set<String>,
    val cookieHeaders: Map<String, String> = emptyMap(),
    val account: SourceAccount? = null,
    val sdkNamespace: String,
    val features: Map<String, Set<String>> = emptyMap(),
    val navigate: suspend (SourceWebNavigation) -> String? = { null },
    val login: suspend () -> Boolean = { false },
) {
    override fun toString(): String = "SourceWebOwner(<redacted>)"
}

class SourceWebNavigation(val feature: String, val action: String, val parameters: JsonObject) {
    override fun toString(): String = "SourceWebNavigation(<redacted>)"
}

/** One instance belongs to one screen; its composable host must be mounted by that screen. */
class SourceWebEffects {
    private val mutex = Mutex()
    private val mutablePending = MutableStateFlow<SourceWebRequest?>(null)
    val pending: StateFlow<SourceWebRequest?> = mutablePending.asStateFlow()

    internal suspend fun requestLogin(scope: SourceEffectScope): Boolean {
        scope.ensureCurrent()
        val request = mutablePending.value ?: return false
        if (!request.isActive || request.scope.executionId != scope.executionId) return false
        request.finish(JsonObject().apply { addProperty("loginRequested", true) })
        return true
    }

    suspend fun handle(
        effect: SourceHostEffect,
        scope: SourceEffectScope,
        owner: SourceWebOwner,
    ): SourceHostEffectResult = mutex.withLock {
        if (effect.type !in setOf("web.auth", "web.page")) return@withLock unsupported()
        scope.ensureCurrent()
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return@withLock unsupported()
        val recipe = SourceWebRecipe.parse(effect, owner)
        if (owner.account != null && owner.account.ref.instanceId != scope.instanceId) denied()
        val request = SourceWebRequest(recipe, scope, owner)
        try {
            withContext(Dispatchers.Main.immediate) {
                check(mutablePending.value == null)
                scope.ensureCurrent()
                mutablePending.value = request
            }
            request.result.await().also { scope.ensureCurrent() }
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                request.dispose()
                if (mutablePending.value === request) mutablePending.value = null
            }
        }
    }

    private fun unsupported() = SourceHostEffectResult.Failure(SourceErrorCode.UNSUPPORTED)
}

class SourceWebRequest internal constructor(
    internal val recipe: SourceWebRecipe,
    internal val scope: SourceEffectScope,
    internal val owner: SourceWebOwner,
) {
    internal val result = CompletableDeferred<SourceHostEffectResult>()
    private val disposed = AtomicBoolean(false)
    private var release: (() -> Unit)? = null
    internal val isActive: Boolean get() = !disposed.get() && scope.isActive && !result.isCompleted

    internal fun attach(release: () -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (!isActive) release() else this.release = release
    }

    internal fun finish(value: JsonElement) {
        if (isActive) result.complete(SourceHostEffectResult.Success(value))
    }

    internal fun fail(code: SourceErrorCode) { result.complete(SourceHostEffectResult.Failure(code)) }

    internal fun dispose() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (disposed.compareAndSet(false, true)) {
            release?.invoke()
            release = null
            result.cancel()
        }
    }

    override fun toString(): String = "SourceWebRequest(<redacted>)"
}

internal class SourceWebRecipe(
    val url: String,
    val title: String,
    val auth: Boolean,
    val domains: Set<String>,
    val pageDomains: Set<String>,
    val cookieHosts: Set<String>,
    val cookieNames: Set<String>,
    val bridgeAliases: Map<String, String>,
    val mappings: List<SourceWebUrlMapping>,
) {
    fun checkedUrl(value: String, base: String? = null): String = checkedResolvedUrl(value, base, requireGrantedHost = true)

    fun checkedRedirectUrl(value: String, base: String? = null): String =
        checkedResolvedUrl(value, base, requireGrantedHost = false)

    private fun checkedResolvedUrl(value: String, base: String?, requireGrantedHost: Boolean): String {
        if (value.length > 8192 || value.any { it == '\u0000' || it == '\r' || it == '\n' }) denied()
        val uri = runCatching { if (base == null) URI(value) else URI(base).resolve(value) }.getOrElse { denied() }
        val host = uri.host ?: denied()
        if (uri.rawUserInfo != null || requireGrantedHost && (uri.scheme != "https" || uri.port !in setOf(-1, 443)) ||
            !requireGrantedHost && uri.scheme !in setOf("http", "https")) denied()
        if (requireGrantedHost && !ScriptNetworkPolicy.isHostAllowed(host, domains)) denied()
        return uri.toASCIIString()
    }

    fun checkedPageUrl(value: String, base: String? = null): String = checkedUrl(value, base).also {
        if (!ScriptNetworkPolicy.isHostAllowed(URI(it).host, pageDomains)) denied()
    }

    fun navigation(value: String, owner: SourceWebOwner): SourceWebNavigation? {
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (uri.rawUserInfo != null || uri.port != -1 || value.length > 8192) return null
        return mappings.firstOrNull { it.matches(uri) }?.takeIf { it.action in owner.features[it.feature].orEmpty() }
            ?.let { SourceWebNavigation(it.feature, it.action, it.parameters.deepCopy()) }
    }

    override fun toString(): String = "SourceWebRecipe(<redacted>)"

    companion object {
        val verbs = setOf("device.info", "page.info", "page.title", "page.ui", "navigation.open", "navigation.close",
            "session.present", "session.profile", "session.login", "toast.show")

        fun parse(effect: SourceHostEffect, owner: SourceWebOwner): SourceWebRecipe {
            val params = effect.parameters
            val approved = owner.approvedDomains.toSet().onEach { ScriptNetworkPolicy.validateGrant(it) }
            if (approved.isEmpty() || approved.size > 64 || owner.sdkNamespace.length !in 1..128) denied()
            val requested = params.stringSet("allowedHosts", 64).ifEmpty { approved }
                .onEach { ScriptNetworkPolicy.validateGrant(it) }
            if (requested.any { !ScriptNetworkPolicy.isGrantCovered(it, approved) }) denied()
            val cookieHosts = params.stringSet("cookieHosts", 32).ifEmpty {
                setOf(runCatching { URI(params.string("url")).host }.getOrNull() ?: denied())
            }.onEach { ScriptNetworkPolicy.validateDomain(it) }
            if (cookieHosts.any { !ScriptNetworkPolicy.isHostAllowed(it, requested) }) denied()
            val names = params.stringSet("cookieNames", 32)
            if (names.any { !Regex("[A-Za-z0-9_.-]{1,80}").matches(it) } || effect.type == "web.auth" && names.isEmpty()) denied()
            val aliases = params.get("bridgeAliases")?.let { element ->
                if (!element.isJsonObject || element.asJsonObject.size() > 64) denied()
                element.asJsonObject.entrySet().associate { (name, value) ->
                    if (name.length !in 1..128 || !value.isJsonPrimitive || !value.asJsonPrimitive.isString || value.asString !in verbs) denied()
                    name to value.asString
                }
            }.orEmpty()
            val mappings = params.get("urlMappings")?.let { element ->
                if (!element.isJsonArray || element.asJsonArray.size() > 32) denied()
                element.asJsonArray.map { value -> SourceWebUrlMapping.parse(value, owner) }
            }.orEmpty()
            return SourceWebRecipe(params.string("url"), params.optionalString("title", 200).orEmpty(),
                effect.type == "web.auth", approved, requested, cookieHosts, names, aliases, mappings).also { it.checkedPageUrl(it.url) }
        }
    }
}

internal class SourceWebUrlMapping(
    private val scheme: String,
    private val host: String,
    private val pathPrefix: String,
    val feature: String,
    val action: String,
    val parameters: JsonObject,
) {
    fun matches(uri: URI): Boolean = uri.scheme == scheme && uri.host == host &&
        (uri.path.orEmpty() == pathPrefix || uri.path.orEmpty().startsWith(pathPrefix.trimEnd('/') + "/"))

    companion object {
        fun parse(value: JsonElement, owner: SourceWebOwner): SourceWebUrlMapping {
            if (!value.isJsonObject) denied()
            val p = value.asJsonObject
            val scheme = p.string("scheme")
            val host = p.string("host")
            val prefix = p.optionalString("pathPrefix", 2048).orEmpty()
            val feature = p.string("feature")
            val action = p.string("action")
            if (!Regex("[a-z][a-z0-9+.-]{1,30}").matches(scheme) || scheme in setOf("file", "content", "javascript", "intent", "data", "blob") ||
                !Regex("[a-z0-9][a-z0-9.-]{0,252}").matches(host) || prefix.isNotEmpty() && !prefix.startsWith('/') ||
                feature != "web" || action != "openPage" || action !in owner.features[feature].orEmpty()) denied()
            val parameters = p.get("parameters")?.let { if (!it.isJsonObject) denied() else it.asJsonObject.deepCopy() } ?: JsonObject()
            return SourceWebUrlMapping(scheme, host, prefix, feature, action, parameters)
        }
    }
}

internal fun JsonObject.string(name: String): String = optionalString(name, 8192)?.takeIf { it.isNotBlank() } ?: denied()
internal fun JsonObject.optionalString(name: String, max: Int): String? = get(name)?.takeUnless { it.isJsonNull }?.let {
    if (!it.isJsonPrimitive || !it.asJsonPrimitive.isString || it.asString.length > max) denied()
    it.asString
}
private fun JsonObject.stringSet(name: String, max: Int): Set<String> = get(name)?.let { value ->
    if (!value.isJsonArray || value.asJsonArray.size() > max) denied()
    value.asJsonArray.map {
        if (!it.isJsonPrimitive || !it.asJsonPrimitive.isString || it.asString.length > 253) denied()
        it.asString
    }.toSet()
}.orEmpty()
internal fun denied(): Nothing = throw SourceException(SourceErrorCode.PLUGIN, "Source web permission denied")

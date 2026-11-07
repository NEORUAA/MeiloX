package com.ljyh.mei.source.core

import com.ljyh.mei.source.api.AccountRevisionCapability
import com.ljyh.mei.source.api.MusicProvider
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf

/** Accepted writes outlive a screen, but never the source/account which accepted them. */
@Singleton
class SourceMutationCoordinator internal constructor(
    private val registry: ProviderRegistry,
    parentScope: CoroutineScope,
) {
    @Inject constructor(registry: ProviderRegistry) : this(registry, CoroutineScope(SupervisorJob() + Dispatchers.IO))

    // Deferred failures belong to their awaiting callers, including when the supplied
    // lifecycle uses a reporting supervisor for otherwise uncaught background failures.
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))

    private class Key(val provider: MusicProvider, val resource: String) {
        override fun equals(other: Any?) = other is Key && provider === other.provider && resource == other.resource
        override fun hashCode() = 31 * System.identityHashCode(provider) + resource.hashCode()
    }
    private class Entry {
        var epoch = 0L
        var readers = 0
        var pending: Deferred<Any?>? = null
        var signature: Any? = null
        var owner: SourceMutationOwner? = null
        var confirmedOwner: SourceMutationOwner? = null
        var confirmedBoolean: Boolean? = null
    }
    private val lock = Any()
    private val entries = mutableMapOf<Key, Entry>()

    init {
        scope.launch {
            registry.revision.collect {
                synchronized(lock) {
                    entries.keys.removeAll { registry.provider(it.provider.id) !== it.provider }
                }
            }
        }
    }

    internal suspend fun capture(providerId: String, expectedProvider: MusicProvider? = null,
        expectedRevision: String? = null): SourceMutationOwner {
        registry.awaitInitialized()
        val provider = registry.requireProvider(providerId)
        val revision = (provider as? AccountRevisionCapability)?.accountRevision?.first()
        if (expectedProvider != null && (provider !== expectedProvider || revision != expectedRevision) ||
            expectedProvider == null && expectedRevision != null && revision != expectedRevision) {
            throw CancellationException("Source account changed before accepting a mutation")
        }
        return SourceMutationOwner(provider, provider.version, revision).also { ensureCurrent(it) }
    }

    internal suspend fun ensureCurrent(owner: SourceMutationOwner) {
        currentCoroutineContext().ensureActive()
        if (registry.provider(owner.provider.id) !== owner.provider ||
            registry.instance(owner.provider.instanceId) !== owner.provider || owner.provider.version != owner.version ||
            (owner.provider as? AccountRevisionCapability)?.accountRevision?.first() != owner.revision) {
            throw CancellationException("Source mutation owner changed")
        }
    }

    internal suspend fun <T> mutate(owner: SourceMutationOwner, resource: String, signature: Any? = null,
        block: suspend () -> T): T {
        val caller = currentCoroutineContext().minusKey(Job).minusKey(SourceMutationContext)
        ensureCurrent(owner)
        val key = Key(owner.provider, resource)
        val task = synchronized(lock) {
            val entry = entries.getOrPut(key, ::Entry)
            val previous = entry.pending
            if (signature != null && previous != null && !previous.isCompleted && entry.signature == signature && entry.owner == owner) previous else {
                lateinit var accepted: Deferred<Any?>
                accepted = scope.async(context = caller, start = CoroutineStart.LAZY) {
                    pinned(owner, resource) {
                        previous?.join()
                        ensureCurrent(owner)
                        block().also { ensureCurrent(owner) }
                    }
                }
                entry.epoch++
                entry.pending = accepted
                entry.signature = signature
                entry.owner = owner
                accepted.invokeOnCompletion {
                    synchronized(lock) {
                        entry.epoch++
                        if (entry.pending === accepted) { entry.pending = null; entry.signature = null }
                    }
                }
                accepted.start()
                accepted
            }
        }
        @Suppress("UNCHECKED_CAST")
        return task.await() as T
    }

    internal fun confirmed(owner: SourceMutationOwner, resource: String): Boolean? = synchronized(lock) {
        entries[Key(owner.provider, resource)]?.takeIf { it.confirmedOwner == owner }?.confirmedBoolean
    }

    internal fun confirm(owner: SourceMutationOwner, resource: String, value: Boolean) = synchronized(lock) {
        entries.getOrPut(Key(owner.provider, resource), ::Entry).apply {
            confirmedOwner = owner
            confirmedBoolean = value
        }
        Unit
    }

    internal fun invalidate(owner: SourceMutationOwner, matches: (String) -> Boolean) = synchronized(lock) {
        entries.forEach { (key, value) ->
            if (key.provider === owner.provider && matches(key.resource)) {
                if (value.confirmedOwner == owner) {
                    value.confirmedOwner = null
                    value.confirmedBoolean = null
                }
                value.epoch++
            }
        }
    }

    /** Readers wait for accepted writes and retry responses which raced a later write. */
    internal suspend fun <T> read(owner: SourceMutationOwner, resources: List<String>, block: suspend () -> T): T {
        val executing = currentCoroutineContext()[SourceMutationContext]
        val keys = resources.distinct().filterNot {
            executing != null && executing.owner.provider === owner.provider && executing.resource == it
        }.map { Key(owner.provider, it) }
        val registered = synchronized(lock) { keys.map { key -> key to entries.getOrPut(key, ::Entry).also { it.readers++ } } }
        try { while (true) {
            ensureCurrent(owner)
            val pending = synchronized(lock) { keys.mapNotNull { entries[it]?.pending }.distinct() }
            if (pending.isNotEmpty()) { pending.forEach { it.join() }; continue }
            val epochs = synchronized(lock) {
                if (keys.any { entries[it]?.pending != null }) null else keys.map { entries[it]?.epoch ?: 0L }
            } ?: continue
            val result = try { Result.success(block()) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { Result.failure(error) }
            ensureCurrent(owner)
            if (synchronized(lock) {
                keys.indices.all { entries[keys[it]]?.pending == null && (entries[keys[it]]?.epoch ?: 0L) == epochs[it] }
            }) return result.getOrThrow()
        } } finally {
            synchronized(lock) {
                registered.forEach { (key, entry) ->
                    entry.readers--
                    if (entry.readers == 0 && entry.pending == null && entry.confirmedBoolean == null && entries[key] === entry) entries.remove(key)
                }
            }
        }
    }

    private suspend fun <T> pinned(owner: SourceMutationOwner, resource: String, block: suspend () -> T): T = coroutineScope {
        val operation = currentCoroutineContext()[Job]!!
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            val revision = (owner.provider as? AccountRevisionCapability)?.accountRevision ?: flowOf(null)
            combine(registry.revision, revision) { _, account -> account }.collect { account ->
                if (registry.provider(owner.provider.id) !== owner.provider ||
                    registry.instance(owner.provider.instanceId) !== owner.provider ||
                    owner.provider.version != owner.version || account != owner.revision) {
                    operation.cancel(CancellationException("Source mutation owner changed"))
                }
            }
        }
        try { withContext(SourceMutationContext(owner, resource)) { ensureCurrent(owner); block() } }
        finally { withContext(NonCancellable) { watcher.cancelAndJoin() } }
    }
}

internal data class SourceMutationOwner(val provider: MusicProvider, val version: String, val revision: String?)

/** The script checks this pin again after it acquires its operation mutex. */
internal class SourceMutationContext(val owner: SourceMutationOwner, val resource: String) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<SourceMutationContext>
}

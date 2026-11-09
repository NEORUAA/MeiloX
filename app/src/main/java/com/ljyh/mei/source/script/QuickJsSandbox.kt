package com.ljyh.mei.source.script

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.guava.await

internal open class QuickJsException(message: String) : Exception(message)
internal class QuickJsSandboxDeadException(message: String = "Source engine is unavailable") : QuickJsException(message)
internal class QuickJsIsolateTerminatedException(message: String = "Source isolate ended") : QuickJsException(message)
internal class QuickJsDataInputException(message: String = "Invalid source engine input") : QuickJsException(message)
internal class QuickJsSandboxUnsupportedException(message: String = "Source engine is unsupported") : QuickJsException(message)
internal class QuickJsMemoryLimitExceededException(message: String = "Source isolate exceeded its memory limit") : QuickJsException(message)
internal class QuickJsEvaluationResultSizeLimitExceededException(message: String = "Source result exceeded its limit") : QuickJsException(message)
internal class QuickJsEvaluationFailedException(message: String = "Source evaluation failed") : QuickJsException(message)
internal class QuickJsTimeoutException(message: String = "Source evaluation timed out") : QuickJsException(message)

internal data class QuickJsIsolateParameters(
    var maxHeapSizeBytes: Long = 32L * 1024 * 1024,
    var maxStackSizeBytes: Long = 1024L * 1024,
    var maxEvaluationReturnSizeBytes: Int = 256 * 1024,
)

internal data class QuickJsTerminationInfo(val status: Int) {
    companion object {
        const val STATUS_UNKNOWN_ERROR = 0
        const val STATUS_SANDBOX_DEAD = 1
        const val STATUS_MEMORY_LIMIT_EXCEEDED = 2
    }
}

/** Host-side IPC only: plugin code runs in an isolated service with no app data or network access. */
internal class QuickJsSandbox private constructor(
    private val binding: Binding,
    private val remote: IQuickJsSandbox,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val sessions = ConcurrentHashMap<Int, QuickJsIsolate>()
    private val evaluations = ConcurrentHashMap<Long, Evaluation>()
    private val death = IBinder.DeathRecipient { die() }

    internal val isClosed: Boolean get() = closed.get()
    val processId: Int get() = getPid()
    fun getPid(): Int = try { remote.pid } catch (_: Exception) { die(); throw QuickJsSandboxDeadException() }

    private val callback = object : IQuickJsEvaluation.Stub() {
        override fun success(evaluationId: Long, result: ParcelFileDescriptor) {
            val evaluation = evaluations[evaluationId]
            if (evaluation == null) { result.closeQuietly(); return }
            if (!evaluation.acknowledge()) { result.closeQuietly(); return }
            evaluation.result.set(result)
            if (evaluation.future.isCancelled || closed.get()) {
                finish(evaluation)
                if (closed.get()) evaluation.future.setException(QuickJsSandboxDeadException())
                return
            }
            io.execute {
                try {
                    val bytes = DataInputStream(ParcelFileDescriptor.AutoCloseInputStream(result)).use { input ->
                        val length = input.readInt()
                        if (length < 0) throw QuickJsDataInputException()
                        if (length > evaluation.isolate.maximumResultBytes) throw QuickJsEvaluationResultSizeLimitExceededException()
                        ByteArray(length).also {
                            input.readFully(it)
                            if (input.read() != -1) throw QuickJsDataInputException()
                        }
                    }
                    val value = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
                    finish(evaluation)
                    evaluation.future.set(value)
                } catch (error: Exception) {
                    finish(evaluation)
                    evaluation.future.setException(if (error is QuickJsException) error else QuickJsDataInputException())
                }
            }
        }

        override fun failure(evaluationId: Long, code: Int) {
            val evaluation = evaluations[evaluationId] ?: return
            if (!evaluation.acknowledge()) return
            finish(evaluation)
            val error = when (code) {
                1 -> QuickJsEvaluationFailedException()
                2 -> QuickJsMemoryLimitExceededException()
                3 -> QuickJsTimeoutException()
                4, 5 -> QuickJsIsolateTerminatedException()
                6 -> QuickJsEvaluationResultSizeLimitExceededException()
                8 -> QuickJsDataInputException()
                else -> QuickJsSandboxDeadException()
            }
            if (code == 2) evaluation.isolate.terminate(QuickJsTerminationInfo.STATUS_MEMORY_LIMIT_EXCEEDED)
            if (code == 7) die()
            evaluation.future.setException(error)
        }
    }

    fun createIsolate(parameters: QuickJsIsolateParameters): QuickJsIsolate {
        if (closed.get()) throw QuickJsSandboxDeadException()
        val id = try {
            remote.openSession(parameters.maxHeapSizeBytes, parameters.maxStackSizeBytes,
                parameters.maxEvaluationReturnSizeBytes)
        } catch (_: Exception) { die(); throw QuickJsSandboxDeadException() }
        if (id <= 0) throw QuickJsSandboxDeadException()
        val isolate = QuickJsIsolate(id, parameters.maxEvaluationReturnSizeBytes, this)
        sessions[id] = isolate
        if (closed.get()) { sessions.remove(id, isolate); isolate.onSandboxDead(); throw QuickJsSandboxDeadException() }
        return isolate
    }

    private fun evaluate(isolate: QuickJsIsolate, code: String): ListenableFuture<String> {
        if (closed.get()) throw QuickJsSandboxDeadException()
        if (code.length > MAX_SOURCE_BYTES) {
            return SettableFuture.create<String>().apply { setException(QuickJsDataInputException()) }
        }
        val bytes = try {
            Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(code)).let { buffer ->
                    if (buffer.remaining() > MAX_SOURCE_BYTES) throw QuickJsDataInputException()
                    ByteArray(buffer.remaining()).also(buffer::get)
                }
        } catch (_: Exception) { return SettableFuture.create<String>().apply { setException(QuickJsDataInputException()) } }
        val evaluation = Evaluation(nextEvaluation.incrementAndGet(), isolate)
        check(isolate.pending.compareAndSet(null, evaluation)) { "A source isolate already has an evaluation" }
        evaluations[evaluation.id] = evaluation
        evaluation.future.addListener({
            if (evaluation.future.isCancelled) cancel(evaluation)
        }, MoreExecutors.directExecutor())
        try {
            val pipe = ParcelFileDescriptor.createPipe()
            evaluation.input.set(pipe[1])
            evaluation.deadline = timer.schedule({
                cancel(evaluation)
                evaluation.future.setException(QuickJsTimeoutException())
            }, EVALUATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            try { remote.evaluate(isolate.id, evaluation.id, pipe[0], callback) }
            finally { pipe[0].closeQuietly() }
            io.execute {
                try {
                    DataOutputStream(ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])).use { output ->
                        output.writeInt(bytes.size)
                        output.write(bytes)
                    }
                }
                catch (error: Exception) {
                    if (!evaluation.future.isCancelled && !evaluation.acknowledged.get() && !evaluation.cancelSent.get()) {
                        android.util.Log.w("SourceJsIo", "Input writer failed kind=${error.javaClass.simpleName}")
                        cancel(evaluation)
                        evaluation.future.setException(QuickJsDataInputException())
                    }
                } finally { evaluation.input.compareAndSet(pipe[1], null) }
            }
        } catch (_: Exception) {
            finish(evaluation)
            evaluation.future.setException(QuickJsSandboxDeadException())
            die()
        }
        return evaluation.future
    }

    private fun cancel(evaluation: Evaluation) {
        evaluation.closePipes()
        if (evaluation.acknowledged.get() || !evaluation.cancelSent.compareAndSet(false, true)) return
        evaluation.deadline?.cancel(false)
        try { remote.cancel(evaluation.isolate.id, evaluation.id) }
        catch (_: Exception) { die(); return }
        evaluation.hardDeadline = timer.schedule({
            if (!evaluation.acknowledged.get()) {
                // The interrupt acknowledgement is sent only after native evaluation unwinds.
                // A stuck native engine must not retain CPU or memory beyond this grace period.
                close()
            }
        }, INTERRUPT_GRACE_MS, TimeUnit.MILLISECONDS)
    }

    private fun finish(evaluation: Evaluation) {
        evaluations.remove(evaluation.id, evaluation)
        evaluation.isolate.pending.compareAndSet(evaluation, null)
        evaluation.deadline?.cancel(false)
        evaluation.closePipes()
    }

    private fun closeIsolate(isolate: QuickJsIsolate) {
        sessions.remove(isolate.id, isolate)
        isolate.pending.get()?.let { evaluation ->
            cancel(evaluation)
            evaluation.future.setException(QuickJsIsolateTerminatedException())
        }
        try { remote.closeSession(isolate.id) } catch (_: Exception) { die() }
    }

    private fun die() {
        if (!closed.compareAndSet(false, true)) return
        sessions.values.forEach(QuickJsIsolate::onSandboxDead)
        sessions.clear()
        evaluations.values.forEach {
            it.acknowledge()
            finish(it)
            it.future.setException(QuickJsSandboxDeadException())
        }
        runCatching { remote.asBinder().unlinkToDeath(death, 0) }
        binding.unbind()
    }

    override fun close() {
        // This Binder method kills the isolated process directly, independently of JS executors.
        runCatching { remote.shutdown() }
        die()
    }

    internal class QuickJsIsolate internal constructor(
        internal val id: Int,
        internal val maximumResultBytes: Int,
        private val sandbox: QuickJsSandbox,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)
        private val termination = AtomicReference<QuickJsTerminationInfo?>(null)
        private val callbacks = java.util.concurrent.CopyOnWriteArrayList<Pair<Executor, (QuickJsTerminationInfo) -> Unit>>()
        internal val pending = AtomicReference<Evaluation?>(null)

        fun evaluateJavaScriptAsync(code: String): ListenableFuture<String> {
            check(!closed.get()) { "Source isolate is closed" }
            termination.get()?.let { return SettableFuture.create<String>().apply { setException(QuickJsIsolateTerminatedException()) } }
            return sandbox.evaluate(this, code)
        }

        fun addOnTerminatedCallback(executor: Executor, callback: (QuickJsTerminationInfo) -> Unit) {
            synchronized(callbacks) {
                termination.get()?.let { info -> dispatch(executor, callback, info); return }
                callbacks += executor to callback
            }
        }

        internal fun terminate(status: Int) {
            val info = QuickJsTerminationInfo(status)
            synchronized(callbacks) {
                if (!termination.compareAndSet(null, info)) return
                callbacks.forEach { (executor, callback) -> dispatch(executor, callback, info) }
                callbacks.clear()
            }
        }

        private fun dispatch(executor: Executor, callback: (QuickJsTerminationInfo) -> Unit, info: QuickJsTerminationInfo) {
            runCatching { executor.execute { runCatching { callback(info) } } }
        }

        internal fun onSandboxDead() { if (!closed.get()) terminate(QuickJsTerminationInfo.STATUS_SANDBOX_DEAD) }
        override fun close() { if (closed.compareAndSet(false, true)) sandbox.closeIsolate(this) }
    }

    internal class Evaluation(val id: Long, val isolate: QuickJsIsolate) {
        val future = SettableFuture.create<String>()
        val acknowledged = AtomicBoolean(false)
        val cancelSent = AtomicBoolean(false)
        val input = AtomicReference<ParcelFileDescriptor?>(null)
        val result = AtomicReference<ParcelFileDescriptor?>(null)
        @Volatile var deadline: ScheduledFuture<*>? = null
        @Volatile var hardDeadline: ScheduledFuture<*>? = null
        fun acknowledge(): Boolean {
            if (!acknowledged.compareAndSet(false, true)) return false
            hardDeadline?.cancel(false)
            return true
        }
        fun closePipes() { input.getAndSet(null)?.closeQuietly(); result.getAndSet(null)?.closeQuietly() }
    }

    private class Binding(private val context: Context, val future: SettableFuture<QuickJsSandbox>) : ServiceConnection {
        private val unbound = AtomicBoolean(false)
        @Volatile var sandbox: QuickJsSandbox? = null
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val remote = IQuickJsSandbox.Stub.asInterface(binder)
            if (unbound.get()) { runCatching { remote.shutdown() }; return }
            try {
                val value = QuickJsSandbox(this, remote)
                sandbox = value
                binder.linkToDeath(value.death, 0)
                if (!future.set(value)) value.close()
            } catch (_: Exception) {
                future.setException(QuickJsSandboxDeadException())
                unbind()
            }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            sandbox?.die()
            future.setException(QuickJsSandboxDeadException())
            unbind()
        }
        override fun onBindingDied(name: ComponentName) { onServiceDisconnected(name); unbind() }
        override fun onNullBinding(name: ComponentName) { future.setException(QuickJsSandboxDeadException()); unbind() }
        fun unbind() { if (unbound.compareAndSet(false, true)) runCatching { context.unbindService(this) } }
    }

    companion object {
        private const val MAX_SOURCE_BYTES = 16 * 1024 * 1024
        private const val EVALUATION_TIMEOUT_MS = 3_000L
        private const val INTERRUPT_GRACE_MS = 500L
        private val nextEvaluation = AtomicLong()
        private val io = Executors.newFixedThreadPool(4) { runnable -> Thread(runnable, "SourceJsIo").apply { isDaemon = true } }
        private val callbacks = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "SourceJsConnection").apply { isDaemon = true } }
        private val timer = Executors.newScheduledThreadPool(2) { runnable -> Thread(runnable, "SourceJsDeadline").apply { isDaemon = true } }
        fun isSupported(): Boolean = true
        suspend fun connect(context: Context): QuickJsSandbox = createConnectedInstanceAsync(context).await()
        fun createConnectedInstanceAsync(context: Context): ListenableFuture<QuickJsSandbox> {
            val future = SettableFuture.create<QuickJsSandbox>()
            val app = context.applicationContext
            val binding = Binding(app, future)
            try {
                val intent = Intent(app, QuickJsEngineService::class.java)
                val name = "source_js_" + UUID.randomUUID().toString().replace("-", "")
                if (!app.bindIsolatedService(intent, Context.BIND_AUTO_CREATE, name, callbacks, binding)) {
                    future.setException(QuickJsSandboxDeadException())
                    binding.unbind()
                }
                timer.schedule({
                    if (future.setException(QuickJsSandboxDeadException())) binding.unbind()
                }, 5_000, TimeUnit.MILLISECONDS)
            } catch (_: Exception) { future.setException(QuickJsSandboxDeadException()); binding.unbind() }
            future.addListener({ if (future.isCancelled) { binding.sandbox?.close(); binding.unbind() } }, MoreExecutors.directExecutor())
            return future
        }
    }
}

private fun ParcelFileDescriptor.closeQuietly() { runCatching { close() } }

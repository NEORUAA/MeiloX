package com.ljyh.mei.source.script

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.QuickJsInterruptedException
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** A permissionless worker. Only UTF-8 JavaScript and bounded result strings cross this boundary. */
class QuickJsEngineService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = ConcurrentHashMap<Int, EngineSession>()
    private val engineSlots = Semaphore(MAX_SESSIONS)
    // JNI lifecycle operations replace process-wide class references. Evaluations may share
    // those references, but no runtime may be created or destroyed while they are in use.
    private val nativeAdmission = Mutex()
    private val nativeReaders = Semaphore(MAX_SESSIONS)
    private val retirementHandler = Handler(Looper.getMainLooper())
    private var nextSessionId = 1

    private suspend fun <T> withNativeEvaluation(block: suspend () -> T): T {
        var acquired = false
        try {
            nativeAdmission.withLock {
                nativeReaders.acquire()
                acquired = true
            }
            return block()
        } finally {
            if (acquired) nativeReaders.release()
        }
    }

    private suspend fun <T> withNativeLifecycle(block: suspend () -> T): T {
        var acquired = 0
        try {
            // Hold admission while draining existing readers so new evaluations cannot
            // starve a queued lifecycle operation. Cancellation releases partial admission.
            nativeAdmission.withLock {
                repeat(MAX_SESSIONS) {
                    nativeReaders.acquire()
                    acquired++
                }
            }
            return block()
        } finally {
            repeat(acquired) { nativeReaders.release() }
        }
    }

    private fun enforceOwner(input: ParcelFileDescriptor? = null) {
        if (!Process.isIsolated() || Binder.getCallingUid() != applicationInfo.uid) {
            input.closeQuietly()
            throw SecurityException("Source worker caller rejected")
        }
    }

    private val binder = object : IQuickJsSandbox.Stub() {
        override fun openSession(maxHeapBytes: Long, stackBytes: Long, maxResultBytes: Int): Int {
            enforceOwner()
            if (maxHeapBytes !in 1..MAX_HEAP_BYTES || stackBytes !in 1..MAX_STACK_BYTES ||
                maxResultBytes !in 1..MAX_RESULT_BYTES) return 0
            return synchronized(sessions) {
                if (sessions.size >= MAX_SESSIONS) return@synchronized 0
                val id = nextSessionId++
                if (nextSessionId <= 0) nextSessionId = 1
                sessions[id] = EngineSession(id, maxHeapBytes, stackBytes, maxResultBytes)
                id
            }
        }

        override fun getPid(): Int {
            enforceOwner()
            return Process.myPid()
        }

        override fun evaluate(sessionId: Int, evaluationId: Long, utf8Script: ParcelFileDescriptor, callback: IQuickJsEvaluation) {
            enforceOwner(utf8Script)
            val session = sessions[sessionId]
            if (session == null) {
                utf8Script.closeQuietly()
                reportFailure(callback, evaluationId, FAILURE_CLOSED)
            } else session.evaluate(evaluationId, utf8Script, callback)
        }

        override fun cancel(sessionId: Int, evaluationId: Long) {
            enforceOwner()
            sessions[sessionId]?.cancel(evaluationId)
        }

        override fun closeSession(sessionId: Int) {
            enforceOwner()
            // Logical retirement is synchronous; freeing a native VM never runs on a Binder thread.
            sessions.remove(sessionId)?.retire()
        }

        override fun shutdown() {
            enforceOwner()
            // This Binder thread remains available even if native JavaScript fails to unwind.
            Process.killProcess(Process.myPid())
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        sessions.values.toList().forEach(EngineSession::retire)
        sessions.clear()
        scope.cancel()
        super.onDestroy()
        if (Process.isIsolated()) Process.killProcess(Process.myPid())
    }

    private inner class EngineSession(
        private val id: Int,
        private val maxHeap: Long,
        private val maxStack: Long,
        private val maxResult: Int,
    ) {
        private val monitor = Any()
        private val nativeDispatcher = Executors.newSingleThreadExecutor { runnable ->
            // The native stack limit leaves room for ART and JNI frames on this thread.
            Thread(null, runnable, "source-js-$id", 4L * 1024 * 1024)
        }.asCoroutineDispatcher()
        private val cleanupStarted = AtomicBoolean()
        @Volatile private var retired = false
        @Volatile private var engine: QuickJs? = null
        private var ownsEngineSlot = false
        private var active: Evaluation? = null
        private val pending = ConcurrentHashMap<Long, Evaluation>()

        fun evaluate(evaluationId: Long, input: ParcelFileDescriptor, callback: IQuickJsEvaluation) {
            val evaluation = synchronized(monitor) {
                if (retired || active != null || pending.size >= 2 || pending.containsKey(evaluationId)) {
                    input.closeQuietly()
                    reportFailure(callback, evaluationId, if (retired) FAILURE_CLOSED else FAILURE_UNAVAILABLE)
                    return
                }
                Evaluation(evaluationId, input, callback).also { active = it; pending[evaluationId] = it }
            }
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    withTimeout(EVALUATION_TIMEOUT_MS) {
                        evaluation.ensureActive()
                        evaluation.phase = "INPUT"
                        val source = try { readSource(input) } catch (error: IOException) { throw InputFailure(error) }
                        val result = withContext(nativeDispatcher) {
                            evaluation.ensureActive()
                            val runtime = currentEngine(evaluation)
                            evaluation.ensureActive()
                            evaluation.phase = "EVALUATION"
                            withNativeEvaluation {
                                evaluation.ensureActive()
                                // No host functions, modules, filesystem or network bindings are registered.
                                runtime.evaluate<Any?>(source) as? String ?: throw InvalidResult()
                            }
                        }
                        evaluation.ensureActive()
                        evaluation.phase = "RESULT"
                        // UTF-16 length is a lower bound on UTF-8 size. Reject before allocating a
                        // UTF-8 copy, whose worst-case size is then at most three times maxResult.
                        if (result.length > maxResult) throw ResultTooLarge()
                        val bytes = result.toByteArray(Charsets.UTF_8)
                        if (bytes.size > maxResult) throw ResultTooLarge()
                        evaluation.ensureActive()
                        evaluation.phase = "TRANSFER"
                        writeResult(evaluation, bytes)
                    }
                } catch (error: Throwable) {
                    // Never send exception text to Android's uncaught coroutine logger.
                    evaluation.failure = error
                    val kinds = generateSequence(error) { it.cause }.take(4)
                        .joinToString(">") { it.javaClass.name }
                    val errno = generateSequence(error) { it.cause }.take(4)
                        .filterIsInstance<android.system.ErrnoException>().firstOrNull()?.errno ?: 0
                    android.util.Log.w("SourceJsWorker", "Evaluation failure phase=${evaluation.phase} kinds=$kinds errno=$errno")
                }
            }
            evaluation.job = job
            job.invokeOnCompletion { completionError ->
                val error = evaluation.failure ?: completionError
                input.closeQuietly()
                evaluation.output.closeQuietly()
                pending.remove(evaluationId, evaluation)
                synchronized(monitor) { if (active === evaluation) active = null }
                if (evaluation.acknowledged.compareAndSet(false, true)) {
                    val code = when {
                        evaluation.cancelled.get() -> if (retired) FAILURE_CLOSED else FAILURE_CANCELLED
                        error is TimeoutCancellationException || error is QuickJsInterruptedException -> FAILURE_TIMEOUT
                        error is CancellationException -> FAILURE_CANCELLED
                        error is ResultTooLarge -> FAILURE_TOO_LARGE
                        error is InvalidResult -> FAILURE_SCRIPT
                        error is InputFailure -> FAILURE_INPUT
                        error is OutOfMemoryError -> FAILURE_MEMORY
                        error is QuickJsException && error.message?.contains("InternalError: out of memory") == true -> FAILURE_MEMORY
                        error is QuickJsException -> FAILURE_SCRIPT
                        else -> FAILURE_UNAVAILABLE
                    }
                    // Completion runs only after evaluate() has returned and released its native lock.
                    reportFailure(callback, evaluationId, code)
                }
                if (retired && synchronized(monitor) { active == null }) cleanup()
            }
            if (retired || evaluation.cancelled.get()) job.cancel()
            job.start()
        }

        private suspend fun currentEngine(evaluation: Evaluation): QuickJs {
            engine?.let { return it }
            evaluation.phase = "ADMISSION"
            engineSlots.acquire()
            ownsEngineSlot = true
            return withNativeLifecycle {
                evaluation.ensureActive()
                evaluation.phase = "ENGINE_CREATE"
                val created = QuickJs.create(jobDispatcher = nativeDispatcher)
                // Retain even a partially configured VM so failure still closes its native allocation.
                engine = created
                evaluation.phase = "ENGINE_LIMITS"
                created.memoryLimit = maxHeap
                created.maxStackSize = maxStack
                created.evaluationTimeoutMillis = EVALUATION_TIMEOUT_MS
                // Prime lazy JNI converters even for malformed non-string results before
                // parallel evaluations begin. Neither bootstrap creates persistent bindings.
                created.evaluate<Any?>(
                    "[true, NaN, 1, 1.5, [1], new Set([1]), new Map([[true, 1]]), " +
                        "new Uint8Array([1]), new Int8Array([1]), {}, new Error()]"
                )
                created.evaluate<String>(
                    "Promise.reject(Symbol()).catch(() => {}); " +
                        "Promise.reject(new Error()).catch(() => {}); ''"
                )
                try {
                    created.evaluate<Any?>("throw new Error()")
                } catch (_: QuickJsException) {
                    // Prime the JNI Throwable and evaluation-failure callback caches here too.
                }
                created
            }
        }

        fun cancel(evaluationId: Long) {
            pending[evaluationId]?.cancel()
        }

        fun retire() {
            val evaluation = synchronized(monitor) {
                retired = true
                active
            }
            pending.values.toList().forEach(Evaluation::cancel)
            if (evaluation == null) cleanup()
        }

        private fun cleanup() {
            if (!cleanupStarted.compareAndSet(false, true)) return
            val complete = AtomicBoolean()
            val deadline = Runnable {
                if (!complete.get() && Process.isIsolated()) {
                    android.util.Log.w("SourceJsWorker", "Native retirement deadline exceeded")
                    Process.killProcess(Process.myPid())
                }
            }
            scope.launch {
                try {
                    withContext(nativeDispatcher) {
                        withNativeLifecycle {
                            // Waiting for a healthy peer evaluation is not a stalled free/GC.
                            // Start this deadline only after exclusive native access is granted.
                            retirementHandler.postDelayed(deadline, RETIREMENT_TIMEOUT_MS)
                            engine?.close()
                            engine = null
                        }
                    }
                } catch (_: Throwable) {
                    // Retirement must be proven before admitting another native VM.
                    if (Process.isIsolated()) Process.killProcess(Process.myPid())
                } finally {
                    complete.set(true)
                    retirementHandler.removeCallbacks(deadline)
                    if (ownsEngineSlot) engineSlots.release()
                    nativeDispatcher.close()
                }
            }
        }

        private inner class Evaluation(val id: Long, val input: ParcelFileDescriptor, val callback: IQuickJsEvaluation) {
            val cancelled = AtomicBoolean()
            val acknowledged = AtomicBoolean()
            @Volatile var job: Job? = null
            @Volatile var output: ParcelFileDescriptor? = null
            @Volatile var failure: Throwable? = null
            @Volatile var phase: String = "QUEUED"

            fun ensureActive() {
                if (retired || cancelled.get()) throw CancellationException("Source evaluation ended")
            }

            fun cancel() {
                cancelled.set(true)
                input.closeQuietly()
                output.closeQuietly()
                synchronized(monitor) {
                    // A delayed cancellation of a completed transfer must not interrupt the
                    // next native evaluation in this session.
                    if (active === this) runCatching { engine?.interruptEvaluation() }
                }
                job?.cancel()
            }
        }

        private fun writeResult(evaluation: Evaluation, bytes: ByteArray) {
            // A result callback acknowledges native completion. The next evaluation may begin
            // once its caller has drained this result, without racing our coroutine completion.
            synchronized(monitor) { if (active === evaluation) active = null }
            val pipe = ParcelFileDescriptor.createPipe()
            evaluation.output = pipe[1]
            val output = DataOutputStream(ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]))
            try {
                evaluation.ensureActive()
                evaluation.callback.success(evaluation.id, pipe[0])
                evaluation.acknowledged.set(true)
                output.writeInt(bytes.size)
                output.write(bytes)
            } finally {
                // EOF lets the caller start its next evaluation. Retire this completed
                // transfer before publishing EOF, independently of coroutine completion.
                pending.remove(evaluation.id, evaluation)
                runCatching { output.close() }
                pipe[0].closeQuietly()
                pipe[1].closeQuietly()
                evaluation.output = null
            }
        }
    }

    private fun readSource(input: ParcelFileDescriptor): String {
        val bytes = DataInputStream(ParcelFileDescriptor.AutoCloseInputStream(input)).use { stream ->
            val length = stream.readInt()
            if (length < 0) throw IOException("Invalid source frame")
            if (length > MAX_SOURCE_BYTES) throw ResultTooLarge()
            val source = ByteArray(length)
            stream.readFully(source)
            if (stream.read() != -1) throw IOException("Invalid source frame")
            source
        }
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }

    private fun reportFailure(callback: IQuickJsEvaluation, evaluationId: Long, code: Int) {
        // Engine exception text can contain credentials or source code. Only fixed codes leave.
        runCatching { callback.failure(evaluationId, code) }
    }

    private fun ParcelFileDescriptor?.closeQuietly() { runCatching { this?.close() } }

    private class ResultTooLarge : Exception()
    private class InvalidResult : Exception()
    private class InputFailure(cause: IOException) : Exception(null, cause)

    companion object {
        const val MAX_HEAP_BYTES = 32L * 1024 * 1024
        const val MAX_STACK_BYTES = 1024L * 1024
        const val MAX_SOURCE_BYTES = 16 * 1024 * 1024
        const val MAX_RESULT_BYTES = 2 * 1024 * 1024
        const val EVALUATION_TIMEOUT_MS = 3_000L
        const val RETIREMENT_TIMEOUT_MS = 2_000L
        const val MAX_SESSIONS = 4
        const val FAILURE_SCRIPT = 1
        const val FAILURE_MEMORY = 2
        const val FAILURE_TIMEOUT = 3
        const val FAILURE_CANCELLED = 4
        const val FAILURE_CLOSED = 5
        const val FAILURE_TOO_LARGE = 6
        const val FAILURE_UNAVAILABLE = 7
        const val FAILURE_INPUT = 8
    }
}

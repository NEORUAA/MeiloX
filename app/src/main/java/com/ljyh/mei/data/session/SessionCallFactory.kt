package com.ljyh.mei.data.session

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer

/** Pins transport work to its creation-time account, including clones and unread bodies. */
internal class SessionCallFactory(
    private val sessions: SessionStore,
    private val transport: Call.Factory,
    private val prepare: (Request, SessionStamp) -> Request = { request, _ -> request },
) : Call.Factory {
    override fun newCall(request: Request): Call {
        val owner = request.tag(SessionStamp::class.java) ?: sessions.snapshot()
        sessions.requireCurrent(owner)
        return create(request, owner)
    }

    private fun create(original: Request, owner: SessionStamp): Call {
        val request = prepare(original, owner).newBuilder().tag(SessionStamp::class.java, owner).build()
        return BoundCall(original, owner, transport.newCall(request))
    }

    private inner class BoundCall(
        private val original: Request,
        private val owner: SessionStamp,
        private val delegate: Call,
    ) : Call by delegate {
        private val started = AtomicBoolean()
        private val invalidation = AtomicReference<Closeable?>()
        private val finished = AtomicBoolean()

        override fun clone(): Call {
            sessions.requireCurrent(owner)
            return create(original, owner)
        }

        override fun isExecuted() = started.get()

        override fun execute(): Response {
            begin()
            return try {
                guard(delegate.execute())
            } catch (error: Throwable) {
                finish()
                throw error
            }
        }

        override fun enqueue(responseCallback: Callback) {
            check(started.compareAndSet(false, true)) { "Request has already been executed" }
            try {
                watch()
            } catch (error: IOException) {
                finish()
                responseCallback.onFailure(this, error)
                return
            }
            try {
                delegate.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        finish()
                        responseCallback.onFailure(this@BoundCall, e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val guarded = try { guard(response) } catch (error: IOException) {
                            finish()
                            responseCallback.onFailure(this@BoundCall, error)
                            return
                        }
                        try {
                            responseCallback.onResponse(this@BoundCall, guarded)
                        } catch (error: Throwable) {
                            guarded.close()
                            throw error
                        }
                    }
                })
            } catch (error: Throwable) {
                finish()
                throw error
            }
        }

        private fun begin() {
            check(started.compareAndSet(false, true)) { "Request has already been executed" }
            try { watch() } catch (error: Throwable) { finish(); throw error }
        }

        private fun watch() {
            invalidation.set(sessions.onInvalidated { revision ->
                if (revision > owner.generation) delegate.cancel()
            })
            sessions.requireCurrent(owner)
            if (delegate.isCanceled()) throw IOException("Canceled")
        }

        private fun guard(response: Response): Response {
            try {
                sessions.requireCurrent(owner)
                if (delegate.isCanceled()) throw IOException("Canceled")
                val body = response.body
                val source = object : ForwardingSource(body.source()) {
                    override fun read(sink: Buffer, byteCount: Long): Long = try {
                        sessions.requireCurrent(owner)
                        if (this@BoundCall.delegate.isCanceled()) throw IOException("Canceled")
                        super.read(sink, byteCount).also {
                            sessions.requireCurrent(owner)
                            if (it == -1L) finish()
                        }
                    } catch (error: Throwable) {
                        try { super.close() } finally { finish() }
                        throw error
                    }

                    override fun close() { try { super.close() } finally { finish() } }
                }.buffer()
                return response.newBuilder().body(object : ResponseBody() {
                    override fun contentType() = body.contentType()
                    override fun contentLength() = body.contentLength()
                    override fun source() = source
                }).build()
            } catch (error: Throwable) {
                response.close()
                finish()
                throw error
            }
        }

        private fun finish() {
            if (finished.compareAndSet(false, true)) invalidation.getAndSet(null)?.close()
        }
    }
}

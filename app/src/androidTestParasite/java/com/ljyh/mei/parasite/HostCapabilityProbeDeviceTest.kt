package com.ljyh.mei.parasite

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ljyh.mei.data.session.SessionChangedException
import com.ljyh.mei.data.session.SessionIdentity
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real request ownership and cancellation with synthetic host responses only. */
@RunWith(AndroidJUnit4::class)
class HostCapabilityProbeDeviceTest {
    @Test fun currentOwnerCompletesAllCapabilitiesAndCancelsItsPendingRequest() {
        val f = Fixture()
        f.run()

        assertEquals(PATHS, f.backend.dispatched.map { it.path })
        assertTrue(f.backend.executed.get() in (PATHS.size - 1)..PATHS.size)
        assertEquals(listOf(PLAYABLE), f.playable)
        assertEquals(1, f.backend.canceled.get())
        assertEquals(listOf("request_cancel observed_running=true worker_finished=true rejected=true"),
            f.reports.filter { it.startsWith("request_cancel") })
        assertEquals("probe_complete session_unchanged=true", f.reports.last())
        assertFalse(f.reports.any { it.startsWith("probe_aborted") || it.contains(" failed ") })
        assertEquals(mapOf("uid" to "41", "limit" to "2", "offset" to "0"),
            f.backend.dispatched[1].parameters)
        assertEquals(mapOf("id" to "51", "n" to "1", "s" to "0"),
            f.backend.dispatched[2].parameters)
        assertEquals(mapOf("s" to "music", "type" to "1", "limit" to "1", "offset" to "1"),
            f.backend.dispatched.last().parameters)
        assertTrue(f.reports.none { it.contains(PLAYABLE) })
    }

    @Test fun replacementAfterTheAccountCheckCannotDispatchUnderTheNewIdentity() {
        val f = Fixture()
        f.backend.onIdentityRead = { read ->
            // The helper's check receives the old identity; its next newCall sees the replacement.
            if (read == 4) f.backend.identity = SessionIdentity(42, true, false)
        }
        f.run()

        assertAccountCreationWasRejected(f)
        assertEquals(42L, f.bridge.sessions.snapshot().identity.userId)
    }

    @Test fun accountCreationCannotAdoptSameAccountReauthorization() {
        val f = Fixture()
        f.backend.onIdentityRead = { read ->
            // The first newCall identity read follows the helper's successful ownership check.
            if (read == 5) f.bridge.sessions.invalidate()
        }
        f.run()

        assertAccountCreationWasRejected(f)
        assertEquals(41L, f.bridge.sessions.snapshot().identity.userId)
        assertEquals(1L, f.bridge.sessions.snapshot().generation)
    }

    @Test fun staleCancellationCannotBorrowAReplacementOrReauthorizedSession() {
        for (replaceAccount in listOf(false, true)) {
            val f = Fixture()
            f.onPlayable = {
                if (replaceAccount) f.backend.identity = SessionIdentity(42, true, false)
                f.bridge.sessions.invalidate()
            }
            f.run()

            assertEquals(PATHS.dropLast(1), f.backend.dispatched.map { it.path })
            assertEquals(PATHS.size - 1, f.backend.executed.get())
            assertEquals(0, f.backend.canceled.get())
            assertEquals(listOf("probe_aborted type=${SessionChangedException::class.java.name}"),
                f.reports.filter { it.startsWith("probe_aborted") })
            assertFalse(f.reports.any { it.startsWith("request_cancel") || it.startsWith("probe_complete") })
        }
    }

    private fun assertAccountCreationWasRejected(f: Fixture) {
        assertTrue(f.backend.dispatched.isEmpty())
        assertEquals(0, f.backend.executed.get())
        assertEquals(listOf("session authenticated=true",
            "probe=account failed type=${SessionChangedException::class.java.name}",
            "account_matches_session=false"), f.reports)
    }

    private class Fixture {
        val backend = Backend()
        val bridge = HostRequestBridge(HostSessionBridge()).apply { bind(backend) }
        val reports = mutableListOf<String>()
        val playable = mutableListOf<String>()
        var onPlayable: () -> Unit = {}
        fun run() = HostCapabilityProbe(bridge, { reports += it }, { url ->
            playable += url
            onPlayable()
        }).run()
    }

    private data class Dispatch(val path: String, val parameters: Map<String, String>)

    private class Backend : HostRequestBackend {
        var identity = SessionIdentity(41, true, false)
        var onIdentityRead: (Int) -> Unit = {}
        private val identityReads = AtomicInteger()
        val dispatched = CopyOnWriteArrayList<Dispatch>()
        val executed = AtomicInteger()
        val canceled = AtomicInteger()
        override fun sessionIdentity(): SessionIdentity {
            val observed = identity
            onIdentityRead(identityReads.incrementAndGet())
            return observed
        }
        override fun open(path: String, parameters: Map<String, String>): HostPendingRequest {
            dispatched += Dispatch(path, parameters.toMap())
            val cancellation = CountDownLatch(1)
            val canceledOnce = AtomicBoolean()
            return object : HostPendingRequest {
                override fun execute(): String {
                    executed.incrementAndGet()
                    if (path == "search/get" && parameters["offset"] == "1") {
                        check(cancellation.await(5, TimeUnit.SECONDS)) { "Synthetic cancellation timed out" }
                        throw IOException("Synthetic request canceled")
                    }
                    return when (path) {
                        "nuser/account/get" -> """{"code":200,"profile":{"userId":41}}"""
                        "user/playlist" -> """{"code":200,"playlist":[{"id":51}]}"""
                        "v6/playlist/detail" -> """{"code":200,"playlist":{"trackIds":[{"id":77}]}}"""
                        "v1/cloud/get", "listen/together/status/get", "djradio/category/get" -> """{"code":200}"""
                        "search/get" -> """{"code":200,"result":{"songs":[{"id":88}]}}"""
                        "v3/song/detail" -> """{"code":200,"songs":[{"id":77}]}"""
                        "song/lyric/v1" -> """{"code":200,"lrc":{"lyric":"[00:01]fixture"}}"""
                        "song/enhance/player/url/v1" ->
                            """{"code":200,"data":[{"code":200,"url":"$PLAYABLE","freeTrialInfo":null}]}"""
                        else -> error("Unexpected host business path: $path")
                    }
                }
                override fun cancel() {
                    if (canceledOnce.compareAndSet(false, true)) canceled.incrementAndGet()
                    cancellation.countDown()
                }
                override fun close() = Unit
            }
        }
    }

    companion object {
        private const val PLAYABLE = "https://example.invalid/capability-fixture.mp3"
        private val PATHS = listOf("nuser/account/get", "user/playlist", "v6/playlist/detail", "v1/cloud/get",
            "listen/together/status/get", "djradio/category/get", "search/get", "v3/song/detail",
            "song/lyric/v1", "song/enhance/player/url/v1", "search/get")
    }
}

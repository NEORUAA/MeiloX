package com.ljyh.mei.parasite

import com.ljyh.mei.data.session.SessionChangedException
import com.ljyh.mei.data.session.SessionIdentity
import com.ljyh.mei.di.RetrofitModule
import com.ljyh.mei.runtime.RuntimeBackendModule
import java.io.IOException
import java.util.concurrent.Executor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HostCatalogCollectionTest {
    @Test fun albumStateKeepsTheTvRequestEnvelopeAndNullableFlag() = runBlocking {
        val wire = Wire()
        for (collected in listOf(false, true)) {
            wire.response = { """{"code":200,"data":{"id":10,"collect":$collected}}""" }
            assertEquals(collected, wire.backend.albumCollected(10, wire.bridge.sessions.snapshot()))
            assertEquals("tv-artist-page/album/get" to mapOf("request" to "{\"albumId\":\"10\"}"), wire.requests.last())
        }
    }

    @Test fun artistStateKeepsTheTvArtistFieldAndNeverUsesLinkedUserState() = runBlocking {
        val wire = Wire()
        for (followed in listOf(false, true)) {
            wire.response = { """{"code":200,"data":{"artistDetail":{"id":10,"followed":$followed},"user":{"followed":${!followed}}}}""" }
            assertEquals(followed, wire.backend.artistFollowed(10, wire.bridge.sessions.snapshot()))
            assertEquals("tv-artist-page/artistdetail" to mapOf("artistId" to "10"), wire.requests.last())
        }
    }

    @Test fun missingFlagsWrongIdentitiesAndBusinessRejectionsAreNotFalse() = runBlocking {
        val wire = Wire()
        for (json in listOf(
            """{"code":200,"data":{"id":10}}""",
            """{"code":200,"data":{"id":20,"collect":true}}""",
            """{"code":200,"data":null}""", """{"code":301}""",
        )) {
            wire.response = { json }
            assertTrue(runCatching { wire.backend.albumCollected(10, wire.bridge.sessions.snapshot()) }.isFailure)
        }
        for (json in listOf(
            """{"code":200,"data":{"artistDetail":{"id":10}}}""",
            """{"code":200,"data":{"artistDetail":{"id":20,"followed":true}}}""",
            """{"code":200,"data":{"user":{"followed":true}}}""",
            """{"code":200,"data":null}""", """{"code":301}""",
        )) {
            wire.response = { json }
            assertTrue(runCatching { wire.backend.artistFollowed(10, wire.bridge.sessions.snapshot()) }.isFailure)
        }
    }

    @Test fun writesKeepExactHostParametersAndPropagateBusinessCodes() = runBlocking {
        val wire = Wire()
        val owner = wire.bridge.sessions.snapshot()
        for (code in listOf(200, 301, 500)) {
            wire.response = { """{"code":$code}""" }
            assertEquals(code, wire.backend.setArtistFollowed(10, true, owner).code)
            assertEquals("v1/artist/sub" to mapOf("artistId" to "10"), wire.requests.last())
            assertEquals(code, wire.backend.setArtistFollowed(10, false, owner).code)
            assertEquals("artist/unsub" to mapOf("artistIds" to "[10]"), wire.requests.last())
        }
        assertEquals(6, wire.requests.size)
    }

    @Test fun obsoleteOwnerCannotReachAnyCollectionRoute() = runBlocking {
        val wire = Wire()
        val owner = wire.bridge.sessions.snapshot()
        wire.bridge.sessions.invalidate()
        val calls: List<suspend () -> Any> = listOf(
            { wire.backend.albumCollected(10, owner) }, { wire.backend.artistFollowed(10, owner) },
            { wire.backend.setArtistFollowed(10, true, owner) }, { wire.backend.setArtistFollowed(10, false, owner) },
        )
        calls.forEach { assertTrue(runCatching { it() }.exceptionOrNull() is SessionChangedException) }
        assertTrue(wire.requests.isEmpty())
    }

    @Test fun lateHostResponseCannotCrossAnAccountGeneration() = runBlocking {
        val wire = Wire()
        wire.response = {
            wire.bridge.sessions.invalidate()
            """{"code":200,"data":{"id":10,"collect":true}}"""
        }
        assertTrue(runCatching { wire.backend.albumCollected(10, wire.bridge.sessions.snapshot()) }.exceptionOrNull() is IOException)
        assertEquals(1, wire.requests.size)
    }

    private class Wire {
        val requests = mutableListOf<Pair<String, Map<String, String>>>()
        var response: () -> String = { """{"code":200}""" }
        private val transport = object : HostRequestBackend {
            override fun sessionIdentity() = SessionIdentity(1, true, false)
            override fun open(path: String, parameters: Map<String, String>): HostPendingRequest {
                requests += path to parameters
                return object : HostPendingRequest {
                    override fun execute() = response()
                    override fun cancel() = Unit
                    override fun close() = Unit
                }
            }
        }
        val bridge = HostRequestBridge(HostSessionBridge()).apply { bind(transport) }
        val backend = RuntimeBackendModule.catalogCollections(HostCatalogCollectionBackend(
            RetrofitModule.provideRetrofit(HostCallFactory(bridge, Executor { it.run() })),
        ))
    }
}

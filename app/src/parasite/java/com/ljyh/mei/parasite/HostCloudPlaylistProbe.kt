package com.ljyh.mei.parasite

import com.ljyh.mei.data.model.SongSourceIdentity
import com.ljyh.mei.data.session.SessionChangedException
import com.ljyh.mei.data.session.SessionIdentity
import com.ljyh.mei.di.RetrofitModule
import java.util.concurrent.Executor
import kotlinx.coroutines.runBlocking

/** Separate synthetic transport: never bind or mutate the official request/session graph. */
internal object HostCloudPlaylistProbe {
    fun run() = runBlocking {
        val requests = mutableListOf<Pair<String, Map<String, String>>>()
        var respond: () -> String = { """{"code":200}""" }
        val transport = object : HostRequestBackend {
            override fun sessionIdentity() = SessionIdentity(7, true, false)
            override fun open(path: String, parameters: Map<String, String>): HostPendingRequest {
                requests += path to parameters
                return object : HostPendingRequest {
                    override fun execute() = respond()
                    override fun cancel() = Unit
                    override fun close() = Unit
                }
            }
        }
        val bridge = HostRequestBridge(HostSessionBridge()).apply { bind(transport) }
        val retrofit = RetrofitModule.provideRetrofit(HostCallFactory(bridge, Executor { it.run() }))
        val tracks = HostPlaylistTracksBackend(retrofit, bridge.sessions)
        val owner = bridge.sessions.snapshot()
        val cloud = SongSourceIdentity(999, 88, 7, 17)
        for (op in listOf("add", "del")) {
            check(tracks.modifySources(op, 10, listOf(cloud, SongSourceIdentity(2), cloud), owner).code == 200)
            check(requests.last() == ("v1/playlist/manipulate/tracks" to
                (mapOf("op" to op, "pid" to "10", "trackIds" to "[\"999\",\"2\"]") +
                    if (op == "add") mapOf("reverse" to "true") else emptyMap())))
        }
        check(runCatching { tracks.modifySources("add", 10, listOf(cloud.copy(accountId = 8)), owner) }.isFailure)
        check(requests.size == 2)
        bridge.sessions.setRecoveryRequired(true)
        check(runCatching { tracks.modifySources("add", 10, listOf(cloud), owner) }.exceptionOrNull() is SessionChangedException)
        check(requests.size == 2)
        bridge.sessions.setRecoveryRequired(false)
        respond = { """{"code":502}""" }
        check(tracks.modifySources("add", 10, listOf(cloud), owner).code == 502)
        respond = { bridge.sessions.setRecoveryRequired(true); """{"code":200}""" }
        check(runCatching { tracks.modifySources("add", 10, listOf(cloud), owner) }.isFailure)
        check(requests.size == 4)
        HostRuntimeProbe.report("cloud_playlist_closed_passed audio_ids=true body_tag_preserved=true " +
            "recovery_guard=true business_outcomes=true synthetic_only=true official_mutations=0")
    }
}

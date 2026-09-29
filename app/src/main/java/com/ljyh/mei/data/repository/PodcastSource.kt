package com.ljyh.mei.data.repository

import com.ljyh.mei.data.model.melox.Podcast
import com.ljyh.mei.data.model.melox.PodcastDetail
import com.ljyh.mei.data.model.melox.PodcastHome
import com.ljyh.mei.data.model.melox.PodcastPage
import com.ljyh.mei.data.model.melox.PodcastProgramPage
import com.ljyh.mei.parasite.HostSessionStamp

interface PodcastSource {
    suspend fun podcastHome(session: HostSessionStamp): PodcastHome
    suspend fun podcasts(session: HostSessionStamp, categoryId: Long, offset: Int = 0, limit: Int = 30): List<Podcast>
    suspend fun podcastDetail(session: HostSessionStamp, id: Long, offset: Int = 0, limit: Int = 50): PodcastDetail
    suspend fun podcastPrograms(session: HostSessionStamp, id: Long, offset: Int = 0, limit: Int = 50): PodcastProgramPage
    suspend fun subscribedPodcasts(session: HostSessionStamp, offset: Int = 0, limit: Int = 50): PodcastPage
    suspend fun setPodcastSubscribed(session: HostSessionStamp, id: Long, subscribed: Boolean)
}

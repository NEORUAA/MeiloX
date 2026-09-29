package com.ljyh.mei.data.repository

import com.ljyh.mei.data.model.melox.Podcast
import com.ljyh.mei.data.model.melox.PodcastDetail
import com.ljyh.mei.data.model.melox.PodcastHome
import com.ljyh.mei.data.model.melox.PodcastPage
import com.ljyh.mei.data.model.melox.PodcastProgramPage
import com.ljyh.mei.data.session.SessionStamp

interface PodcastSource {
    suspend fun podcastHome(session: SessionStamp): PodcastHome
    suspend fun podcasts(session: SessionStamp, categoryId: Long, offset: Int = 0, limit: Int = 30): List<Podcast>
    suspend fun podcastDetail(session: SessionStamp, id: Long, offset: Int = 0, limit: Int = 50): PodcastDetail
    suspend fun podcastPrograms(session: SessionStamp, id: Long, offset: Int = 0, limit: Int = 50): PodcastProgramPage
    suspend fun subscribedPodcasts(session: SessionStamp, offset: Int = 0, limit: Int = 50): PodcastPage
    suspend fun setPodcastSubscribed(session: SessionStamp, id: Long, subscribed: Boolean)
}

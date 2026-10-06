package com.ljyh.mei.ui.screen.artist

import com.google.gson.Gson
import com.ljyh.mei.data.model.api.ArtistAlbum
import com.ljyh.mei.data.model.api.ArtistSong
import com.ljyh.mei.data.network.Resource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtistFollowStateTest {
    @Test
    fun songsFollowStateTakesPriorityOverAlbumFollowState() {
        assertEquals(
            true,
            resolveArtistFollowed(
                "42",
                songs("""{"id":42,"followed":true}"""),
                albums("""{"id":42,"followed":false}"""),
                null,
            ),
        )
        assertEquals(
            false,
            resolveArtistFollowed(
                "42",
                songs("""{"id":42,"followed":false}"""),
                albums("""{"id":42,"followed":true}"""),
                null,
            ),
        )
    }

    @Test
    fun omittedFollowFieldStaysUnknownAndAllowsAlbumFallback() {
        val songResponse = songs("""{"id":42}""")
        val albumResponse = albums("""{"id":42}""")

        assertNull(songResponse.data.artist.followed)
        assertNull(albumResponse.data.artist.followed)
        assertNull(resolveArtistFollowed("42", songResponse, albumResponse, null))
        assertEquals(
            true,
            resolveArtistFollowed(
                "42",
                songResponse,
                albums("""{"id":42,"followed":true}"""),
                null,
            ),
        )
        assertEquals(
            false,
            resolveArtistFollowed(
                "42",
                songResponse,
                albums("""{"id":42,"followed":false}"""),
                null,
            ),
        )
    }

    @Test
    fun explicitNullFollowFieldIsUnknown() {
        val songResponse = songs("""{"id":42,"followed":null}""")
        val albumResponse = albums("""{"id":42,"followed":null}""")

        assertNull(songResponse.data.artist.followed)
        assertNull(albumResponse.data.artist.followed)
        assertNull(resolveArtistFollowed("42", songResponse, albumResponse, null))
    }

    @Test
    fun absentArtistIsUnknownAndAllowsMatchingAlbumFallback() {
        assertNull(resolveArtistFollowed("42", songs(null), albums(null), null))
        assertEquals(
            true,
            resolveArtistFollowed(
                "42",
                songs(null),
                albums("""{"id":42,"followed":true}"""),
                null,
            ),
        )
    }

    @Test
    fun responsesForOtherArtistsCannotPublishFollowState() {
        assertNull(
            resolveArtistFollowed(
                "42",
                songs("""{"id":41,"followed":true}"""),
                albums("""{"id":43,"followed":false}"""),
                null,
            ),
        )
        assertEquals(
            false,
            resolveArtistFollowed(
                "42",
                songs("""{"id":41,"followed":true}"""),
                albums("""{"id":42,"followed":false}"""),
                null,
            ),
        )
        assertNull(
            resolveArtistFollowed(
                "42",
                songs("""{"id":42}"""),
                albums("""{"id":43,"followed":true}"""),
                null,
            ),
        )
    }

    @Test
    fun successfulFollowAndUnfollowOverrideStaleServerResponses() {
        for (followed in listOf(true, false)) {
            assertEquals(
                followed,
                resolveArtistFollowed(
                    "42",
                    songs("""{"id":42,"followed":${!followed}}"""),
                    albums("""{"id":42,"followed":${!followed}}"""),
                    followed,
                ),
            )
        }
    }

    @Test
    fun confirmedFollowStateSurvivesUnavailableServerReads() {
        for (followed in listOf(true, false)) {
            assertEquals(
                followed,
                resolveArtistFollowed(
                    "42",
                    Resource.Loading,
                    Resource.Error("Albums failed"),
                    followed,
                ),
            )
            assertEquals(
                followed,
                resolveArtistFollowed("42", songs(null), albums(null), followed),
            )
        }
    }

    @Test
    fun loadingOrFailedReadsDoNotInventAnUnfollowedState() {
        assertNull(resolveArtistFollowed("42", Resource.Loading, Resource.Loading, null))
        assertNull(
            resolveArtistFollowed(
                "42",
                Resource.Error("Songs failed"),
                Resource.Error("Albums failed"),
                null,
            ),
        )
        assertEquals(
            true,
            resolveArtistFollowed(
                "42",
                Resource.Error("Songs failed"),
                albums("""{"id":42,"followed":true}"""),
                null,
            ),
        )
    }

    private fun songs(artist: String?): Resource.Success<ArtistSong> = Resource.Success(
        Gson().fromJson(response(artist, "hotSongs"), ArtistSong::class.java),
    )

    private fun albums(artist: String?): Resource.Success<ArtistAlbum> = Resource.Success(
        Gson().fromJson(response(artist, "hotAlbums"), ArtistAlbum::class.java),
    )

    private fun response(artist: String?, itemsField: String): String {
        val artistField = artist?.let { "\"artist\":$it," }.orEmpty()
        return """{$artistField"$itemsField":[],"more":false,"code":200}"""
    }
}

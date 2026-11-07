package com.ljyh.mei.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchRepositoryTest {
    @Test
    fun sharedSearchDiscoveryPreservesOpaqueOwnershipAndProviderCategories() {
        val discovery = com.ljyh.mei.ui.screen.search.parseSearchDiscovery("catalog.plugin", com.google.gson.JsonParser.parseString("""
            {"recommendations":[{"id":"playlist/opaque/9223372036854775806","name":"Recommended","artworkUrl":null}],
             "categories":[{"id":"genre/official","title":"Official","kind":"playlist"}]}
        """.trimIndent()).asJsonObject)
        assertEquals(com.ljyh.mei.source.api.PlaylistRef("catalog.plugin", "playlist/opaque/9223372036854775806"), discovery.recommendations.single().ref)
        assertEquals("genre/official", discovery.categories.single().id)
        assertEquals("Official", discovery.categories.single().title)
    }

    @Test(expected = com.ljyh.mei.source.api.SourceException::class)
    fun sharedSearchDiscoveryRejectsLossyNumericIdentities() {
        com.ljyh.mei.ui.screen.search.parseSearchDiscovery("catalog.plugin", com.google.gson.JsonParser.parseString("""
            {"recommendations":[{"id":9223372036854775806,"name":"Unsafe identity"}],"categories":[]}
        """).asJsonObject)
    }

}

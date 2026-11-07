package com.ljyh.mei.playback

import org.junit.Assert.*
import org.junit.Test

class PrivateFmModesTest {
    @Test fun recommendationParametersRemainSourceNeutralAndPreserveOpaqueIds() {
        for (mode in PrivateFmMode.entries) {
            val parameters = mode.parameters(listOf("track/alpha", "9223372036854775806", "track/alpha", ""),
                PrivateFmScene("scene/focus", "Focus", "opaque-state"))
            assertEquals(mode.name.lowercase(), parameters["mode"])
            assertEquals("[\"track/alpha\",\"9223372036854775806\"]", parameters["recentTrackIds"])
            if (mode == PrivateFmMode.SCENE) {
                assertEquals("scene/focus", parameters["sceneCode"])
                assertEquals("opaque-state", parameters["cascadeMode"])
            } else assertFalse(parameters.containsKey("sceneCode"))
            assertFalse(parameters.containsKey("entranceType"))
        }
    }

    @Test fun scenesKeepOpaqueSourceIdentifiersAcrossPreferenceRoundTrips() {
        val scene = PrivateFmScene("scene:focus/新", "Example focus", "opaque-cascade")
        assertEquals(scene, PrivateFmScene.decode(scene.encode()))
        assertNull(PrivateFmScene.decode("not json"))
        assertNull(PrivateFmScene.decode("{\"code\":\"scene\"}"))
    }
}

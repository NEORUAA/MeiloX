package com.ljyh.mei.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlymeSystemTest {
    @Test fun recognizesFlymeDisplayNamesRegardlessOfCase() {
        assertTrue(isFlymeRom("Flyme OS 8.0.5.0A", "", ""))
        assertTrue(isFlymeRom("flyme 12.1", "", ""))
        assertTrue(isFlymeRom(" FLYME 10", "", ""))
    }

    @Test fun recognizesTheReferenceApksRomPropertiesWithAnUnbrandedDisplay() {
        assertTrue(isFlymeRom("UP1A.231005.007", "true", ""))
        assertTrue(isFlymeRom("UP1A.231005.007", "", "true"))
    }

    @Test fun otherRomsAndADeviceBrandAloneAreNotFlyme() {
        assertFalse(isFlymeRom("UP1A.231005.007", "", ""))
        assertFalse(isFlymeRom("Meizu AOSP", "", ""))
        assertFalse(isFlymeRom("OneUI", "", ""))
    }
}

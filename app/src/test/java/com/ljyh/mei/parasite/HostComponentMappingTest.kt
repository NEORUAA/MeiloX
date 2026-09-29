package com.ljyh.mei.parasite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostComponentMappingTest {
    @Test fun onlyExplicitModuleComponentsInTheHostAreMapped() {
        assertEquals(HostComponentMapping.ACTIVITY,
            HostComponentMapping.target(HostIdentity.PACKAGE, HostComponentMapping.MODULE_ACTIVITY))
        assertEquals(HostComponentMapping.SERVICE,
            HostComponentMapping.target(HostIdentity.PACKAGE, HostComponentMapping.MODULE_SERVICE))
        assertNull(HostComponentMapping.target(HostIdentity.PACKAGE, HostComponentMapping.ACTIVITY))
        assertNull(HostComponentMapping.target(HostIdentity.PACKAGE, "com.netease.cloudmusic.service.PlayService"))
        assertNull(HostComponentMapping.target("com.neoruaa.meilox.parasite", HostComponentMapping.MODULE_ACTIVITY))
        assertNull(HostComponentMapping.target("another.package", HostComponentMapping.MODULE_SERVICE))
        assertNull(HostComponentMapping.target(null, null))
    }

    @Test fun onlyAppResourceIdsInTheHostUseTheModuleResourcePackage() {
        assertTrue(HostComponentMapping.usesModuleResource(HostIdentity.PACKAGE, 0x7f080123))
        assertFalse(HostComponentMapping.usesModuleResource(HostIdentity.PACKAGE, 0x01080001))
        assertFalse(HostComponentMapping.usesModuleResource(HostIdentity.PACKAGE, 0))
        assertFalse(HostComponentMapping.usesModuleResource("another.package", 0x7f080123))
    }
}

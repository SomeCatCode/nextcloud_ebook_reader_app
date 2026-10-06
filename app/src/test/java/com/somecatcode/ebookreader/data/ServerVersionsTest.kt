package com.somecatcode.ebookreader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerVersionsTest {

    @Test
    fun comparesNumericallyAndRanksPreReleasesFirst() {
        assertTrue(ServerVersions.compare("0.10.0", "0.9.9") > 0)
        assertEquals(0, ServerVersions.compare("0.8", "0.8.0"))
        assertTrue(ServerVersions.compare("0.8.0-rc.1", "0.8.0") < 0)
        assertTrue(ServerVersions.compare("v1.0.0", "0.99.0") > 0)
    }

    @Test
    fun unknownVersionMeansOlderThanTheFirstReportingVersion() {
        assertTrue(ServerVersions.isAtLeast(null, "0.7.0"))
        assertFalse(ServerVersions.isAtLeast(null, "0.8.0"))
        assertTrue(ServerVersions.isOutdated(null))
        assertTrue(ServerVersions.isOutdated("0.7.0"))
        assertFalse(ServerVersions.isOutdated("0.8.0"))
        assertFalse(ServerFeature.SHARING.availableOn("0.7.0"))
        assertTrue(ServerFeature.ANNOTATIONS.availableOn("0.7.0"))
    }
}

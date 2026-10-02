package com.somecatcode.ebookreader.data.download

import com.somecatcode.ebookreader.data.download.ResumePolicy.ResponseAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumePolicyTest {

    @Test
    fun freshDownloadHasNoRange() {
        val plan = ResumePolicy.plan(partSize = 0, savedEtag = null, expectedTotal = 1000)
        assertNull(plan.rangeFrom)
        assertNull(plan.ifRange)
        assertEquals(false, plan.discardPart)
    }

    @Test
    fun partialFileWithStrongEtagResumesWithIfRange() {
        val plan = ResumePolicy.plan(partSize = 400, savedEtag = "\"abc\"", expectedTotal = 1000)
        assertEquals(400L, plan.rangeFrom)
        assertEquals("\"abc\"", plan.ifRange)
        assertEquals(false, plan.discardPart)
    }

    @Test
    fun partialFileWithoutUsableEtagIsDiscarded() {
        assertTrue(ResumePolicy.plan(400, null, 1000).discardPart)
        assertTrue(ResumePolicy.plan(400, "W/\"weak\"", 1000).discardPart)
    }

    @Test
    fun partLargerThanExpectedRestarts() {
        val plan = ResumePolicy.plan(2000, "\"abc\"", 1000)
        assertNull(plan.rangeFrom)
        assertTrue(plan.discardPart)
    }

    @Test
    fun responseInterpretation() {
        assertEquals(ResponseAction.Append, ResumePolicy.interpret(206, 400, 400, 400, 1000))
        assertEquals(ResponseAction.Restart, ResumePolicy.interpret(206, 400, 0, 400, 1000))
        assertEquals(ResponseAction.Restart, ResumePolicy.interpret(200, 400, null, 400, 1000))
        assertEquals(ResponseAction.AlreadyComplete, ResumePolicy.interpret(416, 1000, null, 1000, 1000))
        assertEquals(ResponseAction.Restart, ResumePolicy.interpret(416, 900, null, 900, 1000))
        assertEquals(ResponseAction.Fail(true), ResumePolicy.interpret(503, null, null, 0, 1000))
        assertEquals(ResponseAction.Fail(false), ResumePolicy.interpret(404, null, null, 0, 1000))
    }

    @Test
    fun parsesContentRange() {
        assertEquals(100L to 1000L, ResumePolicy.parseContentRange("bytes 100-999/1000"))
        assertEquals(0L to null, ResumePolicy.parseContentRange("bytes 0-9/*"))
        assertNull(ResumePolicy.parseContentRange("garbage"))
        assertNull(ResumePolicy.parseContentRange(null))
    }
}

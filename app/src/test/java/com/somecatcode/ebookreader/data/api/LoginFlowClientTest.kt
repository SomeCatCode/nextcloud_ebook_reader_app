package com.somecatcode.ebookreader.data.api

import com.somecatcode.ebookreader.data.RoutingDispatcher
import com.somecatcode.ebookreader.data.serverWith
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LoginFlowClientTest {

    private val dispatcher = RoutingDispatcher()
    private val server = serverWith(dispatcher)
    private val client = LoginFlowClientImpl(OkHttpClient(), allowInsecure = true)

    @After
    fun tearDown() = server.shutdown()

    private fun startBody() =
        """{"poll":{"token":"tok123","endpoint":"${server.url("/index.php/login/v2/poll")}"},"login":"${server.url("/index.php/login/v2/flow/abc")}"}"""

    @Test
    fun startPostsToLoginV2AndNormalisesInput() = runBlocking {
        dispatcher.on("POST", "/index.php/login/v2") { MockResponse().setBody(startBody()) }
        val start = client.start(server.url("/").toString() + "index.php/")
        assertEquals("tok123", start.poll.token)
        assertTrue(start.login.endsWith("/flow/abc"))
        val req = dispatcher.requests.single()
        assertEquals("POST", req.method)
        assertEquals("/index.php/login/v2", req.path)
        assertTrue(req.getHeader("User-Agent")!!.contains("E-Book Reader"))
    }

    @Test
    fun pollOnceReturnsNullOn404ThenResult() = runBlocking {
        var calls = 0
        dispatcher.on("POST", "/login/v2/poll") {
            if (++calls < 3) {
                MockResponse().setResponseCode(404)
            } else {
                MockResponse().setBody("""{"server":"https://cloud.example.org","loginName":"alice","appPassword":"pw-1"}""")
            }
        }
        val poll = LoginFlowPoll("tok123", server.url("/index.php/login/v2/poll").toString())
        assertNull(client.pollOnce(poll))
        assertNull(client.pollOnce(poll))
        val result = client.pollOnce(poll)!!
        assertEquals("alice", result.loginName)
        assertEquals("pw-1", result.appPassword)
        assertEquals("token=tok123", dispatcher.requests.last().body.readUtf8())
    }

    @Test
    fun awaitLoginPollsUntilSuccess() = runTest {
        var calls = 0
        dispatcher.on("POST", "/login/v2/poll") {
            if (++calls < 4) {
                MockResponse().setResponseCode(404)
            } else {
                MockResponse().setBody("""{"server":"https://c","loginName":"a","appPassword":"p"}""")
            }
        }
        val sleeps = mutableListOf<Long>()
        val poll = LoginFlowPoll("t", server.url("/index.php/login/v2/poll").toString())
        val result = client.awaitLogin(poll, intervalMs = 2000, timeoutMs = 60_000, sleep = { sleeps += it })
        assertEquals("p", result!!.appPassword)
        assertEquals(listOf(2000L, 2000L, 2000L), sleeps)
    }

    @Test
    fun awaitLoginTimesOut() = runTest {
        dispatcher.on("POST", "/login/v2/poll") { MockResponse().setResponseCode(404) }
        val poll = LoginFlowPoll("t", server.url("/index.php/login/v2/poll").toString())
        var slept = 0L
        assertNull(client.awaitLogin(poll, intervalMs = 2000, timeoutMs = 10_000, sleep = { slept += it }))
        assertTrue(slept >= 10_000)
    }

    @Test
    fun awaitLoginCanBeCancelled() = runBlocking {
        dispatcher.on("POST", "/login/v2/poll") { MockResponse().setResponseCode(404) }
        val poll = LoginFlowPoll("t", server.url("/index.php/login/v2/poll").toString())
        val job = async { client.awaitLogin(poll, intervalMs = 50, timeoutMs = 60_000) }
        kotlinx.coroutines.delay(150)
        job.cancel()
        try {
            job.await()
            fail()
        } catch (e: CancellationException) {
            // expected
        }
    }

    @Test
    fun normalisationRules() {
        fun n(s: String) = LoginFlowClientImpl.normalizeServerInput(s)
        assertEquals("https://cloud.example.org", n("cloud.example.org"))
        assertEquals("https://cloud.example.org", n("  https://cloud.example.org/  "))
        assertEquals("https://cloud.example.org/nextcloud", n("https://cloud.example.org/nextcloud/index.php/"))
        assertEquals("https://cloud.example.org", n("https://cloud.example.org/login"))
        assertEquals("https://cloud.example.org:8443", n("cloud.example.org:8443"))
        try {
            n("http://cloud.example.org")
            fail("plain http must be rejected")
        } catch (e: ApiException.BadRequest) {
            assertTrue(e.message!!.contains("HTTPS"))
        }
    }

    @Test
    fun httpsIsRequiredForStartedFlow() = runBlocking {
        val strict = LoginFlowClientImpl(OkHttpClient())
        try {
            strict.start(server.url("/").toString())
            fail()
        } catch (e: ApiException.BadRequest) {
            // plain http server rejected
        }
    }
}

package com.rembrowser.rembrowse.engine

import coil3.network.NetworkHeaders
import coil3.network.NetworkRequest
import coil3.network.NetworkRequestBody
import coil3.network.NetworkResponse
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.util.TreeMap
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.ByteString.Companion.encodeUtf8
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.mozilla.geckoview.GeckoWebExecutor
import org.mozilla.geckoview.WebRequest
import org.mozilla.geckoview.WebResponse

/**
 * The network client that fetches site and add-on icons through the engine, against a fake
 * engine: what it sends (GET only, anonymous, no redirects followed by the engine), where it lets
 * a redirect go (web addresses only), and its size cap and time-out.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GeckoImageNetworkClientTest {

    private class Sent(val url: String, val method: String, val flags: Int, val headers: Map<String, String>)

    /** An engine that answers with [answer] and remembers what it was sent. */
    private class FakeEngine(private val answer: suspend (WebRequest) -> WebResponse) : EngineFetch {
        val sent = mutableListOf<Sent>()

        override suspend fun fetch(request: WebRequest, flags: Int): WebResponse {
            sent += Sent(request.uri, request.method, flags, TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER).apply { putAll(request.headers) })
            return answer(request)
        }
    }

    /** A body that remembers being closed. */
    private class Body(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false

        override fun close() {
            closed = true
            super.close()
        }
    }

    private fun response(
        url: String,
        code: Int = 200,
        body: InputStream? = Body(PNG),
        headers: Map<String, String> = mapOf("Content-Type" to "image/png"),
    ): WebResponse = WebResponse.Builder(url)
        .statusCode(code)
        .apply { headers.forEach { (name, value) -> header(name, value) } }
        .apply { body?.let { body(it) } }
        .build()

    private fun TestScope.client(
        engine: EngineFetch,
        maxBytes: Long = GeckoImageNetworkClient.MAX_ICON_BYTES,
        nanoTime: () -> Long = { testScheduler.currentTime * 1_000_000 },
    ) = GeckoImageNetworkClient(
        engine = engine,
        maxBytes = maxBytes,
        ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        nanoTime = nanoTime,
        currentTimeMillis = { testScheduler.currentTime },
    )

    /** What Coil gets: the response, with its body read in full. */
    private class Received(val response: NetworkResponse, val bytes: ByteArray)

    private suspend fun GeckoImageNetworkClient.get(url: String, request: NetworkRequest = NetworkRequest(url)): Received =
        executeRequest(request) { response ->
            val buffer = Buffer()
            response.body?.writeTo(buffer)
            Received(response, buffer.readByteArray())
        }

    private suspend inline fun <reified E : Throwable> failsWith(block: () -> Unit): E {
        try {
            block()
        } catch (e: Throwable) {
            if (e is E) return e
            throw AssertionError("Expected ${E::class.simpleName}, got $e", e)
        }
        fail("Expected ${E::class.simpleName}")
        throw AssertionError()
    }

    @Test
    fun anIconIsAnAnonymousGetThatTheEngineDoesNotRedirect() = runTest {
        val engine = FakeEngine { response(it.uri) }
        val received = client(engine).get("https://site.test/favicon.ico")

        val sent = engine.sent.single()
        assertEquals("https://site.test/favicon.ico", sent.url)
        assertEquals("GET", sent.method)
        assertEquals(GeckoWebExecutor.FETCH_FLAGS_ANONYMOUS or GeckoWebExecutor.FETCH_FLAGS_NO_REDIRECTS, sent.flags)
        assertTrue("Asks for a picture", sent.headers.getValue("Accept").startsWith("image/"))
        assertEquals(200, received.response.code)
        assertEquals("image/png", received.response.headers["content-type"])
        assertArrayEquals(PNG, received.bytes)
    }

    @Test
    fun coilsOwnHeadersAreSentToo() = runTest {
        val engine = FakeEngine { response(it.uri) }
        val headers = NetworkHeaders.Builder().set("Accept", "image/png").set("If-None-Match", "\"v1\"").build()
        client(engine).get("https://site.test/favicon.ico", NetworkRequest("https://site.test/favicon.ico", headers = headers))
        assertEquals("image/png", engine.sent.single().headers["Accept"])
        assertEquals("\"v1\"", engine.sent.single().headers["If-None-Match"])
    }

    @Test
    fun anyOtherMethodOrABodyIsRefusedBeforeAnythingIsSent() = runTest {
        val engine = FakeEngine { response(it.uri) }
        val client = client(engine)
        for (request in listOf(
            NetworkRequest("https://site.test/favicon.ico", method = "POST"),
            NetworkRequest("https://site.test/favicon.ico", method = "PUT"),
            NetworkRequest("https://site.test/favicon.ico", method = "GET", body = NetworkRequestBody("x".encodeUtf8())),
        )) {
            failsWith<IOException> { client.get(request.url, request) }
        }
        assertEquals(0, engine.sent.size)
    }

    @Test
    fun onlyWebAddressesAreFetched() = runTest {
        val engine = FakeEngine { response(it.uri) }
        val client = client(engine)
        for (url in listOf("file:///data/data/x.png", "content://media/1", "ftp://site.test/i.png", "blob:https://site.test/1", "about:blank")) {
            failsWith<IOException> { client.get(url) }
        }
        assertEquals(0, engine.sent.size)
    }

    @Test
    fun aRedirectToAnotherWebAddressIsFollowedAnonymouslyAndItsBodyClosed() = runTest {
        val redirectBody = Body(ByteArray(0))
        val engine = FakeEngine {
            when (it.uri) {
                "https://site.test/favicon.ico" -> response(it.uri, 301, redirectBody, mapOf("location" to "/static/icon.png"))
                "https://site.test/static/icon.png" -> response(it.uri, 302, null, mapOf("Location" to "http://cdn.test/icon.png"))
                else -> response(it.uri)
            }
        }
        val received = client(engine).get("https://site.test/favicon.ico")
        assertEquals(
            listOf("https://site.test/favicon.ico", "https://site.test/static/icon.png", "http://cdn.test/icon.png"),
            engine.sent.map { it.url }
        )
        assertTrue(engine.sent.all { it.flags == GeckoImageNetworkClient.FETCH_FLAGS && it.method == "GET" })
        assertTrue(redirectBody.closed)
        assertArrayEquals(PNG, received.bytes)
    }

    @Test
    fun aRedirectAnywhereButAWebAddressFailsTheIcon() = runTest {
        for (location in listOf(
            "javascript:alert(1)",
            "file:///etc/hosts",
            "data:image/png;base64,iVBORw0KGgo=",
            "ftp://site.test/icon.png",
            "content://com.example/icon",
            "about:config",
        )) {
            val body = Body(ByteArray(0))
            val engine = FakeEngine { response(it.uri, 302, body, mapOf("Location" to location)) }
            failsWith<IOException> { client(engine).get("https://site.test/favicon.ico") }
            assertEquals(location, 1, engine.sent.size)
            assertTrue(location, body.closed)
        }
    }

    @Test
    fun aRedirectWithoutALocationIsTheAnswer() = runTest {
        val engine = FakeEngine { response(it.uri, 302, Body(ByteArray(0)), emptyMap()) }
        assertEquals(302, client(engine).get("https://site.test/favicon.ico").response.code)
    }

    @Test
    fun tooManyRedirectsFail() = runTest {
        var hop = 0
        val engine = FakeEngine { response(it.uri, 302, null, mapOf("Location" to "/hop${++hop}")) }
        failsWith<IOException> { client(engine).get("https://site.test/favicon.ico") }
        assertEquals(GeckoImageNetworkClient.MAX_REDIRECTS + 1, engine.sent.size)
    }

    @Test
    fun aMissingIconIsHandedToCoilAsItIs() = runTest {
        // Coil decides from the status whether to keep the answer in its disk cache
        val engine = FakeEngine { response(it.uri, 404, Body("Not Found".toByteArray()), mapOf("Content-Type" to "text/html")) }
        val received = client(engine).get("https://site.test/favicon.ico")
        assertEquals(404, received.response.code)
        assertEquals("Not Found", received.bytes.decodeToString())
    }

    @Test
    fun anIconSaidToBeTooLargeIsRefusedUnread() = runTest {
        val body = Body(ByteArray(32))
        val engine = FakeEngine { response(it.uri, body = body, headers = mapOf("Content-Length" to "17")) }
        var handedOver = false
        failsWith<IOException> { client(engine, maxBytes = 16).executeRequest(NetworkRequest("https://site.test/i.png")) { handedOver = true } }
        assertTrue("Coil never saw it", !handedOver)
        assertTrue(body.closed)
        assertEquals("Nothing was read", 32, body.available())
    }

    @Test
    fun anIconLargerThanTheCapFailsWhileItIsRead() = runTest {
        val tooLarge = FakeEngine { response(it.uri, body = Body(ByteArray(17)), headers = emptyMap()) }
        failsWith<IOException> { client(tooLarge, maxBytes = 16).get("https://site.test/i.png") }

        val atTheCap = FakeEngine { response(it.uri, body = Body(ByteArray(16)), headers = emptyMap()) }
        assertEquals(16, client(atTheCap, maxBytes = 16).get("https://site.test/i.png").bytes.size)
    }

    @Test
    fun noAnswerWithinTheTimeOutFails() = runTest {
        val engine = FakeEngine { awaitCancellation() }
        failsWith<SocketTimeoutException> { client(engine).get("https://site.test/favicon.ico") }
        assertEquals(GeckoImageNetworkClient.TIMEOUT_MILLIS, testScheduler.currentTime)
    }

    @Test
    fun aBodyStillComingAtTheDeadlineFails() = runTest {
        // Each read takes 6 seconds of a 15-second allowance
        var clock = 0L
        val slow = object : InputStream() {
            override fun read(): Int = read(ByteArray(1), 0, 1).let { if (it < 0) -1 else 0 }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                clock += 6_000_000_000L
                b[off] = 1
                return 1
            }
        }
        val engine = FakeEngine { response(it.uri, body = slow, headers = emptyMap()) }
        failsWith<SocketTimeoutException> { client(engine, nanoTime = { clock }).get("https://site.test/favicon.ico") }
    }

    @Test
    fun buildingTheEngineFetchDoesNotAskForTheRuntime() {
        GeckoEngineFetch { throw AssertionError("The runtime was asked for") }
    }

    private companion object {
        val PNG = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)
    }
}

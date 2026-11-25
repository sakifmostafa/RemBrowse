package com.rembrowser.rembrowse.engine

import com.rembrowser.rembrowse.model.SiteNotifications
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.UnknownHostException
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The client that downloads a site notification's large icon must never reach this device or the
 * local network: a page could otherwise have RemBrowse knock on a router's or another app's door.
 * Its guard rests on two facts about OkHttp, pinned here so an OkHttp update that changes either
 * is noticed: a custom [Dns] is asked for every name the client connects to, and an address
 * written in a URL is connected to without asking it (which is why each address is checked
 * before it is requested, the first and every redirect alike).
 *
 * No Robolectric: OkHttp runs as on a plain JVM. The servers here listen on 127.0.0.1 only.
 */
class NotificationIconClientTest {

    /** A [Dns] that answers from [answers] and remembers every name it was asked about. */
    private class FakeDns(private val answers: Map<String, String>) : Dns {
        val asked: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override fun lookup(hostname: String): List<InetAddress> {
            asked += hostname
            val address = answers[hostname] ?: throw UnknownHostException(hostname)
            return listOf(InetAddress.getByName(address))
        }
    }

    /**
     * An HTTP server on 127.0.0.1 that answers every connection with [response] and counts the
     * connections it was given.
     */
    private class LocalServer(private val response: String) : Closeable {
        private val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val port: Int = socket.localPort
        val connections = AtomicInteger()

        init {
            thread(isDaemon = true, name = "local-server-$port") {
                while (!socket.isClosed) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    connections.incrementAndGet()
                    client.use {
                        val reader = it.getInputStream().bufferedReader()
                        // The request's head ends with an empty line
                        var line = reader.readLine()
                        while (!line.isNullOrEmpty()) line = reader.readLine()
                        it.getOutputStream().write(response.toByteArray())
                        it.getOutputStream().flush()
                    }
                }
            }
        }

        override fun close() = socket.close()
    }

    private fun ok(body: String) =
        "HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"

    private fun redirect(location: String) =
        "HTTP/1.1 302 Found\r\nLocation: $location\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"

    // The guard on names

    @Test
    fun aNameThatLeadsOnlyToThisDeviceOrTheLocalNetworkIsNotFound() {
        val dns = PublicAddressesOnly(
            FakeDns(
                mapOf(
                    "loopback.test" to "127.0.0.1",
                    "router.test" to "192.168.1.1",
                    "office.test" to "10.0.0.7",
                    "tailnet.test" to "100.101.102.103",
                    "v6loop.test" to "::1",
                )
            )
        )
        for (name in listOf("loopback.test", "router.test", "office.test", "tailnet.test", "v6loop.test")) {
            assertThrows(name, UnknownHostException::class.java) { dns.lookup(name) }
        }
    }

    @Test
    fun aNameWithBothKindsOfAddressKeepsOnlyThePublicOnes() {
        val names = object : Dns {
            override fun lookup(hostname: String) =
                listOf(InetAddress.getByName("192.168.1.1"), InetAddress.getByName("93.184.215.14"))
        }
        assertEquals(listOf(InetAddress.getByName("93.184.215.14")), PublicAddressesOnly(names).lookup("mixed.test"))
    }

    // Fact 1: OkHttp asks the Dns for every name

    @Test
    fun okHttpAsksTheDnsForTheNameAndNeverConnectsWhenItLeadsToThisDevice() {
        LocalServer(ok("icon")).use { server ->
            val names = FakeDns(mapOf("icon.test" to "127.0.0.1"))
            val client = notificationIconClient(PublicAddressesOnly(names))
            val call = client.newCall(Request.Builder().url("http://icon.test:${server.port}/i.png").build())
            assertThrows(UnknownHostException::class.java) { call.execute().close() }
            assertEquals(listOf("icon.test"), names.asked.distinct())
            assertEquals("Nothing reached the server on this device", 0, server.connections.get())
        }
    }

    @Test
    fun withoutTheGuardTheSameNameReachesTheServerOnThisDevice() {
        // The control for the test above: the name and the server are set up to be reachable
        LocalServer(ok("icon")).use { server ->
            val names = FakeDns(mapOf("icon.test" to "127.0.0.1"))
            val client = notificationIconClient(names)
            val bytes = downloadOver(client, "http://icon.test:${server.port}/i.png")
            assertEquals("icon", bytes?.decodeToString())
            assertEquals(1, server.connections.get())
        }
    }

    // Fact 2: an address in the URL is connected to without asking the Dns

    @Test
    fun okHttpConnectsToAnAddressInTheUrlWithoutAskingTheDns() {
        LocalServer(ok("icon")).use { server ->
            val names = FakeDns(emptyMap())
            val client = notificationIconClient(PublicAddressesOnly(names))
            client.newCall(Request.Builder().url("http://127.0.0.1:${server.port}/i.png").build()).execute().use {
                assertEquals(200, it.code)
            }
            assertEquals("The guard was never asked", emptyList<String>(), names.asked.toList())
            assertEquals(1, server.connections.get())
        }
    }

    @Test
    fun soAnAddressOnThisDeviceOrTheLocalNetworkIsRefusedBeforeItIsRequested() {
        LocalServer(ok("icon")).use { server ->
            val local = listOf(
                "http://127.0.0.1:${server.port}/i.png",
                "http://192.168.1.20/i.png",
                "http://[::1]:${server.port}/i.png",
                "http://2130706433:${server.port}/i.png",
            )
            for (url in local) {
                assertNull(url, SiteNotifications.iconUrlOf(url))
                assertNull(url, downloadOver(notificationIconClient(PublicAddressesOnly(FakeDns(emptyMap()))), url))
            }
            assertEquals(0, server.connections.get())
        }
    }

    // Redirects

    @Test
    fun okHttpFollowsNoRedirectItselfAndARedirectToALocalAddressIsNotFollowed() {
        LocalServer(ok("secret")).use { local ->
            LocalServer(redirect("http://127.0.0.1:${local.port}/x.png")).use { site ->
                // The site is reached by name; the guard is left out so it can be on this machine
                val client = notificationIconClient(FakeDns(mapOf("icon.test" to "127.0.0.1")))
                assertNull(downloadOver(client, "http://icon.test:${site.port}/i.png"))
                assertEquals(1, site.connections.get())
                assertEquals("The redirect's target was never asked", 0, local.connections.get())
            }
        }
    }

    @Test
    fun aRedirectToALocalAddressIsNotRequested() {
        for (location in listOf(
            "http://127.0.0.1/x.png",
            "http://192.168.1.1/x.png",
            "http://[fe80::1]/x.png",
            "http://0x7f.1/x.png",
            "http://localhost/x.png",
        )) {
            val seen = mutableListOf<String>()
            val client = notificationIconClient(PublicAddressesOnly(FakeDns(emptyMap()))).newBuilder()
                .addInterceptor(Interceptor { chain ->
                    seen += chain.request().url.toString()
                    chain.answer(302, location = location)
                })
                .build()
            assertNull(location, downloadOver(client, "https://icon.test/i.png"))
            assertEquals(location, listOf("https://icon.test/i.png"), seen)
        }
    }

    @Test
    fun aRedirectToANameThatLeadsToThisDeviceIsRefusedByTheGuard() {
        LocalServer(ok("secret")).use { local ->
            val names = FakeDns(mapOf("router.test" to "127.0.0.1"))
            val client = notificationIconClient(PublicAddressesOnly(names)).newBuilder()
                .addInterceptor(Interceptor { chain ->
                    // The first answer comes from here; the redirect goes out to the network
                    if (chain.request().url.host == "icon.test") {
                        chain.answer(302, location = "http://router.test:${local.port}/x.png")
                    } else {
                        chain.proceed(chain.request())
                    }
                })
                .build()
            assertThrows(UnknownHostException::class.java) { downloadOver(client, "https://icon.test/i.png") }
            assertEquals(listOf("router.test"), names.asked.distinct())
            assertEquals(0, local.connections.get())
        }
    }

    @Test
    fun aRedirectToAnotherSiteOnTheInternetIsFollowed() {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
        val seen = mutableListOf<String>()
        val client = notificationIconClient(PublicAddressesOnly(FakeDns(emptyMap()))).newBuilder()
            .addInterceptor(Interceptor { chain ->
                seen += chain.request().url.toString()
                when (chain.request().url.host) {
                    "icon.test" -> chain.answer(301, location = "//cdn.test/icons/i.png")
                    else -> chain.answer(200, body = png)
                }
            })
            .build()
        assertArrayEquals(png, downloadOver(client, "https://icon.test/i.png"))
        assertEquals(listOf("https://icon.test/i.png", "https://cdn.test/icons/i.png"), seen)
    }

    @Test
    fun anIconLargerThanTheLimitIsNotRead() {
        val client = notificationIconClient(PublicAddressesOnly(FakeDns(emptyMap()))).newBuilder()
            .addInterceptor(Interceptor { chain -> chain.answer(200, body = ByteArray(SiteNotifications.MAX_ICON_BYTES + 1)) })
            .build()
        assertNull(downloadOver(client, "https://icon.test/i.png"))
    }

    @Test
    fun theRequestCarriesTheGivenUserAgent() {
        var agent: String? = null
        val client = notificationIconClient(PublicAddressesOnly(FakeDns(emptyMap()))).newBuilder()
            .addInterceptor(Interceptor { chain ->
                agent = chain.request().header("User-Agent")
                chain.answer(200, body = byteArrayOf(1))
            })
            .build()
        downloadOver(client, "https://icon.test/i.png")
        assertEquals("RemBrowse-test", agent)
    }

    private fun downloadOver(client: okhttp3.OkHttpClient, url: String): ByteArray? =
        downloadNotificationIcon(client, url, userAgent = "RemBrowse-test")

    private fun Interceptor.Chain.answer(code: Int, location: String? = null, body: ByteArray = ByteArray(0)): Response =
        Response.Builder()
            .request(request())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("Test")
            .apply { location?.let { header("Location", it) } }
            .body(body.toResponseBody("image/png".toMediaType()))
            .build()
}

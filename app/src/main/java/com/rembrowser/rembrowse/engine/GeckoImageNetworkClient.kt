package com.rembrowser.rembrowse.engine

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.annotation.ExperimentalCoilApi
import coil3.network.NetworkClient
import coil3.network.NetworkFetcher
import coil3.network.NetworkHeaders
import coil3.network.NetworkRequest
import coil3.network.NetworkResponse
import coil3.network.NetworkResponseBody
import com.rembrowser.rembrowse.model.SiteNotifications
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.Buffer
import okio.ForwardingSource
import okio.Source
import okio.buffer
import okio.source
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoWebExecutor
import org.mozilla.geckoview.WebRequest
import org.mozilla.geckoview.WebResponse

/**
 * Coil's image loader for site and add-on icons, whose web requests go through the browser engine
 * ([GeckoImageNetworkClient]) rather than an HTTP client of the app's own. Only the engine's
 * network fetcher is added: Coil 3 has none of its own, and the app has no other Coil network
 * artifact. The disk and memory caches are Coil's usual ones.
 *
 * Nothing here asks [engine] for anything until the first icon is fetched (Coil makes the
 * network client on first use too), so building the loader never starts the engine.
 */
@OptIn(ExperimentalCoilApi::class)
internal fun iconImageLoader(context: PlatformContext, engine: EngineFetch): ImageLoader =
    ImageLoader.Builder(context)
        .components {
            add(NetworkFetcher.Factory(networkClient = { GeckoImageNetworkClient(engine) }))
        }
        .build()

/**
 * One request to the browser engine. Outside the tests, [GeckoEngineFetch]: the engine's
 * GeckoWebExecutor.
 */
internal fun interface EngineFetch {
    /**
     * Sends [request] with GeckoWebExecutor's fetch [flags] and returns the response once its
     * headers are in; its body is read afterwards. Throws when the request fails.
     */
    suspend fun fetch(request: WebRequest, flags: Int): WebResponse
}

/**
 * [EngineFetch] over the runtime's [GeckoWebExecutor], the engine's own network stack: a request
 * goes the way the engine's own requests go (an extension's proxy, the engine's name lookup and
 * certificate checks), not around them.
 *
 * [runtime] is asked for at the first request, on the main thread, where GeckoWebExecutor's
 * results arrive; never before. It should hand back the runtime already running, or throw:
 * fetching an icon is no reason to start the engine.
 */
internal class GeckoEngineFetch(private val runtime: () -> GeckoRuntime) : EngineFetch {

    // Main thread only
    private var executor: GeckoWebExecutor? = null

    override suspend fun fetch(request: WebRequest, flags: Int): WebResponse = withContext(Dispatchers.Main.immediate) {
        val executor = executor ?: GeckoWebExecutor(runtime()).also { executor = it }
        suspendCancellableCoroutine { continuation ->
            executor.fetch(request, flags).accept({ response ->
                if (response == null) {
                    continuation.resumeWithException(IOException("The engine gave no response"))
                } else {
                    // A response that arrives after its request was given up is closed at once
                    continuation.resume(response) { _, late, _ -> late.body?.close() }
                }
            }, { error ->
                continuation.resumeWithException(error ?: IOException("The engine's request failed"))
            })
        }
    }
}

/**
 * Coil's network client over the browser engine ([engine]), for site and add-on icons.
 *
 * - **GET only.** Any other method, or a request with a body, is refused before anything is sent.
 * - **Anonymous.** Sent with [GeckoWebExecutor.FETCH_FLAGS_ANONYMOUS]: the engine sends no cookies
 *   or other data of the user's with it, so the site can't tie it to the user's session.
 * - **Web addresses only.** The engine follows no redirect itself
 *   ([GeckoWebExecutor.FETCH_FLAGS_NO_REDIRECTS]); this client follows up to [MAX_REDIRECTS], each
 *   only to another http or https address. A redirect anywhere else fails the icon.
 * - **No way in to the local network.** A site's icon may not redirect to this device or the
 *   local network, nor to a port pages may not use: a site could otherwise have the browser send
 *   a request of its choosing to a router or to another app (see [refuseRedirect]). An icon asked
 *   of the local network itself may redirect as it likes.
 * - **At most [maxBytes]**, refused from the Content-Length when it says more, and otherwise cut
 *   off while reading.
 * - **At most [timeoutMillis] in all**, from the first request to the last byte of the body.
 *
 * The body is handed to Coil as a stream, which Coil writes to its disk cache.
 */
internal class GeckoImageNetworkClient(
    private val engine: EngineFetch,
    private val maxBytes: Long = MAX_ICON_BYTES,
    private val timeoutMillis: Long = TIMEOUT_MILLIS,
    /** Where the body is read; GeckoWebExecutor's body blocks, so never the main thread. */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nanoTime: () -> Long = System::nanoTime,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) : NetworkClient {

    override suspend fun <T> executeRequest(request: NetworkRequest, block: suspend (NetworkResponse) -> T): T {
        if (!request.method.equals("GET", ignoreCase = true) || request.body != null) {
            throw IOException("Only plain GET requests are sent for icons, not ${request.method}")
        }
        val deadline = nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        val asked = request.url.toHttpUrlOrNull() ?: throw IOException("Icons are fetched from web addresses only")
        var url = asked.toString()
        repeat(MAX_REDIRECTS + 1) {
            val requestMillis = currentTimeMillis()
            val webRequest = webRequestOf(url, request.headers)
            val response = withTimeoutOrNull(millisLeft(deadline)) { engine.fetch(webRequest, FETCH_FLAGS) }
                ?: throw SocketTimeoutException("No answer for an icon within $timeoutMillis ms")
            val responseMillis = currentTimeMillis()
            val next = try {
                redirectOf(response, url, asked)
            } catch (e: IOException) {
                response.body?.close()
                throw e
            }
            if (next != null) {
                response.body?.close()
                url = next
                return@repeat
            }
            try {
                val length = header(response, "Content-Length")?.trim()?.toLongOrNull()
                if (length != null && length > maxBytes) throw IOException("An icon of $length bytes is too large")
                response.setReadTimeoutMillis(millisLeft(deadline))
                val body = response.body?.let { NetworkResponseBody(LimitedSource(it.source(), deadline).buffer()) }
                val networkResponse = NetworkResponse(
                    code = response.statusCode,
                    requestMillis = requestMillis,
                    responseMillis = responseMillis,
                    headers = headersOf(response),
                    body = body,
                    delegate = response,
                )
                return withContext(ioDispatcher) { block(networkResponse) }
            } finally {
                response.body?.close()
            }
        }
        throw IOException("More than $MAX_REDIRECTS redirects for an icon")
    }

    private fun webRequestOf(url: String, headers: NetworkHeaders): WebRequest =
        WebRequest.Builder(url)
            .method("GET")
            .apply {
                for ((name, values) in headers.asMap()) {
                    for (value in values) addHeader(name, value)
                }
                if (headers["Accept"] == null) header("Accept", ACCEPT)
            }
            .build()

    /**
     * Where [response] redirects to, resolved against [from]; null when it is not a redirect.
     * Throws when it redirects anywhere but an http or https address, or somewhere the icon
     * first [asked] for may not lead (see [refuseRedirect]).
     */
    private fun redirectOf(response: WebResponse, from: String, asked: HttpUrl): String? {
        if (response.statusCode !in REDIRECT_CODES) return null
        val location = header(response, "Location") ?: return null
        val next = from.toHttpUrlOrNull()?.resolve(location.trim())
            ?: throw IOException("An icon may only be redirected to a web address")
        refuseRedirect(asked, next)?.let { throw IOException(it) }
        return next.toString()
    }

    private fun headersOf(response: WebResponse): NetworkHeaders {
        val builder = NetworkHeaders.Builder()
        for ((name, value) in response.headers) builder.add(name, value)
        return builder.build()
    }

    private fun millisLeft(deadline: Long): Long {
        val left = deadline - nanoTime()
        if (left <= 0) throw SocketTimeoutException("An icon took longer than $timeoutMillis ms")
        return TimeUnit.NANOSECONDS.toMillis(left).coerceAtLeast(1)
    }

    /** [delegate], failing once more than [maxBytes] have come through or [deadline] has passed. */
    private inner class LimitedSource(delegate: Source, private val deadline: Long) : ForwardingSource(delegate) {
        private var received = 0L

        override fun read(sink: Buffer, byteCount: Long): Long {
            if (nanoTime() - deadline >= 0) throw SocketTimeoutException("An icon took longer than $timeoutMillis ms")
            val read = super.read(sink, byteCount)
            if (read > 0) {
                received += read
                if (received > maxBytes) throw IOException("An icon is larger than $maxBytes bytes")
            }
            return read
        }
    }

    companion object {
        /** An icon's largest size; a site's or an add-on's is far smaller. */
        const val MAX_ICON_BYTES = 1024L * 1024L

        /** The longest an icon may take, from the first request to the last byte. */
        const val TIMEOUT_MILLIS = 15_000L

        /** How many redirects an icon request follows. */
        const val MAX_REDIRECTS = 5

        const val FETCH_FLAGS = GeckoWebExecutor.FETCH_FLAGS_ANONYMOUS or GeckoWebExecutor.FETCH_FLAGS_NO_REDIRECTS

        private const val ACCEPT = "image/avif,image/webp,image/png,image/svg+xml,image/*;q=0.8,*/*;q=0.5"

        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

        /**
         * Why the icon first [asked] for may not be redirected to [next], or null when it may.
         *
         * The request is the browser's own, sent from outside any page, so nothing else stops a
         * site's icon from leading to a router's settings or to an app on this device, with a
         * path and query the site chose. So:
         * - Never to a port pages may not use ([SiteNotifications.BLOCKED_PORTS]).
         * - An icon asked of this device or the local network (by address, or `localhost`) may
         *   go anywhere else: the user's own router or server is trusted with that.
         * - A redirect within the site asked (same scheme, host and port) is followed: the
         *   request before it went there already.
         * - Any other must be to https, and not to an address on this device or the local
         *   network ([SiteNotifications.isLocalHost]). The name of a site on the internet can be
         *   made to point at a local address, and names are not looked up here: a lookup would
         *   go around a proxy extension, and the engine could still be given another answer when
         *   it connects. Over https such a name gets nowhere, since the device it points at has
         *   no certificate for it, so no request is sent.
         */
        internal fun refuseRedirect(asked: HttpUrl, next: HttpUrl): String? = when {
            next.port in SiteNotifications.BLOCKED_PORTS -> "An icon may not be redirected to port ${next.port}"
            SiteNotifications.isLocalHost(asked.host) -> null
            next.scheme == asked.scheme && next.host == asked.host && next.port == asked.port -> null
            SiteNotifications.isLocalHost(next.host) ->
                "A site's icon may not be redirected to this device or the local network"
            !next.isHttps -> "A site's icon may only be redirected to another site over https"
            else -> null
        }

        /** [response]'s header [name], whatever case the engine wrote it in. */
        private fun header(response: WebResponse, name: String): String? =
            response.headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    }
}

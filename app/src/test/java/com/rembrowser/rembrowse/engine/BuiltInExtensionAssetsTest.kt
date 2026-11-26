package com.rembrowser.rembrowse.engine

import com.rembrowser.rembrowse.data.ClipboardImages
import com.rembrowser.rembrowse.model.DeclaredSiteIcon
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The popup viewport helper ships as files in the APK's assets and is only checked by Gecko on
 * the device, where a mistake would silently leave popups unfixed. These checks catch a broken
 * package before that: the id the app installs it under, the files the manifest names, and
 * the API wiring between them.
 */
class BuiltInExtensionAssetsTest {

    private val assets = File("src/main/assets")
    private val dir = File(assets, POPUP_VIEWPORT_EXTENSION_URI.removePrefix("resource://android/assets/"))
    private val manifest by lazy { JSONObject(File(dir, "manifest.json").readText()) }

    @Test
    fun installAddress_pointsAtTheBundledFolder() {
        assertTrue(POPUP_VIEWPORT_EXTENSION_URI.startsWith("resource://android/assets/"))
        assertTrue("Gecko needs the folder's trailing slash", POPUP_VIEWPORT_EXTENSION_URI.endsWith("/"))
        assertTrue("Missing ${dir.path}", File(dir, "manifest.json").isFile)
    }

    @Test
    fun manifest_carriesTheIdTheAppInstallsItUnder() {
        assertEquals(2, manifest.getInt("manifest_version"))
        assertEquals(
            POPUP_VIEWPORT_EXTENSION_ID,
            manifest.getJSONObject("browser_specific_settings").getJSONObject("gecko").getString("id")
        )
    }

    @Test
    fun experimentApi_runsInTheExtensionProcessAndItsFilesExist() {
        val api = manifest.getJSONObject("experiment_apis").getJSONObject("popupViewport")
        val child = api.getJSONObject("child")
        assertEquals(listOf("addon_child"), child.getJSONArray("scopes").strings())
        assertEquals(listOf("popupViewport"), child.getJSONArray("paths").getJSONArray(0).strings())

        val schema = JSONArray(File(dir, api.getString("schema")).readText()).getJSONObject(0)
        assertEquals("popupViewport", schema.getString("namespace"))
        val functions = schema.getJSONArray("functions")
        assertEquals("start", functions.getJSONObject(0).getString("name"))

        val script = File(dir, child.getString("script")).readText()
        assertTrue(script.contains("this.popupViewport = class extends ExtensionAPI"))
        assertTrue(script.contains("\"document-element-inserted\""))
        assertTrue(script.contains("width=device-width"))
    }

    @Test
    fun backgroundPage_loadsTheApi_andPassesPopupSizesToTheApp() {
        val scripts = manifest.getJSONObject("background").getJSONArray("scripts").strings()
        val background = scripts.joinToString("\n") { File(dir, it).readText() }
        assertTrue(background.contains("browser.popupViewport.start()"))
        assertTrue(background.contains("browser.popupViewport.onPopupSize.addListener"))
        // The app listens under this name (WebExtension.setMessageDelegate)
        assertTrue(background.contains("const NATIVE_APP = \"${PopupContentSize.NATIVE_APP}\""))
        assertTrue(background.contains("sendNativeMessage(NATIVE_APP, { type: \"popupSize\""))
    }

    @Test
    fun manifest_asksForTheMessagingGeckoViewRequires() {
        val permissions = manifest.getJSONArray("permissions").strings()
        assertTrue("nativeMessaging" in permissions)
        assertTrue("geckoViewAddons" in permissions)
    }

    @Test
    fun schema_declaresTheSizeEvent() {
        val api = manifest.getJSONObject("experiment_apis").getJSONObject("popupViewport")
        val schema = JSONArray(File(dir, api.getString("schema")).readText()).getJSONObject(0)
        val event = schema.getJSONArray("events").getJSONObject(0)
        assertEquals("onPopupSize", event.getString("name"))
        val properties = event.getJSONArray("parameters").getJSONObject(0).getJSONObject("properties")
        assertEquals(setOf("extensionId", "width", "height"), properties.keys().asSequence().toSet())
        val script = File(dir, api.getJSONObject("child").getString("script")).readText()
        assertTrue(script.contains("onPopupSize: new ExtensionCommon.EventManager"))
    }

    private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

    // --- The page menu helper ---

    private val pageMenuDir = File(assets, PAGE_MENU_EXTENSION_URI.removePrefix("resource://android/assets/"))
    private val pageMenuManifest by lazy { JSONObject(File(pageMenuDir, "manifest.json").readText()) }

    @Test
    fun pageMenu_isBundledUnderTheIdTheAppInstallsItUnder() {
        assertTrue(PAGE_MENU_EXTENSION_URI.endsWith("/"))
        assertEquals(2, pageMenuManifest.getInt("manifest_version"))
        assertEquals(
            PAGE_MENU_EXTENSION_ID,
            pageMenuManifest.getJSONObject("browser_specific_settings").getJSONObject("gecko").getString("id")
        )
    }

    @Test
    fun pageMenu_contentScriptRunsInEveryFrame_andMayMessageTheApp() {
        val permissions = pageMenuManifest.getJSONArray("permissions").strings()
        // GeckoView only lets a content script message the app with this permission
        assertTrue("nativeMessagingFromContent" in permissions)
        assertTrue("nativeMessaging" in permissions)
        assertTrue("geckoViewAddons" in permissions)

        val script = pageMenuManifest.getJSONArray("content_scripts").getJSONObject(0)
        assertEquals(listOf("<all_urls>"), script.getJSONArray("matches").strings())
        assertTrue(script.getBoolean("all_frames"))
        val source = script.getJSONArray("js").strings().joinToString("\n") { File(pageMenuDir, it).readText() }
        assertTrue(source.contains("const NATIVE_APP = \"$PAGE_MENU_NATIVE_APP\""))
        assertTrue(source.contains("addEventListener(\"contextmenu\""))
        // Asked for only once the page and GeckoView have both had their say
        assertTrue(source.contains("event.defaultPrevented"))
        assertTrue(source.contains("sendNativeMessage(NATIVE_APP, { type: \"pageMenu\" })"))
    }

    @Test
    fun pageMenu_messagesAreRecognised() {
        assertTrue(GeckoBrowserEngine.isPageMenuMessage(JSONObject().put("type", "pageMenu")))
        assertTrue(!GeckoBrowserEngine.isPageMenuMessage(JSONObject().put("type", "popupSize")))
        assertTrue(!GeckoBrowserEngine.isPageMenuMessage("pageMenu"))
    }

    @Test
    fun pageMenu_scriptOpensCtrlAndMiddleClickedLinksThroughTheApp() {
        val script = pageMenuManifest.getJSONArray("content_scripts").getJSONObject(0)
        val source = script.getJSONArray("js").strings().joinToString("\n") { File(pageMenuDir, it).readText() }
        assertTrue(source.contains("addEventListener(\"click\""))
        assertTrue(source.contains("addEventListener(\"auxclick\""))
        assertTrue("A page must not be able to fake the click", source.contains("event.isTrusted"))
        assertTrue("A page that handled the click keeps it", source.contains("event.defaultPrevented"))
        assertTrue(source.contains("type: \"openLink\""))
        // Capturing, so a page that only stops the click from bubbling still gets the tab
        assertEquals(2, Regex("""\}, \{ capture: true \}\);""").findAll(source).count())
    }

    @Test
    fun pageMenu_versionWasBumpedSoInstalledCopiesUpdate() {
        // Versions already shipped: an installed copy of the same version keeps its old script
        assertFalse(pageMenuManifest.getString("version") in listOf("1.0.0", "1.1.0", "1.2.0", "1.2.1"))
    }

    private val pageMenuSource by lazy {
        val script = pageMenuManifest.getJSONArray("content_scripts").getJSONObject(0)
        script.getJSONArray("js").strings().joinToString("\n") { File(pageMenuDir, it).readText() }
    }

    @Test
    fun pageMenu_scriptCopiesAPagesPngThroughTheApp() {
        val source = pageMenuSource
        // The page's own Clipboard.prototype.write is replaced, and anything but a PNG goes on to
        // the engine's own method, read through the Xray (never one the page or an older copy of
        // the script left there)
        assertTrue(source.contains("}, page.Clipboard.prototype, { defineAs: \"write\" });"))
        assertTrue(source.contains("const engineWrite = window.Clipboard.prototype.write;"))
        assertTrue(source.contains("return Reflect.apply(engineWrite, this, args);"))
        // Only for the page's own clipboard: the engine answers a call on anything else
        assertTrue(source.contains("this === window.navigator.clipboard ? pngItem(args[0]) : null"))
        assertTrue(source.contains("const PNG = \"image/png\";"))
        assertTrue(source.contains("sendNativeMessage(NATIVE_APP, { type: \"copyImage\", data })"))
        // The same limit as the app's
        assertTrue(source.contains("const MAX_IMAGE_BYTES = 20 * 1024 * 1024;"))
        assertEquals(20 * 1024 * 1024, ClipboardImages.PAGE_IMAGE_MAX_BYTES)
    }

    @Test
    fun pageMenu_takesThePagesPromiseAndErrorBeforeThePageRuns() {
        val source = pageMenuSource
        // At the top of the script (document_start), and never read from the page again: a page
        // that replaces its Promise later must not be handed what settles the copy
        assertTrue(source.contains("\nconst PagePromise = page.Promise;\n"))
        assertTrue(source.contains("\nconst PageDOMException = page.DOMException;\n"))
        assertEquals(1, Regex("""page\.Promise\b""").findAll(source).count())
        assertEquals(1, Regex("""page\.DOMException\b""").findAll(source).count())
        // What the page's promise is given only passes the outcome on; it can't start a copy
        val executor = source.substringAfter("return new PagePromise(exportFunction((resolve, reject) => {")
            .substringBefore("}, page));")
        assertTrue(executor.trimStart().startsWith("done.then("))
        assertFalse(executor.contains("copyPng"))
    }

    @Test
    fun pageMenu_onlyAWriteTheUserJustAskedFor_startsACopy_oneAtATime() {
        val source = pageMenuSource
        val write = source.substringAfter("exportFunction(function write(").substringBefore("}, page.Clipboard.prototype")
        // Checked as the page calls, through the Xray (which the page can't change), then the copy
        // starts there and then, with nothing waited for before it
        assertTrue(write.contains("const activation = window.navigator.userActivation;"))
        val check = write.indexOf("if (!activation || !activation.isActive) {")
        val busy = write.indexOf("if (copying) {")
        val start = write.indexOf("return pagePromise(copyPng(item));")
        assertTrue(check >= 0 && check < busy && busy < start)
        assertFalse(write.contains("await"))
        // Nowhere else starts one, and the copy is marked as running before its first wait
        assertEquals(2, Regex("""copyPng\(""").findAll(source).count())
        assertTrue(source.contains("async function copyPng(item) {\n  copying = true;\n"))
    }

    private fun copyImage(data: Any?, type: Any? = "copyImage"): JSONObject =
        JSONObject().apply {
            if (type != null) put("type", type)
            if (data != null) put("data", data)
        }

    @Test
    fun pageImageCopyData_handsTheBase64On_undecoded() {
        val message = copyImage("iVBORw0KGgo=")
        assertTrue(GeckoBrowserEngine.isPageImageCopy(message))
        assertEquals("iVBORw0KGgo=", GeckoBrowserEngine.pageImageCopyData(message))
        // Not taken for the other messages
        assertFalse(GeckoBrowserEngine.isPageMenuMessage(message))
        assertNull(GeckoBrowserEngine.linkOpenRequest(message))
    }

    @Test
    fun pageImageCopyData_refusesMissingOrTooLongData() {
        assertNull("empty", GeckoBrowserEngine.pageImageCopyData(copyImage("")))
        assertNull("no data", GeckoBrowserEngine.pageImageCopyData(copyImage(null)))
        assertNull("not a string", GeckoBrowserEngine.pageImageCopyData(copyImage(JSONArray().put(1))))
        // 20 MB in base64, and not a character more
        val longest = "A".repeat(ClipboardImages.PAGE_IMAGE_MAX_BASE64)
        assertEquals(longest.length, GeckoBrowserEngine.pageImageCopyData(copyImage(longest))?.length)
        assertNull(GeckoBrowserEngine.pageImageCopyData(copyImage(longest + "A")))
    }

    @Test
    fun pageImageCopyData_refusesTheWrongTypeAndNonObjectMessages() {
        val data = "iVBORw0KGgo="
        assertNull(GeckoBrowserEngine.pageImageCopyData(copyImage(data, type = "pageMenu")))
        assertNull(GeckoBrowserEngine.pageImageCopyData(copyImage(data, type = null)))
        assertNull(GeckoBrowserEngine.pageImageCopyData(data))
        assertNull(GeckoBrowserEngine.pageImageCopyData(null))
        assertFalse(GeckoBrowserEngine.isPageImageCopy(JSONArray().put("copyImage")))
    }

    @Test
    fun pageMenu_scriptSendsTheTopPagesDeclaredIcons_whenTheyChange() {
        val source = pageMenuSource
        assertTrue(source.contains("sendNativeMessage(NATIVE_APP, { type: \"siteIcons\", icons })"))
        // A frame's icons are another site's
        assertTrue(source.contains("if (window !== window.top || !/^https?:\$/.test(location.protocol)) {"))
        assertTrue(source.contains("const ICON_RELS = [\"icon\", \"apple-touch-icon\", \"apple-touch-icon-precomposed\"];"))
        // Web addresses only, as the page resolves them
        assertTrue(source.contains("!/^https?:/i.test(href)"))
        // Again when the page changes them, at most once a second, and only when they changed
        assertTrue(source.contains("attributeFilter: [\"href\", \"rel\", \"sizes\", \"type\"]"))
        assertTrue(source.contains("const ICON_WAIT_MS = 1000;"))
        assertTrue(source.contains("if (icons.length === 0 || sent === sentIcons) {"))
        // An icon for the other colour scheme is left out
        assertTrue(source.contains("if (found && media && !window.matchMedia(media).matches) {"))
        // A send the app wasn't listening for yet is tried again, a few times
        assertTrue(source.contains("const ICON_RETRIES = 3;"))
        assertTrue(source.contains("if (iconRetries < ICON_RETRIES && !iconTimer) {"))
    }

    private fun siteIcons(vararg icons: JSONObject, type: Any? = "siteIcons"): JSONObject =
        JSONObject().apply {
            if (type != null) put("type", type)
            put("icons", JSONArray().apply { icons.forEach { put(it) } })
        }

    private fun link(href: Any?, rel: Any? = "icon", sizes: Any? = null, linkType: Any? = null): JSONObject =
        JSONObject().apply {
            if (href != null) put("href", href)
            if (rel != null) put("rel", rel)
            if (sizes != null) put("sizes", sizes)
            if (linkType != null) put("type", linkType)
        }

    @Test
    fun declaredSiteIcons_readsIconsAndTouchIcons_withTheirSizes() {
        val icons = GeckoBrowserEngine.declaredSiteIcons(
            siteIcons(
                link("https://discord.com/assets/favicon.ico"),
                link("https://discord.com/apple.png", rel = "apple-touch-icon", sizes = "180x180"),
                link("https://cdn.example/icon-192.png", sizes = "192x192 any", linkType = "image/png")
            ),
            pageUrl = "https://discord.com/channels/@me",
            isTopLevel = true
        )
        assertEquals(
            listOf(
                DeclaredSiteIcon("https://discord.com/assets/favicon.ico"),
                DeclaredSiteIcon("https://discord.com/apple.png", isTouchIcon = true, sizes = listOf(180)),
                DeclaredSiteIcon("https://cdn.example/icon-192.png", sizes = listOf(192))
            ),
            icons
        )
    }

    @Test
    fun declaredSiteIcons_onlyFromTheTopPage_ofAWebPage_inTheRightMessage() {
        val message = siteIcons(link("https://a.example/i.png"))
        assertEquals(1, GeckoBrowserEngine.declaredSiteIcons(message, "https://a.example/", true)?.size)
        assertNull("A frame's icons", GeckoBrowserEngine.declaredSiteIcons(message, "https://a.example/", false))
        assertNull(GeckoBrowserEngine.declaredSiteIcons(message, "about:blank", true))
        assertNull(GeckoBrowserEngine.declaredSiteIcons(message, "moz-extension://abc/page.html", true))
        assertNull(GeckoBrowserEngine.declaredSiteIcons(message, null, true))
        assertNull(GeckoBrowserEngine.declaredSiteIcons(siteIcons(link("https://a.example/i.png"), type = "pageMenu"), "https://a.example/", true))
        assertNull(GeckoBrowserEngine.declaredSiteIcons("siteIcons", "https://a.example/", true))
        assertNull(GeckoBrowserEngine.declaredSiteIcons(JSONObject().put("type", "siteIcons"), "https://a.example/", true))
        // Not taken for the other messages
        assertFalse(GeckoBrowserEngine.isPageMenuMessage(message))
        assertNull(GeckoBrowserEngine.linkOpenRequest(message))
        assertNull(GeckoBrowserEngine.pageImageCopyData(message))
    }

    @Test
    fun declaredSiteIcons_leavesOutWhatRemBrowseWontFetchOrCantDecode() {
        val icons = GeckoBrowserEngine.declaredSiteIcons(
            siteIcons(
                link("https://a.example/ok.png"),
                link("https://a.example/vector.svg"),
                link("https://a.example/vector", linkType = "image/svg+xml"),
                link("data:image/png;base64,iVBORw0KGgo="),
                link("javascript:alert(1)"),
                link("ftp://a.example/i.png"),
                link("https://a.example/mask.png", rel = "mask-icon"),
                link("https://a.example/no-rel.png", rel = null),
                link(null),
                link(42),
                link("https://a.example/" + "x".repeat(1024)),
                link("https://user:secret@a.example/i.png"),
                // Into this device or the local network, from a site on the internet
                link("https://192.168.1.1/router.png"),
                link("http://localhost:8080/i.png"),
                link("https://10.0.0.2/i.png"),
                // To another site over plain http, or a port pages may not use
                link("http://cdn.example/i.png"),
                link("https://a.example:25/i.png"),
                // The same address twice is tried once
                link("https://a.example/ok.png")
            ),
            pageUrl = "https://a.example/page",
            isTopLevel = true
        )
        assertEquals(listOf("https://a.example/ok.png"), icons?.map { it.url })
    }

    @Test
    fun declaredSiteIcons_aPageOnTheLocalNetwork_mayUseItsOwnAddresses() {
        val router = GeckoBrowserEngine.declaredSiteIcons(
            siteIcons(link("http://192.168.1.1/icon.png"), link("http://192.168.1.1:8080/other.png")),
            pageUrl = "http://192.168.1.1/admin",
            isTopLevel = true
        )
        assertEquals(listOf("http://192.168.1.1/icon.png", "http://192.168.1.1:8080/other.png"), router?.map { it.url })
        // A plain-http site may use its own address, as for its /favicon.ico
        val plain = GeckoBrowserEngine.declaredSiteIcons(siteIcons(link("http://neverssl.com/i.png")), "http://neverssl.com/", true)
        assertEquals(listOf("http://neverssl.com/i.png"), plain?.map { it.url })
    }

    @Test
    fun declaredSiteIcons_readsAtMostSixteen_andKeepsAtMostEight() {
        val many = (1..40).map { link("https://a.example/$it.png") }.toTypedArray()
        val icons = GeckoBrowserEngine.declaredSiteIcons(siteIcons(*many), "https://a.example/", true)
        assertEquals((1..8).map { "https://a.example/$it.png" }, icons?.map { it.url })
        // Sixteen read: the seventeenth is never looked at, even when the first are refused
        val refusedFirst = (1..16).map { link("https://192.168.0.$it/i.png") } + link("https://a.example/late.png")
        assertEquals(emptyList<String>(), GeckoBrowserEngine.declaredSiteIcons(siteIcons(*refusedFirst.toTypedArray()), "https://a.example/", true)?.map { it.url })
    }

    private fun openLink(url: Any?, type: Any? = "openLink", foreground: Boolean? = null): JSONObject =
        JSONObject().apply {
            if (type != null) put("type", type)
            if (url != null) put("url", url)
            if (foreground != null) put("foreground", foreground)
        }

    @Test
    fun linkOpenRequest_acceptsHttpAndHttps_inAnyLetterCase() {
        for (url in listOf("http://example.com/a", "https://example.com/a?b=1#c", "HTTPS://EXAMPLE.COM/", "HtTp://example.com")) {
            val request = GeckoBrowserEngine.linkOpenRequest(openLink(url))
            assertEquals(url, request?.url)
            assertFalse(url, request!!.inForeground)
        }
    }

    @Test
    fun linkOpenRequest_readsTheForegroundFlag() {
        assertTrue(GeckoBrowserEngine.linkOpenRequest(openLink("https://a.example/", foreground = true))!!.inForeground)
        assertFalse(GeckoBrowserEngine.linkOpenRequest(openLink("https://a.example/", foreground = false))!!.inForeground)
        assertFalse(GeckoBrowserEngine.linkOpenRequest(openLink("https://a.example/"))!!.inForeground)
    }

    @Test
    fun linkOpenRequest_refusesAnythingButWebAddresses() {
        for (url in listOf(
            "javascript:alert(1)", "data:text/html,hi", "file:///etc/passwd", "about:config",
            "blob:https://example.com/1234", "", "ftp://example.com/", "example.com", " https://example.com/"
        )) {
            assertNull(url, GeckoBrowserEngine.linkOpenRequest(openLink(url)))
        }
        assertNull("missing url", GeckoBrowserEngine.linkOpenRequest(openLink(null)))
    }

    @Test
    fun linkOpenRequest_refusesTheWrongTypeAndNonObjectMessages() {
        assertNull(GeckoBrowserEngine.linkOpenRequest(openLink("https://a.example/", type = "pageMenu")))
        assertNull(GeckoBrowserEngine.linkOpenRequest(openLink("https://a.example/", type = null)))
        assertNull(GeckoBrowserEngine.linkOpenRequest("https://a.example/"))
        assertNull(GeckoBrowserEngine.linkOpenRequest(null))
        assertNull(GeckoBrowserEngine.linkOpenRequest(JSONArray().put("openLink")))
    }
}

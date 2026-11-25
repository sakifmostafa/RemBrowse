package com.rembrowser.rembrowse.engine

import com.rembrowser.rembrowse.data.ClipboardImages
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
        assertFalse(pageMenuManifest.getString("version") in listOf("1.0.0", "1.1.0"))
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

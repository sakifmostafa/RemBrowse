package com.rembrowser.rembrowse.engine

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
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
}

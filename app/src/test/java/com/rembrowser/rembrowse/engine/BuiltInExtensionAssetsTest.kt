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
    fun backgroundPage_loadsTheApi() {
        val scripts = manifest.getJSONObject("background").getJSONArray("scripts").strings()
        val background = scripts.joinToString("\n") { File(dir, it).readText() }
        assertTrue(background.contains("browser.popupViewport.start()"))
    }

    private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
}

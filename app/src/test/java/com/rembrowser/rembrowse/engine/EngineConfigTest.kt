package com.rembrowser.rembrowse.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EngineConfigTest {

    @get:Rule
    val folder = TemporaryFolder()

    private var originalRuntimeName: String? = null

    @Before
    fun pretendToBeAndroid() {
        // GeckoView's YAML parser sets fields directly only when it believes it runs on Android,
        // which it decides from this property. Set here so the real parser can be exercised.
        originalRuntimeName = System.getProperty("java.runtime.name")
        System.setProperty("java.runtime.name", "Android Runtime")
    }

    @After
    fun restoreRuntimeName() {
        originalRuntimeName?.let { System.setProperty("java.runtime.name", it) }
            ?: System.clearProperty("java.runtime.name")
    }

    @Test
    fun largeScreensLayOutAtThePaneWidth_phonesKeepTheDesktopLayout() {
        assertEquals(980, EngineConfig.desktopViewportWidth(360))
        assertEquals(980, EngineConfig.desktopViewportWidth(599))
        assertEquals(320, EngineConfig.desktopViewportWidth(600))
        assertEquals(320, EngineConfig.desktopViewportWidth(800))
        assertEquals(320, EngineConfig.desktopViewportWidth(1250))
    }

    @Test
    fun yaml_isAPrefsMapWithPlainNumbersAndBooleans() {
        assertEquals(
            "# Written by RemBrowse at every start. Edits are overwritten.\n" +
                "prefs:\n" +
                "  browser.translations.enable: false\n" +
                "  browser.viewport.desktopWidth: 320\n",
            EngineConfig.yaml(mapOf("browser.translations.enable" to false, "browser.viewport.desktopWidth" to 320))
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun yaml_refusesValuesItCannotWritePlainly() {
        EngineConfig.yaml(mapOf("some.pref" to "text"))
    }

    @Test
    fun prepare_writesAFileThatGeckoViewsOwnParserReadsBack() {
        val path = EngineConfig.prepare(folder.root, smallestScreenWidthDp = 800)

        val file = File(folder.root, "geckoview-config.yaml")
        assertEquals(file.absolutePath, path)
        // Read with the real parser, not a stand-in: this is what the engine will see
        val prefs = EngineConfig.readWithEngineParser(file)!!
        assertEquals(
            mapOf<String, Any?>("browser.translations.enable" to false, "browser.navigation.requireUserInteraction" to false, "dom.suspend_inactive.enabled" to false, "dom.webkitBlink.filesystem.enabled" to true, "browser.viewport.desktopWidth" to 320),
            prefs
        )
        assertTrue("An integer, so it is written as a number preference", prefs["browser.viewport.desktopWidth"] is Int)
        assertTrue("A boolean, so it is written as a true/false preference", prefs["browser.translations.enable"] is Boolean)
    }

    @Test
    fun prepare_onAPhone_turnsTranslationsOffButKeepsTheDesktopLayout() {
        File(folder.root, "geckoview-config.yaml").writeText("prefs:\n  x: 1\n")

        val path = EngineConfig.prepare(folder.root, smallestScreenWidthDp = 411)

        val file = File(folder.root, "geckoview-config.yaml")
        assertEquals(file.absolutePath, path)
        assertEquals(
            "The old file is replaced, not added to",
            mapOf<String, Any?>("browser.translations.enable" to false, "browser.navigation.requireUserInteraction" to false, "dom.suspend_inactive.enabled" to false, "dom.webkitBlink.filesystem.enabled" to true),
            EngineConfig.readWithEngineParser(file)
        )
    }

    @Test
    fun prepare_startsWithoutTheFileWhenItCannotBeReadBack() {
        val file = File(folder.root, "geckoview-config.yaml")

        // The parser fails outright, even with an Error rather than an Exception
        assertNull(EngineConfig.prepare(folder.root, 800) { throw NoClassDefFoundError("org/yaml/snakeyaml/Yaml") })
        assertFalse(file.exists())

        // The parser returns nothing, or something other than what was written
        assertNull(EngineConfig.prepare(folder.root, 800) { null })
        assertNull(
            EngineConfig.prepare(folder.root, 800) {
                mapOf("browser.translations.enable" to false, "browser.viewport.desktopWidth" to "320")
            }
        )
        assertNull(
            EngineConfig.prepare(folder.root, 800) {
                mapOf("browser.translations.enable" to false, "browser.viewport.desktopWidth" to 980)
            }
        )
        assertNull(
            "Every preference must come back, not only one",
            EngineConfig.prepare(folder.root, 800) { mapOf("browser.viewport.desktopWidth" to 320) }
        )
        assertNull(EngineConfig.prepare(folder.root, 411) { mapOf("browser.translations.enable" to "false") })
        assertFalse(file.exists())
    }

    @Test
    fun prepare_startsWithoutTheFileWhenItCannotBeWritten() {
        val notADirectory = folder.newFile("occupied")

        assertNull(EngineConfig.prepare(notADirectory, smallestScreenWidthDp = 800))
    }

    @Test
    fun backgroundTabsKeepRunning_onPhonesAndLargeScreensAlike() {
        assertEquals("dom.suspend_inactive.enabled", EngineConfig.SUSPEND_INACTIVE_PREF)
        for (width in listOf(360, 411, 600, 800, 1250)) {
            for (mouse in listOf(true, false)) {
                assertEquals(false, EngineConfig.prefsFor(width, hasMouseOrTouchpad = mouse)[EngineConfig.SUSPEND_INACTIVE_PREF])
            }
        }
    }

    @Test
    fun pastedFilesCanBeReadAsEntries_onPhonesAndLargeScreensAlike() {
        // Telegram Web A calls item.webkitGetAsEntry() on every pasted file; Gecko hides it on Android.
        assertEquals("dom.webkitBlink.filesystem.enabled", EngineConfig.FILE_ENTRIES_PREF)
        for (width in listOf(360, 411, 600, 800, 1250)) {
            for (mouse in listOf(true, false)) {
                val prefs = EngineConfig.prefsFor(width, hasMouseOrTouchpad = mouse)
                assertEquals(true, prefs[EngineConfig.FILE_ENTRIES_PREF])
                // Folder upload is a separate pref and stays at Gecko's default (off on Android).
                assertFalse(prefs.containsKey("dom.webkitBlink.dirPicker.enabled"))
            }
        }
    }

    @Test
    fun aMouseOrTouchpad_addsTheFinePointerPref_onPhonesAndLargeScreensAlike() {
        assertEquals(6, EngineConfig.FINE_POINTER_WITH_HOVER)
        assertEquals("ui.primaryPointerCapabilities", EngineConfig.POINTER_PREF)
        for (width in listOf(360, 411, 600, 800, 1250)) {
            assertEquals(6, EngineConfig.prefsFor(width, hasMouseOrTouchpad = true)[EngineConfig.POINTER_PREF])
            assertFalse(EngineConfig.POINTER_PREF in EngineConfig.prefsFor(width, hasMouseOrTouchpad = false))
            assertFalse(EngineConfig.POINTER_PREF in EngineConfig.prefsFor(width))
        }
        assertEquals(
            mapOf<String, Any>("browser.translations.enable" to false, "browser.navigation.requireUserInteraction" to false, "dom.suspend_inactive.enabled" to false, "dom.webkitBlink.filesystem.enabled" to true, "ui.primaryPointerCapabilities" to 6),
            EngineConfig.prefsFor(411, hasMouseOrTouchpad = true)
        )
        assertEquals(
            mapOf<String, Any>(
                "browser.translations.enable" to false, "browser.navigation.requireUserInteraction" to false, "dom.suspend_inactive.enabled" to false, "dom.webkitBlink.filesystem.enabled" to true,
                "browser.viewport.desktopWidth" to 320,
                "ui.primaryPointerCapabilities" to 6
            ),
            EngineConfig.prefsFor(800, hasMouseOrTouchpad = true)
        )
    }

    @Test
    fun prepare_withAMouse_writesAFileTheEngineParserReadsBackWithThePointerPref() {
        val path = EngineConfig.prepare(folder.root, smallestScreenWidthDp = 800, hasMouseOrTouchpad = true)

        val file = File(folder.root, "geckoview-config.yaml")
        assertEquals(file.absolutePath, path)
        val prefs = EngineConfig.readWithEngineParser(file)!!
        assertEquals(
            mapOf<String, Any?>(
                "browser.translations.enable" to false, "browser.navigation.requireUserInteraction" to false, "dom.suspend_inactive.enabled" to false, "dom.webkitBlink.filesystem.enabled" to true,
                "browser.viewport.desktopWidth" to 320,
                "ui.primaryPointerCapabilities" to 6
            ),
            prefs
        )
        assertTrue("A number preference", prefs["ui.primaryPointerCapabilities"] is Int)
    }

    @Test
    fun prepare_withAMouseOnAPhone_hasThePointerPrefWithoutTheDesktopWidth() {
        EngineConfig.prepare(folder.root, smallestScreenWidthDp = 411, hasMouseOrTouchpad = true)
        assertEquals(
            mapOf<String, Any?>("browser.translations.enable" to false, "browser.navigation.requireUserInteraction" to false, "dom.suspend_inactive.enabled" to false, "dom.webkitBlink.filesystem.enabled" to true, "ui.primaryPointerCapabilities" to 6),
            EngineConfig.readWithEngineParser(File(folder.root, "geckoview-config.yaml"))
        )
    }

    @Test
    fun prepare_withoutAMouse_leavesThePointerPrefOut() {
        EngineConfig.prepare(folder.root, smallestScreenWidthDp = 800, hasMouseOrTouchpad = false)
        assertFalse(
            "ui.primaryPointerCapabilities" in EngineConfig.readWithEngineParser(File(folder.root, "geckoview-config.yaml"))!!
        )
    }

    @Test
    fun prepare_refusesAFileMissingThePointerPref() {
        assertNull(
            EngineConfig.prepare(folder.root, 800, hasMouseOrTouchpad = true) {
                mapOf("browser.translations.enable" to false, "browser.viewport.desktopWidth" to 320)
            }
        )
    }
}

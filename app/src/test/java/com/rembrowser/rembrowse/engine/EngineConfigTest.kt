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
    fun yaml_isAPrefsMapWithPlainIntegerValues() {
        assertEquals(
            "# Written by RemBrowse at every start. Edits are overwritten.\n" +
                "prefs:\n" +
                "  browser.viewport.desktopWidth: 320\n",
            EngineConfig.yaml(mapOf("browser.viewport.desktopWidth" to 320))
        )
    }

    @Test
    fun prepare_writesAFileThatGeckoViewsOwnParserReadsBack() {
        val path = EngineConfig.prepare(folder.root, smallestScreenWidthDp = 800)

        val file = File(folder.root, "geckoview-config.yaml")
        assertEquals(file.absolutePath, path)
        // Read with the real parser, not a stand-in: this is what the engine will see
        val prefs = EngineConfig.readWithEngineParser(file)!!
        assertEquals(mapOf<String, Any?>("browser.viewport.desktopWidth" to 320), prefs)
        assertTrue("An integer, so it is written as a number preference", prefs.values.single() is Int)
    }

    @Test
    fun prepare_givesTheEngineNoFileOnAPhone_andRemovesAnOldOne() {
        val stale = File(folder.root, "geckoview-config.yaml").apply { writeText("prefs:\n  x: 1\n") }

        assertNull(EngineConfig.prepare(folder.root, smallestScreenWidthDp = 411))
        assertFalse("Left behind, it would be stale", stale.exists())
    }

    @Test
    fun prepare_startsWithoutTheFileWhenItCannotBeReadBack() {
        val file = File(folder.root, "geckoview-config.yaml")

        // The parser fails outright, even with an Error rather than an Exception
        assertNull(EngineConfig.prepare(folder.root, 800) { throw NoClassDefFoundError("org/yaml/snakeyaml/Yaml") })
        assertFalse(file.exists())

        // The parser returns nothing, or something other than what was written
        assertNull(EngineConfig.prepare(folder.root, 800) { null })
        assertNull(EngineConfig.prepare(folder.root, 800) { mapOf("browser.viewport.desktopWidth" to "320") })
        assertNull(EngineConfig.prepare(folder.root, 800) { mapOf("browser.viewport.desktopWidth" to 980) })
        assertFalse(file.exists())
    }

    @Test
    fun prepare_startsWithoutTheFileWhenItCannotBeWritten() {
        val notADirectory = folder.newFile("occupied")

        assertNull(EngineConfig.prepare(notADirectory, smallestScreenWidthDp = 800))
    }
}

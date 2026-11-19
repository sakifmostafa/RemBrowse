package com.rembrowser.rembrowse.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmoJsonParserTest {

    @Test
    fun parseSearchResponse_parsesValidAmoV5Payload() {
        val sampleJson = """
            {
              "count": 2,
              "results": [
                {
                  "id": 607454,
                  "guid": "uBlock0@raymondhill.net",
                  "slug": "ublock-origin",
                  "name": { "en-US": "uBlock Origin" },
                  "summary": { "en-US": "Finally, an efficient wide-spectrum content blocker." },
                  "icon_url": "https://addons.mozilla.org/user-media/addon_icons/607/607454-64.png",
                  "authors": [
                    { "name": "Raymond Hill" }
                  ],
                  "ratings": { "average": 4.82 },
                  "weekly_downloads": 512000,
                  "current_version": {
                    "version": "1.61.2",
                    "file": {
                      "url": "https://addons.mozilla.org/android/downloads/file/4391011/ublock_origin-1.61.2.xpi"
                    }
                  }
                },
                {
                  "id": 999999,
                  "guid": "no-file@test",
                  "slug": "missing-file",
                  "name": { "en-US": "Broken Entry" },
                  "current_version": {
                    "version": "0.1"
                  }
                }
              ]
            }
        """.trimIndent()

        val parsed = AmoJsonParser.parseSearchResponse(sampleJson)
        assertEquals(1, parsed.size)
        val ublock = parsed.first()
        assertEquals("uBlock0@raymondhill.net", ublock.id)
        assertEquals("ublock-origin", ublock.slug)
        assertEquals("uBlock Origin", ublock.name)
        assertEquals("Finally, an efficient wide-spectrum content blocker.", ublock.summary)
        assertEquals("Raymond Hill", ublock.author)
        assertEquals(4.82, ublock.rating, 0.001)
        assertEquals(512000L, ublock.weeklyDownloads)
        assertEquals("1.61.2", ublock.version)
        assertEquals(
            "https://addons.mozilla.org/android/downloads/file/4391011/ublock_origin-1.61.2.xpi",
            ublock.xpiUrl
        )
    }

    @Test
    fun parseSearchResponse_handlesFallbackLocaleAndEmptyResults() {
        val localizedJson = """
            {
              "results": [
                {
                  "guid": "darkreader@darkreader.org",
                  "slug": "darkreader",
                  "name": { "de": "Dark Reader" },
                  "summary": "Dark mode for every website.",
                  "authors": [],
                  "current_version": {
                    "version": "4.9.96",
                    "file": { "url": "https://addons.mozilla.org/darkreader.xpi" }
                  }
                }
              ]
            }
        """.trimIndent()

        val parsed = AmoJsonParser.parseSearchResponse(localizedJson)
        assertEquals(1, parsed.size)
        assertEquals("Dark Reader", parsed.first().name)
        assertEquals("Dark mode for every website.", parsed.first().summary)
        assertEquals("Unknown author", parsed.first().author)

        assertTrue(AmoJsonParser.parseSearchResponse("""{"results":[]}""").isEmpty())
    }
}

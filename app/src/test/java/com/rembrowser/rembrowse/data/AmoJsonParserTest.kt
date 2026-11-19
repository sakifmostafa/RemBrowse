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

    @Test
    fun parseSearchResponse_handlesRequestedLanguageMissingWithDefaultPointer() {
        // Shape returned when ?lang=en-US is requested but only another translation exists
        val json = """
            {
              "results": [
                {
                  "guid": "addon@example",
                  "slug": "addon",
                  "name": { "fr": "Bloqueur", "en-US": null, "_default": "fr" },
                  "summary": { "en-US": null, "_default": "fr", "fr": "Bloque les pubs" },
                  "icon_url": null,
                  "authors": [ { "name": null } ],
                  "current_version": {
                    "version": "2.0",
                    "file": { "url": "https://addons.mozilla.org/addon.xpi" }
                  }
                }
              ]
            }
        """.trimIndent()

        val addon = AmoJsonParser.parseSearchResponse(json).single()
        assertEquals("Bloqueur", addon.name)
        assertEquals("Bloque les pubs", addon.summary)
        assertEquals("A JSON null must not become the text \"null\"", null, addon.iconUrl)
        assertEquals("Unknown author", addon.author)
    }

    @Test
    fun parseSearchResponse_skipsDuplicatesMissingIdsAndInsecureDownloads() {
        fun entry(guid: String?, url: String) = """
            {
              ${if (guid != null) "\"guid\": \"$guid\"," else ""}
              "name": "Addon",
              "current_version": { "version": "1", "file": { "url": "$url" } }
            }
        """.trimIndent()
        val json = """
            { "results": [
              ${entry("one@example", "https://addons.mozilla.org/one.xpi")},
              ${entry("one@example", "https://addons.mozilla.org/one-again.xpi")},
              ${entry(null, "https://addons.mozilla.org/no-id.xpi")},
              ${entry("plain@example", "http://addons.example/plain.xpi")}
            ] }
        """.trimIndent()

        val parsed = AmoJsonParser.parseSearchResponse(json)
        // Duplicate ids would crash the list (it is keyed by id); the rest are unusable
        assertEquals(listOf("one@example"), parsed.map { it.id })
        assertEquals("https://addons.mozilla.org/one.xpi", parsed.single().xpiUrl)
    }
}

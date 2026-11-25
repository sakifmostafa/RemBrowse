package com.rembrowser.rembrowse.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mozilla.geckoview.WebExtension

/** What an extension's permission prompt says about the data it collects, from what the engine passes. */
class ExtensionDataCollectionTest {

    private fun install(vararg names: String) =
        ExtensionDataCollection.forPrompt(ExtensionPromptKind.INSTALL, names.toList())

    @Test
    fun anInstallListsWhatTheExtensionCollectsInItsOrder() {
        val data = install("locationInfo", "browsingActivity")
        assertEquals(listOf("locationInfo", "browsingActivity"), data.collected)
        assertFalse(data.offersTechnicalData)
    }

    @Test
    fun onAnInstallTheOptionalTechnicalDataIsACheckboxNotAnItem() {
        val data = install("websiteContent", ExtensionDataCollection.TECHNICAL_AND_INTERACTION)
        assertEquals(listOf("websiteContent"), data.collected)
        assertTrue(data.offersTechnicalData)
    }

    @Test
    fun anExtensionThatCollectsNothingCanStillOfferTechnicalData() {
        val data = install(ExtensionDataCollection.NONE, ExtensionDataCollection.TECHNICAL_AND_INTERACTION)
        assertEquals(listOf(ExtensionDataCollection.NONE), data.collected)
        assertTrue(data.offersTechnicalData)
    }

    @Test
    fun nothingDeclaredShowsNothing() {
        val data = install()
        assertEquals(emptyList<String>(), data.collected)
        assertFalse(data.offersTechnicalData)
    }

    @Test
    fun noneWinsOverAnythingElseDeclaredWithIt() {
        // Not a valid manifest, but the prompt must not say both
        assertEquals(listOf(ExtensionDataCollection.NONE), install("healthInfo", ExtensionDataCollection.NONE).collected)
    }

    @Test
    fun blanksAndRepeatsAreDropped() {
        assertEquals(listOf("searchTerms", "bookmarksInfo"), install(" searchTerms ", "", "searchTerms", "bookmarksInfo").collected)
    }

    @Test
    fun updatesAndOptionalRequestsListTechnicalDataWithTheRestAndOfferNoCheckbox() {
        for (kind in listOf(ExtensionPromptKind.UPDATE, ExtensionPromptKind.OPTIONAL_PERMISSIONS)) {
            val data = ExtensionDataCollection.forPrompt(
                kind,
                listOf("healthInfo", ExtensionDataCollection.TECHNICAL_AND_INTERACTION)
            )
            assertEquals("$kind", listOf("healthInfo", ExtensionDataCollection.TECHNICAL_AND_INTERACTION), data.collected)
            assertFalse("$kind", data.offersTechnicalData)
        }
    }

    @Test
    fun everyKindTheEngineKnowsHasPlainWords() {
        val engineKinds = WebExtension.DATA_COLLECTION_PERMISSIONS.toSet() - ExtensionDataCollection.NONE
        assertEquals(engineKinds, ExtensionDataCollection.described)
        for (kind in engineKinds) {
            val words = ExtensionDataCollection.describe(kind)
            assertNotEquals("$kind has words of its own", kind, words)
            assertTrue("$kind starts with a capital: $words", words.first().isUpperCase())
        }
        assertTrue(ExtensionDataCollection.NONE in WebExtension.DATA_COLLECTION_PERMISSIONS)
        assertTrue(ExtensionDataCollection.TECHNICAL_AND_INTERACTION in WebExtension.DATA_COLLECTION_PERMISSIONS)
    }

    @Test
    fun aKindAddedToTheEngineLaterIsShownByItsName() {
        assertEquals("neuralInfo", ExtensionDataCollection.describe("neuralInfo"))
    }
}

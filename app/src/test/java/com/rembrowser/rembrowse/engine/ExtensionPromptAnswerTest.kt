package com.rembrowser.rembrowse.engine

import org.junit.Assert.assertEquals
import org.junit.Test
import org.mozilla.geckoview.WebExtension

/** What RemBrowse answers the engine's install prompt with (the three values of PermissionPromptResponse). */
class ExtensionPromptAnswerTest {

    private fun WebExtension.PermissionPromptResponse.values() =
        listOf(isPermissionsGranted, isPrivateModeGranted, isTechnicalAndInteractionDataGranted)

    @Test
    fun anInstallSharesTechnicalDataOnlyWhenTheBoxWasTicked() {
        assertEquals(listOf(true, true, false), installAnswer(allow = true, shareTechnicalData = false).values())
        assertEquals(listOf(true, true, true), installAnswer(allow = true, shareTechnicalData = true).values())
    }

    @Test
    fun aRefusalGrantsNothing() {
        assertEquals(listOf(false, false, false), installAnswer(allow = false, shareTechnicalData = false).values())
        assertEquals(listOf(false, false, false), installAnswer(allow = false, shareTechnicalData = true).values())
    }

    @Test
    fun theDefaultAdBlockerInstalledWithoutAPromptNeverSharesTechnicalData() {
        assertEquals(listOf(true, true, false), trustedInstallAnswer().values())
    }
}

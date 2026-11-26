package com.rembrowser.rembrowse.engine

import com.rembrowser.rembrowse.model.ExtensionInstallPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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

    /** The answers each queued prompt got, by its extension's name, as (allow, share technical data). */
    private val answered = mutableListOf<Triple<String, Boolean, Boolean>>()

    /** A prompt wired to [queue] as the extension manager wires its own: answered once, then gone. */
    private fun queued(queue: ExtensionPromptQueue, name: String): ExtensionInstallPrompt {
        lateinit var prompt: ExtensionInstallPrompt
        prompt = ExtensionInstallPrompt(
            extensionId = "same@example.com",
            extensionName = name,
            version = "1.0",
            permissions = emptyList(),
            offersTechnicalData = true,
            onDecision = { allow, share -> if (queue.remove(prompt)) answered += Triple(name, allow, share) }
        )
        queue.add(prompt)
        return prompt
    }

    @Test
    fun aSecondAnswerFromOnePromptsDialogDoesNotAnswerTheNextPrompt() {
        val queue = ExtensionPromptQueue()
        val first = queued(queue, "First")
        val second = queued(queue, "Second")
        assertSame(first, queue.onShow.value)

        // Two taps on the first dialog before the second prompt is drawn
        queue.answer(first, allow = true, shareTechnicalData = true)
        queue.answer(first, allow = true, shareTechnicalData = true)

        assertEquals(listOf(Triple("First", true, true)), answered)
        assertSame(second, queue.onShow.value)

        queue.answer(second, allow = false, shareTechnicalData = false)
        assertEquals(listOf(Triple("First", true, true), Triple("Second", false, false)), answered)
        assertNull(queue.onShow.value)
    }

    @Test
    fun aPromptStillWaitingItsTurnCannotBeAnsweredUnseen() {
        val queue = ExtensionPromptQueue()
        val first = queued(queue, "First")
        val second = queued(queue, "Second")

        queue.answer(second, allow = true, shareTechnicalData = true)

        assertEquals(emptyList<Triple<String, Boolean, Boolean>>(), answered)
        assertSame(first, queue.onShow.value)
    }

    @Test
    fun twoPromptsThatLookAlikeAreAnsweredSeparately() {
        val queue = ExtensionPromptQueue()
        val first = queued(queue, "Same")
        val second = queued(queue, "Same")

        queue.answer(first, allow = true, shareTechnicalData = false)

        assertSame(second, queue.onShow.value)
        assertEquals(listOf(Triple("Same", true, false)), answered)
    }

    @Test
    fun whenTheWindowGoes_everyWaitingPromptIsDeclined_oldestFirst() {
        val queue = ExtensionPromptQueue()
        queued(queue, "Install")
        queued(queue, "Update")
        queued(queue, "Optional permissions")

        queue.declineAll()

        assertEquals(
            listOf(
                Triple("Install", false, false),
                Triple("Update", false, false),
                Triple("Optional permissions", false, false)
            ),
            answered
        )
        assertNull(queue.onShow.value)
    }

    @Test
    fun afterTheWindowWent_aNewPromptIsDeclinedAtOnce_neverShown() {
        val queue = ExtensionPromptQueue()
        queue.declineAll()
        val shown = mutableListOf<ExtensionInstallPrompt?>()
        val late = queued(queue, "Late update")
        shown += queue.onShow.value

        assertEquals(listOf(Triple("Late update", false, false)), answered)
        assertEquals(listOf<ExtensionInstallPrompt?>(null), shown)

        // Its dialog's answer, should one still arrive, changes nothing
        queue.answer(late, allow = true, shareTechnicalData = true)
        assertEquals(listOf(Triple("Late update", false, false)), answered)
    }

    @Test
    fun aDeclinedPromptIsAnsweredOnce_evenIfItsDialogStillAnswers() {
        val queue = ExtensionPromptQueue()
        val shownPrompt = queued(queue, "On show")

        queue.declineAll()
        queue.answer(shownPrompt, allow = true, shareTechnicalData = false)

        assertEquals(listOf(Triple("On show", false, false)), answered)
    }

    @Test
    fun aPromptWhoseDecisionKeepsItQueued_isStillTakenOut() {
        val queue = ExtensionPromptQueue()
        var decisions = 0
        queue.add(
            ExtensionInstallPrompt(
                extensionId = "stubborn@example.com",
                extensionName = "Stubborn",
                version = "1.0",
                permissions = emptyList(),
                onDecision = { _, _ -> decisions++ }
            )
        )

        queue.declineAll()

        assertEquals(1, decisions)
        assertNull(queue.onShow.value)
    }
}

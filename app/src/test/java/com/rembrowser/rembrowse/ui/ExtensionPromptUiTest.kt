package com.rembrowser.rembrowse.ui

import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rembrowser.rembrowse.model.ExtensionDataCollection
import com.rembrowser.rembrowse.model.ExtensionInstallPrompt
import com.rembrowser.rembrowse.model.ExtensionPromptKind
import com.rembrowser.rembrowse.ui.components.ExtensionInstallPromptDialog
import com.rembrowser.rembrowse.ui.theme.RemBrowseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The extension permission prompt's data section: what the developer says the extension collects,
 * in words, and the optional technical data's checkbox, whose answer goes back with the decision.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-mdpi")
class ExtensionPromptUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** The decisions given, as (allow, share technical data). */
    private val answers = mutableListOf<Pair<Boolean, Boolean>>()

    private lateinit var inputModes: InputModeManager

    private fun prompt(
        dataCollection: List<String> = listOf("locationInfo", "browsingActivity"),
        offersTechnicalData: Boolean = true,
        kind: ExtensionPromptKind = ExtensionPromptKind.INSTALL,
        permissions: List<String> = listOf("geolocation", "storage")
    ) = ExtensionInstallPrompt(
        extensionId = "weather@example.com",
        extensionName = "Weather",
        version = "2.0",
        permissions = permissions,
        kind = kind,
        dataCollection = dataCollection,
        offersTechnicalData = offersTechnicalData,
        onDecision = { _, _ -> }
    )

    private fun show(prompt: ExtensionInstallPrompt) {
        composeRule.setContent {
            inputModes = LocalInputModeManager.current
            RemBrowseTheme(darkTheme = true) {
                ExtensionInstallPromptDialog(prompt = prompt, onDecision = { allow, share -> answers += allow to share })
            }
        }
    }

    private val checkbox get() = composeRule.onNodeWithTag("extension_prompt_technical_data")

    @Test
    fun whatTheExtensionCollectsIsListedInWords() {
        show(prompt())

        composeRule.onNodeWithText("Data this extension collects").assertIsDisplayed()
        composeRule.onNodeWithText("The developer says it collects:").assertIsDisplayed()
        composeRule.onNodeWithText("•  Your location").assertIsDisplayed()
        composeRule.onNodeWithText("•  Your browsing history: the sites you visit, and when").assertIsDisplayed()
    }

    @Test
    fun anExtensionThatCollectsNothingSaysSo() {
        show(prompt(dataCollection = listOf(ExtensionDataCollection.NONE), offersTechnicalData = false))

        composeRule.onNodeWithText("The developer says this extension doesn't collect data.").assertIsDisplayed()
        checkbox.assertDoesNotExist()
    }

    @Test
    fun anExtensionThatDeclaresNothingHasNoDataSection() {
        show(prompt(dataCollection = emptyList(), offersTechnicalData = false))

        composeRule.onNodeWithText("Data this extension collects").assertDoesNotExist()
    }

    @Test
    fun theCheckboxIsLabelledAndStartsUnticked() {
        show(prompt())

        checkbox
            .assertIsOff()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
            .assert(hasText("Share technical and interaction data with the developer"))
        composeRule.onNodeWithText("Add").performClick()

        assertEquals(listOf(true to false), answers)
    }

    @Test
    fun aTickedBoxGoesWithTheAnswer() {
        show(prompt())

        checkbox.performClick().assertIsOn()
        composeRule.onNodeWithText("Add").performClick()

        assertEquals(listOf(true to true), answers)
    }

    @Test
    fun cancelSharesNothingEvenWithTheBoxTicked() {
        show(prompt())

        checkbox.performClick()
        composeRule.onNodeWithText("Cancel").performClick()

        assertEquals(listOf(false to false), answers)
    }

    @Test
    fun theKeyboardReachesAndTicksTheBox() {
        show(prompt())
        composeRule.runOnIdle { inputModes.requestInputMode(InputMode.Keyboard) }

        checkbox.requestFocus().assertIsFocused()
        checkbox.performKeyInput { pressKey(Key.Enter) }
        checkbox.assertIsOn()
        // Tab goes on to the dialog's buttons, the main one first, as in every dialog here
        checkbox.performKeyInput { pressKey(Key.Tab) }
        composeRule.onNodeWithText("Add").assertIsFocused()
    }

    @Test
    fun anUpdateListsTheNewDataWithoutACheckbox() {
        // The engine passes the optional item with an install only (ExtensionDataCollection.forPrompt)
        show(prompt(kind = ExtensionPromptKind.UPDATE, dataCollection = listOf("healthInfo"), offersTechnicalData = false))

        composeRule.onNodeWithText("Data the new version collects").assertIsDisplayed()
        composeRule.onNodeWithText("•  Health information, such as medical history").assertIsDisplayed()
        checkbox.assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-port-xhdpi", fontScale = 1.3f)
    fun onAPhoneAtLargerTextTheDialogScrollsToTheCheckbox() {
        show(prompt(permissions = List(10) { "permission$it" }, dataCollection = ExtensionDataCollection.described.toList() - ExtensionDataCollection.TECHNICAL_AND_INTERACTION))

        checkbox.performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Add").assertIsDisplayed()
        checkbox.performClick()
        composeRule.onNodeWithText("Add").performClick()
        assertEquals(listOf(true to true), answers)
    }
}

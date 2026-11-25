package com.rembrowser.rembrowse.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.ContentBlocking.BounceTrackingProtectionMode

/**
 * What the Enhanced privacy switch sets on the engine ([GeckoBrowserEngine.applyTrackingProtection]),
 * checked on a settings object of GeckoView's own that no runtime is attached to.
 */
class TrackingProtectionTest {

    private fun settingsFor(enhanced: Boolean): ContentBlocking.Settings =
        ContentBlocking.Settings.Builder().build().also {
            GeckoBrowserEngine.applyTrackingProtection(it, enhanced)
        }

    @Test
    fun enhancedPrivacyClearsRedirectTrackersCookies() {
        assertEquals(
            BounceTrackingProtectionMode.BOUNCE_TRACKING_PROTECTION_MODE_ENABLED,
            settingsFor(enhanced = true).bounceTrackingProtectionMode
        )
    }

    @Test
    fun withoutEnhancedPrivacyBounceTrackingProtectionIsOnStandby() {
        // Standby notes the sites that bounce without clearing anything, unlike disabled, which
        // forgets them; so a later switch-on acts at once
        assertEquals(
            BounceTrackingProtectionMode.BOUNCE_TRACKING_PROTECTION_MODE_ENABLED_STANDBY,
            settingsFor(enhanced = false).bounceTrackingProtectionMode
        )
    }

    @Test
    fun switchingBackAndForthFollowsTheSwitch() {
        val settings = settingsFor(enhanced = true)
        GeckoBrowserEngine.applyTrackingProtection(settings, enhanced = false)
        assertEquals(
            BounceTrackingProtectionMode.BOUNCE_TRACKING_PROTECTION_MODE_ENABLED_STANDBY,
            settings.bounceTrackingProtectionMode
        )
        assertFalse(settings.strictSocialTrackingProtection)
        GeckoBrowserEngine.applyTrackingProtection(settings, enhanced = true)
        assertEquals(
            BounceTrackingProtectionMode.BOUNCE_TRACKING_PROTECTION_MODE_ENABLED,
            settings.bounceTrackingProtectionMode
        )
        assertTrue(settings.strictSocialTrackingProtection)
    }

    @Test
    fun theBlockingCategoriesAreUnchanged() {
        val extra = ContentBlocking.AntiTracking.CRYPTOMINING or
            ContentBlocking.AntiTracking.FINGERPRINTING or
            ContentBlocking.AntiTracking.EMAIL
        val standard = settingsFor(enhanced = false)
        assertEquals(ContentBlocking.AntiTracking.DEFAULT, standard.antiTrackingCategories)
        assertFalse(standard.strictSocialTrackingProtection)
        val enhanced = settingsFor(enhanced = true)
        assertEquals(ContentBlocking.AntiTracking.DEFAULT or extra, enhanced.antiTrackingCategories)
        assertTrue(enhanced.strictSocialTrackingProtection)
        for (settings in listOf(standard, enhanced)) {
            // Never the "content" list, which blocks general-purpose hosts and breaks sites
            assertEquals(0, settings.antiTrackingCategories and ContentBlocking.AntiTracking.CONTENT)
            assertEquals(ContentBlocking.EtpLevel.DEFAULT, settings.enhancedTrackingProtectionLevel)
        }
    }
}

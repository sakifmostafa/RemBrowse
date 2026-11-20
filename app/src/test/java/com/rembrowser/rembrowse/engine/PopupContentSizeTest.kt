package com.rembrowser.rembrowse.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Reading the popup helper's size reports. */
class PopupContentSizeTest {

    private fun message(vararg fields: Pair<String, Any>) = JSONObject().apply { fields.forEach { (k, v) -> put(k, v) } }

    @Test
    fun readsAPopupSize() {
        val size = PopupContentSize.parse(
            message("type" to "popupSize", "extensionId" to "vpn@proton.ch", "width" to 356, "height" to 600)
        )
        assertEquals(PopupContentSize("vpn@proton.ch", 356, 600), size)
    }

    @Test
    fun ignoresAnythingElse() {
        assertNull(PopupContentSize.parse(null))
        assertNull(PopupContentSize.parse("popupSize"))
        assertNull(PopupContentSize.parse(message("type" to "other", "extensionId" to "a@b", "width" to 300, "height" to 300)))
        assertNull(PopupContentSize.parse(message("type" to "popupSize", "width" to 300, "height" to 300)))
        assertNull(PopupContentSize.parse(message("type" to "popupSize", "extensionId" to "a@b", "width" to 0, "height" to 300)))
        assertNull(PopupContentSize.parse(message("type" to "popupSize", "extensionId" to "a@b", "width" to 300, "height" to 50_000)))
    }
}

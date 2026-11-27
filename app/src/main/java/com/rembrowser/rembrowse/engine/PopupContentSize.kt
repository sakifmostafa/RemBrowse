package com.rembrowser.rembrowse.engine

import org.json.JSONObject

/**
 * A popup's preferred size, as reported by the built-in popup helper
 * (assets/extensions/popup_viewport) over GeckoView's native messaging, in CSS pixels, with the
 * size of the viewport it was measured in. A popup with a viewport tag of its own reports the
 * size it already has, without a viewport.
 */
internal data class PopupContentSize(
    val extensionId: String,
    val width: Int,
    val height: Int,
    val viewportWidth: Int? = null,
    val viewportHeight: Int? = null
) {
    companion object {
        /** The app's name on the channel; background.js sends to the same name. */
        const val NATIVE_APP = "rembrowse"

        /** Sizes beyond this are not a real popup; the message is ignored. */
        private const val MAX_PX = 10_000

        /**
         * Reads a `{type: "popupSize", extensionId, width, height, viewportWidth?,
         * viewportHeight?}` message; null for anything else. A viewport that is missing or out of
         * range in either direction is left out.
         */
        fun parse(message: Any?): PopupContentSize? {
            val json = message as? JSONObject ?: return null
            if (json.optString("type") != "popupSize") return null
            val extensionId = json.optString("extensionId").takeIf { it.isNotBlank() } ?: return null
            val width = json.optInt("width", -1)
            val height = json.optInt("height", -1)
            if (width !in 1..MAX_PX || height !in 1..MAX_PX) return null
            val viewportWidth = json.optInt("viewportWidth", -1)
            val viewportHeight = json.optInt("viewportHeight", -1)
            val hasViewport = viewportWidth in 1..MAX_PX && viewportHeight in 1..MAX_PX
            return PopupContentSize(
                extensionId,
                width,
                height,
                viewportWidth.takeIf { hasViewport },
                viewportHeight.takeIf { hasViewport }
            )
        }
    }
}

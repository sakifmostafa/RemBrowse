package com.rembrowser.rembrowse.model

/** An installed add-on as the update check sees it. */
data class AddonUpdateTarget(
    val id: String,
    val name: String,
    /** Part of the browser (the popup helper, the page menu): never checked for updates. */
    val builtIn: Boolean = false
)

/** What became of one add-on when it was checked for a newer version. */
sealed interface AddonUpdateResult {
    /** No newer version, or none the engine could find. */
    data object UpToDate : AddonUpdateResult

    /** A newer version, [version], is installed. */
    data class Updated(val version: String) : AddonUpdateResult

    /**
     * The newer version, [version] when the engine said which, asks for new permissions and was
     * not allowed. [asked] is false when an automatic check declined it again without asking,
     * because the user had already said no to that version.
     */
    data class Declined(val version: String?, val asked: Boolean = true) : AddonUpdateResult

    /** The add-on asked to finish updating the next time it restarts. */
    data object Postponed : AddonUpdateResult

    /** The update couldn't be downloaded or installed; [network] when the download failed. */
    data class Failed(val network: Boolean = false) : AddonUpdateResult
}

/** One add-on's part in a finished check. */
data class AddonUpdateItem(val id: String, val name: String, val result: AddonUpdateResult)

/** A finished check of every add-on, or why it couldn't run at all. */
sealed interface AddonUpdateOutcome {
    /** No network connection, so nothing was asked. */
    data object Offline : AddonUpdateOutcome

    /** The installed add-ons couldn't be listed. */
    data object Unavailable : AddonUpdateOutcome

    data class Checked(val items: List<AddonUpdateItem>) : AddonUpdateOutcome

    /** Something went wrong: the check, or at least one add-on's update. */
    val isError: Boolean
        get() = this !is Checked || items.any { it.result is AddonUpdateResult.Failed }

    /** What a "Check for updates" asked for says, in a sentence or a few. */
    val summary: String
        get() = when (this) {
            Offline -> "Couldn't check for updates. Check your connection and try again."
            Unavailable -> "Couldn't check for updates. Try again later."
            is Checked -> checkedSummary(items)
        }
}

private fun checkedSummary(items: List<AddonUpdateItem>): String {
    if (items.isEmpty()) return "No add-ons to update."
    fun namesWith(predicate: (AddonUpdateResult) -> Boolean) =
        items.filter { predicate(it.result) }.map { it.name }

    val updated = namesWith { it is AddonUpdateResult.Updated }
    val postponed = namesWith { it is AddonUpdateResult.Postponed }
    val declined = namesWith { it is AddonUpdateResult.Declined }
    val failed = namesWith { it is AddonUpdateResult.Failed }
    val networkFailure = items.any { (it.result as? AddonUpdateResult.Failed)?.network == true }

    val sentences = buildList {
        if (updated.isNotEmpty()) add("Updated ${namesInWords(updated)}.")
        if (postponed.isNotEmpty()) {
            val restart = if (postponed.size > 1) "they next restart" else "it next restarts"
            add("${namesInWords(postponed)} will finish updating when $restart.")
        }
        if (declined.isNotEmpty()) {
            val plural = declined.size > 1
            add(
                "${namesInWords(declined)} ${if (plural) "weren't" else "wasn't"} updated, because " +
                    "you didn't allow ${if (plural) "their" else "its"} new permissions."
            )
        }
        if (failed.isNotEmpty()) {
            add(
                "Couldn't update ${namesInWords(failed)}." +
                    if (networkFailure) " Check your connection and try again." else " Try again later."
            )
        }
    }
    return sentences.joinToString(" ").ifEmpty { "All add-ons are up to date." }
}

/** "A", "A and B", "A, B and C". */
private fun namesInWords(names: List<String>): String = when (names.size) {
    1 -> names[0]
    else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
}

/** The add-on update controls in Settings → Extensions. */
data class AddonUpdatesUiState(
    /** Check for and install new versions once a day without being asked. */
    val updateAutomatically: Boolean = true,
    /** A check asked for with "Check for updates" is running. */
    val checking: Boolean = false,
    val lastCheckedMillis: Long? = null,
    /** How the last check asked for went, until an automatic check runs; those say nothing here. */
    val outcome: AddonUpdateOutcome? = null
)

package com.rembrowser.rembrowse.viewmodel

import com.rembrowser.rembrowse.fakes.FakeDownloadHistoryStore
import com.rembrowser.rembrowse.fakes.FakeDownloadStorage
import com.rembrowser.rembrowse.fakes.TrackedInputStream
import com.rembrowser.rembrowse.model.DownloadItem
import com.rembrowser.rembrowse.model.DownloadRequest
import com.rembrowser.rembrowse.model.DownloadStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadsViewModelTest {

    private val storage = FakeDownloadStorage()
    private val history = FakeDownloadHistoryStore()
    private val settled = mutableListOf<DownloadRequest>()
    private var askFirst = true
    private var idCounter = 0

    /**
     * Runs [block] with a view model whose file copying only happens when the test calls
     * `advanceUntilIdle()`, so the state before, during and after a download can be observed.
     */
    private fun downloadsTest(block: suspend TestScope.(DownloadsViewModel) -> Unit) = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val viewModel = DownloadsViewModel(
                storage = storage,
                history = history,
                scope = scope,
                ioDispatcher = StandardTestDispatcher(testScheduler),
                askBeforeDownloading = { askFirst },
                onRequestSettled = { settled += it },
                clock = { 1_000L },
                idGenerator = { "dl-${++idCounter}" }
            )
            block(viewModel)
        } finally {
            scope.cancel()
        }
    }

    private fun request(
        fileName: String = "report.pdf",
        body: TrackedInputStream = TrackedInputStream(PAYLOAD),
        contentLength: Long = PAYLOAD.size.toLong(),
        skipConfirmation: Boolean = false,
        tabId: String? = "tab-1"
    ) = DownloadRequest(
        tabId = tabId,
        url = "https://example.com/files/$fileName",
        fileName = fileName,
        mimeType = "application/pdf",
        contentLength = contentLength,
        skipConfirmation = skipConfirmation,
        body = body
    )

    @Test
    fun aRequestWaitsForAnAnswerAndIsSavedOnceAccepted() = downloadsTest { viewModel ->
        val body = TrackedInputStream(PAYLOAD)
        val request = request(body = body)

        viewModel.onDownloadRequested(request)
        advanceUntilIdle()

        // Nothing is fetched or written until the user says yes
        assertEquals(request, viewModel.uiState.value.pendingRequest)
        assertTrue(viewModel.uiState.value.items.isEmpty())
        assertFalse(body.wasRead)
        assertFalse(body.isClosed)

        viewModel.answerPendingRequest(accept = true)
        var state = viewModel.uiState.value
        assertNull(state.pendingRequest)
        assertEquals(DownloadStatus.IN_PROGRESS, state.items.single().status)
        assertEquals(1, state.activeCount)
        assertEquals(DownloadStatus.IN_PROGRESS, state.notice?.status)

        advanceUntilIdle()
        state = viewModel.uiState.value
        val item = state.items.single()
        assertEquals(DownloadStatus.COMPLETED, item.status)
        assertEquals("report.pdf", item.fileName)
        assertEquals(storage.uriFor("report.pdf"), item.contentUri)
        assertEquals(PAYLOAD.size.toLong(), item.receivedBytes)
        assertEquals(PAYLOAD.size.toLong(), item.totalBytes)
        assertEquals(1_000L, item.startedAtMillis)
        assertArrayEquals(PAYLOAD, storage.saved.getValue("report.pdf"))
        assertEquals(DownloadStatus.COMPLETED, state.notice?.status)
        assertEquals(0, state.activeCount)
        assertTrue(body.isClosed)
        assertEquals(listOf(request), settled)
        assertEquals(listOf(item), history.stored)
    }

    @Test
    fun aDeclinedRequestIsClosedWithoutReadingOrSavingAnything() = downloadsTest { viewModel ->
        val body = TrackedInputStream(PAYLOAD)
        val request = request(body = body)
        viewModel.onDownloadRequested(request)

        viewModel.answerPendingRequest(accept = false)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.pendingRequest)
        assertTrue(viewModel.uiState.value.items.isEmpty())
        assertNull(viewModel.uiState.value.notice)
        assertFalse(body.wasRead)
        assertTrue(body.isClosed)
        assertTrue(storage.saved.isEmpty())
        assertEquals(listOf(request), settled)
    }

    @Test
    fun askingCanBeTurnedOffAndIsSkippedWhenTheUserAlreadyChoseToSave() = downloadsTest { viewModel ->
        askFirst = false
        viewModel.onDownloadRequested(request(fileName = "a.pdf"))
        assertNull(viewModel.uiState.value.pendingRequest)

        askFirst = true
        viewModel.onDownloadRequested(request(fileName = "b.pdf", skipConfirmation = true))
        assertNull(viewModel.uiState.value.pendingRequest)

        advanceUntilIdle()
        assertEquals(setOf("a.pdf", "b.pdf"), storage.saved.keys)
        // Newest first
        assertEquals(listOf("b.pdf", "a.pdf"), viewModel.uiState.value.items.map { it.fileName })
    }

    @Test
    fun requestsAreAnsweredOneAtATimeAndAFloodIsTurnedAway() = downloadsTest { viewModel ->
        val bodies = List(DownloadsViewModel.MAX_PENDING_REQUESTS + 2) { TrackedInputStream(PAYLOAD) }
        bodies.forEachIndexed { index, body ->
            viewModel.onDownloadRequested(request(fileName = "file-$index.bin", body = body))
        }
        advanceUntilIdle()

        val pending = viewModel.uiState.value.pendingRequests
        assertEquals(DownloadsViewModel.MAX_PENDING_REQUESTS, pending.size)
        assertEquals("file-0.bin", viewModel.uiState.value.pendingRequest?.fileName)
        // The ones past the limit were dropped and their connections released
        assertTrue(bodies.takeLast(2).all { it.isClosed })
        assertTrue(bodies.dropLast(2).none { it.isClosed })

        viewModel.answerPendingRequest(accept = false)
        assertEquals("file-1.bin", viewModel.uiState.value.pendingRequest?.fileName)
    }

    @Test
    fun aBrokenConnectionFailsTheDownloadAndRemovesThePartialFile() = downloadsTest { viewModel ->
        askFirst = false
        val body = TrackedInputStream(PAYLOAD, failAfterBytes = 10)
        viewModel.onDownloadRequested(request(body = body))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        val item = state.items.single()
        assertEquals(DownloadStatus.FAILED, item.status)
        assertNull(item.contentUri)
        assertEquals(10L, item.receivedBytes)
        assertEquals(DownloadStatus.FAILED, state.notice?.status)
        assertTrue(storage.saved.isEmpty())
        assertEquals(listOf("report.pdf"), storage.discarded)
        assertTrue(body.isClosed)
        assertEquals(1, settled.size)
        assertEquals(DownloadStatus.FAILED, history.stored.single().status)
    }

    @Test
    fun aFullDiskFailsTheDownloadCleanly() = downloadsTest { viewModel ->
        askFirst = false
        storage.failOnCreate = true
        val body = TrackedInputStream(PAYLOAD)
        viewModel.onDownloadRequested(request(body = body))
        advanceUntilIdle()

        assertEquals(DownloadStatus.FAILED, viewModel.uiState.value.items.single().status)
        assertFalse(body.wasRead)
        assertTrue(body.isClosed)
        assertEquals(1, settled.size)
    }

    @Test
    fun cancellingPartWayStopsReadingAndRemovesWhatWasWritten() = downloadsTest { viewModel ->
        askFirst = false
        lateinit var cancel: () -> Unit
        // Cancels from inside the copy loop, once the second chunk is about to be read
        val body = TrackedInputStream(PAYLOAD, chunkSize = 8, beforeRead = { read -> if (read == 16) cancel() })
        viewModel.onDownloadRequested(request(body = body))
        val id = viewModel.uiState.value.items.single().id
        cancel = { viewModel.cancel(id) }

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(DownloadStatus.CANCELLED, state.items.single().status)
        assertNull(state.items.single().contentUri)
        assertNull("Cancelling is not announced", state.notice)
        assertTrue(body.position < PAYLOAD.size)
        assertTrue(storage.saved.isEmpty())
        assertEquals(listOf("report.pdf"), storage.discarded)
        assertTrue(body.isClosed)
        assertEquals(1, settled.size)
    }

    @Test
    fun cancellingBeforeTheCopyStartsWritesNothing() = downloadsTest { viewModel ->
        askFirst = false
        val body = TrackedInputStream(PAYLOAD)
        viewModel.onDownloadRequested(request(body = body))
        val id = viewModel.uiState.value.items.single().id

        viewModel.cancel(id)
        assertEquals(DownloadStatus.CANCELLED, viewModel.uiState.value.items.single().status)
        advanceUntilIdle()

        assertEquals(DownloadStatus.CANCELLED, viewModel.uiState.value.items.single().status)
        assertFalse(body.wasRead)
        assertTrue(storage.saved.isEmpty())
        assertTrue(storage.discarded.isEmpty())

        // Cancelling something that is over, or unknown, does nothing
        viewModel.cancel(id)
        viewModel.cancel("nope")
        assertEquals(1, viewModel.uiState.value.items.size)
    }

    @Test
    fun aTakenNameIsNumberedAndTheListShowsTheNameActuallyUsed() = downloadsTest { viewModel ->
        askFirst = false
        viewModel.onDownloadRequested(request())
        advanceUntilIdle()
        viewModel.onDownloadRequested(request())
        advanceUntilIdle()

        assertEquals(listOf("report (1).pdf", "report.pdf"), viewModel.uiState.value.items.map { it.fileName })
        assertEquals(setOf("report.pdf", "report (1).pdf"), storage.saved.keys)
        assertEquals("report (1).pdf", viewModel.uiState.value.notice?.fileName)
    }

    @Test
    fun aDataAddressIsNotKeptInTheList() = downloadsTest { viewModel ->
        askFirst = false
        val embedded = "data:application/pdf;base64," + "A".repeat(200_000)
        viewModel.onDownloadRequested(
            DownloadRequest(
                tabId = "tab-1",
                url = embedded,
                fileName = "download.pdf",
                mimeType = "application/pdf",
                contentLength = DownloadItem.UNKNOWN_SIZE,
                skipConfirmation = false,
                body = TrackedInputStream(PAYLOAD)
            )
        )
        advanceUntilIdle()

        val item = viewModel.uiState.value.items.single()
        assertEquals(DownloadStatus.COMPLETED, item.status)
        assertEquals("data:", item.sourceUrl)
        assertEquals("", item.sourceHost)
        assertEquals("data:", history.stored.single().sourceUrl)
    }

    @Test
    fun aFileOfUnknownSizeStillDownloads() = downloadsTest { viewModel ->
        askFirst = false
        viewModel.onDownloadRequested(request(contentLength = DownloadItem.UNKNOWN_SIZE))

        val running = viewModel.uiState.value.items.single()
        assertEquals(DownloadItem.UNKNOWN_SIZE, running.totalBytes)
        assertNull("No fraction to draw without a total", running.progress)

        advanceUntilIdle()
        val done = viewModel.uiState.value.items.single()
        assertEquals(DownloadStatus.COMPLETED, done.status)
        assertEquals(PAYLOAD.size.toLong(), done.totalBytes)
    }

    @Test
    fun progressIsPublishedWhileALargeFileArrives() = downloadsTest { viewModel ->
        askFirst = false
        val large = ByteArray(700 * 1024) { (it % 251).toByte() }
        val seen = mutableListOf<Long>()
        lateinit var id: String
        val body = TrackedInputStream(large, chunkSize = 64 * 1024, beforeRead = {
            seen += viewModel.uiState.value.items.first { item -> item.id == id }.receivedBytes
        })
        viewModel.onDownloadRequested(request(fileName = "big.bin", body = body, contentLength = large.size.toLong()))
        id = viewModel.uiState.value.items.single().id

        advanceUntilIdle()

        assertTrue("Progress moved before the end: $seen", seen.any { it in 1 until large.size })
        assertEquals("…in steps, not once per chunk", 3, seen.distinct().size)
        assertArrayEquals(large, storage.saved.getValue("big.bin"))
        assertEquals(1f, viewModel.uiState.value.items.single().let { it.receivedBytes.toFloat() / it.totalBytes })
    }

    @Test
    fun finishedDownloadsCanBeRemovedOrClearedButRunningOnesStay() = downloadsTest { viewModel ->
        askFirst = false
        viewModel.onDownloadRequested(request(fileName = "one.pdf"))
        viewModel.onDownloadRequested(request(fileName = "two.pdf"))
        advanceUntilIdle()
        viewModel.onDownloadRequested(request(fileName = "running.pdf"))
        val runningId = viewModel.uiState.value.items.first().id
        val oneId = viewModel.uiState.value.items.first { it.fileName == "one.pdf" }.id

        viewModel.remove(runningId)
        assertEquals("A running download is cancelled, not removed", 3, viewModel.uiState.value.items.size)

        viewModel.remove(oneId)
        assertEquals(listOf("running.pdf", "two.pdf"), viewModel.uiState.value.items.map { it.fileName })
        assertEquals(listOf("two.pdf"), history.stored.map { it.fileName })
        assertTrue("The file itself is left alone", "one.pdf" in storage.saved)

        viewModel.clearFinished()
        assertEquals(listOf("running.pdf"), viewModel.uiState.value.items.map { it.fileName })
        assertTrue(viewModel.uiState.value.hasFinishedItems.not())
        assertTrue(history.stored.isEmpty())

        advanceUntilIdle()
        assertEquals(DownloadStatus.COMPLETED, viewModel.uiState.value.items.single().status)
    }

    @Test
    fun openingChecksTheFileIsStillThereAndReportsWhenNothingCanShowIt() = downloadsTest { viewModel ->
        askFirst = false
        viewModel.onDownloadRequested(request())
        advanceUntilIdle()
        val item = viewModel.uiState.value.items.single()
        val launched = mutableListOf<DownloadItem>()

        viewModel.open(item.id) { launched += it; true }
        advanceUntilIdle()
        assertEquals(listOf(item), launched)
        assertNull(viewModel.uiState.value.message)

        viewModel.open(item.id) { false }
        advanceUntilIdle()
        assertEquals("No app on this device can open report.pdf.", viewModel.uiState.value.message)
        viewModel.clearMessage()

        storage.missingUris += item.contentUri!!
        viewModel.open(item.id) { launched += it; true }
        advanceUntilIdle()
        assertEquals("Not launched for a file that is gone", 1, launched.size)
        assertEquals("report.pdf has been moved or deleted.", viewModel.uiState.value.message)
    }

    @Test
    fun onlyAFinishedDownloadCanBeOpened() = downloadsTest { viewModel ->
        askFirst = false
        viewModel.onDownloadRequested(request(body = TrackedInputStream(PAYLOAD, failAfterBytes = 0)))
        val id = viewModel.uiState.value.items.single().id
        var launches = 0

        viewModel.open(id) { launches++; true }
        advanceUntilIdle()
        viewModel.open(id) { launches++; true }
        advanceUntilIdle()

        assertEquals(DownloadStatus.FAILED, viewModel.uiState.value.items.single().status)
        assertEquals(0, launches)
    }

    @Test
    fun theListIsRestoredAndKeptToASensibleLength() = runTest {
        val earlier = List(DownloadsViewModel.MAX_ITEMS) { index ->
            DownloadItem(
                id = "old-$index",
                fileName = "old-$index.bin",
                sourceUrl = "https://example.com/old-$index.bin",
                mimeType = "",
                status = DownloadStatus.COMPLETED,
                contentUri = "content://downloads/old-$index.bin"
            )
        }
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val viewModel = DownloadsViewModel(
                storage = storage,
                history = FakeDownloadHistoryStore(earlier),
                scope = scope,
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
                askBeforeDownloading = { false }
            )
            assertEquals(earlier, viewModel.uiState.value.items)

            viewModel.onDownloadRequested(request(fileName = "new.pdf"))

            val items = viewModel.uiState.value.items
            assertEquals(DownloadsViewModel.MAX_ITEMS, items.size)
            assertEquals("new.pdf", items.first().fileName)
            assertNotNull(items.firstOrNull { it.id == "old-0" })
            assertNull("The oldest entry made room", items.firstOrNull { it.id == "old-${DownloadsViewModel.MAX_ITEMS - 1}" })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun shutdownStopsRunningDownloadsAndReleasesUnansweredRequests() = downloadsTest { viewModel ->
        val waiting = TrackedInputStream(PAYLOAD)
        viewModel.onDownloadRequested(request(fileName = "waiting.pdf", body = waiting))
        askFirst = false
        val running = TrackedInputStream(PAYLOAD)
        viewModel.onDownloadRequested(request(fileName = "running.pdf", body = running))

        viewModel.shutdown()
        advanceUntilIdle()

        assertTrue(waiting.isClosed)
        assertTrue(running.isClosed)
        assertTrue(storage.saved.isEmpty())
        assertEquals(DownloadStatus.CANCELLED, viewModel.uiState.value.items.single().status)
    }

    private companion object {
        val PAYLOAD = ByteArray(64) { it.toByte() }
    }
}

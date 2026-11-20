package com.rembrowser.rembrowse.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rembrowser.rembrowse.model.HistoryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The browsing history survives a restart in real SharedPreferences. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class BrowsingHistoryStoreTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun clear() {
        context.getSharedPreferences("rembrowse_history", Context.MODE_PRIVATE).edit().clear().commit()
    }

    // Writes on the calling thread, so the saved list is there when save() returns
    private fun store() = SharedPrefsBrowsingHistoryStore(context, writer = { it.run() })

    @Test
    fun savedHistory_loadsBackInOrder() {
        val entries = listOf(
            HistoryEntry("https://two.example/", "Two", 2_000L),
            HistoryEntry("https://one.example/", "", 1_000L)
        )
        store().save(entries)

        assertEquals(entries, store().load())
    }

    @Test
    fun nothingSaved_orUnreadable_loadsEmpty() {
        assertTrue(store().load().isEmpty())
        context.getSharedPreferences("rembrowse_history", Context.MODE_PRIVATE).edit()
            .putString("entries", "not json").commit()
        assertTrue(store().load().isEmpty())
    }
}

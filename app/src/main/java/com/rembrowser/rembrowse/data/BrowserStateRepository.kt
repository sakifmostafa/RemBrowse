package com.rembrowser.rembrowse.data

import com.rembrowser.rembrowse.model.PersistedBrowserState

interface BrowserStateRepository {
    fun loadState(): PersistedBrowserState
    fun saveState(state: PersistedBrowserState)
}

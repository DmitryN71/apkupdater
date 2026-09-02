package com.apkupdater.data.ui


sealed class SearchUiState {
    data object Loading: SearchUiState()
    data object Error : SearchUiState()
    data class Success(val updates: List<AppUpdate>): SearchUiState()

    inline fun onLoading(block: (Loading) -> Unit): SearchUiState {
        if (this is Loading) block(this)
        return this
    }

    inline fun onError(block: (Error) -> Unit): SearchUiState {
        if (this is Error) block(this)
        return this
    }

    inline fun onSuccess(block: (Success) -> Unit): SearchUiState {
        if (this is Success) block(this)
        return this
    }

    fun mutableUpdates(): MutableList<AppUpdate> {
        if (this is Success) {
            return updates.toMutableList()
        }
        return mutableListOf()
    }

    fun updates(): List<AppUpdate> {
        if (this is Success) {
            return updates
        }
        return emptyList()
    }

    /**
     * Publishes a changed list without changing which state we are in — used inside
     * `state.update {}` so concurrent writers cannot overwrite each other. A progress tick
     * arriving while a search is running is dropped rather than turning the screen into an
     * empty result list mid-search.
     */
    fun withUpdates(updates: List<AppUpdate>): SearchUiState =
        if (this is Success) Success(updates) else this
}

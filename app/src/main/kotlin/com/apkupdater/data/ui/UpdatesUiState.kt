package com.apkupdater.data.ui


sealed class UpdatesUiState {
	data class Loading(val completed: Int = 0, val total: Int = 0): UpdatesUiState()
	data object Error : UpdatesUiState()
	data class Success(val updates: List<AppUpdate>): UpdatesUiState()

	inline fun onLoading(block: (Loading) -> Unit): UpdatesUiState {
		if (this is Loading) block(this)
		return this
	}

	inline fun onError(block: (Error) -> Unit): UpdatesUiState {
		if (this is Error) block(this)
		return this
	}

	inline fun onSuccess(block: (Success) -> Unit): UpdatesUiState {
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

}

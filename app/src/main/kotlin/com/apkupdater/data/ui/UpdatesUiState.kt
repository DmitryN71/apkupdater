package com.apkupdater.data.ui


sealed class UpdatesUiState {
	/**
	 * A check is running. It carries [updates] — the cards of downloads and installs that were
	 * already in flight when the check started.
	 *
	 * That list is not drawn (the screen shows a shimmer while loading); it is here so the
	 * running work survives the check. Without it, every mutation had to publish a Success,
	 * because Loading had nowhere to put the list — so a single progress tick arriving during
	 * a manual Refresh turned the screen into "all up to date" in the middle of the check, and
	 * setSuccess then found no in-flight entries to carry over, so a still-downloading card
	 * came back reading "Update". Tapping it started a second download of the same app.
	 */
	data class Loading(
		val completed: Int = 0,
		val total: Int = 0,
		val updates: List<AppUpdate> = emptyList()
	): UpdatesUiState()
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

	fun mutableUpdates(): MutableList<AppUpdate> = when (this) {
		is Success -> updates.toMutableList()
		is Loading -> updates.toMutableList()
		else -> mutableListOf()
	}

	fun updates(): List<AppUpdate> = when (this) {
		is Success -> updates
		is Loading -> updates
		else -> emptyList()
	}

	/**
	 * Publishes a changed list WITHOUT changing which state we are in. Every mutation goes
	 * through this, inside `state.update {}`, so that concurrent writers — progress ticks on
	 * Main, install results on IO — cannot overwrite each other, and so that a write during a
	 * check does not end the check early.
	 */
	fun withUpdates(updates: List<AppUpdate>): UpdatesUiState = when (this) {
		is Loading -> copy(updates = updates)
		is Success -> Success(updates)
		// Error has no list to change, and turning it into an empty Success would quietly
		// replace a failed check with "all up to date".
		else -> this
	}

}

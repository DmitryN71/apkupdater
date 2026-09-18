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
		val updates: List<AppUpdate> = emptyList(),
		/** The screen was [Idle] when this check started. See [restored]. */
		val wasIdle: Boolean = false,
		/** The [Success.only] of the screen this check replaced. See [restored]. */
		val wasOnly: Source? = null
	): UpdatesUiState() {
		/**
		 * What goes back on screen when a check ends without publishing anything — stopped, or
		 * failed before any source answered.
		 *
		 * This used to be a bare Success of whatever the check was carrying, so a check stopped
		 * before anything answered claimed "All up to date" on an empty list — never true, since
		 * nothing had been checked. With checking at launch now optional that would be the very
		 * first thing many users saw, so a check that started from nothing goes back to nothing.
		 */
		fun restored(): UpdatesUiState =
			if (wasIdle && updates.isEmpty()) Idle else Success(updates, wasOnly)
	}

	/**
	 * Nothing has been checked yet: the app was opened with checking at launch turned off.
	 *
	 * Deliberately not an empty [Success], which the screen draws as "All up to date" — a claim
	 * with nothing behind it. This one asks for a check instead.
	 */
	data object Idle : UpdatesUiState()
	data object Error : UpdatesUiState()

	/**
	 * @property only set when the check behind this list covered that one source alone, so an
	 * empty list can say "GitHub: no updates" instead of claiming every source is up to date.
	 */
	data class Success(val updates: List<AppUpdate>, val only: Source? = null): UpdatesUiState()

	inline fun onLoading(block: (Loading) -> Unit): UpdatesUiState {
		if (this is Loading) block(this)
		return this
	}

	inline fun onIdle(block: () -> Unit): UpdatesUiState {
		if (this is Idle) block()
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
		is Success -> copy(updates = updates)
		// Error and Idle have no list to change, and turning either into an empty Success
		// would quietly replace it with "all up to date".
		else -> this
	}

}

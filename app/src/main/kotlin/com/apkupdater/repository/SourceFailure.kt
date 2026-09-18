package com.apkupdater.repository

import java.util.concurrent.atomic.AtomicInteger


/**
 * Thrown by a source whose every request failed, so that UpdatesRepository counts it as a failed
 * source rather than as one that answered "nothing new".
 */
class SourceUnreachable(source: String, cause: Throwable?) :
	Exception("Every $source request failed", cause)

/**
 * Tallies the failures across the separate requests one source makes during a check — one per
 * repository for GitHub and GitLab, one per device kind for RuStore, one per chunk of apps for
 * APKMirror.
 *
 * Each of those requests catches its own error, so that one missing repository does not sink the
 * rest. The price was that a source with no network at all came back as "no updates", exactly
 * like a real answer — and a check of that one source said so on the screen. Only when EVERY
 * request failed is the source itself reported as failed; one bad repository is still just a
 * line in the log.
 */
class FailureTally {
	private val failures = AtomicInteger(0)

	@Volatile
	private var last: Throwable? = null

	fun record(error: Throwable) {
		last = error
		failures.incrementAndGet()
	}

	/** Throws [SourceUnreachable] when at least one request was made and every one failed. */
	fun throwIfAllFailed(requests: Int, source: String) {
		if (requests > 0 && failures.get() >= requests) throw SourceUnreachable(source, last)
	}
}

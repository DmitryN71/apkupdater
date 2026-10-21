package com.apkupdater.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


/**
 * The app's own record of what happened to installs and checks (build 186), kept in a small file
 * and handed over by "Copy app logs" ahead of the system log.
 *
 * The system log (`logcat`) is not ours to keep. Two testers' copies came back empty or nearly
 * so: one ROM held six seconds of it, another gave an app none of its own lines at all — and a
 * report of "the first install attempt always fails" arrived with nothing to read. This file
 * survives both, and a restart.
 *
 * Only the events a report needs: an install started and how, what Android answered, the
 * confirmation and how it was shown, a failure and its reason, a source that did not answer.
 * Each line also goes to the system log, as before.
 */
object AppJournal {

	/** Past this the older half is dropped; a few days of events fit easily. */
	private const val MAX_BYTES = 256 * 1024

	@Volatile
	private var file: File? = null

	private val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

	fun init(context: Context) {
		file = File(context.filesDir, "journal.log")
	}

	fun add(tag: String, message: String) {
		Log.i(tag, message)
		val target = file ?: return
		synchronized(this) {
			runCatching {
				if (target.length() > MAX_BYTES) {
					val text = target.readText()
					target.writeText(text.substring(text.length / 2).substringAfter('\n'))
				}
				target.appendText("${time.format(Date())} $tag: $message\n")
			}
		}
	}

	fun read(): String = synchronized(this) {
		runCatching { file?.takeIf { it.exists() }?.readText() }.getOrNull().orEmpty()
	}
}

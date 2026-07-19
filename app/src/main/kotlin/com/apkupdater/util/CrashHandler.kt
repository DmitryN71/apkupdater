package com.apkupdater.util

import android.content.Context
import android.os.Build
import com.apkupdater.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Date


/**
 * Persists the last uncaught exception to a file so the crash can be shared from Settings on the
 * next launch. The app can't rely on the ROM's own crash reporter (e.g. the broken Pasty upload
 * seen on some GSIs) or on the user running logcat/adb.
 *
 * Only JVM/Kotlin throwables pass through here — a genuine native crash (SIGSEGV/SIGABRT, e.g.
 * from a broken su spawn) does NOT. Chains to the previous handler so the normal system crash
 * dialog still appears and the process still dies as usual.
 */
class CrashHandler private constructor(
	private val context: Context,
	private val previous: Thread.UncaughtExceptionHandler?
) : Thread.UncaughtExceptionHandler {

	override fun uncaughtException(thread: Thread, throwable: Throwable) {
		runCatching { write(thread, throwable) }
		previous?.uncaughtException(thread, throwable)
	}

	private fun write(thread: Thread, throwable: Throwable) {
		val stack = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
		val report = buildString {
			appendLine("APKUpdater crash report")
			appendLine("Time: ${Date()}")
			appendLine("Version: ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})")
			appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
			appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
			appendLine("Fingerprint: ${Build.FINGERPRINT}")
			appendLine("Thread: ${thread.name}")
			appendLine()
			append(stack)
		}
		file(context).writeText(report)
	}

	companion object {
		fun file(context: Context): File = File(context.filesDir, "last_crash.txt")

		fun install(context: Context) {
			val previous = Thread.getDefaultUncaughtExceptionHandler()
			Thread.setDefaultUncaughtExceptionHandler(CrashHandler(context.applicationContext, previous))
		}
	}
}

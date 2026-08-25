package com.apkupdater.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Typeface
import android.os.Build
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.core.content.ContextCompat
import com.apkupdater.R
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.text.DecimalFormatSymbols
import java.util.Calendar
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

// A clickable modifier that will disable the default ripple
fun Modifier.clickableNoRipple(onClick: () -> Unit) = this.
	clickable(MutableInteractionSource(), null, onClick = onClick)

// Launches a coroutine and executes it inside the mutex
fun CoroutineScope.launchWithMutex(
	mutex: Mutex,
	context: CoroutineContext = EmptyCoroutineContext,
	block: suspend CoroutineScope.() -> Unit
) = launch(context) {
	mutex.withLock {
		block()
	}
}

fun Boolean?.orFalse() = this ?: false

fun PackageInfo.name(context: Context) = applicationInfo?.loadLabel(context.packageManager)?.toString() ?: packageName

fun Context.getAppIcon(packageName: String) = runCatching {
	packageManager.getApplicationIcon(packageName)
}.getOrElse {
	Log.e("getAppIcon", "App not found. Uninstalled most likely.")
	ContextCompat.getDrawable(this, R.drawable.ic_root)
}

fun Context.getAppName(packageName: String): String = runCatching {
	packageManager.getPackageInfo(packageName, 0).name(this)
}.getOrDefault("")

inline fun <reified T> List<Flow<T>>.combine(crossinline block: suspend (Array<T>) -> Unit) =
	combine(this) { block(it) }

fun ByteArray.toSha1(): String = MessageDigest
	.getInstance("SHA-1")
	.digest(this)
	.joinToString(separator = "", transform = { "%02x".format(it) })

fun String.toSha1Aptoide(): String = chunked(2).joinToString(separator = ":") { it.uppercase() }

fun ByteArray.toSha256(): String = MessageDigest
	.getInstance("SHA-256")
	.digest(this)
	.joinToString(separator = "", transform = { "%02x".format(it) })

fun PackageInfo.getSignature(): ByteArray = runCatching {
	if (Build.VERSION.SDK_INT >= 28) {
		signingInfo?.apkContentsSigners?.get(0)?.toByteArray() ?: ByteArray(0)
	} else {
		@Suppress("DEPRECATION")
		signatures?.get(0)?.toByteArray() ?: ByteArray(0)
	}
}.getOrDefault(ByteArray(0))

fun PackageInfo.getSignatureSha1(): String = getSignature().toSha1()

fun PackageInfo.getSignatureSha256(): String = getSignature().toSha256()

fun millisUntilHour(hour: Int): Long {
	val calendar = Calendar.getInstance()
	if (calendar.get(Calendar.HOUR_OF_DAY) >= hour) calendar.add(Calendar.HOUR, 24)
	calendar.set(Calendar.HOUR_OF_DAY, hour)
	calendar.set(Calendar.MINUTE, 0)
	return calendar.timeInMillis - System.currentTimeMillis()
}

fun Intent.getIntentExtra(): Intent? = when {
	Build.VERSION.SDK_INT > 33 -> getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
	else -> @Suppress("DEPRECATION") getParcelableExtra(Intent.EXTRA_INTENT)
}

fun Intent.getAppId() = runCatching {
	action?.split(".")?.get(1)?.toInt()
}.getOrNull()

fun PackageManager.isAndroidTv() = hasSystemFeature(PackageManager.FEATURE_LEANBACK)

fun Context.isAndroidTv() = packageManager.isAndroidTv()

/** Locale-aware medium date, e.g. "18 Jul 2026" / "18 июл. 2026". */
fun formatDate(millis: Long): String =
	java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(millis))

/**
 * Parses a source release date (ISO-8601-ish: "2026-07-15T10:20:30Z",
 * with a timezone offset, or plain "2026-07-15") into a locale-formatted date.
 * Returns "" if it can't be parsed.
 */
fun formatIsoDate(raw: String): String {
	val s = raw.trim()
	if (s.isBlank()) return ""
	val normalized = s.replace('T', ' ').substringBefore('+').substringBefore('Z').substringBefore('.').trim()
	for (pattern in listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd")) {
		val date = runCatching {
			java.text.SimpleDateFormat(pattern, java.util.Locale.US).apply {
				isLenient = false
				timeZone = java.util.TimeZone.getTimeZone("UTC")
			}.parse(normalized)
		}.getOrNull()
		if (date != null) return formatDate(date.time)
	}
	return ""
}

/** Human-readable byte size, e.g. "142.5 MB". */
fun formatBytes(b: Long): String = when {
	b >= 1_073_741_824 -> "%.1f GB".format(b / 1_073_741_824.0)
	b >= 1_048_576 -> "%.1f MB".format(b / 1_048_576.0)
	b >= 1024 -> "%.0f KB".format(b / 1024.0)
	else -> "$b B"
}

/**
 * Size of leftover APK downloads — only the app's own download temp dir
 * (cache/downloads). Deliberately excludes other cache (Coil images, HTTP
 * metadata, gplayapi data) which is not ours to count or clear.
 */
fun Context.downloadCacheSizeBytes(): Long =
	File(cacheDir, "downloads").walkBottomUp().filter { it.isFile }.sumOf { it.length() }

/** Deletes the leftover APK downloads; returns bytes freed. */
fun Context.clearDownloadCacheBytes(): Long {
	val freed = downloadCacheSizeBytes()
	runCatching {
		// Recursive: half-finished downloads waiting to be resumed live in a subdirectory,
		// and clearing the cache by hand is meant to reclaim those too.
		File(cacheDir, "downloads").listFiles()?.forEach { it.deleteRecursively() }
	}
	return freed
}

fun randomUUID() = UUID.randomUUID().toString()

fun isDark() = Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

fun Spanned.toAnnotatedString(): AnnotatedString = runCatching {
	buildAnnotatedString {
		val spanned = this@toAnnotatedString
		val text = spanned.toString()
		append(text)
		getSpans(0, spanned.length, Any::class.java).forEach { span ->
			val start = getSpanStart(span)
			val end = getSpanEnd(span)
			if (start < 0 || end < 0 || start > end || end > text.length) return@forEach
			when (span) {
				is StyleSpan -> when (span.style) {
					Typeface.BOLD -> addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, end)
					Typeface.ITALIC -> addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, end)
					Typeface.BOLD_ITALIC -> addStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic), start, end)
				}
				is UnderlineSpan -> addStyle(SpanStyle(textDecoration = TextDecoration.Underline), start, end)
				is ForegroundColorSpan -> addStyle(SpanStyle(color = Color(span.foregroundColor)), start, end)
			}
		}
	}
}.getOrElse { AnnotatedString(this.toString()) }

fun OkHttpClient.Builder.addUserAgentInterceptor(agent: String) = addNetworkInterceptor {
	it.proceed(it.request().newBuilder().header("User-Agent", agent).build())
}

fun filterVersionTag(version: String) = version
	.replace(Regex("^\\D*"), "")
	//.replace(Regex("\\D+\$"), "") // In case we want to remove non-numeric at end too

fun Float.to2f() = String
	.format("%.2f", this)
	.replace('.', DecimalFormatSymbols.getInstance(Locale.getDefault()).decimalSeparator)

/**
 * Returns true if [newVersion] looks like a downgrade compared to [oldVersion].
 * Compares numeric segments left-to-right: "16.21.22" vs "16.15.10" → 21 > 15 → downgrade.
 * Returns false (not a downgrade) if either version is empty or non-parseable.
 */
fun isVersionDowngrade(oldVersion: String, newVersion: String): Boolean {
	if (oldVersion.isBlank() || newVersion.isBlank()) return false
	val oldParts = oldVersion.split(Regex("[.\\-_+]")).mapNotNull { it.filter(Char::isDigit).toLongOrNull() }
	val newParts = newVersion.split(Regex("[.\\-_+]")).mapNotNull { it.filter(Char::isDigit).toLongOrNull() }
	if (oldParts.isEmpty() || newParts.isEmpty()) return false
	val len = minOf(oldParts.size, newParts.size)
	for (i in 0 until len) {
		if (newParts[i] < oldParts[i]) return true
		if (newParts[i] > oldParts[i]) return false
	}
	return false
}

package com.apkupdater.util

import android.os.Build
import android.webkit.WebView

private val KNOWN_ABIS = setOf(
	"arm64-v8a",
	"armeabi-v7a",
	"armeabi",
	"x86_64",
	"x86",
	"mips64",
	"mips"
)

/**
 * Queries Android's WebViewUpdateService (Binder IPC only – never loads libwebviewchromium.so)
 * for the package currently configured as the system WebView provider.
 */
fun currentWebViewPackageName(): String? {
	if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
	return runCatching { WebView.getCurrentWebViewPackage()?.packageName }.getOrNull()
}

fun isGoogleOrAospWebViewPackage(packageName: String): Boolean =
	packageName == "com.google.android.webview" ||
		packageName.startsWith("com.google.android.webview.") ||
		packageName == "com.android.webview"

fun isSystemWebViewPackage(
	packageName: String,
	activeWebViewPackage: String? = currentWebViewPackageName(),
	sdkInt: Int = Build.VERSION.SDK_INT
): Boolean {
	if (isGoogleOrAospWebViewPackage(packageName)) return true
	if (packageName == "com.huawei.webview" ||
		packageName == "org.cromite.webview" ||
		packageName == "us.spotco.mulch_wv" ||
		packageName.endsWith(".webview")
	) {
		return true
	}
	if (!activeWebViewPackage.isNullOrEmpty() && packageName == activeWebViewPackage) {
		// On Android 7–9 (API 24–28), Monochrome Chrome could act as the system WebView provider;
		// on Android 10+ (Trichrome), standalone Chrome APKs ship single-ABI builds.
		if (!packageName.startsWith("com.android.chrome") || sdkInt < Build.VERSION_CODES.Q) {
			return true
		}
	}
	return false
}

fun isHybrid64BitDevice(
	supported64BitAbis: List<String> = Build.SUPPORTED_64_BIT_ABIS.toList(),
	supported32BitAbis: List<String> = Build.SUPPORTED_32_BIT_ABIS.toList()
): Boolean = supported64BitAbis.isNotEmpty() && supported32BitAbis.isNotEmpty()

/**
 * Checks whether an APKMirror/APKPure variant's [apkArches] can safely be installed on this device.
 *
 * System WebView providers are loaded in-process via `dlopen()` by other apps rather than running
 * solely in an ABI-matched Zygote of their own: installing a 32-bit-only `armeabi-v7a` WebView on
 * an `arm64-v8a` device breaks every 64-bit app that opens a WebView with `UnsatisfiedLinkError`,
 * and installing a 64-bit-only Google WebView on a dual-ABI (64+32-bit) device breaks 32-bit apps.
 *
 * When [only64Bit] is enabled on a hybrid 64/32-bit device, regular apps must match one of
 * [supported64BitAbis] (universal/noarch builds still pass), while system WebView keeps the strict
 * dual-ABI rule so 32-bit host apps continue working.
 */
fun isApkMirrorArchCompatible(
	apkArches: List<String>,
	packageName: String,
	supportedAbis: List<String> = Build.SUPPORTED_ABIS.toList(),
	supported64BitAbis: List<String> = Build.SUPPORTED_64_BIT_ABIS.toList(),
	supported32BitAbis: List<String> = Build.SUPPORTED_32_BIT_ABIS.toList(),
	activeWebViewPackage: String? = currentWebViewPackageName(),
	sdkInt: Int = Build.VERSION.SDK_INT,
	only64Bit: Boolean = false
): Boolean {
	if (apkArches.isEmpty() || "universal" in apkArches || "noarch" in apkArches) {
		return true
	}

	if (isSystemWebViewPackage(packageName, activeWebViewPackage, sdkInt)) {
		val primary = supportedAbis.firstOrNull()
		if (primary != null && primary !in apkArches) return false
		if (isGoogleOrAospWebViewPackage(packageName) &&
			supported64BitAbis.isNotEmpty() &&
			supported32BitAbis.isNotEmpty()
		) {
			return apkArches.any { it in supported64BitAbis } &&
				apkArches.any { it in supported32BitAbis }
		}
		return apkArches.any { it in supportedAbis }
	}

	val allowedAbis = if (only64Bit && supported64BitAbis.isNotEmpty()) supported64BitAbis else supportedAbis
	if (apkArches.any { it in allowedAbis }) return true

	// Fallback only for non-standard arch labels; never match "armeabi-v7a" to "arm64-v8a"
	// on 64-bit-only devices or when only64Bit is enabled.
	return apkArches.any { arch ->
		arch !in KNOWN_ABIS && when (arch) {
			"arm64" -> "arm64-v8a" in allowedAbis
			"arm" -> !only64Bit && allowedAbis.any { it.startsWith("arm") }
			else -> false
		}
	}
}

/**
 * Ranks variants of the same `versionCode`: native primary ABI (e.g. `arm64-v8a`) ahead of
 * universal builds, ahead of secondary 32-bit compatibility ABIs (`armeabi-v7a`).
 */
fun apkMirrorArchScore(
	apkArches: List<String>,
	supportedAbis: List<String> = Build.SUPPORTED_ABIS.toList()
): Int {
	val primary = supportedAbis.firstOrNull()
	return when {
		primary != null && primary in apkArches -> 3
		apkArches.isEmpty() || "universal" in apkArches || "noarch" in apkArches -> 2
		apkArches.any { it in supportedAbis } -> 1
		else -> 0
	}
}

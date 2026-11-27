package com.apkupdater.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewAbiTest {

	private val dualArmAbis = listOf("arm64-v8a", "armeabi-v7a", "armeabi")
	private val dualArm64 = listOf("arm64-v8a")
	private val dualArm32 = listOf("armeabi-v7a", "armeabi")

	private val arm64OnlyAbis = listOf("arm64-v8a")

	@Test
	fun rejects32BitOnlyWebViewOnDualAbiArm64HeadUnit() {
		// Exact case from sprd ums512_1h10_Natv (Android 10): WebView 159.0.8154.18 (815401810)
		assertFalse(
			isApkMirrorArchCompatible(
				apkArches = listOf("armeabi-v7a"),
				packageName = "com.google.android.webview",
				supportedAbis = dualArmAbis,
				supported64BitAbis = dualArm64,
				supported32BitAbis = dualArm32,
				activeWebViewPackage = "com.google.android.webview",
				sdkInt = 29
			)
		)
	}

	@Test
	fun rejects64BitOnlyGoogleWebViewOnDualAbiDevice() {
		assertFalse(
			isApkMirrorArchCompatible(
				apkArches = listOf("arm64-v8a"),
				packageName = "com.google.android.webview.beta",
				supportedAbis = dualArmAbis,
				supported64BitAbis = dualArm64,
				supported32BitAbis = dualArm32,
				activeWebViewPackage = "com.google.android.webview",
				sdkInt = 29
			)
		)
	}

	@Test
	fun acceptsMultiAbiGoogleWebViewOnDualAbiDevice() {
		// WebView 158.0.8127.46 (812704603): arm64-v8a + armeabi-v7a
		assertTrue(
			isApkMirrorArchCompatible(
				apkArches = listOf("arm64-v8a", "armeabi-v7a"),
				packageName = "com.google.android.webview",
				supportedAbis = dualArmAbis,
				supported64BitAbis = dualArm64,
				supported32BitAbis = dualArm32,
				activeWebViewPackage = "com.google.android.webview",
				sdkInt = 29
			)
		)
	}

	@Test
	fun accepts64BitOnlyWebViewAndRejects32BitNormalAppsOnArm64OnlyPhone() {
		// Modern 64-bit-only phone (e.g. Galaxy S26 Ultra) with empty SUPPORTED_32_BIT_ABIS
		assertTrue(
			isApkMirrorArchCompatible(
				apkArches = listOf("arm64-v8a"),
				packageName = "com.google.android.webview",
				supportedAbis = arm64OnlyAbis,
				supported64BitAbis = dualArm64,
				supported32BitAbis = emptyList(),
				activeWebViewPackage = "com.google.android.webview",
				sdkInt = 35
			)
		)
		assertFalse(
			isApkMirrorArchCompatible(
				apkArches = listOf("armeabi-v7a"),
				packageName = "com.example.legacy32",
				supportedAbis = arm64OnlyAbis,
				supported64BitAbis = dualArm64,
				supported32BitAbis = emptyList(),
				activeWebViewPackage = "com.google.android.webview",
				sdkInt = 35
			)
		)
	}

	@Test
	fun prefersNativePrimaryAbiOverCompatibilityAbi() {
		val nativeScore = apkMirrorArchScore(listOf("arm64-v8a"), dualArmAbis)
		val universalScore = apkMirrorArchScore(listOf("universal"), dualArmAbis)
		val compat32Score = apkMirrorArchScore(listOf("armeabi-v7a"), dualArmAbis)
		assertTrue(nativeScore > universalScore)
		assertTrue(universalScore > compat32Score)
	}

	@Test
	fun only64BitRejects32BitAppsOnHybridDeviceWhileKeepingWebViewDualAbi() {
		// Regular 32-bit app: allowed when only64Bit=false, rejected when only64Bit=true
		assertTrue(
			isApkMirrorArchCompatible(
				apkArches = listOf("armeabi-v7a"),
				packageName = "com.example.legacy32",
				supportedAbis = dualArmAbis,
				supported64BitAbis = dualArm64,
				supported32BitAbis = dualArm32,
				activeWebViewPackage = "com.google.android.webview",
				sdkInt = 34,
				only64Bit = false
			)
		)
		assertFalse(
			isApkMirrorArchCompatible(
				apkArches = listOf("armeabi-v7a"),
				packageName = "com.example.legacy32",
				supportedAbis = dualArmAbis,
				supported64BitAbis = dualArm64,
				supported32BitAbis = dualArm32,
				activeWebViewPackage = "com.google.android.webview",
				sdkInt = 34,
				only64Bit = true
			)
		)
		// Universal build still passes when only64Bit=true
		assertTrue(
			isApkMirrorArchCompatible(
				apkArches = listOf("universal"),
				packageName = "com.example.universal",
				supportedAbis = dualArmAbis,
				supported64BitAbis = dualArm64,
				supported32BitAbis = dualArm32,
				activeWebViewPackage = "com.google.android.webview",
				sdkInt = 34,
				only64Bit = true
			)
		)
		// System WebView on hybrid device STILL rejects 64-bit-only even when only64Bit=true
		assertFalse(
			isApkMirrorArchCompatible(
				apkArches = listOf("arm64-v8a"),
				packageName = "com.google.android.webview",
				supportedAbis = dualArmAbis,
				supported64BitAbis = dualArm64,
				supported32BitAbis = dualArm32,
				activeWebViewPackage = "com.google.android.webview",
				sdkInt = 34,
				only64Bit = true
			)
		)
	}
}

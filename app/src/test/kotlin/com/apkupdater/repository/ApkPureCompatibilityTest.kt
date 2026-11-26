package com.apkupdater.repository

import com.apkupdater.data.apkpure.AppUpdateResponse
import com.apkupdater.data.apkpure.AppUpdateResponseAsset
import com.apkupdater.data.apkpure.AppUpdateResponseIcon
import com.apkupdater.data.apkpure.AppUpdateResponseIconData
import com.apkupdater.util.formatIsoDate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkPureCompatibilityTest {

	private val dualArmAbis = listOf("arm64-v8a", "armeabi-v7a", "armeabi")
	private val dualArm64 = listOf("arm64-v8a")
	private val dualArm32 = listOf("armeabi-v7a", "armeabi")
	private val arm64OnlyAbis = listOf("arm64-v8a")

	private fun response(
		packageName: String = "com.example.app",
		nativeCode: List<String>? = null,
		sdkVersion: String? = "23",
		updateDate: String? = "2026-11-25T20:00:49+07:00"
	) = AppUpdateResponse(
		package_name = packageName,
		version_code = 100L,
		version_name = "1.0.0",
		sign = emptyList(),
		whatsnew = "",
		description_short = "",
		label = "Test App",
		asset = AppUpdateResponseAsset(type = "APK", size = 1024L, url = "https://example.com/app.apk"),
		icon = AppUpdateResponseIcon(
			original = AppUpdateResponseIconData(height = "0", width = "0", url = ""),
			thumbnail = AppUpdateResponseIconData(height = "0", width = "0", url = "")
		),
		native_code = nativeCode,
		sdk_version = sdkVersion,
		update_date = updateDate
	)

	@Test
	fun rejectsSingleAbiWebViewOnDualAbiDevice() {
		assertFalse(
			isApkPureCompatible(
				update = response("com.google.android.webview", listOf("armeabi-v7a")),
				supportedAbis = dualArmAbis,
				supported64BitAbis = dualArm64,
				supported32BitAbis = dualArm32,
				activeWebViewPackage = "com.google.android.webview",
				sdkInt = 29
			)
		)
		assertFalse(
			isApkPureCompatible(
				update = response("com.google.android.webview", listOf("arm64-v8a")),
				supportedAbis = dualArmAbis,
				supported64BitAbis = dualArm64,
				supported32BitAbis = dualArm32,
				activeWebViewPackage = "com.google.android.webview",
				sdkInt = 29
			)
		)
	}

	@Test
	fun acceptsDualAbiWebViewOnDualAbiDevice() {
		assertTrue(
			isApkPureCompatible(
				update = response("com.google.android.webview", listOf("arm64-v8a", "armeabi-v7a")),
				supportedAbis = dualArmAbis,
				supported64BitAbis = dualArm64,
				supported32BitAbis = dualArm32,
				activeWebViewPackage = "com.google.android.webview",
				sdkInt = 29
			)
		)
	}

	@Test
	fun accepts64BitOnlyWebViewAndRejects32BitAppOnArm64OnlyDevice() {
		assertTrue(
			isApkPureCompatible(
				update = response("com.google.android.webview", listOf("arm64-v8a")),
				supportedAbis = arm64OnlyAbis,
				supported64BitAbis = dualArm64,
				supported32BitAbis = emptyList(),
				activeWebViewPackage = "com.google.android.webview",
				sdkInt = 35
			)
		)
		assertFalse(
			isApkPureCompatible(
				update = response("com.example.legacy", listOf("armeabi-v7a")),
				supportedAbis = arm64OnlyAbis,
				supported64BitAbis = dualArm64,
				supported32BitAbis = emptyList(),
				activeWebViewPackage = "com.google.android.webview",
				sdkInt = 35
			)
		)
	}

	@Test
	fun rejectsUpdateWhenMinSdkExceedsDeviceSdk() {
		assertFalse(
			isApkPureCompatible(
				update = response("com.example.app", listOf("arm64-v8a"), sdkVersion = "35"),
				supportedAbis = dualArmAbis,
				supported64BitAbis = dualArm64,
				supported32BitAbis = dualArm32,
				activeWebViewPackage = "com.google.android.webview",
				sdkInt = 34
			)
		)
		assertTrue(
			isApkPureCompatible(
				update = response("com.example.app", listOf("arm64-v8a"), sdkVersion = "34"),
				supportedAbis = dualArmAbis,
				supported64BitAbis = dualArm64,
				supported32BitAbis = dualArm32,
				activeWebViewPackage = "com.google.android.webview",
				sdkInt = 34
			)
		)
	}

	@Test
	fun acceptsMissingOrNoarchNativeCodeAndBlankSdk() {
		assertTrue(
			isApkPureCompatible(
				update = response("com.example.app", nativeCode = null, sdkVersion = null),
				supportedAbis = arm64OnlyAbis,
				supported64BitAbis = dualArm64,
				supported32BitAbis = emptyList(),
				activeWebViewPackage = "com.google.android.webview",
				sdkInt = 34
			)
		)
		assertTrue(
			isApkPureCompatible(
				update = response("com.example.app", nativeCode = emptyList(), sdkVersion = ""),
				supportedAbis = arm64OnlyAbis,
				supported64BitAbis = dualArm64,
				supported32BitAbis = emptyList(),
				activeWebViewPackage = "com.google.android.webview",
				sdkInt = 34
			)
		)
	}

	@Test
	fun formatsApkPureUpdateDate() {
		assertTrue(formatIsoDate("2026-11-25T20:00:49+07:00").isNotEmpty())
	}
}

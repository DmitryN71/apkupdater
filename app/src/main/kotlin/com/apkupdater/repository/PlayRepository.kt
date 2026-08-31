package com.apkupdater.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import com.apkupdater.data.ui.AppInstalled
import com.apkupdater.data.ui.AppUpdate
import com.apkupdater.data.ui.Link
import com.apkupdater.data.ui.PlaySource
import com.apkupdater.data.ui.getPackageNames
import com.apkupdater.data.ui.getVersion
import com.apkupdater.data.ui.getVersionCode
import com.apkupdater.prefs.Prefs
import com.apkupdater.util.play.NativeDeviceInfoProvider
import com.apkupdater.util.play.PlayHttpClient
import com.aurora.gplayapi.data.models.App
import com.aurora.gplayapi.data.models.AuthData
import com.aurora.gplayapi.data.models.File
import com.aurora.gplayapi.helpers.AppDetailsHelper
import com.aurora.gplayapi.helpers.PurchaseHelper
import com.aurora.gplayapi.helpers.SearchHelper
import com.google.gson.Gson
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong


class PlayRepository(
    private val context: Context,
    private val playHttpClient: PlayHttpClient,
    private val gson: Gson,
    private val prefs: Prefs
) {
    companion object {
        const val AUTH_URL = "https://auroraoss.com/api/auth"
        // Bump whenever getNativeDeviceProperties() changes in a way Play must be told about.
        // 1 = report the device's own locales first, so Play stops defaulting to English splits.
        const val DEVICE_PROFILE_VERSION = 1
        /** Shared with every Aurora user, so don't ask it for a new account more often. */
        private const val FORCED_AUTH_COOLDOWN_MS = 60_000L
    }

    private val lastForcedAuth = AtomicLong(0L)

    private fun refreshAuth(): AuthData {
        Log.i("PlayRepository", "Refreshing token.")
        val properties = NativeDeviceInfoProvider(context).getNativeDeviceProperties()
        val playResponse = playHttpClient.postAuth(AUTH_URL, gson.toJson(properties).toByteArray())
        if (playResponse.isSuccessful) {
            val authData = gson.fromJson(String(playResponse.responseBytes), AuthData::class.java)
            prefs.playAuthData.put(authData)
            prefs.playProfileVersion.put(DEVICE_PROFILE_VERSION)
            // Stored as received; the locale is applied on every use, including here — the
            // other auth() paths return through refreshAuth() and would otherwise skip it.
            return authData.withDeviceLocale()
        }
        throw IllegalStateException("Auth not successful.")
    }

    private fun auth(): AuthData {
        val savedData = prefs.playAuthData.get()
        if (savedData.email.isEmpty()) {
            return refreshAuth()
        }
        if (prefs.playProfileVersion.get() != DEVICE_PROFILE_VERSION) {
            // Saved session was created with an older device profile — recreate it once so the
            // new properties (locales) actually take effect.
            Log.i("PlayRepository", "Device profile changed, re-authenticating.")
            return refreshAuth()
        }
        if (System.currentTimeMillis() - prefs.lastPlayCheck.get() > 60 * 60 * 1_000) {
            // Update check time
            prefs.lastPlayCheck.put(System.currentTimeMillis())
            Log.i("PlayRepository", "Checking token validity.")

            // 1h has passed check if token still works
            val app = runCatching {
                AppDetailsHelper(savedData)
                    .using(playHttpClient)
                    .getAppByPackageName("com.google.android.gm")
            }.getOrElse {
                return refreshAuth()
            }

            if (app.packageName.isEmpty()) {
                return refreshAuth()
            }
            Log.i("PlayRepository", "Token still valid.")
        }
        return savedData.withDeviceLocale()
    }

    /**
     * Makes the Play session speak the user's language.
     *
     * gplayapi builds the `Accept-Language` and `X-DFE-UserLanguages` request headers from
     * [AuthData.locale] — and Play picks which language splits to deliver from those. The
     * locale comes with the anonymous account handed out by the auth service, so it was
     * whatever that account was created as (English), which is why updating e.g. Gmail through
     * this app turned it English. The device properties uploaded at auth time do NOT drive
     * this; only the session locale does.
     */
    private fun AuthData.withDeviceLocale() = apply {
        runCatching { locale = Locale.getDefault() }
            .onFailure { Log.e("PlayRepository", "Could not set session locale.", it) }
    }

    suspend fun search(text: String) = flow {
        if (text.contains(" ") || !text.contains(".")) {
            // Normal Search
            val authData = auth()
            val updates = SearchHelper(authData)
                .using(playHttpClient)
                .searchResults(text)
                .appList
                .take(10)
                .map { it.toAppUpdate(::getInstallFiles) }
            emit(Result.success(updates))
        } else {
            // Package Name Search
            val authData = auth()
            val update = AppDetailsHelper(authData)
                .using(playHttpClient)
                .getAppByPackageName(text)
                .toAppUpdate(::getInstallFiles)
            emit(Result.success(listOf(update)))
        }
    }.catch {
        emit(Result.failure(it))
        Log.e("PlayRepository", "Error searching for $text.", it)
    }

    suspend fun updates(apps: List<AppInstalled>) = flow {
        val authData = auth()
        val details = AppDetailsHelper(authData)
            .using(playHttpClient)
            .getAppByPackageName(apps.getPackageNames())
        // Play answers for some apps with no version at all: it has no build for this session's
        // device profile — a TV-only app asked about from a phone, a region-locked listing. Those
        // used to vanish into the same filter as "already up to date", so an app Play will never
        // serve looked exactly like an app that needs nothing, and the user had nowhere to learn
        // why it never updates. Trying to install it now says so (InstallViewModel.playErrorResId);
        // here they are at least named in the log so a forum report can be answered.
        val (unavailable, offered) = details.partition { it.versionCode <= 0 }
        // Only name the ones Play actually identified: a throttled bulkDetails answers with
        // hundreds of blank records, and logging those produced a screenful of commas.
        val named = unavailable.map { it.packageName }.filter { it.isNotBlank() }
        if (named.isNotEmpty()) {
            Log.w("PlayRepository", "No version offered for: ${named.joinToString()}")
        }
        val updates = offered
            .filter { it.versionCode > apps.getVersionCode(it.packageName) }
            .map {
                it.toAppUpdate(
                    ::getInstallFiles,
                    apps.getVersion(it.packageName),
                    apps.getVersionCode(it.packageName)
                )
            }
        emit(updates)
    }.catch {
        emit(emptyList())
        Log.e("PlayRepository", "Error looking for updates.", it)
    }

    /**
     * Asks Play for an app's files, and if it refuses, tries once more on a different anonymous
     * account.
     *
     * The refusal looks like HTTP 429 on `/fdfe/delivery` while `/fdfe/purchase` still answers
     * 200, and gplayapi hands back file entries with an empty url rather than an error. The
     * limit is bound to the ACCOUNT, not to the device or the IP — Aurora Store users hitting
     * the same wall report that a VPN (three different servers) and wiping app data both change
     * nothing, while asking the dispenser for another anonymous account makes updates work
     * again. Dmitry saw the same thing by hand: the twentieth attempt succeeded, because every
     * attempt re-requests the link.
     *
     * So: one retry on a fresh account, no more often than [FORCED_AUTH_COOLDOWN_MS], because
     * the dispenser is shared with every Aurora user and a batch of ten failing apps must not
     * turn into ten sign-ups. Not a cure — for some users on that thread only a real Google
     * account helped — but it automates the retry that does work.
     */
    private fun getInstallFiles(app: App): List<File> {
        val files = purchaseFiles(app, auth())
        if (files.isNotEmpty()) return files

        val now = System.currentTimeMillis()
        val previous = lastForcedAuth.get()
        if (now - previous < FORCED_AUTH_COOLDOWN_MS) return files
        if (!lastForcedAuth.compareAndSet(previous, now)) return files

        Log.i("PlayRepository", "No download link for ${app.packageName}; trying a fresh account.")
        // Only the sign-up is guarded: a purchase that THROWS carries the real reason
        // (AppNotSupported, AppNotPurchased, AppRemoved) and must reach the caller intact.
        val fresh = runCatching { refreshAuth() }.getOrElse {
            Log.e("PlayRepository", "Could not get a fresh anonymous account", it)
            return files
        }
        return purchaseFiles(app, fresh)
    }

    private fun purchaseFiles(app: App, authData: AuthData): List<File> {
        val files = PurchaseHelper(authData)
            .using(playHttpClient)
            .purchase(app.packageName, app.versionCode, app.offerType)
            .filter { it.type == File.FileType.BASE || it.type == File.FileType.SPLIT }
        // All or nothing on purpose: dropping only the blank ones could leave a base APK without
        // one of its splits, which installs as a broken app instead of failing honestly. An empty
        // list is what the install paths already know how to explain.
        return if (files.any { it.url.isBlank() }) emptyList() else files
    }

}

fun App.toAppUpdate(
    getInstallFiles: (App) -> List<File>,
    oldVersion: String = "",
    oldVersionCode: Long = 0L
) = AppUpdate(
    displayName,
    packageName,
    versionName,
    oldVersion,
    versionCode.toLong(),
    oldVersionCode,
    PlaySource,
    Uri.parse(iconArtwork.url),
    Link.Play { getInstallFiles(this) },
    whatsNew = changes,
    sourceUrl = "https://play.google.com/store/apps/details?id=$packageName",
    updateDate = updatedOn.orEmpty()
)

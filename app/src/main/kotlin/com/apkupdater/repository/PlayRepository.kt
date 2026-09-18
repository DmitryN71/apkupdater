package com.apkupdater.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import com.apkupdater.data.play.DispenserAuth
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
import com.aurora.gplayapi.data.models.PlayFile
import com.aurora.gplayapi.exceptions.GooglePlayException
import com.aurora.gplayapi.helpers.AppDetailsHelper
import com.aurora.gplayapi.helpers.AuthHelper
import com.aurora.gplayapi.helpers.PurchaseHelper
import com.aurora.gplayapi.helpers.SearchHelper
import com.google.gson.Gson
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong


/** How a manual switch of the anonymous Play account ended. See [PlayRepository.switchAccount]. */
sealed interface PlayAccountSwitch {
    data object Done : PlayAccountSwitch
    /** Refused without asking the dispenser: the last switch was less than a minute ago. */
    data object TooSoon : PlayAccountSwitch
    data class Failed(val reason: String) : PlayAccountSwitch
}

class PlayRepository(
    private val context: Context,
    private val playHttpClient: PlayHttpClient,
    private val gson: Gson,
    private val prefs: Prefs
) {
    companion object {
        const val AUTH_URL = "https://auroraoss.com/api/auth"
        // Bump whenever getNativeDeviceProperties() changes in a way Play must be told about, or
        // whenever the saved session's format changes.
        // 1 = report the device's own locales first, so Play stops defaulting to English splits.
        // 2 = the session is built on the device and stored by the library's serialiser (142).
        const val DEVICE_PROFILE_VERSION = 2
        /** Shared with every Aurora user, so don't ask it for a new account more often. */
        private const val FORCED_AUTH_COOLDOWN_MS = 60_000L
        /** How often a saved session is proved still to work. */
        private const val VALIDITY_INTERVAL_MS = 60 * 60 * 1_000L
        /** How soon to try again when proving it failed AND a fresh sign-in failed too. */
        private const val VALIDITY_RETRY_MS = 5 * 60 * 1_000L
    }

    private val lastForcedAuth = AtomicLong(0L)

    /**
     * Every sign-in and every read of the saved session goes through this lock.
     *
     * There are four ways to a sign-in — no session yet, the hourly validity check, the automatic
     * retry on a refused download, and the menu's Switch account — and they run on different
     * threads: a check, several parallel downloads, a tap. Unlocked, two of them could ask the
     * shared dispenser for accounts at the same moment, and the once-a-minute limit, checked and
     * set in two steps, did not stop them. Held only for the sign-in and the hourly check, never
     * for the requests that use the session afterwards.
     */
    private val authLock = Any()

    /** Bumped by every successful sign-in, so a failed request can tell its session was replaced. */
    private val sessionGeneration = AtomicInteger(0)

    /**
     * The library's own serialiser for its own session class. Gson used to do this, and only
     * worked by accident: it fills fields behind the constructor's back, so the device-profile
     * object inside the session was never actually constructed — its computed fields simply
     * happened to be present in the JSON the dispenser sent. Unknown keys are ignored so a
     * session written by a newer library still loads.
     */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Signs in anonymously and builds a full Play session.
     *
     * The dispenser is asked for an ACCOUNT only — an e-mail and an auth token. The session
     * itself (GSF id, device-config token, user profile) is built here, on the device, by
     * AuthHelper, which is what Aurora Store has done since 4.8. Until build 142 we read the
     * dispenser's whole serialised AuthData with Gson instead, which depended on Aurora's server
     * serialising the same class with the same field names — a coupling Aurora itself dropped.
     * The device properties go to both: the dispenser sees them, and AuthHelper uploads them to
     * Play as this session's device profile.
     */
    private fun signIn(): AuthData {
        Log.i("PlayRepository", "Refreshing token.")
        val properties = NativeDeviceInfoProvider(context).getNativeDeviceProperties()
        val playResponse = playHttpClient.postAuth(AUTH_URL, gson.toJson(properties).toByteArray())
        if (!playResponse.isSuccessful) throw IllegalStateException("Auth not successful.")
        val issued = gson.fromJson(String(playResponse.responseBytes), DispenserAuth::class.java)
        if (issued.email.isBlank() || issued.authToken.isBlank()) {
            throw IllegalStateException("Dispenser returned no account.")
        }
        val authData = AuthHelper.using(playHttpClient).build(
            email = issued.email,
            token = issued.authToken,
            tokenType = AuthHelper.Token.AUTH,
            isAnonymous = true,
            properties = properties,
            locale = Locale.getDefault()
        )
        prefs.playAuthJson.put(json.encodeToString(AuthData.serializer(), authData))
        prefs.playProfileVersion.put(DEVICE_PROFILE_VERSION)
        // A session this fresh is known to work; the hourly proof can start counting from now.
        prefs.lastPlayCheck.put(System.currentTimeMillis())
        sessionGeneration.incrementAndGet()
        return authData.withDeviceLocale()
    }

    /**
     * Swaps the anonymous account for a fresh one from the dispenser, because the user asked to.
     *
     * The automatic swap in [getInstallFiles] only fires while installing, only on a 429 or a
     * dead session, and only once a minute for the whole app — when the fresh account it gets is
     * throttled too (the pool is shared with every Aurora user, so that happens) it gives up until
     * the next minute and the next failure. Aurora Store offers the same thing by hand, and it is
     * what helped in its 4PDA thread when a VPN and wiping app data did not: the limit is bound
     * to the ACCOUNT.
     *
     * Shares [lastForcedAuth] with the automatic swap, so the two together still ask the
     * dispenser at most once a minute however hard the menu item is pressed. The minute is spent
     * before asking, as the automatic path does, so a dispenser that is down is not hammered either.
     * Blocking; call it off the main thread.
     */
    fun switchAccount(): PlayAccountSwitch = synchronized(authLock) { switchAccountLocked() }

    private fun switchAccountLocked(): PlayAccountSwitch {
        val now = System.currentTimeMillis()
        if (now - lastForcedAuth.get() < FORCED_AUTH_COOLDOWN_MS) {
            Log.i("PlayRepository", "Account switch requested too soon after the last one.")
            return PlayAccountSwitch.TooSoon
        }
        lastForcedAuth.set(now)
        Log.i("PlayRepository", "Switching the anonymous account at the user's request.")
        return try {
            refreshAuth()
            Log.i("PlayRepository", "Switched to a fresh anonymous account.")
            PlayAccountSwitch.Done
        } catch (e: Exception) {
            Log.e("PlayRepository", "Manual account switch failed", e)
            PlayAccountSwitch.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /** [signIn] under [authLock]. Reentrant, so it is safe from inside [auth] as well. */
    private fun refreshAuth(): AuthData = synchronized(authLock) { signIn() }

    /** The session to use, proved or replaced as needed — under [authLock], see there. */
    private fun auth(): AuthData = synchronized(authLock) { savedOrFreshSession() }

    private fun savedOrFreshSession(): AuthData {
        val saved = prefs.playAuthJson.get()
        if (saved.isEmpty()) {
            return refreshAuth()
        }
        if (prefs.playProfileVersion.get() != DEVICE_PROFILE_VERSION) {
            // Saved session was created with an older device profile, or in the old Gson
            // format — recreate it once so the new properties actually take effect.
            Log.i("PlayRepository", "Device profile changed, re-authenticating.")
            return refreshAuth()
        }
        // A session that no longer decodes — written by a build with a different idea of the
        // class — is worth a fresh sign-in, not a crash on every check.
        val savedData = runCatching { json.decodeFromString(AuthData.serializer(), saved) }
            .getOrElse {
                Log.w("PlayRepository", "Saved session unreadable, re-authenticating.", it)
                return refreshAuth()
            }
        val now = System.currentTimeMillis()
        if (now - prefs.lastPlayCheck.get() > VALIDITY_INTERVAL_MS) {
            Log.i("PlayRepository", "Checking token validity.")
            val valid = runCatching {
                AppDetailsHelper(savedData)
                    .using(playHttpClient)
                    .getAppByPackageName("com.google.android.gm")
                    .packageName.isNotEmpty()
            }.getOrDefault(false)
            if (!valid) {
                // Stamped as "try again in five minutes" BEFORE the sign-in, which overwrites it
                // with "now" if it succeeds. This used to stamp a full hour before even checking,
                // so when the dispenser was down at that moment the dead session stayed in use
                // for the rest of the hour and every Play check in it came back quietly empty.
                // Five minutes rather than every call, because the dispenser is shared and a
                // batch of downloads must not each ask it again.
                prefs.lastPlayCheck.put(now - VALIDITY_INTERVAL_MS + VALIDITY_RETRY_MS)
                return refreshAuth()
            }
            prefs.lastPlayCheck.put(now)
            Log.i("PlayRepository", "Token still valid.")
        }
        return savedData.withDeviceLocale()
    }

    /**
     * Makes the Play session speak the user's language.
     *
     * gplayapi builds the `Accept-Language` and `X-DFE-UserLanguages` request headers from
     * [AuthData.locale] — and Play picks which language splits to deliver from those. The
     * session is built with the device locale now, but the device locale can change under a
     * saved session, so it is re-applied on every use. AuthData is immutable since gplayapi 3.6,
     * hence copy() rather than assignment.
     */
    private fun AuthData.withDeviceLocale() = copy(locale = Locale.getDefault())

    suspend fun search(text: String) = flow {
        if (text.contains(" ") || !text.contains(".")) {
            // Normal Search.
            //
            // The library's own return value cannot be used here. Since 3.6.1 it builds every
            // cluster of one search with the SAME id and collects them into a map keyed by that
            // id, so all but the LAST survive — and the last is usually a tail group such as
            // "You might also like". That is why searching for KMPlayer returned an abandoned
            // clone and nothing else. Aurora Store has the same collapse.
            //
            // So the call below is made for its request, not its result: the library builds the
            // Play headers, which need internals we cannot reach. We then re-parse the same
            // response with its public parsing methods and walk every cluster. Ordering is
            // Play's own, so the real matches come first; duplicates across clusters are
            // dropped by package name.
            val authData = auth()
            val helper = SearchHelper(authData).using(playHttpClient)
            val collapsed = helper.searchResults(text)
            val apps = playHttpClient.takeSearchResponse()?.let { bytes ->
                runCatching {
                    val listResponse = helper.getPrefetchPayLoad(bytes).listResponse
                    if (!listResponse.hasItem()) return@runCatching emptyList<App>()
                    listResponse.item.subItemList.flatMap { helper.getAppsFromItem(it) }
                }.getOrElse {
                    Log.e("PlayRepository", "Could not re-parse the search response.", it)
                    emptyList()
                }
            }.orEmpty()
            // Falls back to whatever the library did manage to return, so a change in its
            // parsing can only cost us results, never all of them.
            val found = apps.ifEmpty { collapsed.streamClusters.values.flatMap { it.clusterAppList } }
            val updates = found
                .distinctBy { it.packageName }
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
        Log.e("PlayRepository", "Error looking for updates.", it)
        // Rethrown, not swallowed. A flow that ends without emitting was counted as Play having
        // answered "nothing", so a dead session or a refused request looked exactly like being up
        // to date — and "Check only Play" then told the user Play was fine at the very moment
        // switching the account would have helped. UpdatesRepository counts it as a failed source,
        // which is reported, and keeps Play's existing cards instead of wiping them.
        throw it
    }

    /**
     * Asks Play for an app's files, and if it is being throttled, tries once more on a different
     * anonymous account.
     *
     * The throttle is HTTP 429 on `/fdfe/delivery` while `/fdfe/purchase` still answers 200.
     * Since gplayapi 3.6 that THROWS — `GooglePlayException.Unknown` with code 429 — where the
     * old library handed back file entries with an empty url, so the retry keys on the exception
     * now. The limit is bound to the ACCOUNT, not to the device or the IP — Aurora Store users
     * hitting the same wall report that a VPN (three different servers) and wiping app data
     * both change nothing, while asking the dispenser for another anonymous account makes
     * updates work again. Dmitry saw the same thing by hand: the twentieth attempt succeeded,
     * because every attempt re-requests the link.
     *
     * So: one retry on a fresh account, no more often than [FORCED_AUTH_COOLDOWN_MS], because
     * the dispenser is shared with every Aurora user and a batch of ten failing apps must not
     * turn into ten sign-ups. Every other refusal — not supported, not purchased, removed —
     * carries its own reason and goes straight to the caller.
     */
    private fun getInstallFiles(app: App): List<PlayFile> {
        var sessionUsed = sessionGeneration.get()
        return try {
            val session = synchronized(authLock) { auth().also { sessionUsed = sessionGeneration.get() } }
            purchaseFiles(app, session)
        } catch (e: Exception) {
            // Two signals for the throttle, because only one of them is typed. A 429 on
            // /fdfe/delivery comes back as Unknown(429); a 429 on /fdfe/purchase is fed into
            // the protobuf parser unchecked by the library and surfaces as a parse error — so
            // also trust the last HTTP status our own client saw. A dead session (401,
            // AuthException) gets the same treatment: a fresh sign-in is the fix for it too,
            // and waiting for the hourly validity check to notice is an hour too long.
            //
            // The status is this thread's own, not the app-wide one: see statusOnThisThread.
            val throttled = (e is GooglePlayException.Unknown && e.code == 429) ||
                playHttpClient.statusOnThisThread() == 429
            if (!throttled && e !is GooglePlayException.AuthException) throw e
            val retryAfter = playHttpClient.retryAfterOnThisThread()

            val fresh = synchronized(authLock) {
                if (sessionGeneration.get() != sessionUsed) {
                    // Someone signed in on a fresh account while this attempt was failing — in
                    // "Update all", usually the first app to hit the same wall. Use that account
                    // instead of asking for yet another. Until now the once-a-minute limit made
                    // every later app of the batch give up, so one throttle cost nine failures
                    // with a working session already saved.
                    Log.i(
                        "PlayRepository",
                        "Play refused ${app.packageName}; retrying on the account fetched meanwhile."
                    )
                    auth()
                } else {
                    val now = System.currentTimeMillis()
                    if (now - lastForcedAuth.get() < FORCED_AUTH_COOLDOWN_MS) throw e
                    lastForcedAuth.set(now)
                    Log.i(
                        "PlayRepository",
                        "Play refused ${app.packageName} (${e.javaClass.simpleName}); trying a fresh account." +
                            if (retryAfter > 0) " Play asked for ${retryAfter}s." else ""
                    )
                    runCatching { refreshAuth() }.getOrElse { t ->
                        Log.e("PlayRepository", "Could not get a fresh anonymous account", t)
                        throw e
                    }
                }
            }
            purchaseFiles(app, fresh)
        }
    }

    private fun purchaseFiles(app: App, authData: AuthData): List<PlayFile> =
        PurchaseHelper(authData)
            .using(playHttpClient)
            .purchase(app.packageName, app.versionCode, app.offerType)
            .filter { it.type == PlayFile.Type.BASE || it.type == PlayFile.Type.SPLIT }

}

fun App.toAppUpdate(
    getInstallFiles: (App) -> List<PlayFile>,
    oldVersion: String = "",
    oldVersionCode: Long = 0L
) = AppUpdate(
    displayName,
    packageName,
    versionName,
    oldVersion,
    versionCode,
    oldVersionCode,
    PlaySource,
    Uri.parse(iconArtwork.url),
    Link.Play { getInstallFiles(this) },
    whatsNew = changes,
    sourceUrl = "https://play.google.com/store/apps/details?id=$packageName",
    updateDate = updatedOn
)

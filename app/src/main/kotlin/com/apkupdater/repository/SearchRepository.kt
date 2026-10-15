package com.apkupdater.repository

import android.util.Log
import com.apkupdater.data.github.GitProvider
import com.apkupdater.data.github.parseRepoQuery
import com.apkupdater.data.ui.ApkMirrorSource
import com.apkupdater.data.ui.ApkPureSource
import com.apkupdater.data.ui.AppUpdate
import com.apkupdater.data.ui.AppGallerySource
import com.apkupdater.data.ui.AptoideSource
import com.apkupdater.data.ui.FdroidSource
import com.apkupdater.data.ui.GitHubSource
import com.apkupdater.data.ui.GitLabSource
import com.apkupdater.data.ui.IzzySource
import com.apkupdater.data.ui.PlaySource
import com.apkupdater.data.ui.RuStoreSource
import com.apkupdater.prefs.Prefs
import com.apkupdater.util.combine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow

class SearchRepository(
    private val apkMirrorRepository: ApkMirrorRepository,
    private val fdroidRepository: FdroidRepository,
    private val izzyRepository: FdroidRepository,
    private val aptoideRepository: AptoideRepository,
    private val gitHubRepository: GitHubRepository,
    private val apkPureRepository: ApkPureRepository,
    private val gitLabRepository: GitLabRepository,
    private val playRepository: PlayRepository,
    private val ruStoreRepository: RuStoreRepository,
    private val appGalleryRepository: AppGalleryRepository,
    private val prefs: Prefs
) {

    /**
     * What one search found, and which sources failed to answer it. The names matter: a source
     * that could not be reached used to be dropped without a trace, so a search that hit
     * GitHub's hourly limit looked exactly like a search for something that does not exist.
     */
    data class Results(val apps: List<AppUpdate>, val failed: List<String>)

    fun search(text: String) = flow {
        val sources = mutableListOf<Pair<String, Flow<Result<List<AppUpdate>>>>>()
        // A link or owner/repo is a question about one repository, asked of the git hosts only
        // and whether or not those sources are switched on — the user named the place to look.
        // Sent to the stores as well it only made noise: Play took the URL for a package name.
        val repoQuery = parseRepoQuery(text)
        if (repoQuery.isNotEmpty()) {
            repoQuery.forEach { repo ->
                when (repo.platform) {
                    GitProvider.GITHUB -> sources.add(GitHubSource.name to gitHubRepository.lookup(repo.user, repo.repo))
                    GitProvider.GITLAB -> sources.add(GitLabSource.name to gitLabRepository.lookup(repo.user, repo.repo))
                }
            }
        } else {
            // The sources switched on in Settings — or all of them, when the user has asked the
            // Search tab to look everywhere (build 181); the filter row over the results then
            // narrows what came back.
            val all = prefs.searchAllSources.get()
            if (all || prefs.useApkMirror.get()) sources.add(ApkMirrorSource.name to apkMirrorRepository.search(text))
            if (all || prefs.useFdroid.get()) sources.add(FdroidSource.name to fdroidRepository.search(text))
            if (all || prefs.useIzzy.get()) sources.add(IzzySource.name to izzyRepository.search(text))
            if (all || prefs.useAptoide.get()) sources.add(AptoideSource.name to aptoideRepository.search(text))
            if (all || prefs.useGitHub.get()) sources.add(GitHubSource.name to gitHubRepository.search(text))
            if (all || prefs.useApkPure.get()) sources.add(ApkPureSource.name to apkPureRepository.search(text))
            if (all || prefs.useGitLab.get()) sources.add(GitLabSource.name to gitLabRepository.search(text))
            if (all || prefs.usePlay.get()) sources.add(PlaySource.name to playRepository.search(text))
            if (all || prefs.useRuStore.get()) sources.add(RuStoreSource.name to ruStoreRepository.search(text))
            if (all || prefs.useAppGallery.get()) sources.add(AppGallerySource.name to appGalleryRepository.search(text))
        }

        if (sources.isNotEmpty()) {
            sources.map { it.second }.combine { updates ->
                val result = updates.filter { it.isSuccess }.mapNotNull { it.getOrNull() }
                // A bare owner/repo asks GitHub and GitLab both; the one without such a
                // project answers 404, which is an empty success, never a failure.
                val failed = updates.indices
                    .filter { updates[it].isFailure }
                    .map { sources[it].first }
                    .distinct()
                // The store's own first answer, which ranking keeps even when its name shares no
                // letter with the query — see rankByRelevance and STORE_SEARCH. RuStore's search
                // keeps the store's order, so the first item is its best guess.
                val storeTop = updates.indices
                    .filter { sources[it].first in STORE_SEARCH }
                    .mapNotNull { updates[it].getOrNull()?.firstOrNull()?.id }
                    .toSet()
                // A direct lookup is an exact answer by definition, and the query — a URL —
                // would match no app name, so ranking would throw it away.
                val found = result.flatten().let { if (repoQuery.isEmpty()) it.rankByRelevance(text, storeTop) else it }
                // Deduplicate before the UI ever sees the list. AppUpdate.id is a hash of
                // source+package+versionCode+version, so one source listing the same build twice
                // (ApkMirror rows per architecture, Aptoide variants) produces two items sharing
                // an id — and a LazyGrid keyed by id throws "Key ... was already used" the moment
                // BOTH become visible, which is why it crashed while scrolling rather than on
                // arrival. The Updates list already did this; Search never did.
                emit(Result.success(Results(found.distinctBy { it.id }, failed)))
            }.collect()
        } else {
            emit(Result.success(Results(emptyList(), emptyList())))
        }
    }.catch {
        emit(Result.failure(it))
        Log.e("SearchRepository", "Error searching.", it)
    }

    /**
     * Sources search very differently: F-Droid/Izzy/GitHub/GitLab filter locally with a strict
     * `contains`, while ApkMirror/Aptoide/APKPure/Play/RuStore return whatever their own fuzzy
     * server-side search decides — which is how a query like "XXX" came back with "XYYX22".
     *
     * So drop hits that match no part of the query at all, and order what is left by how well it
     * matches. Previously everything was merged and sorted alphabetically, which buried an exact
     * match in the middle of the list.
     *
     * That filter also dropped every app whose name is written in the other alphabet (build 185,
     * reported on 4PDA): «йота» found no "Yota", «спар» no "Мой SPAR", «вк мессенджер» no
     * "VK Мессенджер", «ростикс» no "Rostic's" — while RuStore itself had put each of them first.
     * Two answers to that, both after every plain match: the query and the name are compared
     * once more in [fold]ed Latin, and RuStore's own first answer ([storeTop], see
     * [STORE_SEARCH]) is kept even when nothing matches, for the spellings no transliteration
     * evens out.
     */
    private fun List<AppUpdate>.rankByRelevance(query: String, storeTop: Set<Int>): List<AppUpdate> {
        val matcher = QueryMatcher(query)
        if (matcher.isEmpty) return sortedBy { it.name.lowercase() }

        // Lower is better; NO_MATCH is dropped entirely.
        fun rank(app: AppUpdate): Int = matcher.tier(app.name, app.packageName).let { tier ->
            if (tier == NO_MATCH && app.id in storeTop) STORE_TOP else tier
        }

        return map { it to rank(it) }
            .filter { it.second != NO_MATCH }
            .sortedWith(compareBy({ it.second }, { it.first.name.lowercase() }))
            .map { it.first }
    }

    /**
     * How well a name and package match one query — the tiers [rankByRelevance] sorts by, short
     * of the store-top rule. A class of its own so RuStoreRepository can ask the same question
     * before it spends two requests per result on details (build 185): «вк мессенджер» brought
     * 32 RuStore apps, all 32 were fetched, and 30 were then thrown away here — about forty
     * seconds behind a VPN.
     */
    class QueryMatcher(query: String) {
        private val q = query.trim().lowercase()
        private val words = q.split(" ").filter { it.isNotBlank() }
        private val foldedWords = words.map { fold(it) }.filter { it.isNotEmpty() }

        val isEmpty: Boolean get() = q.isEmpty()

        /** 0 (the name IS the query) to 5 (matches across alphabets), or [NO_MATCH]. */
        fun tier(appName: String, packageName: String): Int {
            val name = appName.lowercase()
            val pkg = packageName.lowercase()
            return when {
                name == q -> 0
                name.startsWith(q) -> 1
                name.contains(q) -> 2
                pkg.contains(q) -> 3
                // Multi-word queries in any order, e.g. "vanced youtube" -> "YouTube Vanced".
                words.all { name.contains(it) || pkg.contains(it) } -> 4
                // The same, across alphabets: «йота» → yota finds "Yota", «вк» → vk "VK".
                foldedWords.isNotEmpty() && fold(appName).let { n ->
                    val p = fold(packageName)
                    foldedWords.all { n.contains(it) || p.contains(it) }
                } -> 5
                else -> NO_MATCH
            }
        }

        fun matches(appName: String, packageName: String) = tier(appName, packageName) != NO_MATCH
    }

    companion object {
        const val NO_MATCH = Int.MAX_VALUE

        /** A store's own first answer, kept although nothing in it matches — see [STORE_SEARCH]. */
        private const val STORE_TOP = 6

        /**
         * Stores whose first answer is kept even when nothing in it matches the query. RuStore
         * alone: it is the store Russian queries are for, and its search answers them well —
         * «ростикс» puts Rostic's first. Play answers a Russian query from its US profile with
         * whatever it has, AppGallery answers a typo with unrelated Chinese apps, and Aptoide and
         * APKPure match loosely; keeping their first answers brought the junk this filter exists
         * to drop back in, one app per store.
         */
        private val STORE_SEARCH = setOf(RuStoreSource.name)

        private val CYRILLIC_TO_LATIN = mapOf(
            'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "e",
            'ж' to "zh", 'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m",
            'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u",
            'ф' to "f", 'х' to "kh", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh", 'щ' to "shch",
            'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya",
            'і' to "i", 'ї' to "yi", 'є' to "ye", 'ґ' to "g"
        )

        /**
         * A name or query folded to plain Latin, so the two alphabets can be compared:
         * «Пятёрочка» → pyaterochka, "Rostic's" → rostix. Cyrillic is transliterated, apostrophes
         * vanish ("Rostic's" is one word), any other non-letter becomes a space, and the spellings
         * that differ most often between the two alphabets are evened out on both sides, inside
         * each word only — x for ks and cs («Яндекс» / Yandex, «ростикс» / Rostic's), h for kh
         * («хабр» / Habr), k for ck. Within a word, not across the space: "VK Studio" must stay
         * "vk studio" for «вк» to find it, not run together into "vxtudio".
         *
         * Only a fallback after the plain comparisons, so it adds matches and costs none; it can
         * still miss a pair spelled more differently than this evens out («вотсап» / WhatsApp).
         */
        internal fun fold(text: String): String = buildString {
            text.lowercase().forEach { c ->
                when {
                    c in 'a'..'z' || c in '0'..'9' -> append(c)
                    c == '\'' || c == '’' || c == '`' -> Unit
                    else -> append(CYRILLIC_TO_LATIN[c] ?: " ")
                }
            }
        }
            .split(' ')
            .filter { it.isNotEmpty() }
            .joinToString(" ") { word ->
                word.replace("ck", "k").replace("ks", "x").replace("cs", "x").replace("kh", "h")
            }
    }

}

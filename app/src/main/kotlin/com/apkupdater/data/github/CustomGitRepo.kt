package com.apkupdater.data.github

import java.util.UUID

enum class GitProvider { GITHUB, GITLAB }

data class CustomGitRepo(
	val platform: GitProvider = GitProvider.GITHUB,
	val user: String = "",
	val repo: String = "",
	val packageName: String = "",
	val installedPackageName: String = "",
	val id: String = UUID.randomUUID().toString()
)

fun parseRepoUrl(url: String): CustomGitRepo? {
	val cleaned = url
		.removePrefix("https://")
		.removePrefix("http://")
		.removePrefix("www.")
		.trimEnd('/')

	val parts = cleaned.split("/")
	if (parts.size < 3) return null

	val host = parts[0].lowercase()
	val user = parts[1]
	val repo = parts[2]

	if (user.isBlank() || repo.isBlank()) return null

	val platform = when {
		host.contains("github") -> GitProvider.GITHUB
		host.contains("gitlab") -> GitProvider.GITLAB
		else -> return null
	}

	return CustomGitRepo(
		platform = platform,
		user = user,
		repo = repo,
		packageName = "$user/$repo"
	)
}

/**
 * A search query that names one repository, as the repositories it could be: a GitHub or GitLab
 * link gives exactly one, a bare `owner/repo` gives one per provider, anything else none.
 *
 * GitHub cannot be searched by Android package name — it knows nothing about package names, and
 * a project like Happ publishes only release files, no source to search through. Naming the
 * repository is the one reliable way in, so Search accepts it directly.
 */
fun parseRepoQuery(text: String): List<CustomGitRepo> {
	val q = text.trim()
	parseRepoUrl(q)?.let { return listOf(it.withoutGitSuffix()) }
	val match = Regex("^([\\w.-]+)/([\\w.-]+)$").find(q) ?: return emptyList()
	val (user, repo) = match.destructured
	return GitProvider.entries.map { CustomGitRepo(it, user, repo, "$user/$repo").withoutGitSuffix() }
}

/** A clone URL ends in `.git`; the API wants the bare name. */
private fun CustomGitRepo.withoutGitSuffix(): CustomGitRepo {
	val bare = repo.removeSuffix(".git")
	return if (bare == repo) this else copy(repo = bare, packageName = "$user/$bare")
}

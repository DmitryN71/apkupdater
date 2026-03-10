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

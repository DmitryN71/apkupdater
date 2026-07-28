package com.apkupdater.data.gitlab


/** Only the numeric id — needed to build upload URLs, see GitLabRepository.resolveApkUrl. */
data class GitLabProject(
	val id: Long = 0L
)

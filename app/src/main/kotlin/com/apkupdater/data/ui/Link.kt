package com.apkupdater.data.ui

import com.aurora.gplayapi.data.models.PlayFile


sealed class Link {
    data object Empty: Link()
    data class Url(val link: String, val size: Long = 0L): Link()
    data class Xapk(val link: String, val size: Long = 0L): Link()
    data class Play(val getInstallFiles: () -> List<PlayFile>): Link()

    /**
     * One app delivered as several APKs installed together: a base and the config splits this
     * device needs (processor, screen density, language). RuStore's delivery since build 189.
     */
    data class Splits(val parts: List<Url>): Link()

    /** Returns file size in bytes if known, 0 otherwise. */
    val fileSize: Long get() = when (this) {
        is Url -> size
        is Xapk -> size
        is Splits -> parts.sumOf { it.size }
        else -> 0L
    }
}

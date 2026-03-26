package com.apkupdater.data.ui

import com.aurora.gplayapi.data.models.File


sealed class Link {
    data object Empty: Link()
    data class Url(val link: String, val size: Long = 0L): Link()
    data class Xapk(val link: String, val size: Long = 0L): Link()
    data class Play(val getInstallFiles: () -> List<File>): Link()

    /** Returns file size in bytes if known, 0 otherwise. */
    val fileSize: Long get() = when (this) {
        is Url -> size
        is Xapk -> size
        else -> 0L
    }
}

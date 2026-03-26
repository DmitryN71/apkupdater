package com.apkupdater.data.apkpure


data class AppUpdateResponseAsset(
    val type: String,
    val url: String,
    val size: Long = 0L
)

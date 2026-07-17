package com.apkupdater.util

/**
 * Tracks whether the app UI is currently visible (set by MainActivity).
 * Used to decide between showing the install-confirmation dialog directly
 * (foreground) or posting a notification (background).
 */
object AppVisibility {
    @Volatile
    var foreground = false
}

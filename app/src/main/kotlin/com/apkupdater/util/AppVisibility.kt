package com.apkupdater.util

/**
 * Tracks whether the app UI is currently visible (set by MainActivity).
 * Used to decide between showing the install-confirmation dialog directly
 * (foreground) or posting a notification (background).
 */
object AppVisibility {
    /** The window is visible: between onStart and onStop. See MainActivity.onStart. */
    @Volatile
    var foreground = false

    /**
     * The window is in front: between onResume and onPause. Narrower than [foreground], and
     * fresher — onPause comes at once when the user leaves, onStop up to seconds later. While
     * visible but not in front, InstallReceiver opens the confirmation AND posts its
     * notification, in case Android refused the launch without a word.
     */
    @Volatile
    var resumed = false
}

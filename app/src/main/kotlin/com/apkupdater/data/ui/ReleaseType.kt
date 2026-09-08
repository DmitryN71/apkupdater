package com.apkupdater.data.ui

import androidx.annotation.StringRes
import com.apkupdater.R

/**
 * Whether a version looks like something other than a finished release.
 *
 * Most sources are already filtered before they get here: ignoreAlpha and ignoreBeta are on by
 * default and APKMirror, APKPure, Aptoide, F-Droid and RuStore all honour them. The two that do
 * not are GitHub and GitLab, where the "version" is whatever the author typed into a tag — and
 * that is exactly where an unfinished build reaches the list unannounced. GitHub has a
 * prerelease flag, but a great many projects never set it and simply tag `2.0.0-rc1`.
 *
 * So this reads the version string, and takes the flag as a second opinion when there is one.
 * It is deliberately conservative: a word has to stand on its own to count, so `1.0-beta2`
 * matches while a version that merely contains those letters — `devel`, `precise`, `src` —
 * does not. Anything unrecognised is Stable and shows no chip at all.
 */
enum class ReleaseType(@StringRes val labelRes: Int?) {
    Stable(null),
    PreRelease(R.string.release_type_pre_release),
    Beta(R.string.release_type_beta),
    Alpha(R.string.release_type_alpha);

    companion object {
        // The lookarounds are what keep this from crying wolf. IGNORE_CASE applies to them
        // too, so an upper-case neighbour blocks a match just as a lower-case one does.
        private val alphaRegex = Regex("(?<![a-z])alpha", RegexOption.IGNORE_CASE)
        private val betaRegex = Regex("(?<![a-z])beta", RegexOption.IGNORE_CASE)
        private val preReleaseRegex = Regex(
            "(?<![a-z])(rc|pre|preview|dev|nightly|snapshot|canary|insider|unstable)(?![a-z])",
            RegexOption.IGNORE_CASE
        )

        /**
         * @param version the version name as the source gave it.
         * @param flagged the source said so itself — only GitHub can, through its release flag.
         */
        fun from(version: String, flagged: Boolean = false): ReleaseType = when {
            alphaRegex.containsMatchIn(version) -> Alpha
            betaRegex.containsMatchIn(version) -> Beta
            flagged || preReleaseRegex.containsMatchIn(version) -> PreRelease
            else -> Stable
        }
    }
}

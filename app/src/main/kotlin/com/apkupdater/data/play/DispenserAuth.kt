package com.apkupdater.data.play

/**
 * What we take from Aurora's token dispenser: the anonymous account and its auth token.
 *
 * The reply carries a whole serialised Play session as well, and until build 142 we read all
 * of it with Gson. That only ever worked because the dispenser happened to serialise the very
 * same class with the very same field names, and Gson fills fields behind the constructor's
 * back. The session is built on the device now — see PlayRepository.refreshAuth — which is
 * what Aurora Store itself has done since 4.8. Under data/ so the Gson keep rule covers it.
 */
data class DispenserAuth(
    val email: String = "",
    val authToken: String = ""
)

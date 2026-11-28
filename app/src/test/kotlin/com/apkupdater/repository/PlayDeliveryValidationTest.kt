package com.apkupdater.repository

import com.apkupdater.viewmodel.verifyPlayFileSha256
import com.aurora.gplayapi.PlayException
import com.aurora.gplayapi.model.AppFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

class PlayDeliveryValidationTest {

    private fun appFile(url: String = "https://play.googleapis.com/base.apk") = AppFile(
        name = "base.apk",
        url = url,
        size = 123L,
        type = AppFile.Type.BASE,
        sha1 = "",
        sha256 = ""
    )

    @Test
    fun validatesHttpsPlayUrlsOnly() {
        assertTrue(hasValidPlayDownloadUrls(listOf(appFile())))
        assertFalse(hasValidPlayDownloadUrls(emptyList()))
        assertFalse(hasValidPlayDownloadUrls(listOf(appFile(url = ""))))
        assertFalse(hasValidPlayDownloadUrls(listOf(appFile(url = "http://insecure.example/a.apk"))))
    }

    @Test
    fun retriesUnauthorizedByInvalidatingSession() = runBlocking {
        var attempts = 0
        var invalidations = 0
        val files = resolvePlayFilesWithRetry(
            maxAttempts = 3,
            baseDelayMs = 0L,
            delayFn = {},
            onInvalidateSession = { invalidations++ },
            fetch = {
                attempts++
                if (attempts == 1) throw PlayException.Unauthorized(401)
                listOf(appFile())
            }
        )
        assertEquals(2, attempts)
        assertEquals(1, invalidations)
        assertEquals(1, files.size)
    }

    @Test
    fun verifiesSha256ChecksumWhenProvided() {
        val temp = File.createTempFile("play-sha-", ".apk")
        try {
            temp.writeBytes("hello world".toByteArray())
            // SHA-256 of "hello world"
            val expected = "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9"
            verifyPlayFileSha256(temp, expected)
            verifyPlayFileSha256(temp, "")

            var failed = false
            try {
                verifyPlayFileSha256(temp, "0000000000000000000000000000000000000000000000000000000000000000")
            } catch (_: IOException) {
                failed = true
            }
            assertTrue(failed)
        } finally {
            temp.delete()
        }
    }
}

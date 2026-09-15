package com.apkupdater.service

import com.apkupdater.data.github.GitHubRelease
import retrofit2.http.GET
import retrofit2.http.Path

interface GitHubService {

    @GET("/repos/{user}/{repo}/releases")
    suspend fun getReleases(
        @Path("user") user: String = "DmitryN71",
        @Path("repo") repo: String = "apkupdater"
    ): List<GitHubRelease>

    /**
     * The newest non-draft, non-prerelease release — and NOT simply the first entry of the list
     * above. GitHub can serve a published release here while omitting it from that list
     * entirely: `anilbeesetti/nextplayer` v0.18.0 was published on 2026-09-14 with five APKs and
     * did not appear among the 43 releases the list returned a day later. Reported on 4PDA by
     * Maximoff, verified against the live API on 2026-09-15.
     *
     * Answers 404 when a repository has no release that qualifies — no releases at all, or only
     * prereleases — so every caller has to tolerate that.
     */
    @GET("/repos/{user}/{repo}/releases/latest")
    suspend fun getLatestRelease(
        @Path("user") user: String,
        @Path("repo") repo: String
    ): GitHubRelease

}

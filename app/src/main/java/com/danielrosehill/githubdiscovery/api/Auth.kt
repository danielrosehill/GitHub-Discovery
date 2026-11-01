package com.danielrosehill.githubdiscovery.api

import com.danielrosehill.githubdiscovery.BuildConfig
import kotlinx.coroutines.delay
import java.net.URLEncoder

/**
 * GitHub OAuth Device Flow (https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps#device-flow).
 * Needs only the public client ID; no client secret and no backend. Each user approves the app on
 * github.com with their own account and the token stays on their device.
 */
object DeviceFlow {
    /** public_repo is what addStar/removeStar need. Star Lists mutations ride on the same token. */
    const val SCOPE = "public_repo"

    data class Code(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val intervalSec: Int,
        val expiresInSec: Int,
    )

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    suspend fun start(): Code {
        val j = Http.postForm(
            "https://github.com/login/device/code",
            "client_id=${enc(BuildConfig.GITHUB_CLIENT_ID)}&scope=${enc(SCOPE)}",
        )
        j.str("error")?.let { throw ApiException(j.str("error_description") ?: it) }
        return Code(
            deviceCode = j.getString("device_code"),
            userCode = j.getString("user_code"),
            verificationUri = j.optString("verification_uri", "https://github.com/login/device"),
            intervalSec = j.optInt("interval", 5),
            expiresInSec = j.optInt("expires_in", 900),
        )
    }

    /** Polls until the user approves; returns the access token. */
    suspend fun awaitToken(code: Code): String {
        var interval = code.intervalSec
        val deadline = System.currentTimeMillis() + code.expiresInSec * 1000L
        while (System.currentTimeMillis() < deadline) {
            delay(interval * 1000L)
            val j = Http.postForm(
                "https://github.com/login/oauth/access_token",
                "client_id=${enc(BuildConfig.GITHUB_CLIENT_ID)}&device_code=${enc(code.deviceCode)}" +
                    "&grant_type=${enc("urn:ietf:params:oauth:grant-type:device_code")}",
            )
            j.str("access_token")?.let { return it }
            when (j.str("error")) {
                "authorization_pending" -> Unit
                "slow_down" -> interval = j.optInt("interval", interval + 5)
                "expired_token" -> throw ApiException("The code expired. Start sign-in again.")
                "access_denied" -> throw ApiException("Sign-in was cancelled on GitHub.")
                else -> throw ApiException(j.str("error_description") ?: j.str("error") ?: "Sign-in failed")
            }
        }
        throw ApiException("The code expired. Start sign-in again.")
    }
}

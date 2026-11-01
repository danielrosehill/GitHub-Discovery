package com.danielrosehill.githubdiscovery.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ApiException(message: String, val unauthorized: Boolean = false) : Exception(message)

internal object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val JSON = "application/json".toMediaType()
    private val FORM = "application/x-www-form-urlencoded".toMediaType()

    suspend fun postForm(url: String, form: String): JSONObject = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url)
            .header("Accept", "application/json")
            .post(form.toRequestBody(FORM))
            .build()
        client.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ApiException("GitHub returned HTTP ${r.code}: ${text.take(200)}")
            JSONObject(text)
        }
    }

    suspend fun get(url: String, token: String): JSONObject = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
        client.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (r.code == 401) throw ApiException("Your GitHub sign-in has expired. Sign in again.", unauthorized = true)
            if (!r.isSuccessful) throw ApiException(errorMessage(r.code, text))
            JSONObject(text)
        }
    }

    suspend fun graphql(token: String, query: String, vars: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val body = JSONObject().put("query", query).put("variables", vars).toString()
        val req = Request.Builder().url("https://api.github.com/graphql")
            .header("Authorization", "Bearer $token")
            .post(body.toRequestBody(JSON))
            .build()
        client.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (r.code == 401) throw ApiException("Your GitHub sign-in has expired. Sign in again.", unauthorized = true)
            if (!r.isSuccessful) throw ApiException(errorMessage(r.code, text))
            val j = JSONObject(text)
            val errs = j.optJSONArray("errors")
            if (errs != null && errs.length() > 0) {
                throw ApiException(errs.getJSONObject(0).optString("message", "GitHub GraphQL error"))
            }
            j.getJSONObject("data")
        }
    }

    private fun errorMessage(code: Int, text: String): String {
        val msg = runCatching { JSONObject(text).optString("message") }.getOrNull().orEmpty()
        return when {
            code == 403 && msg.contains("rate limit", true) -> "GitHub rate limit reached. Wait a minute and try again."
            msg.isNotBlank() -> "GitHub: $msg"
            else -> "GitHub returned HTTP $code"
        }
    }
}

internal fun JSONObject.str(key: String): String? = if (!has(key) || isNull(key)) null else optString(key)

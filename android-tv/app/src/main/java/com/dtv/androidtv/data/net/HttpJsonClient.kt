package com.dtv.androidtv.data.net

import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Shared OkHttp wrapper aligned with Go `common.HttpClient`:
 * no system proxy, generous timeouts, JSON helpers.
 */
class HttpJsonClient(
    private val client: OkHttpClient = defaultClient(),
) {
    fun get(url: String, headers: Map<String, String> = emptyMap()): Response {
        val builder = Request.Builder().url(url).get()
        headers.forEach { (k, v) -> builder.header(k, v) }
        return client.newCall(builder.build()).execute()
    }

    fun getJson(url: String, headers: Map<String, String> = emptyMap()): JSONObject {
        get(url, headers).use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}: ${body.take(200)}")
            }
            return JSONObject(body.ifBlank { "{}" })
        }
    }

    fun postFormJson(
        url: String,
        form: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
    ): JSONObject {
        val formBody = FormBody.Builder().apply {
            form.forEach { (k, v) -> add(k, v) }
        }.build()
        val builder = Request.Builder().url(url).post(formBody)
        headers.forEach { (k, v) -> builder.header(k, v) }
        client.newCall(builder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}: ${body.take(200)}")
            }
            return JSONObject(body.ifBlank { "{}" })
        }
    }

    fun postRawJson(
        url: String,
        json: String,
        headers: Map<String, String> = emptyMap(),
    ): JSONObject {
        val media = "application/json; charset=utf-8".toMediaType()
        val builder = Request.Builder().url(url).post(json.toRequestBody(media))
        headers.forEach { (k, v) -> builder.header(k, v) }
        client.newCall(builder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}: ${body.take(200)}")
            }
            return JSONObject(body.ifBlank { "{}" })
        }
    }

    companion object {
        fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .writeTimeout(45, TimeUnit.SECONDS)
                .followRedirects(true)
                .retryOnConnectionFailure(true)
                // Match Go: do not inherit HTTP(S)_PROXY for Douyu / CDN calls.
                .proxy(java.net.Proxy.NO_PROXY)
                .build()
    }
}

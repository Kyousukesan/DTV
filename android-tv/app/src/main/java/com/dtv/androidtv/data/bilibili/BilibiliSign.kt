package com.dtv.androidtv.data.bilibili

import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Bilibili has **two** signing schemes — do not mix them:
 *
 * 1. Live-list MD5 (`second/getList` `w_rid`): ordered `k=v` join + fixed
 *    [BilibiliConstants.LIST_SIGN_SECRET]. Values are **not** URL-escaped.
 *    Params must include `w_webid` and the same `wts` used in the query.
 *    Stale / missing `w_webid` commonly yields business code **-352**
 *    (captcha / risk control).
 *
 * 2. WBI (`getInfoByRoom` etc.): nav `img_key`/`sub_key` → mixin_key via
 *    [MIXIN_KEY_ENC_TAB], params sorted by key, WBI-encoded, then md5.
 */
internal object BilibiliSign {
    /** WBI field permutation table (Go `mixinKeyEncTab`). */
    private val MIXIN_KEY_ENC_TAB = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
        27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
        37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
        22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52,
    )

    fun md5Hex(value: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun nowSeconds(): Long = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis())

    /**
     * Live-list sign: returns (`queryWithoutWRid`, `w_rid`).
     * Caller appends `&w_rid=…` — keep param order identical to Go.
     */
    fun listSignQuery(params: List<Pair<String, String>>, wts: Long): Pair<String, String> {
        val pairs = params + ("wts" to wts.toString())
        val query = pairs.joinToString("&") { "${it.first}=${it.second}" }
        return query to md5Hex(query + BilibiliConstants.LIST_SIGN_SECRET)
    }

    fun mixinKey(imgKey: String, subKey: String): String {
        val orig = imgKey + subKey
        val builder = StringBuilder(32)
        for (idx in MIXIN_KEY_ENC_TAB) {
            if (idx < orig.length) builder.append(orig[idx])
        }
        val key = builder.toString()
        return if (key.length > 32) key.substring(0, 32) else key
    }

    /**
     * WBI value encoding: drop `!'()*`; encode space as `%20` (not `+`).
     */
    fun wbiEncodeValue(raw: String): String {
        val banned = "!'()*"
        val out = StringBuilder(raw.length * 2)
        for (c in raw) {
            when {
                c.isLetterOrDigit() || c == '-' || c == '_' || c == '.' || c == '~' ->
                    out.append(c)
                banned.indexOf(c) >= 0 -> Unit
                else -> {
                    val bytes = c.toString().toByteArray(Charsets.UTF_8)
                    for (b in bytes) {
                        out.append('%')
                        out.append("%02X".format(b.toInt() and 0xFF))
                    }
                }
            }
        }
        return out.toString()
    }

    /** Full WBI query including `wts` and `w_rid`. */
    fun wbiSignedQuery(
        params: List<Pair<String, String>>,
        imgKey: String,
        subKey: String,
        wts: Long,
    ): String {
        val pairs = (params + ("wts" to wts.toString())).sortedBy { it.first }
        val query = pairs.joinToString("&") {
            "${wbiEncodeValue(it.first)}=${wbiEncodeValue(it.second)}"
        }
        return "$query&w_rid=${md5Hex(query + mixinKey(imgKey, subKey))}"
    }

    fun buildUrl(endpoint: String, query: String): String =
        if (query.isEmpty()) endpoint else "$endpoint?$query"

    fun queryEscape(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name())
            .replace("+", "%20")

    /** Filename stem from `…/xxx.png` → `xxx` (Go `fileKey`). */
    fun fileKey(rawUrl: String): String {
        val slash = rawUrl.lastIndexOf('/')
        if (slash < 0) return ""
        var name = rawUrl.substring(slash + 1)
        val firstDot = name.indexOf('.')
        if (firstDot > 0) name = name.substring(0, firstDot)
        val lastDot = name.lastIndexOf('.')
        if (lastDot > 0) name = name.substring(0, lastDot)
        return name
    }
}

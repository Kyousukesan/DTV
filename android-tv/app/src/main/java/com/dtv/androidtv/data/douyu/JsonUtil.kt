package com.dtv.androidtv.data.douyu

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToLong

/** Loose JSON helpers mirroring Go `jsonString` / `jsonInt64` / `jsonFloat`. */
internal object JsonUtil {
    fun asString(value: Any?): String =
        when (value) {
            null, JSONObject.NULL -> ""
            is String -> value
            is Number -> {
                val d = value.toDouble()
                if (d == d.toLong().toDouble()) value.toLong().toString() else value.toString()
            }
            is Boolean -> value.toString()
            else -> value.toString()
        }

    fun asLong(value: Any?): Long =
        when (value) {
            null, JSONObject.NULL -> 0L
            is Number -> value.toLong()
            is String -> {
                val trimmed = value.trim()
                trimmed.toLongOrNull()
                    ?: parseChineseCount(trimmed)
            }
            else -> 0L
        }

    fun asDouble(value: Any?): Double =
        when (value) {
            null, JSONObject.NULL -> 0.0
            is Number -> value.toDouble()
            is String -> value.trim().toDoubleOrNull() ?: parseChineseCount(value).toDouble()
            else -> 0.0
        }

    fun asInt(value: Any?): Int? {
        return when (value) {
            null, JSONObject.NULL -> null
            is Number -> {
                val d = value.toDouble()
                if (d != d.toLong().toDouble()) null else d.toInt()
            }
            is String -> {
                val trimmed = value.trim()
                trimmed.toIntOrNull()
                    ?: trimmed.toDoubleOrNull()?.takeIf { it == it.toLong().toDouble() }?.toInt()
            }
            else -> null
        }
    }

    fun optMap(obj: JSONObject?, key: String): JSONObject? =
        obj?.optJSONObject(key)

    fun keys(obj: JSONObject): Iterator<String> = obj.keys()

    fun arrayOrEmpty(obj: JSONObject?, key: String): JSONArray =
        obj?.optJSONArray(key) ?: JSONArray()

    fun fullUrl(prefix: String, raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
        if (trimmed.startsWith("//")) return "https:$trimmed"
        return prefix + trimmed.removePrefix("/")
    }

    private fun parseChineseCount(raw: String): Long {
        var text = raw.trim()
        if (text.isEmpty()) return 0L
        var multiplier = 1.0
        when {
            text.contains("亿") -> {
                multiplier = 100_000_000.0
                text = text.replace("亿", "")
            }
            text.contains("万") -> {
                multiplier = 10_000.0
                text = text.replace("万", "")
            }
        }
        val f = text.trim().toDoubleOrNull() ?: return 0L
        return (f * multiplier).roundToLong()
    }
}

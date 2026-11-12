package com.dtv.androidtv.data.auth

import android.content.Context
import com.dtv.androidtv.data.model.AuthAccount
import com.dtv.androidtv.data.model.LivePlatform
import org.json.JSONObject

/**
 * Persists Douyu / Bilibili login cookies + profile for TV QR sessions.
 * Not a desktop WebView path — SharedPreferences only.
 */
class AuthStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadAccount(platform: LivePlatform): AuthAccount {
        val cookie = cookie(platform)
        val loggedIn = cookie.isNotBlank() && prefs.getBoolean(key(platform, "logged_in"), false)
        return AuthAccount(
            platform = platform.id,
            loggedIn = loggedIn,
            userName = prefs.getString(key(platform, "user_name"), "").orEmpty(),
            avatar = prefs.getString(key(platform, "avatar"), "").orEmpty(),
        )
    }

    fun cookie(platform: LivePlatform): String =
        prefs.getString(key(platform, "cookie"), "").orEmpty()

    fun saveSession(
        platform: LivePlatform,
        cookie: String,
        userName: String = "",
        avatar: String = "",
    ) {
        prefs.edit()
            .putString(key(platform, "cookie"), cookie.trim())
            .putBoolean(key(platform, "logged_in"), cookie.isNotBlank())
            .putString(key(platform, "user_name"), userName)
            .putString(key(platform, "avatar"), avatar)
            .apply()
    }

    fun clear(platform: LivePlatform) {
        prefs.edit()
            .remove(key(platform, "cookie"))
            .remove(key(platform, "logged_in"))
            .remove(key(platform, "user_name"))
            .remove(key(platform, "avatar"))
            .apply()
    }

    fun exportDebug(platform: LivePlatform): String =
        JSONObject()
            .put("platform", platform.id)
            .put("loggedIn", loadAccount(platform).loggedIn)
            .put("userName", loadAccount(platform).userName)
            .toString()

    private fun key(platform: LivePlatform, field: String): String =
        "auth.${platform.id}.$field"

    companion object {
        private const val PREFS_NAME = "dtv_tv_auth"
    }
}

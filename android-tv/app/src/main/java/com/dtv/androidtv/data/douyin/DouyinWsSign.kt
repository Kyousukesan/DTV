package com.dtv.androidtv.data.douyin

import android.content.Context
import org.mozilla.javascript.Context as RhinoContext
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import java.io.InputStream
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

/**
 * Douyin webcast WS signature (Go `wsSignature` in danmaku.go).
 *
 * 1. Take fixed-order query keys from the WSS URL
 * 2. Join as `k=v,k=v,...` and MD5-hex
 * 3. Run `get_sign(md5)` from `assets/douyin/sign.js` via Rhino
 *
 * Fallback when Rhino / sign.js fails: [signatureOrEmpty] returns "" and the
 * dialer still appends `&signature=` (Douyin usually rejects — see gaps doc).
 */
object DouyinWsSign {
    private val SIGN_KEYS = listOf(
        "live_id", "aid", "version_code", "webcast_sdk_version", "room_id",
        "sub_room_id", "sub_channel_id", "did_rule", "user_unique_id",
        "device_platform", "device_type", "ac", "identity",
    )

    private val lock = Any()
    private var scope: Scriptable? = null
    private val loadError = AtomicReference<Exception?>(null)

    /** Optional Application context so AssetManager can open `douyin/sign.js`. */
    @Volatile
    var appContext: Context? = null

    /** Build MD5 hex of the fixed-order param string (no JS). */
    fun md5Param(wssUrl: String): String {
        val values = parseQueryValues(wssUrl)
        val toSign = SIGN_KEYS.joinToString(",") { k -> "$k=${values[k].orEmpty()}" }
        val digest = MessageDigest.getInstance("MD5").digest(toSign.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { b -> "%02x".format(b) }
    }

    /**
     * Full signature for [wssUrl] (without `&signature=` yet).
     * Throws if sign.js cannot be loaded or `get_sign` fails.
     */
    fun signature(wssUrl: String): String {
        val md5 = md5Param(wssUrl)
        return getSign(md5).trim()
    }

    /** Like [signature] but returns "" on failure (caller may still dial). */
    fun signatureOrEmpty(wssUrl: String): String =
        runCatching { signature(wssUrl) }.getOrDefault("")

    fun getSign(md5Hex: String): String {
        synchronized(lock) {
            ensureScopeLoaded()
            val safe = md5Hex.replace("\\", "\\\\").replace("'", "\\'")
            val cx = RhinoContext.enter()
            try {
                cx.optimizationLevel = -1
                val result = cx.evaluateString(scope, "get_sign('$safe')", "get_sign", 1, null)
                return RhinoContext.toString(result).orEmpty()
            } finally {
                RhinoContext.exit()
            }
        }
    }

    fun lastLoadError(): Exception? = loadError.get()

    /** Clear cached Rhino scope / sticky load failure (unit tests). */
    fun resetForTests() {
        synchronized(lock) {
            scope = null
            loadError.set(null)
        }
    }

    private fun ensureScopeLoaded() {
        if (scope != null) return
        // Do not permanently stick a prior failure — sign.js load is idempotent and
        // a one-shot Rhino quirk should not kill the whole process session.
        try {
            val source = loadSignJs()
            val cx = RhinoContext.enter()
            try {
                cx.optimizationLevel = -1
                // Prefer 1.8: classic function-declaration hoisting inside functions
                // matches goja/V8 for the obfuscated byted_acrawler IIFE body.
                cx.languageVersion = RhinoContext.VERSION_1_8
                val root = cx.initStandardObjects()
                // Minimal console so scripts that probe it do not throw.
                val console = cx.newObject(root)
                ScriptableObject.putProperty(root, "console", console)
                // Go jssign also shims window/self onto the global object.
                ScriptableObject.putProperty(root, "self", root)
                ScriptableObject.putProperty(root, "globalThis", root)
                cx.evaluateString(root, source, "douyin-sign.js", 1, null)
                scope = root
                loadError.set(null)
            } finally {
                RhinoContext.exit()
            }
        } catch (e: Exception) {
            val wrapped = IllegalStateException("编译/加载抖音签名脚本失败: ${e.message}", e)
            loadError.set(wrapped)
            throw wrapped
        }
    }

    private fun loadSignJs(): String {
        val raw = openSignStream().use { input ->
            input.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
        return rhinoCompat(raw)
    }

    /**
     * Rhino does not hoist `function` declarations inside `if { ... }` blocks the way
     * V8/goja do, so `w_0x42f5` is undefined when the init IIFE runs. Wrapping the
     * `byted_acrawler` body in a function restores classic hoisting.
     */
    internal fun rhinoCompat(source: String): String {
        val open = "if (!window.byted_acrawler) {"
        val idx = source.indexOf(open)
        if (idx < 0) return source
        val getSign = source.indexOf("\nfunction get_sign(", idx)
        if (getSign < 0) return source
        // Closing `}` of the if block sits just before get_sign.
        var close = getSign
        while (close > idx && source[close - 1].isWhitespace()) close--
        if (close <= idx || source[close - 1] != '}') return source
        val before = source.substring(0, idx)
        val body = source.substring(idx + open.length, close - 1)
        val after = source.substring(close - 1) // starts with `}`
        // Drop the original closing brace (first char of after) — IIFE supplies it.
        val rest = after.substring(1)
        return before + "if (!window.byted_acrawler) (function() {" + body + "\n})();\n" + rest
    }

    private fun openSignStream(): InputStream {
        val ctx = appContext
        if (ctx != null) {
            runCatching { ctx.assets.open("douyin/sign.js") }.getOrNull()?.let { return it }
        }
        runCatching {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as? Context
        }.getOrNull()?.assets?.let { assets ->
            runCatching { assets.open("douyin/sign.js") }.getOrNull()?.let { return it }
        }
        val cl = DouyinWsSign::class.java.classLoader
        cl?.getResourceAsStream("douyin/sign.js")?.let { return it }
        cl?.getResourceAsStream("assets/douyin/sign.js")?.let { return it }
        error("missing douyin/sign.js (assets or classpath resources)")
    }

    fun parseQueryValues(rawUrl: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val qIdx = rawUrl.indexOf('?')
        if (qIdx < 0) return out
        var query = rawUrl.substring(qIdx + 1)
        val hash = query.indexOf('#')
        if (hash >= 0) query = query.substring(0, hash)
        for (kv in query.split('&')) {
            if (kv.isEmpty()) continue
            val eq = kv.indexOf('=')
            if (eq < 0) continue
            val k = kv.substring(0, eq)
            var v = kv.substring(eq + 1)
            v = runCatching { URLDecoder.decode(v, Charsets.UTF_8.name()) }.getOrDefault(v)
            out[k] = v
        }
        return out
    }

    /**
     * Query path used by Go `buildWSQuery` (order matters for readability;
     * signature only cares about [SIGN_KEYS] values).
     */
    fun buildWsQuery(cursor: String, internalExt: String, userUniqueId: String, roomId: String): String =
        "webcast/im/push/v2/?app_name=douyin_web&version_code=180800" +
            "&webcast_sdk_version=1.0.14-beta.0&update_version_code=1.0.14-beta.0" +
            "&compress=gzip&device_platform=web&cookie_enabled=true" +
            "&screen_width=1536&screen_height=864&browser_language=zh-CN" +
            "&browser_platform=Win32&browser_name=Mozilla" +
            "&browser_version=5.0%20(Windows%20NT%2010.0;%20Win64;%20x64)%20AppleWebKit/537.36%20(KHTML,%20like%20Gecko)%20Chrome/126.0.0.0%20Safari/537.36" +
            "&browser_online=true&tz_name=Asia/Shanghai" +
            "&cursor=$cursor" +
            "&internal_ext=$internalExt" +
            "&host=https://live.douyin.com&aid=6383&live_id=1&did_rule=3&endpoint=live_pc" +
            "&support_wrds=1&user_unique_id=$userUniqueId" +
            "&im_path=/webcast/im/fetch/&identity=audience&need_persist_msg_count=15" +
            "&insert_task_id=&live_reason=&room_id=$roomId&heartbeatDuration=0"
}

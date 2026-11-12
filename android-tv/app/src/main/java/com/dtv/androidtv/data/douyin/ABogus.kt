package com.dtv.androidtv.data.douyin

import java.security.SecureRandom
import kotlin.math.roundToLong

/**
 * Douyin `a_bogus` anti-bot signature — Kotlin port of Go `abogus.go`
 * (itself a line-aligned port of the legacy Rust/Python implementations).
 *
 * Pipeline (order is load-bearing):
 * 1. SM3 (Chinese national hash, not SHA-256) over query / UA / suffix
 * 2. RC4 (byte-wise; legacy char→u8 truncation ≡ Latin-1 bytes)
 * 3. Custom base64 alphabet tables (`s0`…`s4`; only `s3`/`s4` used here)
 * 4. 44-slot main table (timestamps, aid, pageId, checksum) → RC4 with key `y`
 *
 * Validation is empirical: a one-byte mismatch → Douyin returns empty data.
 * [GenerateABogus] must see the **exact** urlencoded query string that will be
 * sent (minus `a_bogus` itself), and [userAgent] must equal the request UA.
 */
internal object ABogus {
    private val sm3Iv = intArrayOf(
        0x7380166F.toInt(), 0x4914B2B9, 0x172442D7, 0xDA8A0600.toInt(),
        0xA96F30BC.toInt(), 0x163138AA, 0xE38DEE4D.toInt(), 0xB0FB0E4E.toInt(),
    )

    private val encodeTables = mapOf(
        "s0" to "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=",
        "s1" to "Dkdpgh4ZKsQB80/Mfvw36XI1R25+WUAlEi7NLboqYTOPuzmFjJnryx9HVGcaStCe=",
        "s2" to "Dkdpgh4ZKsQB80/Mfvw36XI1R25-WUAlEi7NLboqYTOPuzmFjJnryx9HVGcaStCe=",
        "s3" to "ckdp1h4ZKsUB80/Mfvw36XIgR25+WQAlEi7NLboqYTOPuzmFjJnryx9HVGDaStCe",
        "s4" to "Dkdpgh2ZmsQB80/MfvV36XI1R45-WUAlEixNLwoqYTOPuzKFjJnry79HbGcaStCe",
    )

    private val encodeMasks = intArrayOf(16515072, 258048, 4032, 63)
    private val encodeShifts = intArrayOf(18, 12, 6, 0)

    /** Fixed window fingerprint from the reference implementation. */
    private const val WINDOW_ENV = "1920|1080|1920|1040|0|30|0|0|1872|92|1920|1040|1857|92|1|24|Win32"
    private const val DEFAULT_SUFFIX = "cus"
    private val defaultArguments = intArrayOf(0, 1, 14)

    private const val MS_TOKEN_CHARSET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

    /**
     * Produce `a_bogus` (including the trailing `=`).
     *
     * @param query urlencoded query **without** `a_bogus`, in the same key order
     *   that will be placed on the wire
     * @param userAgent must match the HTTP `User-Agent` header
     */
    fun generateABogus(query: String, userAgent: String): String {
        val body = generateRc4BbStr(query, userAgent, WINDOW_ENV, DEFAULT_SUFFIX, defaultArguments)
        val combined = generateRandomStr() + body
        return resultEncrypt(combined, "s4") + "="
    }

    /**
     * Random alphanumeric msToken. Douyin only checks that the param exists;
     * content is not validated (Go `GenerateMsToken`).
     */
    fun generateMsToken(length: Int = DouyinConstants.MS_TOKEN_LENGTH): String {
        val n = if (length <= 0) DouyinConstants.MS_TOKEN_LENGTH else length
        val rng = SecureRandom()
        val out = CharArray(n)
        for (i in 0 until n) {
            out[i] = MS_TOKEN_CHARSET[rng.nextInt(MS_TOKEN_CHARSET.length)]
        }
        return String(out)
    }

    /* ------------------------------------------------------------------ SM3 */

    private fun rotl32(x: Int, nRaw: Int): Int {
        val n = nRaw % 32
        if (n == 0) return x
        return (x shl n) or (x ushr (32 - n))
    }

    private fun sm3Tj(j: Int): Int = if (j < 16) 0x79CC4519 else 0x7A879D8A.toInt()

    private fun sm3Ff(j: Int, x: Int, y: Int, z: Int): Int =
        if (j < 16) x xor y xor z else (x and y) or (x and z) or (y and z)

    private fun sm3Gg(j: Int, x: Int, y: Int, z: Int): Int =
        if (j < 16) x xor y xor z else (x and y) or (x.inv() and z)

    private fun sm3Compress(reg: IntArray, block: ByteArray, offset: Int) {
        val w = IntArray(132)
        for (t in 0 until 16) {
            val i = offset + 4 * t
            w[t] = ((block[i].toInt() and 0xFF) shl 24) or
                ((block[i + 1].toInt() and 0xFF) shl 16) or
                ((block[i + 2].toInt() and 0xFF) shl 8) or
                (block[i + 3].toInt() and 0xFF)
        }
        for (j in 16 until 68) {
            var a = w[j - 16] xor w[j - 9] xor rotl32(w[j - 3], 15)
            a = a xor rotl32(a, 15) xor rotl32(a, 23)
            w[j] = a xor rotl32(w[j - 13], 7) xor w[j - 6]
        }
        for (j in 0 until 64) {
            w[j + 68] = w[j] xor w[j + 4]
        }

        var a = reg[0]
        var b = reg[1]
        var c = reg[2]
        var d = reg[3]
        var e = reg[4]
        var f = reg[5]
        var g = reg[6]
        var h = reg[7]

        for (j in 0 until 64) {
            val ss1 = rotl32(rotl32(a, 12) + e + rotl32(sm3Tj(j), j), 7)
            val ss2 = ss1 xor rotl32(a, 12)
            val tt1 = sm3Ff(j, a, b, c) + d + ss2 + w[j + 68]
            val tt2 = sm3Gg(j, e, f, g) + h + ss1 + w[j]

            d = c
            c = rotl32(b, 9)
            b = a
            a = tt1
            h = g
            g = rotl32(f, 19)
            f = e
            e = tt2 xor rotl32(tt2, 9) xor rotl32(tt2, 17)
        }

        reg[0] = reg[0] xor a
        reg[1] = reg[1] xor b
        reg[2] = reg[2] xor c
        reg[3] = reg[3] xor d
        reg[4] = reg[4] xor e
        reg[5] = reg[5] xor f
        reg[6] = reg[6] xor g
        reg[7] = reg[7] xor h
    }

    private fun sm3Sum(data: ByteArray): ByteArray {
        val reg = sm3Iv.copyOf()
        var offset = 0
        while (offset + 64 <= data.size) {
            sm3Compress(reg, data, offset)
            offset += 64
        }

        val remaining = data.copyOfRange(offset, data.size)
        val buf = ArrayList<Byte>(64)
        remaining.forEach { buf.add(it) }
        buf.add(0x80.toByte())
        while (buf.size % 64 != 56) {
            buf.add(0)
        }
        val bitLength = data.size.toLong() * 8
        for (shift in 56 downTo 0 step 8) {
            buf.add(((bitLength ushr shift) and 0xFF).toByte())
        }

        val padded = buf.toByteArray()
        var i = 0
        while (i < padded.size) {
            sm3Compress(reg, padded, i)
            i += 64
        }

        val out = ByteArray(32)
        var o = 0
        for (v in reg) {
            out[o++] = (v ushr 24).toByte()
            out[o++] = (v ushr 16).toByte()
            out[o++] = (v ushr 8).toByte()
            out[o++] = v.toByte()
        }
        return out
    }

    /* ------------------------------------------------------------------ RC4 */

    private fun rc4Encrypt(plaintext: ByteArray, key: ByteArray): ByteArray {
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0 until 256) {
            j = (j + s[i] + (key[i % key.size].toInt() and 0xFF)) % 256
            val tmp = s[i]
            s[i] = s[j]
            s[j] = tmp
        }
        var i = 0
        j = 0
        val out = ByteArray(plaintext.size)
        for (idx in plaintext.indices) {
            i = (i + 1) % 256
            j = (j + s[i]) % 256
            val tmp = s[i]
            s[i] = s[j]
            s[j] = tmp
            val t = (s[i] + s[j]) % 256
            out[idx] = (plaintext[idx].toInt() xor s[t]).toByte()
        }
        return out
    }

    /* -------------------------------------------------------- custom base64 */

    private fun getLongInt(roundNum: Int, data: ByteArray): Int {
        val i = roundNum * 3
        val b1 = if (i < data.size) data[i].toInt() and 0xFF else 0
        val b2 = if (i + 1 < data.size) data[i + 1].toInt() and 0xFF else 0
        val b3 = if (i + 2 < data.size) data[i + 2].toInt() and 0xFF else 0
        return (b1 shl 16) or (b2 shl 8) or b3
    }

    private fun resultEncrypt(data: ByteArray, table: String): String {
        val t = encodeTables.getValue(table)
        var totalChars = (data.size / 3) * 4
        if (data.size % 3 != 0) totalChars += 4
        val out = StringBuilder(totalChars)
        var roundNum = 0
        var longInt = getLongInt(0, data)
        for (i in 0 until totalChars) {
            if (i / 4 != roundNum) {
                roundNum++
                longInt = getLongInt(roundNum, data)
            }
            val idx = i % 4
            val charIndex = (longInt and encodeMasks[idx]) ushr encodeShifts[idx]
            out.append(t[charIndex])
        }
        return out.toString()
    }

    /* ------------------------------------------------------------- random */

    /**
     * Named "random" in the reference code but uses three fixed floats so the
     * 12-byte prefix is deterministic across runs (matching Go / Rust).
     */
    private fun generRandom(randomNum: Int, option0: Int, option1: Int): ByteArray {
        val byte1 = randomNum and 255
        val byte2 = (randomNum ushr 8) and 255
        return byteArrayOf(
            ((byte1 and 170) or (option0 and 85)).toByte(),
            ((byte1 and 85) or (option0 and 170)).toByte(),
            ((byte2 and 170) or (option1 and 85)).toByte(),
            ((byte2 and 85) or (option1 and 170)).toByte(),
        )
    }

    private fun generateRandomStr(): ByteArray {
        val vals = doubleArrayOf(0.123456789, 0.987654321, 0.555555555)
        return generRandom((vals[0] * 10000.0).toInt(), 3, 45) +
            generRandom((vals[1] * 10000.0).toInt(), 1, 0) +
            generRandom((vals[2] * 10000.0).toInt(), 1, 5)
    }

    /* ------------------------------------------------------------ assemble */

    private fun splitToBytes(num: Long): LongArray =
        longArrayOf(
            (num shr 24) and 255,
            (num shr 16) and 255,
            (num shr 8) and 255,
            num and 255,
        )

    private fun byteAt(data: ByteArray, i: Int): Long =
        if (i < data.size) (data[i].toInt() and 0xFF).toLong() else 0L

    private fun generateRc4BbStr(
        query: String,
        userAgent: String,
        windowEnvStr: String,
        suffix: String,
        arguments: IntArray,
    ): ByteArray {
        val startTime = System.currentTimeMillis()

        // Double SM3 — matches upstream Python (single hash differs).
        val urlList = sm3Sum(sm3Sum((query + suffix).toByteArray(Charsets.UTF_8)))
        val cusOnce = sm3Sum(suffix.toByteArray(Charsets.UTF_8))
        val cus = sm3Sum(cusOnce)
        val uaKey = byteArrayOf(0, 1, 14)
        val ua = sm3Sum(
            resultEncrypt(rc4Encrypt(userAgent.toByteArray(Charsets.UTF_8), uaKey), "s3")
                .toByteArray(Charsets.UTF_8),
        )

        val endTime = startTime + 100
        val b = LongArray(80)
        b[8] = 3
        b[10] = endTime
        b[16] = startTime
        b[18] = 44

        val stBytes = splitToBytes(b[16])
        b[20] = stBytes[0]
        b[21] = stBytes[1]
        b[22] = stBytes[2]
        b[23] = stBytes[3]
        b[24] = (b[16] / 256 / 256 / 256 / 256) and 255
        b[25] = (b[16] / 256 / 256 / 256 / 256 / 256) and 255

        val arg0Bytes = splitToBytes(arguments[0].toLong())
        b[26] = arg0Bytes[0]
        b[27] = arg0Bytes[1]
        b[28] = arg0Bytes[2]
        b[29] = arg0Bytes[3]

        b[30] = (arguments[1].toLong() / 256) and 255
        b[31] = (arguments[1].toLong() % 256) and 255

        val arg1Bytes = splitToBytes(arguments[1].toLong())
        b[32] = arg1Bytes[0]
        b[33] = arg1Bytes[1]

        val arg2Bytes = splitToBytes(arguments[2].toLong())
        b[34] = arg2Bytes[0]
        b[35] = arg2Bytes[1]
        b[36] = arg2Bytes[2]
        b[37] = arg2Bytes[3]

        b[38] = byteAt(urlList, 21)
        b[39] = byteAt(urlList, 22)
        b[40] = byteAt(cus, 21)
        b[41] = byteAt(cus, 22)
        b[42] = byteAt(ua, 23)
        b[43] = byteAt(ua, 24)

        val etBytes = splitToBytes(b[10])
        b[44] = etBytes[0]
        b[45] = etBytes[1]
        b[46] = etBytes[2]
        b[47] = etBytes[3]
        b[48] = b[8]
        b[49] = (b[10] / 256 / 256 / 256 / 256) and 255
        b[50] = (b[10] / 256 / 256 / 256 / 256 / 256) and 255

        val pageId = 110624L
        b[51] = pageId
        val pageBytes = splitToBytes(pageId)
        b[52] = pageBytes[0]
        b[53] = pageBytes[1]
        b[54] = pageBytes[2]
        b[55] = pageBytes[3]

        val aid = 6383L
        b[56] = aid
        b[57] = aid and 255
        b[58] = (aid shr 8) and 255
        b[59] = (aid shr 16) and 255
        b[60] = (aid shr 24) and 255

        val windowEnv = windowEnvStr.toByteArray(Charsets.UTF_8)
        b[64] = windowEnv.size.toLong()
        b[65] = windowEnv.size.toLong() and 255
        b[66] = (windowEnv.size.toLong() shr 8) and 255
        b[69] = 0
        b[70] = 0
        b[71] = 0

        val checksum = b[18] xor b[20] xor b[26] xor b[30] xor b[38] xor b[40] xor b[42] xor
            b[21] xor b[27] xor b[31] xor b[35] xor b[39] xor b[41] xor b[43] xor
            b[22] xor b[28] xor b[32] xor b[36] xor
            b[23] xor b[29] xor b[33] xor b[37] xor
            b[44] xor b[45] xor b[46] xor b[47] xor b[48] xor b[49] xor b[50] xor
            b[24] xor b[25] xor b[52] xor b[53] xor b[54] xor b[55] xor
            b[57] xor b[58] xor b[59] xor b[60] xor b[65] xor b[66] xor b[70] xor b[71]
        b[72] = checksum

        val bbList = ArrayList<Byte>(44 + windowEnv.size + 1)
        val indices = intArrayOf(
            18, 20, 52, 26, 30, 34,
            58, 38, 40, 53, 42, 21,
            27, 54, 55, 31, 35, 57,
            39, 41, 43, 22, 28, 32,
            60, 36, 23, 29, 33, 37,
            44, 45, 59, 46, 47, 48,
            49, 50, 24, 25, 65, 66,
            70, 71,
        )
        for (idx in indices) {
            bbList.add(b[idx].toByte())
        }
        windowEnv.forEach { bbList.add(it) }
        bbList.add(checksum.toByte())

        return rc4Encrypt(bbList.toByteArray(), byteArrayOf('y'.code.toByte()))
    }
}

/** Viewer-count text like `1.2万` / `10万+` → long (Go `parseViewerText`). */
internal fun parseViewerText(text: String): Long {
    var s = text.trim()
    if (s.isEmpty()) return 0L
    s = s.removeSuffix("+").removeSuffix("人").removeSuffix("次").trim()
    var multiplier = 1.0
    when {
        s.endsWith("万") -> {
            multiplier = 10_000.0
            s = s.removeSuffix("万")
        }
        s.endsWith("亿") -> {
            multiplier = 100_000_000.0
            s = s.removeSuffix("亿")
        }
        s.endsWith("w", ignoreCase = true) -> {
            multiplier = 10_000.0
            s = s.dropLast(1)
        }
    }
    s = s.trim()
    val v = s.toDoubleOrNull() ?: return 0L
    return (v * multiplier).roundToLong()
}

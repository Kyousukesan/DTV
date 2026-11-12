package com.dtv.androidtv.data.douyin

import org.junit.Assert.assertTrue
import org.junit.Test

class DouyinWsSignTest {
    @Test
    fun rhinoCompatWrapsBytedBlock() {
        val raw = javaClass.classLoader!!.getResourceAsStream("douyin/sign.js")!!
            .bufferedReader().readText()
        val wrapped = DouyinWsSign.rhinoCompat(raw)
        assertTrue(
            "expected IIFE wrap around byted_acrawler body",
            wrapped.contains("if (!window.byted_acrawler) (function() {"),
        )
        assertTrue(wrapped.contains("function get_sign("))
    }

    @Test
    fun getSignReturnsNonEmpty() {
        DouyinWsSign.resetForTests()
        val sig = DouyinWsSign.getSign("0123456789abcdef0123456789abcdef")
        println("get_sign sample=$sig len=${sig.length}")
        assertTrue("expected non-empty signature, got '$sig'", sig.isNotBlank())
    }
}

package com.dtv.androidtv.data.huya
import org.junit.Test
class HuyaRegPayloadTest {
  @Test fun dump() {
    val bytes = HuyaDanmakuProtocol.buildRegPayload(17363578L)
    println("KT_HEX=" + bytes.joinToString("") { "%02x".format(it) })
    println("KT_LEN=${bytes.size}")
  }
}

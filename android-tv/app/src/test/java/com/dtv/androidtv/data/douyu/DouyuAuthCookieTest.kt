package com.dtv.androidtv.data.douyu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DouyuAuthCookieTest {
    @Test
    fun cookieHasLoginAcceptsH5PairOrDyAuth() {
        assertFalse(DouyuClient.cookieHasLogin(""))
        assertFalse(DouyuClient.cookieHasLogin("dy_did=abc; acf_did=abc"))
        assertTrue(DouyuClient.cookieHasLogin("dy_auth=token; dy_did=abc"))
        assertTrue(
            DouyuClient.cookieHasLogin(
                "acf_auth=a; acf_jwt_token=b; dy_did=abc; acf_did=abc",
            ),
        )
    }
}

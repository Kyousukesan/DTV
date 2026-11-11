package com.dtv.androidtv

import android.app.Application
import com.dtv.androidtv.data.DouyuDtvApi
import com.dtv.androidtv.data.DtvApi

class DtvTvApp : Application() {
    /** Process-wide Douyu API; Slice-1 has no DI framework yet. */
    lateinit var dtvApi: DtvApi
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        dtvApi = DouyuDtvApi()
    }

    companion object {
        lateinit var instance: DtvTvApp
            private set
    }
}

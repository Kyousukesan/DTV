package com.dtv.androidtv

import android.app.Application
import com.dtv.androidtv.data.DouyuDtvApi
import com.dtv.androidtv.data.DtvApi
import com.dtv.androidtv.data.library.LibraryStore

class DtvTvApp : Application() {
    /** Process-wide Douyu API; no DI framework yet. */
    lateinit var dtvApi: DtvApi
        private set

    /** Local follows + recent for TV home rows. */
    lateinit var libraryStore: LibraryStore
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        dtvApi = DouyuDtvApi()
        libraryStore = LibraryStore(this)
    }

    companion object {
        lateinit var instance: DtvTvApp
            private set
    }
}

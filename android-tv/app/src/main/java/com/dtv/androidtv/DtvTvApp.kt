package com.dtv.androidtv

import android.app.Application
import com.dtv.androidtv.data.PlatformCatalog
import com.dtv.androidtv.data.library.LibraryStore

class DtvTvApp : Application() {
    /** Multi-platform API catalog (Douyu / Huya / Douyin / Bilibili). */
    lateinit var platforms: PlatformCatalog
        private set

    /** Local follows + recent for TV home rows (keyed by platform+roomId). */
    lateinit var libraryStore: LibraryStore
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        platforms = PlatformCatalog()
        libraryStore = LibraryStore(this)
    }

    companion object {
        lateinit var instance: DtvTvApp
            private set
    }
}

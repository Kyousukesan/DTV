package com.dtv.androidtv

import android.app.Application
import com.dtv.androidtv.data.PlatformCatalog
import com.dtv.androidtv.data.auth.AuthStore
import com.dtv.androidtv.data.douyin.DouyinWsSign
import com.dtv.androidtv.data.library.LibraryStore
import kotlin.concurrent.thread

class DtvTvApp : Application() {
    /** Multi-platform API catalog (shared Douyu/Bilibili clients for auth). */
    lateinit var platforms: PlatformCatalog
        private set

    lateinit var libraryStore: LibraryStore
        private set

    lateinit var authStore: AuthStore
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        DouyinWsSign.appContext = this
        platforms = PlatformCatalog()
        libraryStore = LibraryStore(this)
        authStore = AuthStore(this)
        // Restore cookies off the main thread (safeAuth / nav may hit network).
        thread(name = "dtv-auth-restore", isDaemon = true) {
            runCatching { platforms.restoreAuth(authStore) }
        }
    }

    companion object {
        lateinit var instance: DtvTvApp
            private set
    }
}

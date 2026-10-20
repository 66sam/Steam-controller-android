package dev.scmapper

import android.app.Application
import dev.scmapper.mapping.ProfileStore

class App : Application() {
    lateinit var profiles: ProfileStore
        private set
    lateinit var settings: Settings
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        profiles = ProfileStore(this)
        settings = Settings(this)
    }

    companion object {
        lateinit var instance: App
            private set
    }
}

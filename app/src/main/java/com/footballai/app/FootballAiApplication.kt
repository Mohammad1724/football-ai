package com.footballai.app

import android.app.Application
import com.footballai.app.data.sync.SyncScheduler
import com.footballai.app.di.AppContainer

class FootballAiApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        SyncScheduler.schedulePeriodicSync(this)
    }
}

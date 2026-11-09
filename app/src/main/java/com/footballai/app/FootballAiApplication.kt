package com.footballai.app

import android.app.Application
import com.footballai.app.data.sync.SyncScheduler
import com.footballai.app.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class FootballAiApplication : Application() {

    lateinit var container: AppContainer
        private set

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Honour the user's background-sync preference instead of re-arming the worker
        // on every launch: reading DataStore is a suspend call, so it happens off the
        // main thread and the scheduler is (re)configured once the value is known.
        applicationScope.launch {
            val enabled = container.settingsRepository.settings.first().backgroundSyncEnabled
            if (enabled) {
                SyncScheduler.schedulePeriodicSync(this@FootballAiApplication)
            } else {
                SyncScheduler.cancelPeriodicSync(this@FootballAiApplication)
            }
        }
    }
}

package com.footballai.app

import android.app.Application
import com.footballai.app.core.CrashLog
import com.footballai.app.data.sync.SyncScheduler
import com.footballai.app.di.AppContainer

class FootballAiApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Installed before anything else so a failure during start-up is recorded too.
        CrashLog.install(this)
        container = AppContainer(this)
        // The rewrite (v2) refreshes its single FotMob-mirror source itself, so
        // the old provider sync is retired: cancel any periodic work an older
        // install scheduled instead of re-arming it on every launch. The old
        // container above is still constructed for now and removed with the
        // rest of the old layer in a later slice.
        SyncScheduler.cancelPeriodicSync(this)
    }
}

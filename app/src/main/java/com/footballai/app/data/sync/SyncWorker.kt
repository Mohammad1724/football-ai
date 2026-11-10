package com.footballai.app.data.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.footballai.app.FootballAiApplication
import com.footballai.app.core.Outcome
import java.util.concurrent.TimeUnit

/**
 * Daily keyless analysis, or optional advanced-provider refresh.
 *
 * The worker is deliberately conservative: it runs once per day (Android may defer execution), only on
 * an unmetered-or-any network when the user has enabled background sync, and it
 * stops immediately when no provider is configured.
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val application = applicationContext as? FootballAiApplication ?: return Result.success()
        val container = application.container
        val settings = container.settingsRepository.current()
        if (!settings.backgroundSyncEnabled || !settings.onboarded) return Result.success()

        val providerId = container.providerRegistry.activeProviderId() ?: return Result.success()

        return try {
            if (settings.dailyAnalysisEnabled) {
                val report = container.dailyAnalysisRepository.run()
                return if (report.errors.isEmpty() && report.failed == 0) Result.success() else Result.retry()
            }
            when (val fixtures = container.syncRepository.syncFixtures(providerId)) {
                is Outcome.Failure -> Result.retry()
                is Outcome.Success -> {
                    if (providerId == "api-football") {
                        container.syncRepository.syncLive(providerId)
                    }
                    container.predictionRepository.resolveFinishedPredictions()
                    Result.success()
                }
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "football-ai-periodic-sync"
    }
}

object SyncScheduler {

    fun schedulePeriodicSync(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<SyncWorker>(24, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            SyncWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun cancelPeriodicSync(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(SyncWorker.WORK_NAME)
    }
}

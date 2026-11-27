package io.github.sparkcyf.voicesipagent.app

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Watchdog (feature: auto-start). Every 15 minutes (the shortest periodic interval Android
 * allows) it makes sure the gateway service is running. [GatewayService.start] is a no-op
 * when the gateway is switched off, so the watchdog never wakes a gateway the user turned
 * off. It also cannot help after a user force-stop — only reopening the app fixes that —
 * and it does not restart Shizuku, which always needs a manual restart after a reboot.
 * WorkManager persists this work across reboots, so no boot receiver is needed for it.
 */
class GatewayWatchdogWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {
    override fun doWork(): Result {
        GatewayService.start(applicationContext)
        return Result.success()
    }

    companion object {
        private const val UNIQUE_NAME = "gateway-watchdog"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<GatewayWatchdogWorker>(15, TimeUnit.MINUTES)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request,
            )
        }
    }
}

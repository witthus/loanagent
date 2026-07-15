package com.loanagent.devicecontroller

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import java.util.concurrent.Executors

object UpgradePollScheduler {
    const val JOB_ID = 71015
    private const val PERIOD_MS = 15 * 60 * 1000L
    private const val TAG = "UpgradePollSched"

    fun schedule(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        val component = ComponentName(context, UpgradePollJobService::class.java)
        val info = JobInfo.Builder(JOB_ID, component)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .setPeriodic(PERIOD_MS)
            .build()
        try {
            scheduler.schedule(info)
        } catch (error: RuntimeException) {
            // HyperOS / some OEMs reject persisted jobs; UI must still open.
            Log.w(TAG, "schedule persisted upgrade poll failed; retrying non-persisted", error)
            try {
                val ephemeral = JobInfo.Builder(JOB_ID, component)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(PERIOD_MS)
                    .build()
                scheduler.schedule(ephemeral)
            } catch (fallback: RuntimeException) {
                Log.w(TAG, "schedule upgrade poll failed", fallback)
            }
        }
    }

    fun runNow(context: Context, onDone: (() -> Unit)? = null) {
        Executors.newSingleThreadExecutor().execute {
            try {
                RemoteUpgradeCoordinator(context.applicationContext).pollAndMaybeInstall()
            } catch (error: Exception) {
                Log.w(TAG, "upgrade poll now failed", error)
            } finally {
                onDone?.invoke()
            }
        }
    }
}

class UpgradePollJobService : JobService() {
    private val executor = Executors.newSingleThreadExecutor()

    override fun onStartJob(params: JobParameters?): Boolean {
        executor.execute {
            try {
                RemoteUpgradeCoordinator(applicationContext).pollAndMaybeInstall()
            } catch (error: Exception) {
                Log.w(TAG, "upgrade poll job failed", error)
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean = false

    companion object {
        private const val TAG = "UpgradePollJob"
    }
}

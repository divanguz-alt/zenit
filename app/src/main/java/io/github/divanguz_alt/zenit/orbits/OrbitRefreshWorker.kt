package io.github.divanguz_alt.zenit.orbits

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/** Daily refresh of the orbital elements, only with network connectivity. */
@HiltWorker
class OrbitRefreshWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: OrbitRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = when (repository.refresh(force = false)) {
        is RefreshResult.Failed -> if (runAttemptCount < 3) Result.retry() else Result.failure()
        else -> Result.success()
    }

    companion object {
        private const val PERIODIC = "orbit-refresh"
        private const val ONCE = "orbit-refresh-once"

        private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            wm.enqueueUniquePeriodicWork(
                PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<OrbitRefreshWorker>(1, TimeUnit.DAYS)
                    .setConstraints(constraints)
                    .build(),
            )
        }

        /** One-off refresh as soon as the network is available (e.g. first launch with an empty cache). */
        fun runSoon(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONCE,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<OrbitRefreshWorker>().setConstraints(constraints).build(),
            )
        }
    }
}

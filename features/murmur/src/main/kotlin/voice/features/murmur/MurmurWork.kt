package voice.features.murmur

import android.app.Application
import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import voice.core.common.rootGraphAs
import voice.core.initializer.AppInitializer
import voice.core.logging.api.Logger
import java.io.IOException
import java.util.concurrent.TimeUnit

@ContributesTo(AppScope::class)
interface MurmurGraph {
  val murmurSync: MurmurSync
  val murmurViewModel: MurmurViewModel
  val murmurUpdater: MurmurUpdater
}

/**
 * Runs [MurmurSync] in the background. There is no push channel, so the server
 * is polled every 15 minutes (WorkManager's minimum) and whenever the user acts.
 * Each run also checks GitHub for a newer app release.
 */
class MurmurSyncWorker(
  context: Context,
  params: WorkerParameters,
) : CoroutineWorker(context, params) {

  override suspend fun doWork(): Result = try {
    val graph = rootGraphAs<MurmurGraph>()
    try {
      val _ = graph.murmurUpdater.check()
    } catch (e: Exception) {
      Logger.w(e, "Murmur: update check failed")
    }
    graph.murmurSync.sync()
    Result.success()
  } catch (e: IOException) {
    Logger.w(e, "Murmur sync failed")
    Result.retry()
  }
}

@Inject
class MurmurScheduler(private val context: Context) {

  private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

  fun schedulePeriodic() {
    val request = PeriodicWorkRequestBuilder<MurmurSyncWorker>(15, TimeUnit.MINUTES)
      .setConstraints(constraints)
      .build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork("murmur-sync", ExistingPeriodicWorkPolicy.KEEP, request)
  }

  fun syncNow() {
    val request = OneTimeWorkRequestBuilder<MurmurSyncWorker>()
      .setConstraints(constraints)
      .build()
    WorkManager.getInstance(context).enqueueUniqueWork("murmur-sync-now", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
  }
}

@ContributesIntoSet(AppScope::class)
@Inject
class MurmurInitializer(private val scheduler: MurmurScheduler) : AppInitializer {
  override fun onAppStart(application: Application) {
    scheduler.schedulePeriodic()
    scheduler.syncNow()
  }
}

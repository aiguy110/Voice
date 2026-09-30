package voice.features.murmur

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.datastore.core.DataStore
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.first
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
  val magnetImports: MagnetImports
  val murmurScheduler: MurmurScheduler
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
    // Imports interrupted by a reboot or the system's foreground-service limits pick up again here.
    graph.murmurScheduler.importNow()
    Result.success()
  } catch (e: IOException) {
    Logger.w(e, "Murmur sync failed")
    Result.retry()
  }
}

/**
 * Downloads magnet imports in a foreground service, since a torrent can take hours.
 * Stopping it (a reboot, the system's data-sync limits) loses nothing: libtorrent
 * rechecks what's on disk when the next run adds the torrent again.
 */
class MagnetImportWorker(
  context: Context,
  params: WorkerParameters,
) : CoroutineWorker(context, params) {

  override suspend fun doWork(): Result {
    val imports = rootGraphAs<MurmurGraph>().magnetImports
    return try {
      imports.run { status -> foreground(status) }
      Result.success()
    } catch (e: IOException) {
      Logger.w(e, "Murmur: magnet import failed")
      Result.retry()
    }
  }

  override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("Starting…")

  private suspend fun foreground(status: String) {
    try {
      setForeground(foregroundInfo(status))
    } catch (e: IllegalStateException) {
      // Started from the background, where Android no longer allows foreground services; carry on without.
      Logger.w(e, "Murmur: magnet import running without a notification")
    }
  }

  private fun foregroundInfo(status: String): ForegroundInfo {
    val manager = applicationContext.getSystemService<NotificationManager>()
    manager?.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Magnet imports", NotificationManager.IMPORTANCE_LOW))
    val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
      .setSmallIcon(R.drawable.murmur_update)
      .setContentTitle("Importing audiobooks")
      .setContentText(status)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .build()
    return if (Build.VERSION.SDK_INT >= 29) {
      ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    } else {
      ForegroundInfo(NOTIFICATION_ID, notification)
    }
  }

  private companion object {
    const val CHANNEL_ID = "murmur_imports"
    const val NOTIFICATION_ID = 0x6d76
  }
}

@Inject
class MurmurScheduler(
  private val context: Context,
  private val settingsStore: DataStore<MurmurSettings>,
) {

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

  /** Starts downloading pending magnet imports, unless that's already happening. */
  suspend fun importNow() {
    val settings = settingsStore.data.first()
    if (settings.imports.isEmpty()) return
    val network = if (settings.allowMetered) NetworkType.CONNECTED else NetworkType.UNMETERED
    val request = OneTimeWorkRequestBuilder<MagnetImportWorker>()
      .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
      .build()
    WorkManager.getInstance(context).enqueueUniqueWork("murmur-magnet-imports", ExistingWorkPolicy.KEEP, request)
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

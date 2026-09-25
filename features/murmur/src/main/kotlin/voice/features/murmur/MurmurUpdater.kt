package voice.features.murmur

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.content.getSystemService
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.net.toUri
import androidx.datastore.core.DataStore
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import voice.core.common.rootGraphAs
import voice.core.logging.api.Logger
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A GitHub release of the Murmur build of Voice. Tags are `murmur-<versionCode>`. */
data class MurmurRelease(
  val versionCode: Long,
  val tag: String,
  val apkUrl: String,
)

/** Progress of downloading the [MurmurUpdater.available] release. */
sealed interface UpdateDownload {
  data object None : UpdateDownload

  /** [fraction] is null until the size is known. */
  data class Running(val fraction: Float?) : UpdateDownload

  /** The APK is cached, so installing doesn't download it again. */
  data object Done : UpdateDownload
}

/**
 * Keeps the Murmur build of Voice up to date from the fork's GitHub releases. [check] runs with
 * every sync; installing goes through [MurmurUpdateActivity] (which gets the install permission)
 * and [MurmurUpdateWorker] (which downloads the APK into the cache, then hands it to a PackageInstaller session).
 */
@SingleIn(AppScope::class)
@Inject
class MurmurUpdater(
  private val context: Context,
  private val settingsStore: DataStore<MurmurSettings>,
) {

  private val client = OkHttpClient.Builder()
    .connectTimeout(60, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .build()

  /** A newer release than the installed one, as of the last check. */
  val available: StateFlow<MurmurRelease?>
    field = MutableStateFlow(null)

  val download: StateFlow<UpdateDownload>
    field = MutableStateFlow<UpdateDownload>(UpdateDownload.None)

  private val scope = MainScope()
  private val downloadDir get() = File(context.cacheDir, "murmur-update")

  /** Only the Murmur build updates itself, so a plain Voice install never turns into one. */
  val enabled: Boolean get() = context.packageName.endsWith(".murmur")

  val installedVersion: Long
    get() = PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))

  suspend fun check(): MurmurRelease? {
    if (!enabled) return null
    val release = latest()?.takeIf { it.versionCode > installedVersion }
    available.value = release
    syncDownload(release)
    if (release == null) {
      notificationManager()?.cancel(NOTIFICATION_ID)
    } else if (settingsStore.data.first().updateNotified < release.versionCode) {
      Logger.i("Murmur: ${release.tag} is available")
      notify(context, "Update available", "${release.tag} is ready. Tap to install.", updateActivityIntent(context))
      settingsStore.updateData { it.copy(updateNotified = release.versionCode) }
    }
    return release
  }

  /**
   * Installs the cached APK, or queues the download and install when there isn't one. The caller must hold
   * the install-packages permission.
   */
  fun install() {
    val apk = available.value?.let(::apkFile)
    if (apk != null && apk.exists()) {
      scope.launch {
        try {
          installFrom(apk)
        } catch (e: IOException) {
          Logger.w(e, "Murmur: installing the downloaded update failed")
          Toast.makeText(context, "Couldn't install the update: ${e.message}", Toast.LENGTH_LONG).show()
        }
      }
      return
    }
    download.value = UpdateDownload.Running(null)
    val request = OneTimeWorkRequestBuilder<MurmurUpdateWorker>()
      .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
      .build()
    WorkManager.getInstance(context).enqueueUniqueWork("murmur-update", ExistingWorkPolicy.KEEP, request)
    Toast.makeText(context, "Downloading the update…", Toast.LENGTH_SHORT).show()
  }

  internal suspend fun downloadAndInstall() {
    val release = check() ?: return downloadStopped()
    val apk = apkFile(release)
    if (!apk.exists()) {
      download.value = UpdateDownload.Running(null)
      downloadApk(release, apk)
      download.value = UpdateDownload.Done
    }
    installFrom(apk)
  }

  /** Called when the worker gives up or finds nothing to download, so the UI stops showing a download. */
  internal fun downloadStopped() {
    download.value = UpdateDownload.None
    syncDownload(available.value)
  }

  private suspend fun downloadApk(
    release: MurmurRelease,
    apk: File,
  ) = withContext(Dispatchers.IO) {
    apk.parentFile!!.mkdirs()
    val part = File(apk.parentFile, "${apk.name}.part")
    client.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { response ->
      if (!response.isSuccessful) throw IOException("APK download: HTTP ${response.code}")
      val total = response.body.contentLength()
      var copied = 0L
      var percent = -1
      part.outputStream().use { out ->
        response.body.byteStream().use { input ->
          val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
          while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            copied += read
            if (total > 0 && copied * 100 / total != percent.toLong()) {
              percent = (copied * 100 / total).toInt()
              download.value = UpdateDownload.Running(copied.toFloat() / total)
            }
          }
        }
      }
      if (total > 0 && copied != total) throw IOException("APK download: got $copied of $total bytes")
    }
    if (!part.renameTo(apk)) throw IOException("APK download: can't move it into place")
  }

  private suspend fun installFrom(apk: File) {
    val installer = context.packageManager.packageInstaller
    val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
      setAppPackageName(context.packageName)
      if (Build.VERSION.SDK_INT >= 31) {
        // Honoured once this app installed the current version; otherwise Android asks.
        setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
      }
    }
    val sessionId = installer.createSession(params)
    try {
      withContext(Dispatchers.IO) {
        installer.openSession(sessionId).use { session ->
          session.openWrite("murmur.apk", 0, apk.length()).use { out ->
            apk.inputStream().use { it.copyTo(out) }
            session.fsync(out)
          }
          val status = PendingIntent.getBroadcast(
            context,
            sessionId,
            Intent(context, MurmurInstallReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
          )
          session.commit(status.intentSender)
        }
      }
      Logger.i("Murmur: installing ${apk.name}")
    } catch (e: Exception) {
      installer.abandonSession(sessionId)
      throw e
    }
  }

  private fun apkFile(release: MurmurRelease) = File(downloadDir, "${release.tag}.apk")

  /** Drops APKs of other releases and reflects whether [release]'s is cached, unless it's downloading. */
  private fun syncDownload(release: MurmurRelease?) {
    downloadDir.listFiles()?.filter { release == null || !it.name.startsWith("${release.tag}.") }?.forEach { it.delete() }
    if (download.value is UpdateDownload.Running) return
    download.value = if (release != null && apkFile(release).exists()) UpdateDownload.Done else UpdateDownload.None
  }

  private suspend fun latest(): MurmurRelease? = withContext(Dispatchers.IO) {
    val request = Request.Builder().url(LATEST_RELEASE).header("Accept", "application/vnd.github+json").build()
    client.newCall(request).execute().use { response ->
      if (!response.isSuccessful) throw IOException("GitHub releases: HTTP ${response.code}")
      val release = murmurJson.decodeFromString(GitHubRelease.serializer(), response.body.string())
      val versionCode = release.tag.removePrefix("murmur-").toLongOrNull() ?: return@use null
      val apk = release.assets.firstOrNull { it.name == "murmur.apk" } ?: return@use null
      MurmurRelease(versionCode, release.tag, apk.url)
    }
  }

  private fun notificationManager() = context.getSystemService<NotificationManager>()

  companion object {
    private const val LATEST_RELEASE = "https://api.github.com/repos/aiguy110/Voice/releases/latest"
    private const val CHANNEL_ID = "murmur_updates"
    private const val NOTIFICATION_ID = 0x6d75

    fun updateActivityIntent(context: Context): Intent = Intent(context, MurmurUpdateActivity::class.java)

    internal fun notify(
      context: Context,
      title: String,
      text: String,
      tap: Intent,
    ) {
      if (Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
      ) {
        return
      }
      val manager = context.getSystemService<NotificationManager>() ?: return
      manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_DEFAULT))
      val notification = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.murmur_update)
        .setContentTitle(title)
        .setContentText(text)
        .setContentIntent(PendingIntent.getActivity(context, 0, tap, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        .setAutoCancel(true)
        .build()
      manager.notify(NOTIFICATION_ID, notification)
    }
  }
}

@Serializable
private data class GitHubRelease(
  @SerialName("tag_name") val tag: String,
  val assets: List<GitHubAsset>,
)

@Serializable
private data class GitHubAsset(
  val name: String,
  @SerialName("browser_download_url") val url: String,
)

class MurmurUpdateWorker(
  context: Context,
  params: WorkerParameters,
) : CoroutineWorker(context, params) {

  override suspend fun doWork(): Result = try {
    rootGraphAs<MurmurGraph>().murmurUpdater.downloadAndInstall()
    Result.success()
  } catch (e: IOException) {
    Logger.w(e, "Murmur: update download failed")
    if (runAttemptCount < 3) {
      Result.retry()
    } else {
      rootGraphAs<MurmurGraph>().murmurUpdater.downloadStopped()
      MurmurUpdater.notify(applicationContext, "Update failed", "Tap to try again.", MurmurUpdater.updateActivityIntent(applicationContext))
      Result.failure()
    }
  }
}

/** Receives the PackageInstaller session result. */
class MurmurInstallReceiver : BroadcastReceiver() {

  override fun onReceive(
    context: Context,
    intent: Intent,
  ) {
    when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
      PackageInstaller.STATUS_PENDING_USER_ACTION -> {
        val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return
        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Starting the prompt only works while Voice is in the foreground; the notification covers the rest.
        MurmurUpdater.notify(context, "Update downloaded", "Tap to finish installing.", confirm)
        try {
          context.startActivity(confirm)
        } catch (e: RuntimeException) {
          Logger.i("Murmur: can't show the install prompt from the background (${e.message})")
        }
      }
      PackageInstaller.STATUS_SUCCESS -> Logger.i("Murmur: update installed")
      else -> {
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown error"
        Logger.w("Murmur: update failed: $message")
        MurmurUpdater.notify(context, "Update failed", message, MurmurUpdater.updateActivityIntent(context))
      }
    }
  }
}

/** Invisible: makes sure Voice may install packages, then starts the update. */
class MurmurUpdateActivity : ComponentActivity() {

  private val allowInstalls = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { startUpdate() }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    if (savedInstanceState != null) return
    if (packageManager.canRequestPackageInstalls()) {
      startUpdate()
    } else {
      Toast.makeText(this, "Allow Voice to install its own updates", Toast.LENGTH_LONG).show()
      allowInstalls.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:$packageName".toUri()))
    }
  }

  private fun startUpdate() {
    if (packageManager.canRequestPackageInstalls()) {
      rootGraphAs<MurmurGraph>().murmurUpdater.install()
    } else {
      Toast.makeText(this, "Voice can't update itself without that permission", Toast.LENGTH_LONG).show()
    }
    finish()
  }
}

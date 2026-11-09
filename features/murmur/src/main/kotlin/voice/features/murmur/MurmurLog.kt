package voice.features.murmur

import android.app.Application
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import voice.core.initializer.AppInitializer
import voice.core.logging.api.LogWriter
import voice.core.logging.api.Logger
import java.io.File
import java.time.Instant
import java.util.concurrent.Executors

/**
 * Keeps the app's recent log lines in two rotating files under `filesDir/murmur`, so diagnostics sent to the server can
 * include them. Release builds have no other log writer, and the phone is usually not on adb when something goes wrong.
 */
internal class MurmurLog(private val dir: File) : LogWriter {

  private val executor = Executors.newSingleThreadExecutor { Thread(it, "murmur-log").apply { isDaemon = true } }
  private val current = File(dir, "log.txt")
  private val previous = File(dir, "log.1.txt")

  override fun log(
    severity: Logger.Severity,
    message: String,
    throwable: Throwable?,
  ) {
    if (severity < Logger.Severity.Info) return
    val line = "${Instant.now()} ${severity.name.first()} $message\n"
    executor.execute { append(line) }
  }

  private fun append(line: String) {
    try {
      dir.mkdirs()
      if (current.length() > MAX_FILE_BYTES) {
        previous.delete()
        current.renameTo(previous)
      }
      current.appendText(line)
    } catch (_: Exception) {
      // Logging must never take the app down.
    }
  }

  /** The newest [maxBytes] of log text, oldest first. */
  fun tail(maxBytes: Int = MAX_FILE_BYTES * 2): String {
    val text = executor.submit<String> {
      listOf(previous, current).filter { it.exists() }.joinToString("") { it.readText() }
    }.get()
    return if (text.length > maxBytes) text.substring(text.length - maxBytes) else text
  }

  companion object {
    const val MAX_FILE_BYTES = 512 * 1024

    @Volatile
    var instance: MurmurLog? = null
      private set

    fun install(application: Application) {
      val log = MurmurLog(File(application.filesDir, "murmur"))
      instance = log
      Logger.install(log)
    }
  }
}

@ContributesIntoSet(AppScope::class)
class MurmurLogInitializer : AppInitializer {
  override fun onAppStart(application: Application) {
    MurmurLog.install(application)
  }
}

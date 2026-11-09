package voice.features.murmur

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.core.content.pm.PackageInfoCompat
import androidx.datastore.core.DataStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import voice.core.data.folders.FolderPickListener
import voice.core.documentfile.CachedDocumentFile
import voice.core.documentfile.CachedDocumentFileFactory
import voice.core.logging.api.Logger

/** With the user's opt-in, sends the tree of every folder they try to add to Voice, so admins can see how it's laid out. */
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class)
@Inject
class MurmurTelemetry(
  private val reporter: MurmurReporter,
  private val documentFileFactory: CachedDocumentFileFactory,
) : FolderPickListener {

  override fun onPicked(uri: Uri) {
    reporter.report("folder_picked") { folderPickedReport(uri) }
  }

  private fun JsonObjectBuilder.folderPickedReport(uri: Uri) {
    val root = if (DocumentsContract.isTreeUri(uri)) {
      DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
    } else {
      uri
    }
    val walk = TreeWalk(MAX_ENTRIES)
    val tree = walk.node(documentFileFactory.create(root))
    put("uri", uri.toString())
    put("entries", walk.entries)
    put("truncated", walk.truncated)
    put("tree", tree)
  }

  companion object {
    const val MAX_ENTRIES = 50_000
  }
}

/**
 * Sends opt-in diagnostic reports (`POST /telemetry`). Reports are built and sent in the background and never throw:
 * telemetry must not break what it describes.
 */
@SingleIn(AppScope::class)
@Inject
class MurmurReporter(
  private val context: Context,
  private val settingsStore: DataStore<MurmurSettings>,
) {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val sentOnce = mutableSetOf<String>()

  /** Sends [event] if the user opted in. With [logs], the recent app log goes along. */
  fun report(
    event: String,
    logs: Boolean = false,
    body: suspend JsonObjectBuilder.() -> Unit,
  ) {
    scope.launch {
      val settings = settingsStore.data.first()
      if (!settings.reportTelemetry || settings.connection == null) return@launch
      try {
        send(event, logs, body)
      } catch (e: Exception) {
        Logger.w(e, "Murmur: sending $event telemetry failed")
      }
    }
  }

  /** Like [report], but only the first time this process sees [key]; for failures that repeat every pass. */
  fun reportOnce(
    key: String,
    event: String,
    logs: Boolean = false,
    body: suspend JsonObjectBuilder.() -> Unit,
  ) {
    if (synchronized(sentOnce) { sentOnce.add(key) }) report(event, logs, body)
  }

  /** Sends [event] now, whether or not the user opted in to automatic reports; for reports the user asks for. */
  suspend fun send(
    event: String,
    logs: Boolean,
    body: suspend JsonObjectBuilder.() -> Unit,
  ) {
    val connection = checkNotNull(settingsStore.data.first().connection) { "Join a Murmur server first." }
    val fields = buildJsonObject { body() }
    val report = buildJsonObject {
      put("event", event)
      put("app", appInfo())
      fields.forEach { (key, value) -> put(key, value) }
      if (logs) put("log", MurmurLog.instance?.tail().orEmpty())
    }
    withContext(Dispatchers.IO) { MurmurApi(connection.serverUrl, connection.token).telemetry(report) }
    Logger.i("Murmur: sent $event telemetry")
  }

  private fun appInfo(): JsonObject {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    return buildJsonObject {
      put("package", context.packageName)
      put("version_name", info.versionName)
      put("version_code", PackageInfoCompat.getLongVersionCode(info))
      put("android_sdk", Build.VERSION.SDK_INT)
      put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
    }
  }
}

/** Describes an exception for a report, with its causes. */
internal fun Throwable.report(): String = stackTraceToString().lines().take(40).joinToString("\n")

/** Renders a document tree as protocol `folder_picked` nodes, stopping after [maxEntries]. */
internal class TreeWalk(private val maxEntries: Int) {

  var entries = 0
    private set
  var truncated = false
    private set

  fun node(file: CachedDocumentFile): JsonObject {
    entries++
    return buildJsonObject {
      put("name", file.name ?: file.uri.lastPathSegment)
      if (file.isDirectory) {
        put("dir", true)
        put(
          "children",
          buildJsonArray {
            for (child in file.children.sortedBy { it.name }) {
              if (entries >= maxEntries) {
                truncated = true
                break
              }
              add(node(child))
            }
          },
        )
      } else {
        put("size", file.length)
      }
    }
  }
}

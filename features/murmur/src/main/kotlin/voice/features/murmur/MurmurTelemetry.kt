package voice.features.murmur

import android.net.Uri
import android.provider.DocumentsContract
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
import kotlinx.serialization.json.JsonObject
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
  private val settingsStore: DataStore<MurmurSettings>,
  private val documentFileFactory: CachedDocumentFileFactory,
) : FolderPickListener {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  override fun onPicked(uri: Uri) {
    scope.launch {
      val settings = settingsStore.data.first()
      val connection = settings.connection
      if (!settings.reportTelemetry || connection == null) return@launch
      try {
        val report = folderPickedReport(uri)
        MurmurApi(connection.serverUrl, connection.token).telemetry(report)
        Logger.i("Murmur: reported the tree of $uri")
      } catch (e: Exception) {
        Logger.w(e, "Murmur: reporting the tree of $uri failed")
      }
    }
  }

  private fun folderPickedReport(uri: Uri): JsonObject {
    val root = if (DocumentsContract.isTreeUri(uri)) {
      DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
    } else {
      uri
    }
    val walk = TreeWalk(MAX_ENTRIES)
    val tree = walk.node(documentFileFactory.create(root))
    return buildJsonObject {
      put("event", "folder_picked")
      put("uri", uri.toString())
      put("entries", walk.entries)
      put("truncated", walk.truncated)
      put("tree", tree)
    }
  }

  companion object {
    const val MAX_ENTRIES = 50_000
  }
}

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

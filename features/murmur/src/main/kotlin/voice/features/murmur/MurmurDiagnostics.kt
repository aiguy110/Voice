package voice.features.murmur

import android.content.Context
import androidx.datastore.core.DataStore
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import voice.core.data.folders.AudiobookFolders
import voice.core.data.repo.BookRepository
import java.io.File

/**
 * The `diagnostics` report the user sends from Murmur settings: Murmur's saved state (without the token), Voice's
 * library folders and books, the download folder on disk, and the recent app log. Enough to work out what happened to
 * a transfer or import without the phone on adb.
 */
@Inject
class MurmurDiagnostics(
  private val context: Context,
  private val settingsStore: DataStore<MurmurSettings>,
  private val audiobookFolders: AudiobookFolders,
  private val bookRepository: BookRepository,
  private val reporter: MurmurReporter,
) {

  suspend fun send() {
    val settings = settingsStore.data.first()
    reporter.send("diagnostics", logs = true) {
      put("settings", murmurJson.encodeToJsonElement(MurmurSettings.serializer(), settings.copy(token = null)))
      put("file_access", StorageAccess.granted(context))
      put(
        "audiobook_folders",
        buildJsonObject {
          audiobookFolders.all().first().forEach { (type, folders) ->
            put(type.name, buildJsonArray { folders.forEach { add(JsonPrimitive(it.uri.toString())) } })
          }
        },
      )
      put(
        "books",
        buildJsonArray {
          bookRepository.all().forEach { book ->
            add(
              buildJsonObject {
                put("id", book.id.value)
                put("name", book.content.name)
                put("author", book.content.author)
                put("active", book.content.isActive)
                put("chapters", book.chapters.size)
              },
            )
          }
        },
      )
      val folder = settings.downloadFolder?.let { runCatching { StorageAccess.file(it) }.getOrNull() }
      put("download_folder_path", folder?.absolutePath)
      if (folder != null) put("download_folder", fileTree(folder, MAX_ENTRIES))
      put("torrents", fileTree(File(context.filesDir, "murmur/torrents"), 1_000))
      put("staging", fileTree(File(context.getExternalFilesDir(null) ?: context.filesDir, "murmur-torrents"), 5_000))
    }
  }

  companion object {
    const val MAX_ENTRIES = 20_000
  }
}

/** A directory as protocol `folder_picked` nodes, read by path; stops after [maxEntries] and marks the cut-off. */
internal fun fileTree(
  root: File,
  maxEntries: Int,
): JsonObject {
  var entries = 0
  fun node(file: File): JsonObject = buildJsonObject {
    entries++
    put("name", file.name)
    if (file.isDirectory) {
      put("dir", true)
      put(
        "children",
        buildJsonArray {
          for (child in file.listFiles().orEmpty().sortedBy { it.name }) {
            if (entries >= maxEntries) {
              add(buildJsonObject { put("truncated", true) })
              break
            }
            add(node(child))
          }
        },
      )
    } else {
      put("size", file.length())
    }
  }
  if (!root.exists()) {
    return buildJsonObject {
      put("name", root.name)
      put("missing", true)
    }
  }
  return node(root)
}

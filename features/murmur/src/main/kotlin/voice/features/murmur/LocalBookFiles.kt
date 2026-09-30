package voice.features.murmur

import android.content.Context
import android.net.Uri
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import voice.core.data.BookId
import voice.core.data.isAudioFile
import voice.core.documentfile.CachedDocumentFile
import voice.core.documentfile.CachedDocumentFileFactory
import java.io.IOException
import java.text.Normalizer

/** An audio file of a local Voice book, addressed by its Murmur manifest path. */
data class LocalFile(
  val path: String,
  val uri: Uri,
  val size: Long,
)

/**
 * Maps a Voice book onto the files Murmur shares. A book's manifest covers its
 * audio files only, so incidental files (covers, .nomedia) don't change its id.
 */
@Inject
class LocalBookFiles(
  private val context: Context,
  private val documentFileFactory: CachedDocumentFileFactory,
) {

  suspend fun files(bookId: BookId): List<LocalFile> = withContext(Dispatchers.IO) {
    val root = documentFileFactory.create(bookId.toUri())
    buildList {
      if (root.isFile) {
        if (root.isAudioFile()) add(root.toLocal(prefix = ""))
      } else {
        collect(root, prefix = "")
      }
    }
  }

  /** Reads every file once: the manifest, and the book torrent's piece hashes. */
  suspend fun describe(files: List<LocalFile>): Pair<List<ManifestFile>, ByteArray> = withContext(Dispatchers.IO) {
    val byPath = files.associateBy { it.path }
    BookTorrent.describe(byPath.mapValues { it.value.size }) { path ->
      context.contentResolver.openInputStream(byPath.getValue(path).uri) ?: throw IOException("cannot open $path")
    }
  }

  private fun MutableList<LocalFile>.collect(
    dir: CachedDocumentFile,
    prefix: String,
  ) {
    dir.children.forEach { child ->
      when {
        child.isDirectory -> collect(child, prefix + child.normalizedName() + "/")
        child.isAudioFile() -> add(child.toLocal(prefix))
      }
    }
  }

  private fun CachedDocumentFile.toLocal(prefix: String) = LocalFile(prefix + normalizedName(), uri, length)

  private fun CachedDocumentFile.normalizedName(): String {
    val name = name ?: throw IOException("unnamed document $uri")
    return Normalizer.normalize(name, Normalizer.Form.NFC)
  }
}

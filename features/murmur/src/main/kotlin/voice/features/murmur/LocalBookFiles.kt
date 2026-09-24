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
import java.security.MessageDigest
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

  suspend fun manifest(files: List<LocalFile>): List<ManifestFile> = withContext(Dispatchers.IO) {
    files.map { ManifestFile(it.path, it.size, sha256(it.uri)) }
  }

  suspend fun sha256(uri: Uri): String = withContext(Dispatchers.IO) {
    val digest = MessageDigest.getInstance("SHA-256")
    val input = context.contentResolver.openInputStream(uri) ?: throw IOException("cannot open $uri")
    input.use {
      val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 16)
      while (true) {
        val read = it.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
      }
    }
    digest.digest().toHex()
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

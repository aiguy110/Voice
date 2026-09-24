package voice.features.murmur

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import voice.core.data.BookId
import voice.core.data.repo.BookRepository
import voice.core.logging.api.Logger
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max

/**
 * Community book covers. Holders upload a thumbnail of their Voice cover when the
 * server has none; everyone else caches the server's copy to show in the library.
 */
@SingleIn(AppScope::class)
@Inject
class MurmurCovers(
  context: Context,
  private val bookRepository: BookRepository,
) {

  private val dir = File(context.filesDir, "murmur/covers")

  /** Bumped whenever a cover lands in the cache, so the UI can pick it up. */
  val changes: StateFlow<Int>
    field = MutableStateFlow(0)

  fun file(murmurBookId: String): File? = File(dir, "$murmurBookId.jpg").takeIf { it.exists() }

  suspend fun sync(
    api: MurmurApi,
    books: List<LibraryBook>,
    shared: Map<String, String>,
  ) {
    for (book in books) {
      try {
        if (book.cover && !book.holding && file(book.id) == null) {
          api.cover(book.id)?.let { store(book.id, it) }
        } else if (!book.cover && book.holding) {
          val voiceBookId = shared[book.id] ?: continue
          thumbnail(BookId(voiceBookId))?.let { api.setCover(book.id, it) }
        }
      } catch (e: Exception) {
        Logger.w(e, "Murmur: cover of ${book.title} failed")
      }
    }
  }

  private suspend fun store(
    murmurBookId: String,
    bytes: ByteArray,
  ) {
    withContext(Dispatchers.IO) {
      dir.mkdirs()
      val tmp = File(dir, "$murmurBookId.tmp")
      tmp.writeBytes(bytes)
      tmp.renameTo(File(dir, "$murmurBookId.jpg"))
    }
    changes.value++
  }

  private suspend fun thumbnail(bookId: BookId): ByteArray? {
    val cover = bookRepository.get(bookId)?.content?.cover ?: return null
    return withContext(Dispatchers.IO) {
      if (!cover.exists()) return@withContext null
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeFile(cover.path, bounds)
      var sample = 1
      while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= THUMBNAIL_SIZE) sample *= 2
      val decoded = BitmapFactory.decodeFile(cover.path, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: return@withContext null
      val scale = THUMBNAIL_SIZE.toFloat() / max(decoded.width, decoded.height)
      val bitmap = if (scale < 1) {
        Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true)
      } else {
        decoded
      }
      ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
    }
  }

  private companion object {
    const val THUMBNAIL_SIZE = 400
  }
}

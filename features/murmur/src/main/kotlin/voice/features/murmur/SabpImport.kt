package voice.features.murmur

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import voice.core.data.Book
import voice.core.data.ChapterId
import voice.core.data.repo.BookRepository
import voice.core.documentfile.CachedDocumentFileFactory
import voice.core.logging.api.Logger
import java.time.Instant

/**
 * Imports listening progress from Smart AudioBook Player, which leaves a [SabpState.FILE_NAME] in each book folder it
 * has played. Only books Voice still shows as not started are touched, so running it again is harmless.
 */
@Inject
class SabpImport(
  private val context: Context,
  private val bookRepository: BookRepository,
  private val documentFileFactory: CachedDocumentFileFactory,
) {

  data class Result(
    val finished: Int,
    val inProgress: Int,
  )

  suspend fun run(): Result = withContext(Dispatchers.IO) {
    var finished = 0
    var inProgress = 0
    bookRepository.all()
      .filter { it.position == 0L }
      .forEach { book ->
        val state = try {
          stateFor(book)
        } catch (e: Exception) {
          Logger.w(e, "Murmur: can't read Smart AudioBook Player state for ${book.content.name}")
          null
        } ?: return@forEach
        when {
          state.finished -> {
            val last = book.chapters.last()
            apply(book, state, last.id, last.duration)
            finished++
          }
          state.started -> {
            val chapter = book.chapters.firstOrNull { state.fileName != null && it.id.value.toUri().relativePathEndsWith(state.fileName) }
              ?: return@forEach
            // A position of 0 would leave the book under "not started".
            apply(book, state, chapter.id, state.positionInFileMs.coerceIn(1, chapter.duration.coerceAtLeast(1)))
            inProgress++
          }
        }
      }
    Logger.d("Murmur: imported Smart AudioBook Player progress: $finished finished, $inProgress in progress")
    Result(finished = finished, inProgress = inProgress)
  }

  private suspend fun apply(
    book: Book,
    state: SabpState,
    chapter: ChapterId,
    positionInChapter: Long,
  ) {
    bookRepository.updateBook(book.id) { content ->
      content.copy(
        currentChapter = chapter,
        positionInChapter = positionInChapter,
        playbackSpeed = state.playbackSpeed,
        lastPlayedAt = if (state.lastPlayedAtMs > 0) Instant.ofEpochMilli(state.lastPlayedAtMs) else content.lastPlayedAt,
      )
    }
  }

  /** Books are folders, except a lone audio file, whose state lives next to it and must name it. */
  private fun stateFor(book: Book): SabpState? {
    val root = documentFileFactory.create(book.id.toUri())
    val folder = if (root.isDirectory) root else documentFileFactory.create(root.uri.parentInTree() ?: return null)
    val file = folder.children.firstOrNull { it.isFile && it.name == SabpState.FILE_NAME } ?: return null
    val state = context.contentResolver.openInputStream(file.uri)?.use(SabpState::parse) ?: return null
    if (!root.isDirectory && state.fileName != root.name) return null
    return state
  }

  private fun Uri.parentInTree(): Uri? {
    if (!DocumentsContract.isDocumentUri(context, this)) return null
    val documentId = DocumentsContract.getDocumentId(this)
    if (!documentId.contains('/')) return null
    return try {
      DocumentsContract.buildDocumentUriUsingTree(this, documentId.substringBeforeLast('/'))
    } catch (_: IllegalArgumentException) {
      null // Not part of a tree grant (a single picked file), so its folder isn't readable.
    }
  }

  private fun Uri.relativePathEndsWith(path: String): Boolean {
    val documentId = try {
      DocumentsContract.getDocumentId(this)
    } catch (_: IllegalArgumentException) {
      lastPathSegment ?: return false
    }
    return documentId == path || documentId.endsWith("/$path") || documentId.endsWith(":$path")
  }
}

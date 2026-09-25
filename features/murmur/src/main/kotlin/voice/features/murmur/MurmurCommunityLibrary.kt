package voice.features.murmur

import android.content.Context
import android.text.format.Formatter
import android.widget.Toast
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import voice.core.data.BookId
import voice.core.data.community.CommunityBook
import voice.core.data.community.CommunityLibrary
import voice.core.data.community.CommunityLibraryState
import voice.core.logging.api.Logger

/** Shows the Murmur library in Voice's book overview, below the local categories. */
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class)
@Inject
class MurmurCommunityLibrary(
  private val context: Context,
  private val repository: MurmurRepository,
  private val covers: MurmurCovers,
) : CommunityLibrary {

  private val scope = MainScope()

  override val state: Flow<CommunityLibraryState?> =
    combine(repository.settings, repository.library, covers.changes) { settings, library, _ ->
      if (settings.connection == null) return@combine null
      CommunityLibraryState(
        name = "Murmur Community",
        books = library
          .filter { !it.holding && it.id !in settings.shared && (it.wanting || it.holders.isNotEmpty()) }
          .map { it.toCommunityBook(settings) },
        shared = settings.shared.values.mapTo(mutableSetOf(), ::BookId),
      )
    }

  override fun refresh() = run(quiet = true) { repository.refreshLibrary() }

  override fun share(bookIds: Set<BookId>) = run {
    val shared = repository.settings.first().shared.values
    val toShare = bookIds.filter { it.value !in shared }
    if (toShare.isEmpty()) return@run
    toast(if (toShare.size == 1) "Preparing the book for sharing…" else "Preparing ${toShare.size} books for sharing…")
    // One at a time: each book is read and hashed in full. A failure doesn't stop the rest.
    val failures = toShare.mapNotNull { bookId ->
      try {
        repository.share(bookId)
        null
      } catch (e: Exception) {
        Logger.w(e, "Murmur: sharing $bookId failed")
        e
      }
    }
    when {
      failures.isEmpty() -> Unit
      toShare.size == 1 -> throw failures.single()
      else -> toast("Murmur: ${failures.size} of ${toShare.size} books couldn't be shared")
    }
  }

  override fun stopSharing(bookId: BookId) = run { repository.stopSharing(bookId) }

  override fun request(communityBookId: String) = run { repository.request(communityBookId) }

  override fun cancelRequest(communityBookId: String) = run { repository.cancelRequest(communityBookId) }

  private fun LibraryBook.toCommunityBook(settings: MurmurSettings) = CommunityBook(
    id = id,
    title = title,
    author = author.ifBlank { null },
    cover = covers.file(id)?.toURI()?.toString(),
    status = when {
      !wanting -> "${Formatter.formatShortFileSize(context, size)} · shared by ${holders.joinToString()}"
      id in settings.downloading -> "Downloading…"
      settings.downloadFolder == null -> "Requested · choose a download folder in Murmur settings"
      holders.isEmpty() -> "Requested · nobody has it right now"
      else -> "Requested from ${holders.joinToString()}"
    },
    requested = wanting,
  )

  private fun run(
    quiet: Boolean = false,
    action: suspend () -> Unit,
  ) {
    scope.launch {
      try {
        action()
      } catch (e: Exception) {
        Logger.w(e, "Murmur: community library action failed")
        if (!quiet) toast("Murmur: ${(e as? MurmurException)?.code ?: e.message ?: e}")
      }
    }
  }

  private fun toast(text: String) {
    Toast.makeText(context, text, Toast.LENGTH_LONG).show()
  }
}

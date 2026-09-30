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
  private val torrents: MurmurTorrents,
) : CommunityLibrary {

  private val scope = MainScope()

  override val state: Flow<CommunityLibraryState?> =
    combine(repository.settings, repository.library, covers.changes, torrents.downloads) { settings, library, _, downloads ->
      if (settings.connection == null) return@combine null
      CommunityLibraryState(
        name = "Murmur Community",
        books = library
          .filter { !it.holding && it.id !in settings.shared && (it.wanting || it.cached || it.holders.isNotEmpty() || it.source) }
          .map { it.toCommunityBook(settings, downloads[it.id]) },
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

  private fun LibraryBook.toCommunityBook(
    settings: MurmurSettings,
    downloaded: Float?,
  ) = CommunityBook(
    id = id,
    title = title,
    author = author.ifBlank { null },
    cover = covers.file(id)?.toURI()?.toString(),
    status = when {
      !wanting -> "${Formatter.formatShortFileSize(context, size)} · ${availability()}"
      downloaded != null -> "Downloading… ${(downloaded * 100).toInt()}%"
      settings.downloadFolder == null -> "Requested · choose a download folder in Murmur settings"
      !StorageAccess.granted(context) -> "Requested · allow file access in Murmur settings"
      cached -> "Requested · ready on the server"
      holders.isNotEmpty() -> "Requested from ${holders.joinToString()}"
      source -> "Requested · from its public torrent"
      else -> "Requested · nobody has it right now"
    },
    requested = wanting,
  )

  private fun LibraryBook.availability() = when {
    cached && holders.isEmpty() -> "on the server"
    cached -> "on the server, shared by ${holders.joinToString()}"
    holders.isNotEmpty() -> "shared by ${holders.joinToString()}"
    else -> "from its public torrent"
  }

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

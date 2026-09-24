package voice.features.murmur

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import voice.core.data.Book
import voice.core.data.repo.BookRepository
import voice.core.logging.api.Logger
import voice.navigation.Navigator

data class MurmurViewState(
  val connection: MurmurSettings.Connection?,
  val settings: MurmurSettings,
  val library: List<LibraryBook>,
  val shareableBooks: List<Book>,
  val busy: Boolean,
  val error: String?,
  val message: String?,
  val showSharePicker: Boolean,
)

@Inject
class MurmurViewModel(
  private val repository: MurmurRepository,
  private val bookRepository: BookRepository,
  private val navigator: Navigator,
  private val sabpImport: SabpImport,
) {

  private val scope = MainScope()
  private var library by mutableStateOf<List<LibraryBook>>(emptyList())
  private var busy by mutableStateOf(false)
  private var error by mutableStateOf<String?>(null)
  private var message by mutableStateOf<String?>(null)
  private var showSharePicker by mutableStateOf(false)

  @Composable
  fun viewState(): MurmurViewState {
    val settings by remember { repository.settings }.collectAsState(initial = MurmurSettings())
    val books by remember { bookRepository.flow() }.collectAsState(initial = emptyList())
    val sharedVoiceIds = settings.shared.values.toSet()
    return MurmurViewState(
      connection = settings.connection,
      settings = settings,
      library = library,
      shareableBooks = books.filter { it.content.isActive && it.id.value !in sharedVoiceIds },
      busy = busy,
      error = error,
      message = message,
      showSharePicker = showSharePicker,
    )
  }

  fun close() = navigator.goBack()

  fun refresh() = run {
    library = repository.library()
    repository.syncNow()
  }

  fun join(
    serverUrl: String,
    username: String,
  ) = run {
    repository.join(serverUrl, username)
    library = repository.library()
  }

  fun leave() = run {
    repository.leave()
    library = emptyList()
  }

  fun openSharePicker() {
    showSharePicker = true
  }

  fun dismissSharePicker() {
    showSharePicker = false
  }

  fun share(book: Book) = run {
    showSharePicker = false
    repository.share(book)
    library = repository.library()
  }

  fun stopSharing(book: LibraryBook) = run {
    repository.stopSharing(book.id)
    library = repository.library()
  }

  fun request(book: LibraryBook) = run {
    repository.request(book.id)
    library = repository.library()
  }

  fun cancelRequest(book: LibraryBook) = run {
    repository.cancelRequest(book.id)
    library = repository.library()
  }

  fun setDownloadFolder(uri: Uri) = run { repository.setDownloadFolder(uri) }

  fun setAllowMetered(allow: Boolean) = run { repository.setAllowMetered(allow) }

  fun setKeepSharing(keep: Boolean) = run { repository.setKeepSharing(keep) }

  fun importSabp() = run {
    val result = sabpImport.run()
    message = if (result.finished + result.inProgress == 0) {
      "No Smart AudioBook Player progress found for books Voice shows as not started."
    } else {
      "Imported ${result.finished} finished and ${result.inProgress} in-progress books from Smart AudioBook Player."
    }
  }

  fun dismissError() {
    error = null
  }

  fun dismissMessage() {
    message = null
  }

  private fun run(action: suspend () -> Unit) {
    scope.launch {
      busy = true
      error = null
      try {
        action()
      } catch (e: MurmurException) {
        error = e.code?.let(::describe) ?: e.message
      } catch (e: Exception) {
        Logger.w(e, "Murmur action failed")
        error = e.message ?: e.toString()
      } finally {
        busy = false
      }
    }
  }

  private fun describe(code: String): String = when (code) {
    "username_taken" -> "That username is already taken."
    "invalid_username" -> "Usernames are 2–32 letters, digits, '.', '_' or '-'."
    "unauthorized" -> "The server doesn't recognise this device any more. Leave and join again."
    "already_holding" -> "You already have this book."
    else -> "Server error: $code"
  }
}

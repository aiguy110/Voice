package voice.features.murmur

import android.net.Uri
import androidx.datastore.core.DataStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import voice.core.data.Book
import voice.core.data.folders.AudiobookFolders
import voice.core.data.folders.FolderType

/** Account, library, and sharing operations the UI drives. Transfers live in [MurmurSync]. */
@SingleIn(AppScope::class)
@Inject
class MurmurRepository(
  private val settingsStore: DataStore<MurmurSettings>,
  private val localBookFiles: LocalBookFiles,
  private val audiobookFolders: AudiobookFolders,
  private val scheduler: MurmurScheduler,
) {

  val settings: Flow<MurmurSettings> = settingsStore.data

  suspend fun join(
    serverUrl: String,
    username: String,
  ) {
    val url = serverUrl.trim().trimEnd('/')
    val registered = MurmurApi(url, token = null).register(username.trim())
    settingsStore.updateData {
      it.copy(serverUrl = url, username = registered.username, token = registered.token, shared = emptyMap(), downloading = emptyMap())
    }
  }

  suspend fun leave() {
    settingsStore.updateData {
      it.copy(serverUrl = null, username = null, token = null, shared = emptyMap(), downloading = emptyMap())
    }
  }

  suspend fun library(): List<LibraryBook> = api().library()

  suspend fun share(book: Book) {
    val files = localBookFiles.files(book.id)
    require(files.isNotEmpty()) { "No audio files found for ${book.content.name}" }
    val manifest = localBookFiles.manifest(files)
    val id = api().share(book.content.name, book.content.author, manifest)
    settingsStore.updateData { it.copy(shared = it.shared + (id to book.id.value)) }
  }

  suspend fun stopSharing(murmurBookId: String) {
    api().unshare(murmurBookId)
    settingsStore.updateData { it.copy(shared = it.shared - murmurBookId) }
  }

  suspend fun request(murmurBookId: String) {
    api().want(murmurBookId)
    scheduler.syncNow()
  }

  suspend fun cancelRequest(murmurBookId: String) {
    api().unwant(murmurBookId)
  }

  suspend fun setDownloadFolder(uri: Uri) {
    audiobookFolders.add(uri, FolderType.Root)
    settingsStore.updateData { it.copy(downloadFolder = uri.toString()) }
    scheduler.syncNow()
  }

  suspend fun setAllowMetered(allow: Boolean) {
    settingsStore.updateData { it.copy(allowMetered = allow) }
    if (allow) scheduler.syncNow()
  }

  suspend fun setKeepSharing(keep: Boolean) {
    settingsStore.updateData { it.copy(keepSharing = keep) }
  }

  fun syncNow() = scheduler.syncNow()

  private suspend fun api(): MurmurApi {
    val connection = settingsStore.data.first().connection ?: throw IllegalStateException("Not connected to a Murmur server")
    return MurmurApi(connection.serverUrl, connection.token)
  }
}

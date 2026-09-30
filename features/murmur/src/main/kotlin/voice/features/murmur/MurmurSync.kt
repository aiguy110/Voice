package voice.features.murmur

import androidx.datastore.core.DataStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import voice.core.data.BookId
import voice.core.logging.api.Logger

/**
 * Brings this device in step with the server: refreshes the library, makes sure
 * the server knows the torrent of every book shared from here, and starts
 * [MurmurTorrents] when there's something to download or seed. Safe to call repeatedly.
 */
@SingleIn(AppScope::class)
@Inject
class MurmurSync(
  private val settingsStore: DataStore<MurmurSettings>,
  private val repository: MurmurRepository,
  private val bookTorrents: BookTorrents,
  private val torrents: MurmurTorrents,
  private val scheduler: MurmurScheduler,
) {

  private val mutex = Mutex()

  suspend fun sync(): Unit = mutex.withLock {
    val settings = settingsStore.data.first()
    settings.connection?.let { connection ->
      repository.refreshLibrary()
      announceTorrents(MurmurApi(connection.serverUrl, connection.token), settings)
    }
    if (torrents.hasWork()) scheduler.torrentsNow()
  }

  /**
   * Books shared before the server spoke BitTorrent have no torrent there until a
   * holder sends their pieces; share them again to do that.
   */
  private suspend fun announceTorrents(
    api: MurmurApi,
    settings: MurmurSettings,
  ) {
    settings.shared.forEach { (murmurId, voiceBookId) ->
      if (bookTorrents.get(api, murmurId) != null) return@forEach
      try {
        Logger.i("Murmur: sending the torrent of $murmurId")
        repository.share(BookId(voiceBookId))
      } catch (e: Exception) {
        Logger.w(e, "Murmur: could not send the torrent of $murmurId")
      }
    }
  }
}

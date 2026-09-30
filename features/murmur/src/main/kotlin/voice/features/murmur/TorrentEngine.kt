package voice.features.murmur

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.swig.error_code
import voice.core.logging.api.Logger
import java.io.File
import java.io.IOException

/**
 * The app's one libtorrent session. It runs only while a magnet lookup or an
 * import needs it, so an idle app holds no sockets and uses no battery.
 */
@SingleIn(AppScope::class)
@Inject
class TorrentEngine(private val context: Context) {

  private val mutex = Mutex()
  private var users = 0
  private var running: SessionManager? = null

  /** Runs [block] with a started session, starting one if needed. */
  suspend fun <R> use(block: suspend (SessionManager) -> R): R {
    val session = mutex.withLock {
      users++
      running ?: try {
        withContext(Dispatchers.IO) { start() }.also { running = it }
      } catch (e: UnsatisfiedLinkError) {
        users--
        throw IOException("Torrents aren't supported on this device's processor", e)
      }
    }
    try {
      return block(session)
    } finally {
      mutex.withLock {
        users--
        if (users == 0) {
          running = null
          withContext(Dispatchers.IO) { session.stop() }
        }
      }
    }
  }

  /** The torrent file a magnet link stands for, fetched from its swarm. */
  suspend fun metadata(magnet: String): ByteArray = use { session ->
    withContext(Dispatchers.IO) {
      val dir = File(context.cacheDir, "murmur-magnet").apply { mkdirs() }
      session.fetchMagnet(magnet, METADATA_TIMEOUT_SECONDS, dir)
        ?: throw IOException("Nobody sharing this magnet link answered within $METADATA_TIMEOUT_SECONDS seconds")
    }
  }

  /**
   * Adds a torrent, downloading only [wanted] files into [saveDir]. The magnet's
   * trackers and peer hints (`tr=`, `x.pe=`) are kept, since they found it before.
   */
  fun add(
    session: SessionManager,
    magnet: String,
    torrent: ByteArray,
    wanted: Set<Int>,
    saveDir: File,
  ): TorrentHandle {
    val info = TorrentInfo(torrent)
    val params = AddTorrentParams.parseMagnetUri(magnet)
    params.setTorrentInfo(info)
    params.setSavePath(saveDir.absolutePath)
    params.filePriorities(Array(info.files().numFiles()) { if (it in wanted) Priority.DEFAULT else Priority.IGNORE })
    val error = error_code()
    val handle = session.swig().add_torrent(params.swig(), error)
    if (error.value() != 0) throw IOException("could not add torrent: ${error.message()}")
    return TorrentHandle(handle)
  }

  fun find(
    session: SessionManager,
    infoHash: String,
  ): TorrentHandle? = session.find(Sha1Hash.parseHex(infoHash))?.takeIf { it.isValid }

  private fun start(): SessionManager {
    val settings = SettingsPack()
      .activeDownloads(4)
      .connectionsLimit(100)
    settings.setEnableDht(true)
    return SessionManager(false).apply {
      start(SessionParams(settings))
      Logger.i("Murmur: torrent session started")
    }
  }

  private companion object {
    const val METADATA_TIMEOUT_SECONDS = 120
  }
}

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
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.swig.error_code
import org.libtorrent4j.swig.int_string_map
import voice.core.logging.api.Logger
import java.io.File
import java.io.IOException

/**
 * The app's one libtorrent session. It runs only while a magnet lookup or
 * [MurmurTorrents] needs it, so an idle app holds no sockets and uses no battery.
 * It listens on [PORT], which Murmur members open to each other.
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
   * Adds a torrent with its files under [saveDir], or at [paths] (absolute, by
   * file index) where given. Only [wanted] files are downloaded, if set. A
   * [magnet]'s trackers and peer hints (`tr=`, `x.pe=`) are kept, since they
   * found it before. [seed] trusts the files to be complete and checks each
   * piece only as it's uploaded.
   */
  fun add(
    session: SessionManager,
    torrent: ByteArray,
    saveDir: File,
    magnet: String? = null,
    wanted: Set<Int>? = null,
    paths: Map<Int, String> = emptyMap(),
    seed: Boolean = false,
  ): TorrentHandle {
    val info = TorrentInfo(torrent)
    val params = magnet?.let(AddTorrentParams::parseMagnetUri) ?: AddTorrentParams()
    params.setTorrentInfo(info)
    params.setSavePath(saveDir.absolutePath)
    if (wanted != null) {
      params.filePriorities(Array(info.files().numFiles()) { if (it in wanted) Priority.DEFAULT else Priority.IGNORE })
    }
    if (paths.isNotEmpty()) {
      val renamed = int_string_map()
      paths.forEach { (index, path) -> renamed[index] = path }
      params.swig().set_renamed_files(renamed)
    }
    if (seed) params.setFlags(params.getFlags().or_(TorrentFlags.SEED_MODE))
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
      .listenInterfaces("0.0.0.0:$PORT,[::]:$PORT")
      .activeDownloads(4)
      .activeSeeds(1000)
      .activeLimit(1000)
      .connectionsLimit(200)
    settings.setEnableDht(true)
    return SessionManager(false).apply {
      start(SessionParams(settings))
      Logger.i("Murmur: torrent session started")
    }
  }

  companion object {
    /** The BitTorrent port, TCP and UDP. */
    const val PORT = 42070
    private const val METADATA_TIMEOUT_SECONDS = 120
  }
}

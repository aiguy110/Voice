package voice.features.murmur

import android.content.Context
import android.util.AtomicFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.AlertListener
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.Vectors
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.SaveResumeDataAlert
import org.libtorrent4j.swig.error_code
import org.libtorrent4j.swig.int_string_map
import org.libtorrent4j.swig.libtorrent
import voice.core.logging.api.Logger
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The app's one libtorrent session. It runs only while a magnet lookup or
 * [MurmurTorrents] needs it, so an idle app holds no sockets and uses no battery.
 * It listens on [PORT], which Murmur members open to each other.
 *
 * Downloads keep libtorrent resume data under `filesDir/murmur-resume`, so a
 * restarted session (the system stops background work often) picks up where it
 * left off instead of re-hashing every file already on disk.
 */
@SingleIn(AppScope::class)
@Inject
class TorrentEngine(private val context: Context) {

  private val mutex = Mutex()
  private var users = 0
  private var running: SessionManager? = null

  private val resumeDir = File(context.filesDir, "murmur-resume")

  /** Downloads in this session whose progress is saved, by info hash. */
  private val resumable = ConcurrentHashMap<String, TorrentHandle>()
  private val pendingSaves = AtomicInteger()

  private val resumeListener = object : AlertListener {
    override fun types() = intArrayOf(AlertType.SAVE_RESUME_DATA.swig(), AlertType.SAVE_RESUME_DATA_FAILED.swig())

    override fun alert(alert: Alert<*>) {
      try {
        if (alert is SaveResumeDataAlert) {
          val params = alert.params()
          val hash = params.infoHashes.v1.toHex()
          if (resumable.containsKey(hash)) writeResume(hash, AddTorrentParams.writeResumeDataBuf(params))
        }
      } catch (e: Exception) {
        Logger.w(e, "Murmur: could not save transfer progress")
      } finally {
        pendingSaves.decrementAndGet()
      }
    }
  }

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
      // Also when cancelled (the system stopping the worker): the session must stop and its progress be saved.
      withContext(NonCancellable) { release(session) }
    }
  }

  private suspend fun release(session: SessionManager) = mutex.withLock {
    users--
    if (users == 0) {
      running = null
      saveResume()
      withTimeoutOrNull(RESUME_FLUSH_MILLIS) { while (pendingSaves.get() > 0) delay(50) }
      resumable.clear()
      pendingSaves.set(0)
      withContext(Dispatchers.IO) { session.stop() }
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
   * piece only as it's uploaded; otherwise saved progress is resumed.
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
    val hash = info.infoHash().toHex()
    val fromMagnet = magnet?.let(AddTorrentParams::parseMagnetUri)
    val resumed = if (seed) null else readResume(hash)
    if (resumed != null && fromMagnet != null) resumed.addSwarm(fromMagnet)
    val params = resumed ?: fromMagnet ?: AddTorrentParams()
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
    return TorrentHandle(handle).also { if (!seed) resumable[hash] = it }
  }

  /** Asks libtorrent for the progress of every download that changed; it's written as the answers come in. */
  fun saveResume() {
    resumable.values.forEach { handle ->
      if (handle.isValid && handle.needSaveResumeData()) {
        pendingSaves.incrementAndGet()
        handle.saveResumeData()
      }
    }
  }

  /** Removes a torrent from [session], saving its progress first if it's a download. */
  fun remove(
    session: SessionManager,
    infoHash: String,
    handle: TorrentHandle,
  ) {
    if (!handle.isValid) return
    if (resumable.containsKey(infoHash) && handle.needSaveResumeData()) {
      pendingSaves.incrementAndGet()
      handle.saveResumeData()
    }
    session.remove(handle)
  }

  /** Drops a download's saved progress, once it's finished or cancelled. */
  fun forget(infoHash: String) {
    resumable.remove(infoHash)
    resumeFile(infoHash).delete()
  }

  private fun resumeFile(hash: String) = File(resumeDir, "$hash.resume")

  private fun readResume(hash: String): AddTorrentParams? {
    val file = resumeFile(hash)
    if (!file.exists()) return null
    val error = error_code()
    val params = libtorrent.read_resume_data_ex(Vectors.bytes2byte_vector(file.readBytes()), error)
    if (error.value() != 0) {
      Logger.w("Murmur: discarding unreadable progress for $hash: ${error.message()}")
      file.delete()
      return null
    }
    Logger.i("Murmur: resuming $hash from saved progress")
    return AddTorrentParams(params)
  }

  /** Resume data keeps only the peers connected when it was saved; a magnet's trackers and peer hints still help. */
  private fun AddTorrentParams.addSwarm(magnet: AddTorrentParams) {
    val trackers = this.trackers
    val missing = magnet.trackers.filterNot { it in trackers }
    if (missing.isNotEmpty()) {
      val tiers = trackerTiers
      this.trackers = trackers + missing
      if (tiers.size == trackers.size) trackerTiers = tiers + missing.map { 0 }
    }
    peers(peers() + magnet.peers())
  }

  private fun writeResume(
    hash: String,
    bytes: ByteArray,
  ) {
    resumeDir.mkdirs()
    val file = AtomicFile(resumeFile(hash))
    val out = file.startWrite()
    try {
      out.write(bytes)
      file.finishWrite(out)
    } catch (e: IOException) {
      file.failWrite(out)
      throw e
    }
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
      addListener(resumeListener)
      start(SessionParams(settings))
      Logger.i("Murmur: torrent session started")
    }
  }

  companion object {
    /** The BitTorrent port, TCP and UDP. */
    const val PORT = 42070
    private const val METADATA_TIMEOUT_SECONDS = 120
    private const val RESUME_FLUSH_MILLIS = 5_000L
  }
}

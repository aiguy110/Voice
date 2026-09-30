package voice.features.murmur

import android.content.Context
import androidx.datastore.core.DataStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.libtorrent4j.SessionManager
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import voice.core.data.layout.DetectedBook
import voice.core.data.layout.detectBooks
import voice.core.data.layout.layoutTree
import voice.core.logging.api.Logger
import voice.core.scanner.MediaScanTrigger
import java.io.File
import java.io.IOException

/** What a magnet link holds, and the books found in it. */
class MagnetPlan(
  val infoHash: String,
  val name: String,
  val magnet: String,
  val torrent: ByteArray,
  val books: List<DetectedBook<Int>>,
)

/** The add-from-magnet-link dialog: typing a link, waiting for its file list, then choosing books. */
data class MagnetState(
  val lookingUp: Boolean = false,
  val error: String? = null,
  val plan: MagnetPlan? = null,
  /** Indices into the plan's books. */
  val selected: Set<Int> = emptySet(),
)

/**
 * Adding books to the library from magnet links: look up, choose books, download into the download folder.
 * Everything happens on the phone; the Murmur server hears about an imported book only if the user shares it later.
 * [MurmurTorrents] drives the downloads through [advance]; finished books keep seeding their public torrent from
 * there when [MurmurTorrents] seeds.
 */
@SingleIn(AppScope::class)
@Inject
class MagnetImports(
  private val context: Context,
  private val settingsStore: DataStore<MurmurSettings>,
  private val engine: TorrentEngine,
  private val scheduler: MurmurScheduler,
  private val mediaScanTrigger: MediaScanTrigger,
) {

  /** Fraction downloaded per import, while it's downloading. */
  val progress: StateFlow<Map<String, Float>>
    field = MutableStateFlow(emptyMap())

  /**
   * The dialog's state. It lives here rather than in a view model because a link
   * opened from another app restarts the activity while the lookup runs.
   */
  val dialog: StateFlow<MagnetState?>
    field = MutableStateFlow(null)

  private val scope = MainScope()
  private var lookup: Job? = null

  /** Torrents of pending imports in the current session, by info hash. */
  private val handles = mutableMapOf<String, TorrentHandle>()

  fun open() {
    dialog.value = MagnetState()
  }

  fun dismiss() {
    lookup?.cancel()
    dialog.value = null
  }

  /** Fetches the torrent behind [magnet] and finds the books in it, for the dialog to offer. */
  fun lookUp(magnet: String) {
    lookup?.cancel()
    dialog.value = MagnetState(lookingUp = true)
    lookup = scope.launch {
      val state = try {
        val link = magnet.trim()
        require(link.startsWith("magnet:?", ignoreCase = true)) { "That isn't a magnet link." }
        Logger.i("Murmur: looking up $link")
        val plan = plan(link, engine.metadata(link))
        Logger.i("Murmur: ${plan.name} holds ${plan.books.size} books")
        if (plan.books.isEmpty()) {
          MagnetState(
            error = "No audiobooks found in ${plan.name}.",
          )
        } else {
          MagnetState(plan = plan, selected = plan.books.indices.toSet())
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Logger.w(e, "Murmur: magnet lookup failed")
        MagnetState(error = e.message ?: e.toString())
      }
      if (dialog.value != null) dialog.value = state
    }
  }

  fun toggle(index: Int) {
    val state = dialog.value ?: return
    dialog.value = state.copy(selected = if (index in state.selected) state.selected - index else state.selected + index)
  }

  suspend fun startSelected() {
    val state = dialog.value ?: return
    val plan = state.plan ?: return
    start(plan, state.selected.sorted().map(plan.books::get))
    dialog.value = null
  }

  private suspend fun start(
    plan: MagnetPlan,
    books: List<DetectedBook<Int>>,
  ) {
    require(books.isNotEmpty()) { "Choose at least one book." }
    val _ = downloadDir(settingsStore.data.first(), context)
    withContext(Dispatchers.IO) {
      torrentFile(plan.infoHash).apply { parentFile?.mkdirs() }.writeBytes(plan.torrent)
    }
    val info = TorrentInfo(plan.torrent)
    val files = info.files()
    fun importFile(
      index: Int,
      path: String,
      isCover: Boolean = false,
    ) = ImportFile(index, files.filePath(index), path, files.fileSize(index), isCover)

    val import = TorrentImport(
      name = plan.name,
      magnet = plan.magnet,
      books = books.map { book ->
        val audio = book.audio.map { importFile(it.node.value!!, it.path) }
        val cover = book.cover?.let { importFile(it.node.value!!, "cover." + it.path.substringAfterLast('.').lowercase(), isCover = true) }
        ImportBook(title = book.title, author = book.author, files = audio + listOfNotNull(cover))
      },
    )
    settingsStore.updateData { it.copy(imports = it.imports + (plan.infoHash to import)) }
    Logger.i("Murmur: importing ${books.size} books from ${plan.name}")
    scheduler.torrentsNow()
  }

  suspend fun cancel(infoHash: String) {
    settingsStore.updateData { it.copy(imports = it.imports - infoHash) }
    // A running session notices on its next pass and cleans up; otherwise clean up here.
    if (infoHash !in handles) deleteIncoming(infoHash)
  }

  /**
   * One pass over pending imports in [session]: adds new ones, drops cancelled
   * ones, and moves finished books into place. Returns a status line, or null
   * if nothing is pending.
   */
  suspend fun advance(session: SessionManager): String? {
    val imports = settingsStore.data.first().imports
    (handles.keys - imports.keys).forEach { cancelled ->
      handles.remove(cancelled)?.let { session.remove(it) }
      deleteIncoming(cancelled)
      progress.value -= cancelled
    }
    if (imports.isEmpty()) return null
    val folder = downloadDir(settingsStore.data.first(), context)
    imports.forEach { (hash, import) ->
      val handle = handles.getOrPut(hash) {
        engine.find(session, hash)
          ?: engine.add(
            session,
            torrentFile(hash).readBytes(),
            saveDir = stagingDir(hash),
            magnet = import.magnet,
            wanted = import.wantedFiles(),
            paths = import.books.withIndex().flatMap { (i, book) ->
              book.files.map { it.index to File(incoming(folder, hash), "$i/${it.path}").absolutePath }
            }.toMap(),
          )
      }
      val wanted = import.books.flatMap { it.files }
      val done = handle.fileProgress()
      val total = wanted.sumOf { it.size }.coerceAtLeast(1)
      val have = wanted.sumOf { minOf(done[it.index], it.size) }
      progress.value += hash to have.toFloat() / total
      if (wanted.all { done[it.index] >= it.size }) {
        handles.remove(hash)?.let { session.remove(it) }
        finish(folder, hash, import)
        deleteIncoming(hash)
        settingsStore.updateData { it.copy(imports = it.imports - hash) }
        progress.value -= hash
      }
    }
    val current = progress.value
    return settingsStore.data.first().imports.entries.joinToString(" · ") { (hash, import) ->
      "${import.name}: ${((current[hash] ?: 0F) * 100).toInt()}%"
    }.ifEmpty { null }
  }

  /** Forgets the session's torrents once it stops. */
  fun sessionEnded() {
    handles.clear()
    progress.value = emptyMap()
  }

  /** Moves each finished book into the download folder, remembers where it came from, and scans it into Voice's library. */
  private suspend fun finish(
    folder: File,
    hash: String,
    import: TorrentImport,
  ) {
    import.books.forEachIndexed { index, book ->
      if (book.done) return@forEachIndexed
      val target = withContext(Dispatchers.IO) {
        val target = File(folder, uniqueName(folder, safeName(book.title)))
        if (!File(incoming(folder, hash), "$index").renameTo(target)) throw IOException("could not move ${book.title} into place")
        target
      }
      val imported = ImportedBook(hash, import.magnet, book.files.associate { it.index to it.path })
      settingsStore.updateData { settings ->
        val current = settings.imports[hash] ?: return@updateData settings
        val books = current.books.toMutableList().also { it[index] = book.copy(target = target.absolutePath, done = true) }
        settings.copy(
          imports = settings.imports + (hash to current.copy(books = books)),
          imported = settings.imported + (target.absolutePath to imported),
        )
      }
      Logger.i("Murmur: imported ${book.title} from ${import.name}")
    }
    mediaScanTrigger.scan()
  }

  private fun TorrentImport.wantedFiles() = books.flatMap { book -> book.files.map { it.index } }.toSet()

  /** Where libtorrent keeps partial pieces of files nobody wants. */
  private fun stagingDir(hash: String) = File(context.getExternalFilesDir(null) ?: context.filesDir, "murmur-torrents/$hash")

  /** The torrent's metadata, kept after the import so its books can seed the public swarm. */
  fun torrentFile(hash: String) = File(context.filesDir, "murmur/torrents/$hash.torrent")

  private suspend fun deleteIncoming(hash: String) {
    withContext(Dispatchers.IO) {
      stagingDir(hash).deleteRecursively()
      val _ = runCatching { incoming(downloadDir(settingsStore.data.first(), context), hash).deleteRecursively() }
      if (settingsStore.data.first().imported.values.none { it.infoHash == hash }) torrentFile(hash).delete()
    }
  }
}

/** Where books are downloaded before they're verified and moved into [folder]. Voice's scanner skips dot folders. */
internal fun incoming(
  folder: File,
  name: String,
) = File(folder, ".murmur/$name")

/** The download folder as a path. Needs file access. */
internal fun downloadDir(
  settings: MurmurSettings,
  context: Context,
): File {
  val uri = checkNotNull(settings.downloadFolder) { "Choose a download folder first." }
  check(StorageAccess.granted(context)) { "Allow file access in Murmur settings first." }
  return checkNotNull(StorageAccess.file(uri)) { "The download folder must be on this device's storage." }
}

/** A name in [folder] that doesn't collide with an existing one; "(2)" goes before a file's [extension]. */
internal fun uniqueName(
  folder: File,
  name: String,
  extension: Boolean = false,
): String {
  val dot = name.lastIndexOf('.').takeIf { extension && it > 0 } ?: name.length
  val stem = name.substring(0, dot)
  val suffix = name.substring(dot)
  return generateSequence(1) { it + 1 }
    .map { if (it == 1) name else "$stem ($it)$suffix" }
    .first { !File(folder, it).exists() }
}

/** The books in a torrent, from its file list alone. */
internal fun plan(
  magnet: String,
  torrent: ByteArray,
): MagnetPlan {
  val info = TorrentInfo(torrent)
  val files = info.files()
  val entries = (0 until files.numFiles())
    .filterNot { files.padFileAt(it) }
    .map { Triple(files.filePath(it).replace('\\', '/'), files.fileSize(it), it) }
  return MagnetPlan(
    infoHash = info.infoHash().toHex(),
    name = info.name(),
    magnet = magnet,
    torrent = torrent,
    books = detectBooks(layoutTree(entries)),
  )
}

package voice.features.murmur

import android.content.Context
import androidx.core.net.toUri
import androidx.datastore.core.DataStore
import androidx.documentfile.provider.DocumentFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
 * Adding books to the library from magnet links: look up, choose books, download, copy into the download folder.
 * Everything happens on the phone; the Murmur server hears about an imported book only if the user shares it later.
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

  /** Fraction downloaded per import, while [run] is downloading it. */
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
    val settings = settingsStore.data.first()
    checkNotNull(settings.downloadFolder) { "Choose a download folder first." }
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
    scheduler.importNow()
  }

  suspend fun cancel(infoHash: String) {
    settingsStore.updateData { it.copy(imports = it.imports - infoHash) }
    // A running import notices on its next pass and cleans up; otherwise clean up here.
    if (progress.value[infoHash] == null) deleteStaging(infoHash)
  }

  /** Downloads and copies every pending import. Returns once none are left. */
  suspend fun run(onProgress: suspend (String) -> Unit) {
    if (settingsStore.data.first().imports.isEmpty()) return
    engine.use { session ->
      val handles = mutableMapOf<String, TorrentHandle>()
      try {
        while (true) {
          val imports = settingsStore.data.first().imports
          (handles.keys - imports.keys).forEach { cancelled ->
            handles.remove(cancelled)?.let { session.remove(it) }
            deleteStaging(cancelled)
          }
          if (imports.isEmpty()) break

          imports.forEach { (hash, import) ->
            val handle = handles.getOrPut(hash) {
              engine.find(session, hash)
                ?: engine.add(session, import.magnet, torrentFile(hash).readBytes(), import.wantedFiles(), stagingDir(hash))
            }
            val wanted = import.books.flatMap { it.files }
            val done = handle.fileProgress()
            val total = wanted.sumOf { it.size }.coerceAtLeast(1)
            val have = wanted.sumOf { minOf(done[it.index], it.size) }
            progress.value += hash to have.toFloat() / total
            if (wanted.all { done[it.index] >= it.size }) {
              finish(hash, import)
              handles.remove(hash)?.let { session.remove(it) }
              deleteStaging(hash)
              settingsStore.updateData { it.copy(imports = it.imports - hash) }
              progress.value -= hash
            }
          }
          val current = progress.value
          val line = settingsStore.data.first().imports.entries.joinToString(" · ") { (hash, import) ->
            "${import.name}: ${((current[hash] ?: 0F) * 100).toInt()}%"
          }
          if (line.isNotEmpty()) onProgress(line)
          delay(POLL_MILLIS)
        }
      } finally {
        progress.value = emptyMap()
      }
    }
  }

  /** Copies each finished book into the download folder and scans it into Voice's library. */
  private suspend fun finish(
    hash: String,
    import: TorrentImport,
  ) {
    val settings = settingsStore.data.first()
    val folder = settings.downloadFolder?.let { DocumentFile.fromTreeUri(context, it.toUri()) }
      ?: throw IOException("no download folder to import ${import.name} into")
    val staging = stagingDir(hash)
    import.books.forEachIndexed { index, book ->
      if (book.done) return@forEachIndexed
      val target = book.target?.let { DocumentFile.fromTreeUri(context, it.toUri()) }?.takeIf { it.exists() }
        ?: withContext(Dispatchers.IO) { folder.createDirectory(uniqueName(folder, safeName(book.title))) }
        ?: throw IOException("could not create a folder for ${book.title}")
      updateBook(hash, index) { it.copy(target = target.uri.toString()) }
      withContext(Dispatchers.IO) {
        book.files.forEach { file ->
          val output = context.contentResolver.openOutputStream(target.resolve(file.path).uri, "wt")
            ?: throw IOException("cannot write ${file.path}")
          output.use { File(staging, file.source).inputStream().use { input -> input.copyTo(it) } }
        }
      }
      updateBook(hash, index) { it.copy(done = true) }
      Logger.i("Murmur: imported ${book.title} from ${import.name}")
    }
    mediaScanTrigger.scan()
  }

  private suspend fun updateBook(
    hash: String,
    index: Int,
    update: (ImportBook) -> ImportBook,
  ) {
    settingsStore.updateData { settings ->
      val import = settings.imports[hash] ?: return@updateData settings
      val books = import.books.toMutableList().also { it[index] = update(it[index]) }
      settings.copy(imports = settings.imports + (hash to import.copy(books = books)))
    }
  }

  private fun TorrentImport.wantedFiles() = books.flatMap { book -> book.files.map { it.index } }.toSet()

  private fun stagingDir(hash: String) = File(context.getExternalFilesDir(null) ?: context.filesDir, "murmur-torrents/$hash")

  private fun torrentFile(hash: String) = File(context.filesDir, "murmur/torrents/$hash.torrent")

  private suspend fun deleteStaging(hash: String) {
    withContext(Dispatchers.IO) {
      stagingDir(hash).deleteRecursively()
      torrentFile(hash).delete()
    }
  }

  private fun uniqueName(
    folder: DocumentFile,
    name: String,
  ): String = generateSequence(1) { it + 1 }
    .map { if (it == 1) name else "$name ($it)" }
    .first { folder.findFile(it) == null }

  private companion object {
    const val POLL_MILLIS = 2_000L
  }
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

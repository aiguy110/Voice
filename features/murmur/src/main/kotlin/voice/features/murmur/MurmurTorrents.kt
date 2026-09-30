package voice.features.murmur

import android.content.Context
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.provider.DocumentsContract
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.datastore.core.DataStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.libtorrent4j.SessionManager
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import voice.core.data.BookId
import voice.core.logging.api.Logger
import voice.core.scanner.MediaScanTrigger
import java.io.File
import java.io.IOException

/**
 * Moves books over BitTorrent: downloads requested books (from the community,
 * or from the public torrent a book came from when nobody else has it), seeds
 * books the community is waiting for, and, on Wi-Fi (while charging, unless the
 * user widened that), seeds every shared book and every imported book's public
 * torrent. Also drives [MagnetImports]. See docs/protocol.md.
 */
@SingleIn(AppScope::class)
@Inject
class MurmurTorrents(
  private val context: Context,
  private val settingsStore: DataStore<MurmurSettings>,
  private val engine: TorrentEngine,
  private val localBookFiles: LocalBookFiles,
  private val magnetImports: MagnetImports,
  private val mediaScanTrigger: MediaScanTrigger,
  private val bookTorrents: BookTorrents,
) {

  /** Murmur book id -> fraction downloaded, for books downloading right now. */
  val downloads: StateFlow<Map<String, Float>>
    field = MutableStateFlow(emptyMap())

  /** Whether [run] would have anything to do right now. */
  suspend fun hasWork(): Boolean {
    val settings = settingsStore.data.first()
    if (settings.imports.isNotEmpty()) return true
    if (seedEverything(settings) && (settings.shared.isNotEmpty() || settings.imported.isNotEmpty())) return true
    val api = settings.connection?.let { MurmurApi(it.serverUrl, it.token) } ?: return false
    return api.library().any { it.wanting } || api.requests().any { it.id in settings.shared }
  }

  /** Runs the session until there's nothing left to do; [onStatus] describes progress. */
  suspend fun run(onStatus: suspend (String) -> Unit) {
    engine.use { session ->
      val running = mutableMapOf<String, TorrentHandle>()
      val announced = mutableSetOf<String>()
      var plan = Plan()
      var plannedAt = 0L
      try {
        while (true) {
          if (System.currentTimeMillis() - plannedAt > PLAN_MILLIS) {
            val next = plan()
            if (next.torrents.keys != plan.torrents.keys) {
              Logger.i("Murmur: downloading ${next.downloads.map { it.book.title }}, in session ${next.torrents.values.map { it.name }}")
            }
            plan = next
            plannedAt = System.currentTimeMillis()
          }
          (running.keys - plan.torrents.keys).forEach { hash -> running.remove(hash)?.let { session.remove(it) } }
          // Adding a torrent that another part of the session is still removing (a magnet import that just
          // finished) returns the dying handle; add it again once it's gone.
          running.values.removeAll { !it.isValid }
          plan.torrents.forEach { (hash, torrent) ->
            if (hash !in running) {
              running[hash] = try {
                torrent.add(session)
              } catch (e: IOException) {
                Logger.w(e, "Murmur: could not add ${torrent.name}")
                return@forEach
              }
            }
          }
          // The server just started wanting these from us: tell the tracker now rather than at the next interval.
          announced.retainAll(plan.requested)
          (plan.requested - announced).forEach { hash ->
            running[hash]?.let {
              it.forceReannounce()
              announced += hash
            }
          }
          val finished = plan.downloads.filter { running[it.infoHash]?.status()?.isFinished == true }
          finished.forEach { download ->
            running.remove(download.infoHash)?.let { session.remove(it) }
            try {
              finish(download)
            } catch (e: IOException) {
              Logger.w(e, "Murmur: finishing ${download.book.title} failed")
            }
          }
          if (finished.isNotEmpty()) plannedAt = 0
          downloads.value = plan.downloads.associate { it.book.id to (running[it.infoHash]?.status()?.progress() ?: 0F) }

          val imports = try {
            magnetImports.advance(session)
          } catch (e: IOException) {
            Logger.w(e, "Murmur: magnet import failed")
            null
          } catch (e: IllegalStateException) {
            Logger.w(e, "Murmur: magnet import can't run")
            null
          }
          if (plan.torrents.isEmpty() && imports == null) break
          onStatus(status(plan, imports))
          delay(POLL_MILLIS)
        }
      } finally {
        downloads.value = emptyMap()
        magnetImports.sessionEnded()
      }
    }
  }

  private fun status(
    plan: Plan,
    imports: String?,
  ): String = listOfNotNull(
    plan.downloads.takeIf { it.isNotEmpty() }?.joinToString(" · ") {
      "${it.book.title}: ${((downloads.value[it.book.id] ?: 0F) * 100).toInt()}%"
    },
    imports,
    (plan.torrents.size - plan.downloads.size).takeIf { it > 0 }?.let { "seeding $it" },
  ).joinToString(" · ")

  /** What should be in the session: requested downloads and books to seed. */
  private suspend fun plan(): Plan {
    val settings = settingsStore.data.first()
    val torrents = mutableMapOf<String, Torrent>()
    val downloads = mutableListOf<Download>()
    val requestedHashes = mutableSetOf<String>()
    val everything = seedEverything(settings)
    val connection = settings.connection
    if (connection != null) {
      val api = MurmurApi(connection.serverUrl, connection.token)
      try {
        val folder = runCatching { downloadDir(settings, context) }.getOrNull()
        if (folder != null) {
          api.library().filter { it.wanting }.forEach { book ->
            val download = download(api, book, folder) ?: return@forEach
            downloads += download
            torrents[download.infoHash] = download.torrent
          }
        }
        val requests = api.requests()
        requestedHashes += requests.map { it.infoHash }
        (requests.map { it.id } + if (everything) settings.shared.keys else emptyList()).distinct().forEach { id ->
          val seed = seed(api, id, settings.shared[id] ?: return@forEach) ?: return@forEach
          torrents.putIfAbsent(seed.first, seed.second)
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Logger.w(e, "Murmur: could not plan transfers")
      }
    }
    if (everything) {
      settings.imported.forEach { (path, imported) ->
        publicSeed(File(path), imported)?.let { torrents.putIfAbsent(imported.infoHash, it) }
      }
    }
    return Plan(torrents, downloads, requestedHashes)
  }

  /** How to download a requested book: from the community, or the public torrent it came from if nobody else has it. */
  private suspend fun download(
    api: MurmurApi,
    book: LibraryBook,
    folder: File,
  ): Download? {
    val incoming = incoming(folder, "")
    if (book.cached || book.holders.isNotEmpty() || !book.source) {
      val bytes = bookTorrents.get(api, book.id) ?: return null
      val info = TorrentInfo(bytes)
      return Download(book, info.infoHash().toHex(), Torrent(book.title) { engine.add(it, bytes, incoming) }, source = null)
    }
    val source = api.source(book.id) ?: return null
    val bytes = bookTorrents.public(source.magnet) ?: return null
    val info = TorrentInfo(bytes)
    val paths = source.files.associate { it.index to File(incoming, "${book.id}/${it.path}").absolutePath }
    val torrent = Torrent(book.title) {
      engine.add(it, bytes, bookTorrents.partsDir(info.infoHash().toHex()), source.magnet, wanted = paths.keys, paths = paths)
    }
    Logger.i("Murmur: nobody has ${book.title}; downloading it from its public torrent")
    return Download(book, info.infoHash().toHex(), torrent, source)
  }

  /** Seeding a shared book from where Voice has it; null if its files can't be reached by path. */
  private suspend fun seed(
    api: MurmurApi,
    murmurId: String,
    voiceBookId: String,
  ): Pair<String, Torrent>? {
    val bytes = bookTorrents.get(api, murmurId) ?: return null
    val info = TorrentInfo(bytes)
    val local = localBookFiles.files(BookId(voiceBookId)).associateBy { it.path }
    val files = info.files()
    val paths = mutableMapOf<Int, String>()
    for (index in 0 until files.numFiles()) {
      val path = files.filePath(index).replace('\\', '/').substringAfter('/')
      val file = local[path]?.let { StorageAccess.file(it.uri) }
      if (file == null) {
        Logger.w("Murmur: can't seed $murmurId: $path isn't on this device's storage")
        return null
      }
      paths[index] = file.absolutePath
    }
    val hash = info.infoHash().toHex()
    return hash to Torrent(info.name()) { engine.add(it, bytes, bookTorrents.partsDir(hash), paths = paths, seed = true) }
  }

  /** Seeding an imported book's public torrent, with its extra files left out. */
  private fun publicSeed(
    root: File,
    imported: ImportedBook,
  ): Torrent? {
    val bytes = bookTorrents.publicFile(imported.infoHash).takeIf { it.exists() }?.readBytes() ?: return null
    val paths = imported.files.mapValues { File(root, it.value) }.filterValues { it.exists() }.mapValues { it.value.absolutePath }
    if (paths.isEmpty()) return null
    return Torrent(root.name) {
      engine.add(it, bytes, bookTorrents.partsDir(imported.infoHash), imported.magnet, wanted = paths.keys, paths = paths, seed = true)
    }
  }

  /** Checks a finished download against its book id, moves it into place, and shares it or withdraws the request. */
  private suspend fun finish(download: Download) {
    val settings = settingsStore.data.first()
    val folder = downloadDir(settings, context)
    val book = download.book
    val root = File(incoming(folder, ""), book.id)
    val (manifest, pieces) = withContext(Dispatchers.IO) {
      val sizes = root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).invariantSeparatorsPath to it.length() }
      BookTorrent.describe(sizes) { File(root, it).inputStream() }
    }
    if (bookId(manifest) != book.id) {
      withContext(Dispatchers.IO) { root.deleteRecursively() }
      throw IOException("${book.title} doesn't match the book it should be; downloading it again")
    }
    val single = manifest.size == 1 && '/' !in manifest.single().path
    val (from, name) = if (single) File(root, manifest.single().path) to manifest.single().path else root to safeName(book.title)
    val target = File(folder, uniqueName(folder, name, extension = single))
    withContext(Dispatchers.IO) {
      if (!from.renameTo(target)) throw IOException("could not move ${book.title} into place")
      root.deleteRecursively()
    }
    val connection = settings.connection ?: return
    val api = MurmurApi(connection.serverUrl, connection.token)
    val source = download.source
    if (source != null) {
      val imported = ImportedBook(download.infoHash, source.magnet, source.files.associate { it.index to it.path })
      settingsStore.updateData { it.copy(imported = it.imported + (target.absolutePath to imported)) }
    }
    if (settings.keepSharing) {
      val shared = api.share(book.title, book.author, manifest, pieces, source)
      val voiceBookId = voiceBookId(settings.downloadFolder!!, folder, target)
      settingsStore.updateData { it.copy(shared = it.shared + (shared.id to voiceBookId)) }
    } else {
      api.unwant(book.id)
    }
    Logger.i("Murmur: received ${book.title}")
    mediaScanTrigger.scan()
  }

  private fun seedEverything(settings: MurmurSettings): Boolean {
    if (!settings.seedOnWifi || !StorageAccess.granted(context)) return false
    if (context.getSystemService<ConnectivityManager>()?.isActiveNetworkMetered != false) return false
    return !settings.seedOnlyCharging || context.getSystemService<BatteryManager>()?.isCharging == true
  }

  private class Plan(
    /** By info hash. */
    val torrents: Map<String, Torrent> = emptyMap(),
    val downloads: List<Download> = emptyList(),
    /** Info hashes of books the community is waiting for from us. */
    val requested: Set<String> = emptySet(),
  )

  private class Torrent(
    val name: String,
    val add: (SessionManager) -> TorrentHandle,
  )

  private class Download(
    val book: LibraryBook,
    val infoHash: String,
    val torrent: Torrent,
    /** Set when it comes from the public torrent the book was imported from. */
    val source: Source?,
  )

  private companion object {
    const val POLL_MILLIS = 3_000L
    const val PLAN_MILLIS = 60_000L
  }
}

/**
 * The Voice BookId of [target], a file or folder directly inside the download folder
 * at [folderPath], as Voice's scanner will name it: a document uri under the folder's tree.
 */
internal fun voiceBookId(
  treeUri: String,
  folderPath: File,
  target: File,
): String {
  val tree = treeUri.toUri()
  val treeDocument = DocumentsContract.getTreeDocumentId(tree)
  val relative = target.relativeTo(folderPath).invariantSeparatorsPath
  val document = if (treeDocument.endsWith(':')) treeDocument + relative else "$treeDocument/$relative"
  return DocumentsContract.buildDocumentUriUsingTree(tree, document).toString()
}

internal fun safeName(title: String): String = title.replace(Regex("""[/\\:*?"<>|]"""), "_").trim().ifEmpty { "Book" }

/** Book and public .torrent files, fetched once and kept. */
@SingleIn(AppScope::class)
@Inject
class BookTorrents(
  private val context: Context,
  private val engine: TorrentEngine,
  private val magnetImports: MagnetImports,
) {

  /** A book's torrent from the Murmur server; null if the server doesn't know its pieces yet. */
  suspend fun get(
    api: MurmurApi,
    murmurId: String,
  ): ByteArray? {
    val file = File(context.filesDir, "murmur/books/$murmurId.torrent")
    if (file.exists()) return withContext(Dispatchers.IO) { file.readBytes() }
    val bytes = api.torrent(murmurId) ?: return null
    withContext(Dispatchers.IO) { file.apply { parentFile?.mkdirs() }.writeBytes(bytes) }
    return bytes
  }

  /** Forgets book torrents, whose tracker URLs belong to one account. */
  suspend fun clear() {
    withContext(Dispatchers.IO) { File(context.filesDir, "murmur/books").deleteRecursively() }
  }

  /** The torrent behind a public magnet link, looked up from its swarm the first time; null if nobody answered. */
  suspend fun public(magnet: String): ByteArray? {
    val hash = TorrentInfoHash.of(magnet) ?: return null
    val file = publicFile(hash)
    if (file.exists()) return withContext(Dispatchers.IO) { file.readBytes() }
    val bytes = try {
      engine.metadata(magnet)
    } catch (e: IOException) {
      Logger.w(e, "Murmur: public torrent lookup failed")
      return null
    }
    withContext(Dispatchers.IO) { file.apply { parentFile?.mkdirs() }.writeBytes(bytes) }
    return bytes
  }

  fun publicFile(infoHash: String): File = magnetImports.torrentFile(infoHash)

  /** Where libtorrent keeps pieces that belong to no wanted file. */
  fun partsDir(infoHash: String) = File(context.getExternalFilesDir(null) ?: context.filesDir, "murmur-torrents/$infoHash")
}

/** A magnet link's v1 info hash, lowercase hex. */
internal object TorrentInfoHash {
  fun of(magnet: String): String? {
    val value = Regex("""xt=urn:btih:([0-9a-fA-F]{40}|[a-zA-Z2-7]{32})""").find(magnet)?.groupValues?.get(1) ?: return null
    return if (value.length == 40) value.lowercase() else base32ToHex(value.uppercase())
  }

  private fun base32ToHex(value: String): String {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    val bits = value.map { alphabet.indexOf(it).toString(2).padStart(5, '0') }.joinToString("")
    return bits.chunked(8).map { it.toInt(2).toByte() }.toByteArray().toHex()
  }
}

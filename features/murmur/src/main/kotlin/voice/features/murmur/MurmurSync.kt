package voice.features.murmur

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.datastore.core.DataStore
import androidx.documentfile.provider.DocumentFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import voice.core.data.BookId
import voice.core.logging.api.Logger
import voice.core.scanner.MediaScanTrigger
import java.io.IOException
import java.io.InputStream

/**
 * Moves bytes: uploads books the server assigned to this device and downloads
 * books this user requested. Safe to call repeatedly; everything resumes.
 */
@SingleIn(AppScope::class)
@Inject
class MurmurSync(
  private val context: Context,
  private val settingsStore: DataStore<MurmurSettings>,
  private val localBookFiles: LocalBookFiles,
  private val mediaScanTrigger: MediaScanTrigger,
) {

  private val mutex = Mutex()

  suspend fun sync(): Unit = mutex.withLock {
    val settings = settingsStore.data.first()
    val connection = settings.connection ?: return
    if (!settings.allowMetered && context.getSystemService<ConnectivityManager>()?.isActiveNetworkMetered != false) {
      Logger.i("Murmur: on a metered network and mobile data is not allowed; skipping transfers")
      return
    }
    val api = MurmurApi(connection.serverUrl, connection.token)
    api.uploads().forEach { upload ->
      runCatching { upload(api, upload) }.onFailure { Logger.w(it, "Murmur: upload of ${upload.title} failed") }
    }
    api.downloads().forEach { download ->
      runCatching { download(api, download) }.onFailure { Logger.w(it, "Murmur: download of ${download.title} failed") }
    }
  }

  private suspend fun upload(
    api: MurmurApi,
    upload: Upload,
  ) {
    val voiceBookId = settingsStore.data.first().shared[upload.bookId]
    val local = voiceBookId?.let { localBookFiles.files(BookId(it)).associateBy(LocalFile::path) }.orEmpty()
    for (file in upload.files) {
      if (file.offset >= file.size) continue
      val localFile = local[file.path]
      if (localFile == null || localFile.size != file.size) {
        withdraw(api, upload.bookId, "local copy of ${upload.title} no longer matches")
        return
      }
      try {
        uploadFile(api, upload.transferId, file, localFile.uri)
      } catch (e: MurmurException) {
        when (e.code) {
          "checksum_mismatch" -> withdraw(api, upload.bookId, "local copy of ${upload.title} changed")
          "not_uploader", "transfer_gone" -> Logger.i("Murmur: upload of ${upload.title} no longer needed")
          else -> throw e
        }
        return
      }
    }
    Logger.i("Murmur: uploaded ${upload.title}")
  }

  private suspend fun uploadFile(
    api: MurmurApi,
    transferId: String,
    file: TransferFile,
    uri: Uri,
  ) {
    var offset = file.offset
    repeat(3) {
      try {
        offset = api.upload(transferId, file.index, offset, FileTailBody(uri, offset, file.size - offset))
        if (offset >= file.size) return
      } catch (e: MurmurException) {
        if (e.code != "offset_mismatch") throw e
        offset = api.uploadOffset(transferId, file.index)
      }
    }
    throw IOException("could not agree on an upload offset for ${file.path}")
  }

  private suspend fun withdraw(
    api: MurmurApi,
    murmurBookId: String,
    reason: String,
  ) {
    Logger.w("Murmur: $reason; no longer sharing it")
    api.unshare(murmurBookId)
    settingsStore.updateData { it.copy(shared = it.shared - murmurBookId) }
  }

  private suspend fun download(
    api: MurmurApi,
    download: Download,
  ) {
    val settings = settingsStore.data.first()
    val folder = settings.downloadFolder?.let { DocumentFile.fromTreeUri(context, it.toUri()) } ?: run {
      Logger.i("Murmur: no download folder chosen; not downloading ${download.title}")
      return
    }
    val singleFile = download.files.size == 1 && '/' !in download.files.single().path
    val target = targetFor(download, folder, singleFile)

    for (file in download.files) {
      val document = if (singleFile) target else target.resolve(file.path)
      fetchFile(api, download.transferId, file, document)
    }

    api.received(download.transferId)
    settingsStore.updateData { it.copy(downloading = it.downloading - download.bookId) }
    if (settings.keepSharing) {
      val manifest = download.files.map { ManifestFile(it.path, it.size, it.sha256) }
      val id = api.share(download.title, download.author, manifest)
      settingsStore.updateData { it.copy(shared = it.shared + (id to BookId(target.uri).value)) }
    }
    Logger.i("Murmur: received ${download.title}")
    mediaScanTrigger.scan()
  }

  /** The file (single-file books) or directory a download is written into, reused across attempts. */
  private suspend fun targetFor(
    download: Download,
    folder: DocumentFile,
    singleFile: Boolean,
  ): DocumentFile {
    settingsStore.data.first().downloading[download.bookId]
      ?.let { DocumentFile.fromTreeUri(context, it.toUri()) }
      ?.takeIf { it.exists() }
      ?.let { return it }

    val name = if (singleFile) download.files.single().path else safeName(download.title)
    val created = if (singleFile) folder.createFile(OCTET_STREAM, name) else folder.createDirectory(name)
    created ?: throw IOException("could not create $name in download folder")
    settingsStore.updateData { it.copy(downloading = it.downloading + (download.bookId to created.uri.toString())) }
    return created
  }

  private fun DocumentFile.resolve(path: String): DocumentFile {
    val segments = path.split('/')
    var dir = this
    for (segment in segments.dropLast(1)) {
      dir = dir.findFile(segment)?.takeIf { it.isDirectory }
        ?: dir.createDirectory(segment)
        ?: throw IOException("could not create directory $segment")
    }
    val name = segments.last()
    return dir.findFile(name) ?: dir.createFile(OCTET_STREAM, name) ?: throw IOException("could not create $name")
  }

  private suspend fun fetchFile(
    api: MurmurApi,
    transferId: String,
    file: TransferFile,
    document: DocumentFile,
  ) {
    var have = document.length()
    if (have == file.size && localBookFiles.sha256(document.uri) == file.sha256) return
    if (have >= file.size) have = 0

    api.download(transferId, file.index, from = have).use { response ->
      withContext(Dispatchers.IO) {
        val mode = if (have > 0) "wa" else "wt"
        val output = context.contentResolver.openOutputStream(document.uri, mode)
          ?: throw IOException("cannot write ${document.uri}")
        output.use { response.body.byteStream().copyTo(it) }
      }
    }
    if (localBookFiles.sha256(document.uri) != file.sha256) {
      document.delete()
      throw IOException("${file.path} failed verification; will retry")
    }
  }

  private fun safeName(title: String): String = title.replace(Regex("""[/\\:*?"<>|]"""), "_").trim().ifEmpty { "Book" }

  private companion object {
    const val OCTET_STREAM = "application/octet-stream"
  }

  /** Streams the bytes of [uri] from [offset] onwards. */
  private inner class FileTailBody(
    private val uri: Uri,
    private val offset: Long,
    private val length: Long,
  ) : RequestBody() {
    override fun contentType(): MediaType = MurmurApi.OCTETS
    override fun contentLength(): Long = length
    override fun writeTo(sink: BufferedSink) {
      val input = context.contentResolver.openInputStream(uri) ?: throw IOException("cannot open $uri")
      input.use {
        it.skipFully(offset)
        sink.write(it.source(), length)
      }
    }
  }
}

private fun InputStream.skipFully(count: Long) {
  var remaining = count
  while (remaining > 0) {
    val skipped = skip(remaining)
    if (skipped <= 0) {
      if (read() < 0) throw IOException("file shorter than expected")
      remaining--
    } else {
      remaining -= skipped
    }
  }
}

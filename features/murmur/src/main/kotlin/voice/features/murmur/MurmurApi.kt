package voice.features.murmur

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Typed client for the Murmur protocol, v1. Every call maps 1:1 onto an endpoint
 * in docs/protocol.md of the Murmur server repo; keep the two in sync.
 */
class MurmurApi(
  serverUrl: String,
  private val token: String?,
  private val client: OkHttpClient = defaultClient,
) {

  private val base: HttpUrl = serverUrl.trimEnd('/').plus("/v1/").toHttpUrl()

  suspend fun register(username: String): RegisterResponse = call(
    post("register", RegisterRequest(username), RegisterRequest.serializer()),
    RegisterResponse.serializer(),
  )

  suspend fun me(): MeResponse = call(get("me"), MeResponse.serializer())

  suspend fun library(): List<LibraryBook> = call(get("library"), LibraryResponse.serializer()).books

  suspend fun share(
    title: String,
    author: String?,
    manifest: List<ManifestFile>,
  ): String = call(
    post("books", ShareRequest(title, author.orEmpty(), manifest), ShareRequest.serializer()),
    ShareResponse.serializer(),
  ).id

  suspend fun unshare(bookId: String) = call(request("books/$bookId/holding").delete())

  suspend fun want(bookId: String) = call(request("books/$bookId/want").put(ByteArray(0).toRequestBody()))

  suspend fun unwant(bookId: String) = call(request("books/$bookId/want").delete())

  suspend fun setCover(
    bookId: String,
    jpeg: ByteArray,
  ) = call(request("books/$bookId/cover").put(jpeg.toRequestBody(JPEG)))

  /** Null if the book has no cover. */
  suspend fun cover(bookId: String): ByteArray? = execute(get("books/$bookId/cover")).use {
    if (it.code == 404) return null
    if (!it.isSuccessful) throw it.toException()
    withContext(Dispatchers.IO) { it.body.bytes() }
  }

  suspend fun uploads(): List<Upload> = call(get("me/uploads"), UploadsResponse.serializer()).uploads

  suspend fun downloads(): List<Download> = call(get("me/downloads"), DownloadsResponse.serializer()).downloads

  suspend fun uploadOffset(
    transferId: String,
    index: Int,
  ): Long {
    val response = execute(request("transfers/$transferId/files/$index").head().build())
    response.use {
      if (!it.isSuccessful) throw it.toException()
      return it.header(UPLOAD_OFFSET)?.toLongOrNull() ?: throw IOException("missing $UPLOAD_OFFSET")
    }
  }

  /** Returns the server's new offset. */
  suspend fun upload(
    transferId: String,
    index: Int,
    offset: Long,
    body: RequestBody,
  ): Long {
    val request = request("transfers/$transferId/files/$index")
      .header(UPLOAD_OFFSET, offset.toString())
      .patch(body)
      .build()
    execute(request).use {
      if (!it.isSuccessful) throw it.toException()
      return it.header(UPLOAD_OFFSET)?.toLongOrNull() ?: throw IOException("missing $UPLOAD_OFFSET")
    }
  }

  /** Caller must close the response. */
  suspend fun download(
    transferId: String,
    index: Int,
    from: Long,
  ): Response {
    val builder = request("transfers/$transferId/files/$index").get()
    if (from > 0) builder.header("Range", "bytes=$from-")
    val response = execute(builder.build())
    if (!response.isSuccessful) {
      response.use { throw it.toException() }
    }
    if (from > 0 && response.code != 206) {
      response.close()
      throw IOException("server ignored range request")
    }
    return response
  }

  suspend fun received(transferId: String) = call(request("transfers/$transferId/received").post(ByteArray(0).toRequestBody()))

  private fun request(path: String): Request.Builder {
    val builder = Request.Builder().url(base.resolve(path)!!)
    if (token != null) builder.header("Authorization", "Bearer $token")
    return builder
  }

  private fun get(path: String): Request = request(path).get().build()

  private fun <T> post(
    path: String,
    body: T,
    serializer: KSerializer<T>,
  ): Request = request(path)
    .post(murmurJson.encodeToString(serializer, body).toRequestBody(JSON))
    .build()

  private suspend fun execute(request: Request): Response = withContext(Dispatchers.IO) {
    client.newCall(request).execute()
  }

  private suspend fun call(builder: Request.Builder) {
    execute(builder.build()).use { if (!it.isSuccessful) throw it.toException() }
  }

  private suspend fun <T> call(
    request: Request,
    serializer: KSerializer<T>,
  ): T = execute(request).use { response ->
    val body = withContext(Dispatchers.IO) { response.body.string() }
    if (!response.isSuccessful) throw MurmurException(response.code, body)
    murmurJson.decodeFromString(serializer, body)
  }

  companion object {
    const val UPLOAD_OFFSET = "Upload-Offset"
    private val JSON = "application/json".toMediaType()
    private val JPEG = "image/jpeg".toMediaType()
    val OCTETS = "application/offset+octet-stream".toMediaType()

    private val defaultClient = OkHttpClient.Builder()
      .readTimeout(60, TimeUnit.SECONDS)
      .writeTimeout(60, TimeUnit.SECONDS)
      .build()
  }
}

private fun Response.toException(): MurmurException = MurmurException(code, body.string())

class MurmurException(
  val status: Int,
  body: String,
) : IOException("HTTP $status: $body") {
  /** The protocol's machine-readable error code, e.g. `username_taken`. */
  val code: String? = runCatching { murmurJson.decodeFromString(ErrorResponse.serializer(), body).error }.getOrNull()
}

@Serializable
private data class ErrorResponse(val error: String)

@Serializable
private data class RegisterRequest(val username: String)

@Serializable
data class RegisterResponse(
  val username: String,
  val token: String,
)

@Serializable
data class MeResponse(val username: String)

@Serializable
private data class LibraryResponse(val books: List<LibraryBook>)

@Serializable
data class LibraryBook(
  val id: String,
  val title: String,
  val author: String,
  val size: Long,
  val fileCount: Int,
  val holders: List<String>,
  val wanters: List<String>,
  val mine: String? = null,
  val cover: Boolean = false,
) {
  val holding: Boolean get() = mine == "holding"
  val wanting: Boolean get() = mine == "wanting"
}

@Serializable
private data class ShareRequest(
  val title: String,
  val author: String,
  val manifest: List<ManifestFile>,
)

@Serializable
private data class ShareResponse(val id: String)

@Serializable
private data class UploadsResponse(val uploads: List<Upload>)

@Serializable
data class Upload(
  val transferId: String,
  val bookId: String,
  val title: String,
  val size: Long,
  val files: List<TransferFile>,
)

@Serializable
private data class DownloadsResponse(val downloads: List<Download>)

@Serializable
data class Download(
  val transferId: String,
  val bookId: String,
  val title: String,
  val author: String,
  val files: List<TransferFile>,
)

@Serializable
data class TransferFile(
  val index: Int,
  val path: String,
  val size: Long,
  val sha256: String,
  val offset: Long = 0,
)

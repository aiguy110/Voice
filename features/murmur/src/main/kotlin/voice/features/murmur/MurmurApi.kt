package voice.features.murmur

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Typed client for the Murmur protocol, v2. Every call maps 1:1 onto an endpoint
 * in docs/protocol.md of the Murmur server repo; keep the two in sync.
 */
class MurmurApi(
  serverUrl: String,
  private val token: String?,
  private val client: OkHttpClient = defaultClient,
) {

  private val base: HttpUrl = serverUrl.trimEnd('/').plus("/v2/").toHttpUrl()

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
    pieces: ByteArray,
    source: Source? = null,
  ): ShareResponse = call(
    post(
      "books",
      ShareRequest(title, author.orEmpty(), manifest, Base64.getEncoder().encodeToString(pieces), source),
      ShareRequest.serializer(),
    ),
    ShareResponse.serializer(),
  )

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

  /** The book's .torrent, whose tracker URL is private to this user; null if the server doesn't know it yet. */
  suspend fun torrent(bookId: String): ByteArray? = execute(get("books/$bookId/torrent")).use {
    if (it.code == 404) return null
    if (!it.isSuccessful) throw it.toException()
    withContext(Dispatchers.IO) { it.body.bytes() }
  }

  /** Where a book imported from a public torrent came from; null if it wasn't. */
  suspend fun source(bookId: String): Source? = execute(get("books/$bookId/source")).use {
    if (it.code == 404) return null
    val body = withContext(Dispatchers.IO) { it.body.string() }
    if (!it.isSuccessful) throw MurmurException(it.code, body)
    murmurJson.decodeFromString(Source.serializer(), body)
  }

  /** Books this user holds that the community is waiting for; seed them now. */
  suspend fun requests(): List<SeedRequest> = call(get("me/requests"), RequestsResponse.serializer()).books

  /** Sends an opt-in diagnostic report; [report] must have an `event` field. */
  suspend fun telemetry(report: JsonObject) = call(
    request("telemetry").post(murmurJson.encodeToString(JsonObject.serializer(), report).toRequestBody(JSON)),
  )

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
    private val JSON = "application/json".toMediaType()
    private val JPEG = "image/jpeg".toMediaType()

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
  /** The server keeps a copy, so a request doesn't wait for a holder. */
  val cached: Boolean = false,
  /** It came from a public torrent, which [MurmurApi.source] describes. */
  val source: Boolean = false,
) {
  val holding: Boolean get() = mine == "holding"
  val wanting: Boolean get() = mine == "wanting"
}

@Serializable
private data class ShareRequest(
  val title: String,
  val author: String,
  val manifest: List<ManifestFile>,
  val pieces: String,
  val source: Source?,
)

@Serializable
data class ShareResponse(
  val id: String,
  val infoHash: String,
)

/** A public torrent a book was imported from: the manifest path of each of its files there. */
@Serializable
data class Source(
  val magnet: String,
  val files: List<SourceFile>,
)

@Serializable
data class SourceFile(
  /** Index in the torrent's file list. */
  val index: Int,
  val path: String,
)

@Serializable
private data class RequestsResponse(val books: List<SeedRequest>)

@Serializable
data class SeedRequest(
  val id: String,
  val infoHash: String,
)

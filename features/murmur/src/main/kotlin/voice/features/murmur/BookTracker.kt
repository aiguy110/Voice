package voice.features.murmur

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.libtorrent4j.TcpEndpoint
import org.libtorrent4j.TorrentHandle
import java.io.IOException
import java.net.InetAddress
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/** Uses Android TLS instead of libtorrent4j's broken HTTPS tracker client on some ARM64 devices. */
internal class BookTracker(torrent: ByteArray) {
  private val fields = TrackerBencode(torrent).dictionary()
  private val announce = fields.getValue("announce").bytes().toString(Charsets.UTF_8)

  // Keep the original info bytes: removing tracker metadata must never change the infohash.
  val trackerless: ByteArray = "d".toByteArray() + fields.filterKeys { it != "announce" && it != "announce-list" }
    .entries.fold(ByteArray(0)) { bytes, (key, value) ->
      bytes + "${key.toByteArray().size}:$key".toByteArray() + value.raw
    } + "e".toByteArray()
  private val wake = Channel<Unit>(Channel.CONFLATED)

  fun reannounce() {
    wake.trySend(Unit)
  }

  suspend fun run(handle: TorrentHandle) {
    val peerId = ByteArray(20).also { SecureRandom().nextBytes(it) }
    var started = false
    var completed = false
    suspend fun announce(event: String): TrackerReply = withContext(Dispatchers.IO) {
      // The wrapper's cached status can be null during a concurrent first read by the transfer loop.
      val status = handle.status(true)
      val left = if (status.isFinished) 0 else (handle.torrentFile().totalSize() - status.totalDone()).coerceAtLeast(1)
      val url =
        trackerUrl(announce, handle.infoHash().toHex(), peerId, left, status.totalPayloadUpload(), status.totalPayloadDownload(), event)
      client.newCall(Request.Builder().url(url).build()).execute().use { response ->
        if (!response.isSuccessful) throw IOException("Tracker HTTP ${response.code}")
        val source = response.body.source()
        source.request(MAX_REPLY_BYTES + 1)
        val body = source.buffer.readByteArray()
        if (body.size > MAX_REPLY_BYTES) throw IOException("Tracker reply too large")
        trackerReply(body)
      }
    }
    try {
      while (true) {
        var interval = 30L
        try {
          val finished = handle.status(true).isFinished
          val event = if (!started) {
            "started"
          } else if (finished && !completed) {
            "completed"
          } else {
            ""
          }
          val reply = announce(event)
          started = true
          if (finished) completed = true
          reply.peers.forEach { (address, port) -> handle.swig().connect_peer(TcpEndpoint(address, port).swig()) }
          interval = reply.interval
          Log.i("Murmur", "Private tracker: ${reply.peers.size} peers for ${handle.infoHash().toHex()}")
        } catch (_: IOException) {
          // Never log the request URL: it contains the member's private announce key.
          Log.w("Murmur", "Private tracker failed; retrying in 30 seconds")
        }
        withTimeoutOrNull(interval * 1000) { wake.receive() }
      }
    } finally {
      if (started && handle.isValid) {
        withContext(NonCancellable) {
          try {
            if (handle.status(true).isFinished && !completed) completed = announce("completed").interval > 0
            announce("stopped")
          } catch (_: IOException) {
            // Tracker entries expire if shutdown happens without a network.
          }
        }
      }
      wake.close()
    }
  }

  private companion object {
    const val MAX_REPLY_BYTES = 64L * 1024
    val client = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()
  }
}

internal fun trackerUrl(
  announce: String,
  hash: String,
  peerId: ByteArray,
  left: Long,
  uploaded: Long,
  downloaded: Long,
  event: String,
): HttpUrl {
  fun encoded(bytes: ByteArray) = bytes.joinToString("") { "%%%02X".format(it.toInt() and 255) }
  val rawHash = hash.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
  return announce.toHttpUrl().newBuilder()
    .addEncodedQueryParameter("info_hash", encoded(rawHash))
    .addEncodedQueryParameter("peer_id", encoded(peerId))
    .addQueryParameter("port", TorrentEngine.PORT.toString())
    .addQueryParameter("uploaded", uploaded.toString())
    .addQueryParameter("downloaded", downloaded.toString())
    .addQueryParameter("left", left.toString())
    .addQueryParameter("compact", "1")
    .addQueryParameter("numwant", "50")
    .apply { if (event.isNotEmpty()) addQueryParameter("event", event) }
    .build()
}

internal data class TrackerReply(
  val interval: Long,
  val peers: List<Pair<String, Int>>,
)

internal fun trackerReply(bytes: ByteArray): TrackerReply {
  val fields = TrackerBencode(bytes).dictionary()
  if ("failure reason" in fields) throw IOException("Tracker rejected announce")
  fun peers(
    key: String,
    width: Int,
  ): List<Pair<String, Int>> {
    val compact = fields[key]?.bytes() ?: return emptyList()
    if (compact.size % width != 0) throw IOException("Invalid compact peers")
    return compact.asList().chunked(width).mapNotNull { peer ->
      val address = InetAddress.getByAddress(peer.take(width - 2).toByteArray()).hostAddress ?: return@mapNotNull null
      val port = ((peer[width - 2].toInt() and 255) shl 8) or (peer.last().toInt() and 255)
      if (port == 0) null else address to port
    }
  }
  val interval = fields["interval"]?.number() ?: throw IOException("Missing tracker interval")
  val minimum = fields["min interval"]?.number() ?: 30
  if (interval !in 1..86400 || minimum !in 0..86400) throw IOException("Invalid tracker interval")
  return TrackerReply(maxOf(interval, minimum, 30), peers("peers", 6) + peers("peers6", 18))
}

/** Bounded bencode reader; values retain their exact encoding for the trackerless torrent. */
internal class TrackerBencode(private val input: ByteArray) {
  internal class Value(
    val raw: ByteArray,
    private val value: Any,
  ) {
    fun bytes(): ByteArray = value as? ByteArray ?: throw IOException("Expected bencoded string")
    fun number(): Long = value as? Long ?: throw IOException("Expected bencoded integer")
  }

  private var offset = 0

  fun dictionary(): Map<String, Value> {
    if (input.getOrNull(offset++) != 'd'.code.toByte()) throw IOException("Expected dictionary")
    val result = linkedMapOf<String, Value>()
    while (input.getOrNull(offset) != 'e'.code.toByte()) {
      val key = read(0).bytes().toString(Charsets.UTF_8)
      if (result.put(key, read(0)) != null) throw IOException("Duplicate dictionary key")
    }
    offset++
    if (offset != input.size) throw IOException("Trailing bencode data")
    return result
  }

  private fun read(depth: Int): Value {
    if (depth > 20 || offset >= input.size) throw IOException("Invalid bencode")
    val start = offset
    val value: Any = when (input[offset].toInt().toChar()) {
      'i' -> {
        offset++
        val end = input.indexOfFrom('e'.code.toByte(), offset)
        val number = input.copyOfRange(offset, end).toString(Charsets.US_ASCII).toLongOrNull()
          ?: throw IOException("Invalid bencoded integer")
        offset = end + 1
        number
      }
      'd', 'l' -> {
        offset++
        while (input.getOrNull(offset) != 'e'.code.toByte()) {
          if (read(depth + 1).raw.isEmpty()) throw IOException("Empty bencode value")
        }
        offset++
        Unit
      }
      in '0'..'9' -> {
        val end = input.indexOfFrom(':'.code.toByte(), offset)
        val length = input.copyOfRange(offset, end).toString(Charsets.US_ASCII).toIntOrNull()
          ?: throw IOException("Invalid bencoded length")
        offset = end + 1
        if (length < 0 || length > input.size - offset) throw IOException("Truncated bencoded string")
        input.copyOfRange(offset, offset + length).also { offset += length }
      }
      else -> throw IOException("Invalid bencode")
    }
    return Value(input.copyOfRange(start, offset), value)
  }

  private fun ByteArray.indexOfFrom(
    byte: Byte,
    from: Int,
  ): Int {
    for (index in from until size) if (this[index] == byte) return index
    throw IOException("Truncated bencode")
  }
}

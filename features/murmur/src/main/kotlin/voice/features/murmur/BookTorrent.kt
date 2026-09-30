package voice.features.murmur

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * A book's torrent, derived from its files alone so every holder builds the same
 * one ("Book torrent" in the protocol doc). Must match the server's
 * `manifest.Info`; the protocol doc has a test vector.
 */
object BookTorrent {

  private const val MIN_PIECE_LENGTH = 256L shl 10
  private const val MAX_PIECE_LENGTH = 16L shl 20
  private const val MAX_PIECES = 2048

  fun pieceLength(total: Long): Long {
    var n = MIN_PIECE_LENGTH
    while (n < MAX_PIECE_LENGTH && (total + n - 1) / n > MAX_PIECES) n *= 2
    return n
  }

  /** Files in canonical (manifest) order. */
  fun <T> canonical(
    files: List<T>,
    path: (T) -> String,
  ): List<T> = files.sortedWith(compareBy(Utf8Comparator, path))

  /**
   * Reads each file once, in canonical order, returning its manifest entry and
   * the torrent's piece hashes. [sizes] and [open] are keyed by manifest path.
   */
  fun describe(
    sizes: Map<String, Long>,
    open: (String) -> InputStream,
  ): Pair<List<ManifestFile>, ByteArray> {
    val paths = canonical(sizes.keys.toList()) { it }
    val length = pieceLength(sizes.values.sum())
    val pieces = ByteArrayOutputStream()
    val piece = MessageDigest.getInstance("SHA-1")
    var inPiece = 0L
    val buffer = ByteArray(1 shl 16)
    val manifest = paths.map { path ->
      val sha256 = MessageDigest.getInstance("SHA-256")
      var size = 0L
      open(path).use { input ->
        while (true) {
          val read = input.read(buffer)
          if (read < 0) break
          var offset = 0
          while (offset < read) {
            val take = minOf((read - offset).toLong(), length - inPiece).toInt()
            piece.update(buffer, offset, take)
            sha256.update(buffer, offset, take)
            offset += take
            inPiece += take
            if (inPiece == length) {
              pieces.write(piece.digest())
              inPiece = 0
            }
          }
          size += read
        }
      }
      if (size != sizes.getValue(path)) throw IOException("$path changed size while reading it")
      ManifestFile(path, size, sha256.digest().toHex())
    }
    if (inPiece > 0) pieces.write(piece.digest())
    return manifest to pieces.toByteArray()
  }

  /** The bencoded info dictionary. */
  fun info(
    manifest: List<ManifestFile>,
    pieces: ByteArray,
  ): ByteArray {
    val out = ByteArrayOutputStream()
    fun raw(s: String) = out.write(s.toByteArray(Charsets.UTF_8))
    fun string(bytes: ByteArray) {
      raw("${bytes.size}:")
      out.write(bytes)
    }
    val files = canonical(manifest) { it.path }
    raw("d5:filesl")
    files.forEach { file ->
      raw("d6:lengthi${file.size}e4:pathl")
      file.path.split('/').forEach { string(it.toByteArray(Charsets.UTF_8)) }
      raw("ee")
    }
    raw("e4:name")
    string(bookId(files).toByteArray())
    raw("12:piece lengthi${pieceLength(files.sumOf { it.size })}e6:pieces")
    string(pieces)
    raw("7:privatei1ee")
    return out.toByteArray()
  }

  fun infoHash(info: ByteArray): String = MessageDigest.getInstance("SHA-1").digest(info).toHex()
}

package voice.features.murmur

import kotlinx.serialization.Serializable
import java.security.MessageDigest

/**
 * One file of a book, as described in the Murmur protocol ("Book identity").
 */
@Serializable
data class ManifestFile(
  val path: String,
  val size: Long,
  val sha256: String,
)

/**
 * The Murmur book id: SHA-256 of the canonical manifest. Must match the server's
 * `manifest.BookID`; the protocol doc has a test vector.
 */
fun bookId(files: List<ManifestFile>): String {
  val digest = MessageDigest.getInstance("SHA-256")
  files.sortedWith(compareBy(Utf8Comparator) { it.path }).forEach {
    digest.update("${it.sha256} ${it.size} ${it.path}\n".toByteArray(Charsets.UTF_8))
  }
  return digest.digest().toHex()
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

// The protocol sorts paths bytewise on UTF-8, which differs from String's UTF-16 order for some characters.
private object Utf8Comparator : Comparator<String> {
  override fun compare(
    a: String,
    b: String,
  ): Int {
    val x = a.toByteArray(Charsets.UTF_8)
    val y = b.toByteArray(Charsets.UTF_8)
    for (i in 0 until minOf(x.size, y.size)) {
      val c = (x[i].toInt() and 0xff) - (y[i].toInt() and 0xff)
      if (c != 0) return c
    }
    return x.size - y.size
  }
}

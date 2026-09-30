package voice.features.murmur

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest

class BookTorrentTest {

  // Test vector from docs/protocol.md in the Murmur server repo.
  @Test
  fun matchesProtocolVector() {
    val content = mapOf("02 - Two.mp3" to "two", "01 - One.mp3" to "one")
    val (manifest, pieces) = BookTorrent.describe(content.mapValues { it.value.length.toLong() }) {
      content.getValue(it).byteInputStream()
    }
    assertEquals(listOf("01 - One.mp3", "02 - Two.mp3"), manifest.map { it.path })
    assertEquals("407741299f0c9129c3f7803263219f214ce354633a52a97c4824287723208a6b", bookId(manifest))
    assertArrayEquals(MessageDigest.getInstance("SHA-1").digest("onetwo".toByteArray()), pieces)
    assertEquals("b4ff4cc803bd57ad19d53ffdc5b3441115cea7be", BookTorrent.infoHash(BookTorrent.info(manifest, pieces)))
  }

  @Test
  fun piecesSpanFiles() {
    val a = ByteArray(200 shl 10) { 'a'.code.toByte() }
    val b = ByteArray(100 shl 10) { 'b'.code.toByte() }
    val (_, pieces) = BookTorrent.describe(mapOf("a" to a.size.toLong(), "b" to b.size.toLong())) {
      (if (it == "a") a else b).inputStream()
    }
    val all = a + b
    val sha1 = { bytes: ByteArray -> MessageDigest.getInstance("SHA-1").digest(bytes) }
    assertArrayEquals(sha1(all.copyOfRange(0, 256 shl 10)) + sha1(all.copyOfRange(256 shl 10, all.size)), pieces)
  }

  @Test
  fun pieceLength() {
    assertEquals(256L shl 10, BookTorrent.pieceLength(512L shl 20))
    assertEquals(512L shl 10, BookTorrent.pieceLength((512L shl 20) + 1))
    assertEquals(16L shl 20, BookTorrent.pieceLength(100L shl 30))
  }
}

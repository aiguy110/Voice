package voice.features.murmur

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress

class BookTrackerTest {
  @Test
  fun encodesEveryBinaryByteWithoutUtf8Conversion() {
    val hash = "00ff2b20803f26253d000102030405060708090a0b"
    val peer = ByteArray(20) { (255 - it).toByte() }
    val url = trackerUrl("https://example.com/v2/announce/secret", hash, peer, 123, 45, 67, "started")
    assertTrue(url.encodedQuery!!.contains("info_hash=%00%FF%2B%20%80%3F%26%25%3D%00"))
    assertTrue(url.encodedQuery!!.contains("peer_id=%FF%FE%FD"))
    assertEquals("123", url.queryParameter("left"))
    assertEquals("42070", url.queryParameter("port"))
    assertEquals("45", url.queryParameter("uploaded"))
    assertEquals("67", url.queryParameter("downloaded"))
    assertEquals("started", url.queryParameter("event"))
    assertEquals(null, trackerUrl(url.toString().substringBefore('?'), hash, peer, 0, 0, 0, "").queryParameter("event"))
  }

  @Test
  fun readsCompactIpv4AndIpv6AndHonorsMinimumInterval() {
    val ipv4 = byteArrayOf(100, 111, 153.toByte(), 83, 164.toByte(), 86)
    val ipv6 = InetAddress.getByName("fd7a:115c:a1e0::422c:9954").address + byteArrayOf(164.toByte(), 86)
    val reply =
      trackerReply(
        "d8:intervali10e12:min intervali45e5:peers6:".toByteArray() + ipv4 + "6:peers618:".toByteArray() + ipv6 + "e".toByteArray(),
      )
    assertEquals(45L, reply.interval)
    assertEquals(2, reply.peers.size)
    assertEquals("100.111.153.83" to 42070, reply.peers[0])
    assertEquals(InetAddress.getByAddress(ipv6.take(16).toByteArray()).hostAddress to 42070, reply.peers[1])
  }

  @Test
  fun keepsInfoBytesExactlyAndRemovesNativeTrackers() {
    val info = "d4:name4:book7:privatei1ee".toByteArray()
    val torrent =
      "d8:announce23:https://example.com/key13:announce-listll23:https://example.com/keyee4:info".toByteArray() + info + "e".toByteArray()
    val stripped = BookTracker(torrent).trackerless
    val fields = TrackerBencode(stripped).dictionary()
    assertArrayEquals(info, fields.getValue("info").raw)
    assertFalse("announce" in fields)
    assertFalse("announce-list" in fields)
  }

  @Test
  fun rejectsFailuresMalformedAndOversizedLengths() {
    listOf(
      "d14:failure reason6:deniede",
      "d8:intervali120e5:peers1:xe",
      "d8:intervali120e6:peers61:xe",
      "d8:intervali120e5:peers99999999999999999999:xe",
      "d8:intervali120e5:peers6:abc",
      "d8:intervali120e5:peers0:eextra",
      "d8:intervali120e8:intervali120ee",
      "d8:intervali0ee",
      "d8:intervali9223372036854775807ee",
      "d8:intervali120e12:min intervali-1ee",
      "d1:x" + "l".repeat(30) + "e".repeat(31),
      "",
    ).forEach { value ->
      try {
        throw AssertionError("Accepted $value as ${trackerReply(value.toByteArray())}")
      } catch (_: IOException) {
        // Expected.
      }
    }
  }
}

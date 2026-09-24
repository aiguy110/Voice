package voice.features.murmur

import org.junit.Assert.assertEquals
import org.junit.Test

class ManifestTest {

  // Test vector from docs/protocol.md in the Murmur server repo.
  @Test
  fun matchesProtocolVector() {
    val files = listOf(
      ManifestFile("02 - Two.mp3", 3, "3fc4ccfe745870e2c0d99f71f30ff0656c8dedd41cc1d7d3d376b0dbe685e2f3"),
      ManifestFile("01 - One.mp3", 3, "7692c3ad3540bb803c020b3aee66cd8887123234ea0c6e7143c0add73ff431ed"),
    )
    assertEquals("407741299f0c9129c3f7803263219f214ce354633a52a97c4824287723208a6b", bookId(files))
  }
}

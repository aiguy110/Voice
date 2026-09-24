package voice.features.murmur

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SabpStateTest {

  // Real files written by Smart AudioBook Player.
  private fun fixture(name: String) = checkNotNull(javaClass.classLoader).getResourceAsStream(name).use { SabpState.parse(it) }

  @Test
  fun startedBook() {
    assertEquals(
      SabpState(
        finished = false,
        started = true,
        fileName = "The Aeneid (Course).m4b",
        positionInFileMs = 11_047_000,
        playbackSpeed = 1.25F,
        lastPlayedAtMs = 1_790_133_274_748,
      ),
      fixture("started.sabp.dat"),
    )
  }

  @Test
  fun finishedBook() {
    val state = fixture("finished.sabp.dat")!!
    assertEquals(true, state.finished)
    assertEquals("19-Apache-Tears-Hardcore-History-Dan-Carlin.mp3", state.fileName)
  }

  @Test
  fun rejectsOtherData() {
    assertNull(SabpState.parse("not serialized".byteInputStream()))
    assertNull(SabpState.parse(ByteArray(0).inputStream()))
  }
}

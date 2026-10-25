package voice.features.murmur

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingMagnetTest {
  @Test
  fun oldSettingsHaveNoPendingMagnets() {
    assertTrue(murmurJson.decodeFromString(MurmurSettings.serializer(), "{}").magnets.isEmpty())
  }

  @Test
  fun unresolvedAndReadyRequestsSurviveSerialization() {
    val settings = MurmurSettings(
      magnets = mapOf(
        "pending" to PendingMagnet("magnet:?xt=urn:btih:pending", "Pending", error = "No peers", lastAttempt = 123),
        "ready" to PendingMagnet("magnet:?xt=urn:btih:ready", "Ready", ready = true),
      ),
    )
    val restored = murmurJson.decodeFromString(
      MurmurSettings.serializer(),
      murmurJson.encodeToString(MurmurSettings.serializer(), settings),
    )
    assertEquals(settings, restored)
  }

  @Test
  fun retriesUnresolvedRequestsAfterFifteenMinutes() {
    val request = PendingMagnet("magnet", "Book", lastAttempt = 1000)
    assertFalse(request.shouldRetry(1000 + 899999))
    assertTrue(request.shouldRetry(1000 + 900000))
    assertFalse(request.copy(ready = true).shouldRetry(1000 + 900000))
    assertTrue(request.copy(lastAttempt = 0).shouldRetry(1))
  }
}

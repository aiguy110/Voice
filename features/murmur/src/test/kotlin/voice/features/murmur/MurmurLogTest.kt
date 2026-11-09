package voice.features.murmur

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import voice.core.logging.api.Logger
import java.io.File

class MurmurLogTest {

  @get:Rule
  val temp = TemporaryFolder()

  @Test
  fun keepsInfoAndAboveAcrossRotation() {
    val dir = temp.newFolder()
    val log = MurmurLog(dir)
    log.log(Logger.Severity.Debug, "noise", null)
    log.log(Logger.Severity.Info, "first", null)
    repeat(MurmurLog.MAX_FILE_BYTES / 1000 + 1) { log.log(Logger.Severity.Warn, "x".repeat(1000), null) }
    log.log(Logger.Severity.Error, "last", null)

    val text = log.tail()
    assertFalse("noise" in text)
    assertTrue("I first" in text)
    assertTrue(text.trimEnd().endsWith("E last"))
    assertTrue(File(dir, "log.1.txt").exists())
    assertTrue(log.tail(100).length <= 100)
  }

  @Test
  fun fileTreeStopsAtTheLimit() {
    val root = temp.newFolder("Murmur")
    File(root, "Book").mkdirs()
    File(root, "Book/01.mp3").writeBytes(ByteArray(3))
    File(root, "Book/02.mp3").writeBytes(ByteArray(4))

    val full = fileTree(root, 100)
    val book = full["children"]!!.jsonArray.single().jsonObject
    assertEquals(listOf(3L, 4L), book["children"]!!.jsonArray.map { it.jsonObject["size"]!!.jsonPrimitive.long })

    val cut = fileTree(root, 2)["children"]!!.jsonArray.single().jsonObject["children"]!!.jsonArray
    assertTrue(cut.last().jsonObject["truncated"]!!.jsonPrimitive.boolean)

    assertTrue(fileTree(File(root, "gone"), 10)["missing"]!!.jsonPrimitive.boolean)
  }
}

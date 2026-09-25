package voice.features.murmur

import android.net.Uri
import io.mockk.mockk
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import voice.core.documentfile.CachedDocumentFile

class TreeWalkTest {

  private class Fake(
    override val name: String,
    override val children: List<CachedDocumentFile> = emptyList(),
    override val length: Long = 0,
    override val isDirectory: Boolean = children.isNotEmpty(),
  ) : CachedDocumentFile {
    override val isFile get() = !isDirectory
    override val lastModified = 0L
    override val uri: Uri = mockk()
  }

  private val tree = Fake(
    "Audiobooks",
    listOf(
      Fake("Tolkien", listOf(Fake("02.mp3", length = 2), Fake("01.mp3", length = 1))),
      Fake("cover.jpg", length = 9),
    ),
  )

  @Test
  fun rendersNamesSizesAndSortedChildren() {
    val walk = TreeWalk(maxEntries = 100)
    val root = walk.node(tree)
    assertEquals(5, walk.entries)
    assertEquals(false, walk.truncated)
    val author = root["children"]!!.jsonArray[0].jsonObject
    assertEquals("Tolkien", author["name"]!!.jsonPrimitive.content)
    assertEquals("true", author["dir"]!!.jsonPrimitive.content)
    val first = author["children"]!!.jsonArray[0].jsonObject
    assertEquals("01.mp3", first["name"]!!.jsonPrimitive.content)
    assertEquals("1", first["size"]!!.jsonPrimitive.content)
  }

  @Test
  fun stopsAtTheLimit() {
    val walk = TreeWalk(maxEntries = 3)
    val _ = walk.node(tree)
    assertEquals(3, walk.entries)
    assertEquals(true, walk.truncated)
  }
}

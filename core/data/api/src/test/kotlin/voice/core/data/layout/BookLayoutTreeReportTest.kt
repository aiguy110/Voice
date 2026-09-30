package voice.core.data.layout

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Runs the analysis over a real library tree, as Murmur's `folder_picked` telemetry
 * reports it, and prints what it finds. Libraries are private, so none are checked in:
 * `LAYOUT_TREE=/path/to/report.json ./gradlew :core:data:api:testDebugUnitTest --tests '*TreeReport*'`.
 */
class BookLayoutTreeReportTest {

  @Test
  fun report() {
    val path = System.getenv("LAYOUT_TREE")
    assumeTrue(path != null)
    val json = Json.parseToJsonElement(File(path!!).readText()).jsonObject
    val tree = (json["report"]?.jsonObject ?: json)["tree"]!!.jsonObject
    val books = detectBooks(tree.toNode())
    val out = buildString {
      books.forEach { book ->
        val path = book.path.joinToString("/")
        appendLine("$path  →  \"${book.title}\" by ${book.author} (narrator ${book.narrator}, ${book.year})")
        appendLine("    ${book.audio.size} files, cover ${book.cover?.path}")
        book.notes.forEach { appendLine("    note: $it") }
      }
      appendLine("${books.size} books")
    }
    System.getenv("LAYOUT_OUT")?.let { File(it).writeText(out) } ?: print(out)
  }

  private fun JsonObject.toNode(): LayoutNode<Unit> {
    val name = this["name"]!!.jsonPrimitive.content
    return if (this["dir"]?.jsonPrimitive?.boolean == true) {
      LayoutNode(name, children = this["children"]?.jsonArray.orEmpty().map { it.jsonObject.toNode() })
    } else {
      LayoutNode(name, size = this["size"]?.jsonPrimitive?.long ?: 0)
    }
  }
}

package voice.core.data.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookLayoutTest {

  private val mb = 1_000_000L

  private fun dir(
    name: String,
    vararg children: LayoutNode<Unit>,
  ) = LayoutNode(name, children = children.toList())

  private fun file(
    name: String,
    size: Long = mb,
  ) = LayoutNode<Unit>(name, size = size)

  private fun tracks(
    pattern: String,
    count: Int,
  ) = Array(count) { file(pattern.format(it + 1)) }

  private fun List<DetectedBook<Unit>>.summary() = map { "${it.path.joinToString("/")} | ${it.author} | ${it.title} | ${it.audio.size}" }

  @Test
  fun authorFoldersWithBookFolders() {
    val books = detectBooks(
      dir(
        "Audiobooks",
        dir("Stephen King", dir("Carrie", *tracks("Carrie - %03d.mp3", 3), file("cover.jpg")), dir("Misery", file("Misery.m4b"))),
        dir("!Player Statistics", file("statistics.xml"), file("a.jpg")),
      ),
    )
    assertEquals(
      listOf("Stephen King/Carrie | Stephen King | Carrie | 3", "Stephen King/Misery | Stephen King | Misery | 1"),
      books.summary(),
    )
    assertEquals("cover.jpg", books.first().cover?.path)
  }

  @Test
  fun authorFolderHoldingOneBookFileIsThatBook() {
    val books =
      detectBooks(
        dir("Audiobooks", dir("Bram Stoker", file("Dracula by Bram Stoker.m4b"), file("EmbeddedCover.jpg"), file("position.sabp.dat"))),
      )
    assertEquals(listOf("Bram Stoker | Bram Stoker | Dracula | 1"), books.summary())
  }

  @Test
  fun bookFolderWithOneNumberedFileKeepsItsName() {
    val books = detectBooks(layoutTree(listOf(Triple("Stall Book/01.mp3", mb, 0))))
    assertEquals(listOf("Stall Book" to null), books.map { it.title to it.author })
  }

  @Test
  fun discFoldersAreOneBook() {
    val books = detectBooks(dir("Audiobooks", dir("The Hobbit", dir("CD 1", *tracks("%02d.mp3", 2)), dir("CD 2", *tracks("%02d.mp3", 2)))))
    assertEquals(listOf("The Hobbit | null | The Hobbit | 4"), books.summary())
    assertEquals(listOf("CD 1/01.mp3", "CD 1/02.mp3", "CD 2/01.mp3", "CD 2/02.mp3"), books.single().audio.map { it.path })
  }

  @Test
  fun numberedSeriesFoldersAreSeparateBooks() {
    val books = detectBooks(
      dir(
        "Audiobooks",
        dir("Jo Nesbo", dir("01 The Bat", *tracks("The Bat - CD %02d.mp3", 2)), dir("02 Cockroaches", file("COCKROACHES.mp3"))),
      ),
    )
    assertEquals(
      listOf("Jo Nesbo/01 The Bat | Jo Nesbo | The Bat | 2", "Jo Nesbo/02 Cockroaches | Jo Nesbo | Cockroaches | 1"),
      books.summary(),
    )
  }

  @Test
  fun wrapperFolderRepeatingTheTitleIsSkipped() {
    val books = detectBooks(
      dir(
        "Audiobooks",
        dir("Robert Jordan", dir("01 The Eye of the World", dir("01 The Eye of the World", *tracks("%02d_Chapter.m4b", 3)))),
      ),
    )
    assertEquals(
      listOf("Robert Jordan/01 The Eye of the World/01 The Eye of the World | Robert Jordan | The Eye of the World | 3"),
      books.summary(),
    )
  }

  @Test
  fun chapterFilesSharingATitleAreOneBook() {
    val books = detectBooks(
      dir(
        "Audiobooks",
        dir("Shirer", dir("The Rise and Fall", *Array(3) { file("The Rise and Fall Part ${it + 1}.m4b", 500 * mb) })),
      ),
    )
    assertEquals(1, books.size)
    assertEquals(3, books.single().audio.size)
  }

  @Test
  fun unrelatedBookSizedFilesAreSeparateBooks() {
    val books = detectBooks(dir("Audiobooks", dir("Wilson", file("01 - Spin.m4b"), file("02 - Axis.m4b"), file("03 - Vortex.m4b"))))
    assertEquals(listOf("Spin", "Axis", "Vortex"), books.map { it.title })
    assertEquals(listOf("Wilson/01 - Spin.m4b", "Wilson/02 - Axis.m4b", "Wilson/03 - Vortex.m4b"), books.map { it.path.joinToString("/") })
  }

  @Test
  fun sameBookInTwoFormatsKeepsTheSingleFile() {
    val books = detectBooks(
      dir(
        "Audiobooks",
        dir(
          "Kerry Patterson",
          dir("Crucial Conversations - Tools for Talking", *tracks("%02d-Crucial Conversations.mp3", 8), file("Crucial Conversations.m4b")),
        ),
      ),
    )
    val book = books.single()
    assertEquals("Crucial Conversations.m4b", book.root.name)
    assertEquals("Crucial Conversations - Tools for Talking" to "Kerry Patterson", book.title to book.author)
    assertEquals(listOf("Kerry Patterson", "Crucial Conversations - Tools for Talking", "Crucial Conversations.m4b"), book.path)
    assertEquals(listOf("Left out 8 MP3 files that duplicate Crucial Conversations.m4b"), book.notes)
  }

  @Test
  fun bookFileNextToABookFolder() {
    val books = detectBooks(
      dir("Audiobooks", dir("Virgil", file("The Aeneid (Course).m4b"), dir("The Aeneid", file("The Aeneid.m4b"), file("The Aeneid.cue")))),
    )
    assertEquals(
      listOf("Virgil/The Aeneid | Virgil | The Aeneid | 1", "Virgil/The Aeneid (Course).m4b | Virgil | The Aeneid (Course) | 1"),
      books.summary(),
    )
    assertNull(books.last().narrator)
  }

  @Test
  fun looseChaptersWithAnExtrasFolderAreOneBook() {
    val books = detectBooks(dir("Audiobooks", dir("Dune", *tracks("Dune %02d.mp3", 3), dir("Extras", file("Interview.mp3")))))
    assertEquals(listOf("Dune | null | Dune | 4"), books.summary())
  }

  @Test
  fun namesLoseRipperNoise() {
    val parsed = ParsedName.parse("1950 - I, Robot (Hagon) 128k 08.33.12 {473mb}")
    assertEquals(ParsedName(title = "I, Robot", author = null, narrator = "Hagon", year = 1950), parsed)
    assertEquals("Foundation", ParsedName.parse("1.0 Foundation").title)
    assertEquals(ParsedName("It", null, "Steven Weber", 1986), ParsedName.parse("It (Read by Steven Weber) (1986)"))
    assertEquals(ParsedName("The Shining", "Stephen King", null, null), ParsedName.parse("Stephen King - The Shining"))
    assertEquals("1984", ParsedName.parse("1984").title)
  }

  @Test
  fun authorPrefixInsideAuthorFolderIsStripped() {
    val books =
      detectBooks(
        dir(
          "Audiobooks",
          dir("Stephen King", dir("Stephen King - Dark Tower I - The Gunslinger (Frank Muller)", *tracks("%d of 2.mp3", 2))),
        ),
      )
    val book = books.single()
    assertEquals("Dark Tower I - The Gunslinger", book.title)
    assertEquals("Stephen King", book.author)
    assertEquals("Frank Muller", book.narrator)
  }

  @Test
  fun torrentPaths() {
    val single = detectBooks(layoutTree(listOf(Triple("Misery.m4b", 400 * mb, 0))))
    assertEquals(listOf("Misery.m4b"), single.map { it.root.name })
    assertNull(single.single().author)

    val folder = detectBooks(
      layoutTree(
        listOf(
          Triple("Stephen King - Carrie/Carrie 01.mp3", mb, 0),
          Triple("Stephen King - Carrie/Carrie 02.mp3", mb, 1),
          Triple("Stephen King - Carrie/folder.jpg", mb, 2),
          Triple("Stephen King - Carrie/Torrent downloaded from X.txt", 1, 3),
        ),
      ),
    ).single()
    assertEquals("Carrie", folder.title)
    assertEquals("Stephen King", folder.author)
    assertEquals(listOf(0, 1), folder.audio.map { it.node.value })
    assertEquals(2, folder.cover?.node?.value)
  }

  @Test
  fun coverPrefersFrontOverBackAndPortraits() {
    val book = detectBooks(
      dir(
        "A",
        dir(
          "The Snowman",
          file("The Snowman - CD 01.mp3"),
          file("Jo_Nesbo.jpg", 2 * mb),
          file("The Snowman_Rear.jpg"),
          file("The Snowman_Front.jpg"),
        ),
      ),
    ).single()
    assertEquals("The Snowman_Front.jpg", book.cover?.path)
  }
}

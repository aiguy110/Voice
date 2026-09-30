package voice.core.scanner

import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import voice.core.data.BookId
import voice.core.data.Chapter
import voice.core.data.ChapterId
import voice.core.data.repo.BookContentRepo
import voice.core.documentfile.FileBasedDocumentFactory
import voice.core.documentfile.FileBasedDocumentFile
import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class BookParserTest {

  @get:Rule
  val testFolder = TemporaryFolder()

  private val parser = BookParser(
    contentRepo = mockk(),
    mediaAnalyzer = mockk(),
    fileFactory = mockk(),
  )

  @Test
  fun folderBookUsesFolderNameWhenAlbumMissing() {
    val bookFolder = testFolder.newFolder("My Audiobook")
    val chapters = listOf(
      chapter(File(bookFolder, "1.mp3").apply { createNewFile() }),
      chapter(File(bookFolder, "2.mp3").apply { createNewFile() }),
    )

    val content = parser.parse(
      chapters = chapters,
      id = BookId(bookFolder.toUri()),
      analyzed = metadata(album = null, title = "First Chapter Title"),
      file = FileBasedDocumentFile(bookFolder),
    )

    assertEquals(expected = "My Audiobook", actual = content.name)
  }

  @Test
  fun folderBookWithSingleChapterStillUsesFolderName() {
    val bookFolder = testFolder.newFolder("Harry Potter 3")
    val chapters = listOf(chapter(File(bookFolder, "track01.mp3").apply { createNewFile() }))

    val content = parser.parse(
      chapters = chapters,
      id = BookId(bookFolder.toUri()),
      analyzed = metadata(album = null, title = "Track Title"),
      file = FileBasedDocumentFile(bookFolder),
    )

    assertEquals(expected = "Harry Potter 3", actual = content.name)
  }

  @Test
  fun singleFileBookUsesTitleWhenAlbumMissing() {
    val bookFile = testFolder.newFile("book.mp3")
    val chapters = listOf(chapter(bookFile))

    val content = parser.parse(
      chapters = chapters,
      id = BookId(bookFile.toUri()),
      analyzed = metadata(album = null, title = "The Title"),
      file = FileBasedDocumentFile(bookFile),
    )

    assertEquals(expected = "The Title", actual = content.name)
  }

  @Test
  fun albumAlwaysWinsOverTitleAndFolderName() {
    val bookFolder = testFolder.newFolder("Folder Name")
    val chapters = listOf(
      chapter(File(bookFolder, "1.mp3").apply { createNewFile() }),
      chapter(File(bookFolder, "2.mp3").apply { createNewFile() }),
    )

    val content = parser.parse(
      chapters = chapters,
      id = BookId(bookFolder.toUri()),
      analyzed = metadata(album = "Album Name", title = "First Chapter Title"),
      file = FileBasedDocumentFile(bookFolder),
    )

    assertEquals(expected = "Album Name", actual = content.name)
  }

  @Test
  fun missingMetadataFallsBackToFolderName() {
    val bookFolder = testFolder.newFolder("Fallback Folder")
    val chapters = listOf(
      chapter(File(bookFolder, "1.mp3").apply { createNewFile() }),
      chapter(File(bookFolder, "2.mp3").apply { createNewFile() }),
    )

    val content = parser.parse(
      chapters = chapters,
      id = BookId(bookFolder.toUri()),
      analyzed = null,
      file = FileBasedDocumentFile(bookFolder),
    )

    assertEquals(expected = "Fallback Folder", actual = content.name)
  }

  @Test
  fun `album artist is the author and numbered album supplies series part`() {
    val bookFolder = testFolder.newFolder("book")
    val chapters = listOf(chapter(File(bookFolder, "1.m4b").apply { createNewFile() }))

    val content = parser.parse(
      chapters = chapters,
      id = BookId(bookFolder.toUri()),
      analyzed = metadata(
        album = "02 The Great Hunt",
        title = "Chapter 1",
        artist = "The Wheel of Time",
        albumArtist = "Robert Jordan",
        series = "The Wheel of Time",
      ),
      file = FileBasedDocumentFile(bookFolder),
    )

    assertEquals(expected = "Robert Jordan", actual = content.author)
    assertEquals(expected = "The Wheel of Time", actual = content.series)
    assertEquals(expected = "02", actual = content.part)
  }

  @Test
  fun `old metadata is backfilled without replacing playback state or title`() = runTest {
    val bookFolder = testFolder.newFolder("book")
    val audioFile = File(bookFolder, "1.m4b").apply { createNewFile() }
    val chapters = listOf(chapter(audioFile))
    val oldContent = parser.parse(
      chapters = chapters,
      id = BookId(bookFolder.toUri()),
      analyzed = metadata(album = "Original Album", title = "Chapter", artist = "Wrong Author"),
      file = FileBasedDocumentFile(bookFolder),
    ).copy(
      name = "My Edited Title",
      positionInChapter = 321L,
      metadataVersion = 0,
    )
    val repo = mockk<BookContentRepo> {
      coEvery { get(oldContent.id) } returns oldContent
      coEvery { put(any()) } just Runs
    }
    val analyzer = mockk<MediaAnalyzer> {
      coEvery { analyze(any()) } returns metadata(
        album = "02 The Great Hunt",
        title = "Chapter",
        artist = "The Wheel of Time",
        albumArtist = "Robert Jordan",
        series = "The Wheel of Time",
      )
    }
    val backfilled = BookParser(repo, analyzer, FileBasedDocumentFactory).parseAndStore(
      chapters = chapters,
      file = FileBasedDocumentFile(bookFolder),
      firstChapterMetadata = null,
    )

    assertEquals(expected = "My Edited Title", actual = backfilled.name)
    assertEquals(expected = 321L, actual = backfilled.positionInChapter)
    assertEquals(expected = "Robert Jordan", actual = backfilled.author)
    assertEquals(expected = "The Wheel of Time", actual = backfilled.series)
    assertEquals(expected = "02", actual = backfilled.part)
    assertEquals(expected = 1, actual = backfilled.metadataVersion)
    coVerify(exactly = 1) { repo.put(backfilled) }
  }

  private fun chapter(file: File): Chapter = Chapter(
    id = ChapterId(file.toUri()),
    name = "Chapter",
    duration = 1000L,
    fileLastModified = Instant.EPOCH,
    markData = emptyList(),
    fileSize = 0,
  )

  private fun metadata(
    album: String?,
    title: String?,
    artist: String? = null,
    albumArtist: String? = null,
    series: String? = null,
    part: String? = null,
  ): Metadata = Metadata(
    duration = 1000L,
    artist = artist,
    albumArtist = albumArtist,
    album = album,
    title = title,
    fileName = "file",
    chapters = emptyList(),
    genre = null,
    narrator = null,
    series = series,
    part = part,
  )
}

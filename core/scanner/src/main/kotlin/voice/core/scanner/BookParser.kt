package voice.core.scanner

import dev.zacsweers.metro.Inject
import voice.core.data.Book
import voice.core.data.BookContent
import voice.core.data.BookId
import voice.core.data.Chapter
import voice.core.data.repo.BookContentRepo
import voice.core.data.toUri
import voice.core.documentfile.CachedDocumentFile
import voice.core.documentfile.CachedDocumentFileFactory
import voice.core.logging.api.Logger
import java.time.Instant

@Inject
internal class BookParser(
  private val contentRepo: BookContentRepo,
  private val mediaAnalyzer: MediaAnalyzer,
  private val fileFactory: CachedDocumentFileFactory,
) {

  suspend fun parseAndStore(
    chapters: List<Chapter>,
    file: CachedDocumentFile,
    firstChapterMetadata: Metadata?,
  ): BookContent {
    val id = BookId(file.uri)
    val existing = contentRepo.get(id)
    if (existing?.metadataVersion == METADATA_VERSION) return existing

    val analyzed = firstChapterMetadata
      ?: mediaAnalyzer.analyze(fileFactory.create(chapters.first().id.toUri()))
    val updated = if (existing == null) {
      parse(chapters, id, analyzed, file)
    } else {
      existing.copy(
        author = analyzed?.albumArtist ?: analyzed?.artist ?: existing.author,
        genre = analyzed?.genre ?: existing.genre,
        narrator = analyzed?.narrator ?: existing.narrator,
        series = analyzed?.series ?: existing.series,
        part = analyzed?.part ?: inferPart(analyzed?.album, analyzed?.series) ?: existing.part,
        metadataVersion = METADATA_VERSION,
      )
    }
    contentRepo.put(updated)
    return updated
  }

  fun parse(
    chapters: List<Chapter>,
    id: BookId,
    analyzed: Metadata?,
    file: CachedDocumentFile,
  ): BookContent {
    return BookContent(
      id = id,
      isActive = true,
      addedAt = Instant.now(),
      author = analyzed?.albumArtist ?: analyzed?.artist,
      lastPlayedAt = Instant.EPOCH,
      name = analyzed?.album
        ?: analyzed?.title?.takeIf { file.isFile }
        ?: file.bookName(),
      playbackSpeed = 1F,
      skipSilence = false,
      chapters = chapters.map { it.id },
      positionInChapter = 0L,
      currentChapter = chapters.first().id,
      cover = null,
      gain = 0F,
      genre = analyzed?.genre,
      narrator = analyzed?.narrator,
      series = analyzed?.series,
      part = analyzed?.part ?: inferPart(analyzed?.album, analyzed?.series),
      metadataVersion = METADATA_VERSION,
    ).also {
      validateIntegrity(it, chapters)
    }
  }

  private fun CachedDocumentFile.bookName(): String {
    val fileName = name
    return if (fileName == null) {
      uri.toString()
        .removePrefix("/storage/emulated/0/")
        .removePrefix("/storage/emulated/")
        .removePrefix("/storage/")
        .also {
          Logger.w("Could not parse fileName from $this. Fallback to $it")
        }
    } else {
      if (isFile) {
        fileName.substringBeforeLast(".")
      } else {
        fileName
      }
    }
  }

  private fun inferPart(
    album: String?,
    series: String?,
  ): String? {
    if (series.isNullOrBlank()) return null
    return album?.let { LEADING_PART.find(it)?.groupValues?.get(1) }
  }

  private companion object {
    const val METADATA_VERSION = 1
    val LEADING_PART = Regex("""^\s*(\d+(?:\.\d+)?)\b""")
  }
}

internal fun validateIntegrity(
  content: BookContent,
  chapters: List<Chapter>,
) {
  // the init block performs integrity validation
  @Suppress("RETURN_VALUE_NOT_USED")
  Book(content, chapters)
}

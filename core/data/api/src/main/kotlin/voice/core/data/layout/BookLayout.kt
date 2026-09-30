package voice.core.data.layout

import voice.core.common.comparator.NaturalOrderComparator
import voice.core.data.isAudioFileName
import voice.core.documentfile.CachedDocumentFile

/**
 * A file or directory as [detectBooks] sees it. [value] is the caller's handle for
 * it (a document, a torrent file index); directories built by [layoutTree] have none.
 */
public class LayoutNode<T>(
  public val name: String,
  public val value: T? = null,
  public val size: Long = 0,
  /** Null for files. */
  public val children: List<LayoutNode<T>>? = null,
) {
  public val isDirectory: Boolean get() = children != null
}

/** A file inside a detected book, [path] relative to the book's directory. */
public data class LayoutFile<T>(
  val path: String,
  val node: LayoutNode<T>,
)

public data class DetectedBook<T>(
  /** The file or directory that is the book, as Voice's scanner treats books. */
  val root: LayoutNode<T>,
  /** Names from the analyzed root down to [root], both included. */
  val path: List<String>,
  val title: String,
  val author: String?,
  val narrator: String?,
  val year: Int?,
  /** The book's audio files in playback order. For a single-file book the path is the file name. */
  val audio: List<LayoutFile<T>>,
  val cover: LayoutFile<T>?,
  /** Human-readable notes on decisions that dropped files, for review before importing. */
  val notes: List<String>,
) {
  val size: Long get() = audio.sumOf { it.node.size }
}

/**
 * Builds a tree from `/`-separated file paths, as a torrent lists them. The
 * returned node is a nameless directory holding the top-level entries.
 */
public fun <T> layoutTree(files: List<Triple<String, Long, T>>): LayoutNode<T> {
  val root = TreeBuilder<T>("")
  files.forEach { (path, size, value) ->
    val segments = path.split('/').filter { it.isNotEmpty() }
    if (segments.isEmpty()) return@forEach
    var dir = root
    segments.dropLast(1).forEach { dir = dir.dirs.getOrPut(it) { TreeBuilder(it) } }
    dir.files += LayoutNode(segments.last(), value, size)
  }
  return root.build()
}

private class TreeBuilder<T>(val name: String) {
  val dirs = LinkedHashMap<String, TreeBuilder<T>>()
  val files = mutableListOf<LayoutNode<T>>()
  fun build(): LayoutNode<T> = LayoutNode(name, children = dirs.values.map { it.build() } + files)
}

/**
 * Finds the audiobooks in a tree whose layout follows no particular convention:
 * author folders, series folders, disc folders, wrapper folders, loose single-file
 * books, and the same book in two formats side by side.
 *
 * The rules, applied from the root down:
 * - A directory without audio anywhere below it (statistics folders, artwork) is ignored,
 *   as is anything hidden.
 * - A directory whose audio-bearing subdirectories all look like discs or parts
 *   (`CD 1`, `Disc2`, `Part 03`, `1`) is one book, subdirectories included.
 * - A directory with no audio of its own is a container (author, series, wrapper):
 *   every subdirectory is analyzed on its own.
 * - Loose audio files in a directory are one book (the directory) unless
 *   - one lone m4b/m4a sits next to files of other formats: the same book twice, so the
 *     lone file is the book and the others are left out, or
 *   - they are all book-sized (m4b, or at least 200 MB) and their names share no
 *     leading words: separate books, one per file.
 * - A directory with both loose audio and audio subdirectories: book-sized loose files
 *   are books of their own and the subdirectories are analyzed on their own; otherwise
 *   the loose files are the main book and the subdirectories are its extras, so the
 *   whole directory is one book.
 *
 * Titles, authors, narrators, and years are guessed from the names: the book's own
 * name for the title, the nearest enclosing directory below the analyzed root that
 * isn't a repeat of the title for the author.
 */
public fun <T> detectBooks(root: LayoutNode<T>): List<DetectedBook<T>> = LayoutAnalyzer<T>().analyze(root)
  .sortedWith(compareBy(NaturalOrderComparator.stringComparator) { it.path.joinToString("/") })

private class LayoutAnalyzer<T> {

  private val hasAudioCache = HashMap<LayoutNode<T>, Boolean>()

  fun analyze(root: LayoutNode<T>): List<DetectedBook<T>> {
    if (!root.isDirectory) {
      return if (root.isAudio()) listOf(fileBook(root, parent = null, ancestors = emptyList(), notes = emptyList())) else emptyList()
    }
    return visit(root, ancestors = emptyList(), isRoot = true)
  }

  /**
   * [ancestors] are the directories strictly between the analyzed root and [dir]. The
   * root itself is never an ancestor: it's the user's library folder or a torrent's
   * nameless top, so it says nothing about authors.
   */
  private fun visit(
    dir: LayoutNode<T>,
    ancestors: List<LayoutNode<T>>,
    isRoot: Boolean,
  ): List<DetectedBook<T>> {
    val entries = dir.children.orEmpty().filterNot { it.name.startsWith('.') }
    val audio = entries.filter { it.isAudio() }
    val subdirs = entries.filter { it.isDirectory && it.hasAudio() }
    val inside = if (isRoot) ancestors else ancestors + dir

    fun children() = subdirs.flatMap { visit(it, inside, isRoot = false) }

    return when {
      audio.isEmpty() && subdirs.isEmpty() -> emptyList()
      subdirs.all { it.isPartFolder() } && subdirs.isNotEmpty() -> listOf(dirBook(dir, ancestors))
      audio.isEmpty() -> children()
      subdirs.isEmpty() -> loose(dir, ancestors, inside, audio, isRoot)
      audio.all { it.isBookSized() } -> audio.map { fileBook(it, dir, inside, emptyList()) } + children()
      else -> listOf(dirBook(dir, ancestors))
    }
  }

  /** Loose audio files in a directory without audio subdirectories. */
  private fun loose(
    dir: LayoutNode<T>,
    ancestors: List<LayoutNode<T>>,
    inside: List<LayoutNode<T>>,
    audio: List<LayoutNode<T>>,
    isRoot: Boolean,
  ): List<DetectedBook<T>> {
    val byFormat = audio.groupBy { it.extension() }
    if (byFormat.size > 1) {
      val lone = byFormat.values.singleOrNull { it.size == 1 && it.single().extension() in CONTAINER_FORMATS }?.single()
      if (lone != null) {
        val others = audio - lone
        val formats = others.map { it.extension().uppercase() }.distinct().joinToString("/")
        val note = "Left out ${others.size} $formats file${if (others.size == 1) "" else "s"} that duplicate ${lone.name}"
        return listOf(fileBook(lone, dir, ancestors, listOf(note), namedBy = dir))
      }
    }
    if (audio.size > 1 && audio.all { it.isBookSized() } && !sharesLeadingWords(audio.map { stem(it.name) })) {
      return audio.map { fileBook(it, dir, inside, emptyList()) }
    }
    if (isRoot && dir.name.isEmpty()) {
      // Files at the top of a torrent have no directory of their own to be the book.
      return if (audio.size == 1) listOf(fileBook(audio.single(), dir, inside, emptyList())) else listOf(dirBook(dir, ancestors))
    }
    return listOf(dirBook(dir, ancestors))
  }

  private fun dirBook(
    dir: LayoutNode<T>,
    ancestors: List<LayoutNode<T>>,
  ): DetectedBook<T> {
    val audio = buildList { collectAudio(dir, prefix = "") }
      .sortedWith(compareBy(NaturalOrderComparator.stringComparator) { it.path })
    return book(root = dir, directory = dir, ancestors = ancestors, audio = audio, notes = emptyList())
  }

  /** [namedBy] is the folder whose name is the book's, when the file only stands in for it. */
  private fun fileBook(
    file: LayoutNode<T>,
    parent: LayoutNode<T>?,
    ancestors: List<LayoutNode<T>>,
    notes: List<String>,
    namedBy: LayoutNode<T>? = null,
  ): DetectedBook<T> {
    val audio = listOf(LayoutFile(file.name, file))
    return book(root = file, directory = parent, ancestors = ancestors, audio = audio, notes = notes, namedBy = namedBy)
  }

  private fun book(
    root: LayoutNode<T>,
    directory: LayoutNode<T>?,
    ancestors: List<LayoutNode<T>>,
    audio: List<LayoutFile<T>>,
    notes: List<String>,
    namedBy: LayoutNode<T>? = null,
  ): DetectedBook<T> {
    val named = ancestors.filter { it.name.isNotEmpty() }
    val enclosingAuthor = named.asReversed().firstNotNullOfOrNull { ParsedName.parseAuthor(it.name) }
    // An author's folder holding one book file directly: the file names the book.
    val authorFolder = enclosingAuthor == null && root.isDirectory && audio.size == 1 && '/' !in audio.single().path &&
      stem(audio.single().path).any { it.isLetter() } && normalize(stem(audio.single().path)) != normalize(root.name) &&
      ParsedName.parseAuthor(root.name) == root.name.trim()
    val parsed = when {
      authorFolder -> ParsedName.parse(audio.single().path.substringBeforeLast('.'), isFile = true).copy(author = root.name.trim())
      namedBy != null -> ParsedName.parse(namedBy.name)
      root.isDirectory -> ParsedName.parse(root.name)
      else -> ParsedName.parse(root.name.substringBeforeLast('.'), isFile = true)
    }
    val folderAuthor = named.asReversed()
      .map { ParsedName.parseAuthor(it.name) }
      .firstOrNull { it != null && normalize(it) != normalize(parsed.title) }
    // Under an author's folder, "X - Y" is a title with a subtitle unless X is that author.
    val splitWrongly = folderAuthor != null && parsed.author != null && normalize(parsed.author) != normalize(folderAuthor)
    val author = folderAuthor ?: parsed.author
    val title = if (splitWrongly) "${parsed.author} - ${parsed.title}" else author?.let { stripAuthor(parsed.title, it) } ?: parsed.title
    return DetectedBook(
      root = root,
      path = (named + namedBy + root.takeIf { it.name.isNotEmpty() }).filterNotNull().map { it.name },
      title = title,
      author = author,
      narrator = parsed.narrator,
      year = parsed.year,
      audio = audio,
      cover = directory?.let { pickCover(it, title) },
      notes = notes,
    )
  }

  private fun MutableList<LayoutFile<T>>.collectAudio(
    dir: LayoutNode<T>,
    prefix: String,
  ) {
    dir.children.orEmpty().filterNot { it.name.startsWith('.') }.forEach { child ->
      when {
        child.isDirectory -> collectAudio(child, prefix + child.name + "/")
        child.isAudio() -> add(LayoutFile(prefix + child.name, child))
      }
    }
  }

  private fun pickCover(
    dir: LayoutNode<T>,
    title: String,
  ): LayoutFile<T>? {
    val images = dir.children.orEmpty().filter { !it.isDirectory && !it.name.startsWith('.') && it.extension() in IMAGE_FORMATS }
    val normalizedTitle = normalize(title)
    return images
      .maxWithOrNull(
        compareBy<LayoutNode<T>> { coverScore(it.name.substringBeforeLast('.'), normalizedTitle) }
          .thenBy { it.size }
          .thenByDescending { it.name },
      )
      ?.let { LayoutFile(it.name, it) }
  }

  private fun coverScore(
    stem: String,
    normalizedTitle: String,
  ): Int {
    val name = stem.lowercase()
    return when {
      name in setOf("cover", "folder", "front") -> 4
      Regex("""(^|\W|_)(back|rear|inlay|cd)(\W|_|$)""").containsMatchIn(name) -> 0
      "front" in name || "cover" in name -> 3
      normalizedTitle.isNotEmpty() && normalize(stem).contains(normalizedTitle) -> 2
      else -> 1
    }
  }

  private fun LayoutNode<T>.isAudio() = !isDirectory && isAudioFileName(name)

  private fun LayoutNode<T>.hasAudio(): Boolean = hasAudioCache.getOrPut(this) {
    if (isDirectory) children.orEmpty().any { !it.name.startsWith('.') && it.hasAudio() } else isAudio()
  }

  private fun LayoutNode<T>.isBookSized() = extension() == "m4b" || size >= BOOK_SIZED_BYTES

  /** A disc or part of a book: no audio-bearing subdirectories of its own, and a name like `CD 2`. */
  private fun LayoutNode<T>.isPartFolder(): Boolean {
    if (children.orEmpty().any { it.isDirectory && it.hasAudio() }) return false
    return PART_FOLDER.containsMatchIn(name.trim()) || name.trim().matches(Regex("""\d{1,3}"""))
  }

  private fun LayoutNode<T>.extension() = name.substringAfterLast('.', "").lowercase()

  private companion object {
    const val BOOK_SIZED_BYTES = 200_000_000L
    val CONTAINER_FORMATS = setOf("m4b", "m4a")
    val IMAGE_FORMATS = setOf("jpg", "jpeg", "png", "webp")
    val PART_FOLDER = Regex(
      """(^|[\s._-])(cd|disc|disk|part|pt|side|tape)[\s._-]*\d{1,3}([\s._-]*of[\s._-]*\d{1,3})?$""",
      RegexOption.IGNORE_CASE,
    )
  }
}

/** A file name without extension, numbering, and part markers: what two parts of one book have in common. */
private fun stem(fileName: String): String = fileName.substringBeforeLast('.')
  .replace(Regex("""^[\d\s._-]+"""), "")
  .replace(Regex("""(?i)[\s._-]*\(?(part|pt|cd|disc|disk)?[\s._-]*\d+([\s._-]*of[\s._-]*\d+)?\)?$"""), "")
  .trim()

private val STOPWORDS = setOf("the", "a", "an", "of", "and", "de", "la", "le", "der", "die", "das")

/** Whether every name starts with the same meaningful word, as parts of one book do. */
private fun sharesLeadingWords(names: List<String>): Boolean {
  val words = names.map { name -> name.lowercase().split(Regex("""[^\p{L}\p{N}]+""")).filter { it.isNotEmpty() } }
  val common = words.reduce { acc, list -> acc.zip(list).takeWhile { (a, b) -> a == b }.map { it.first } }
  return common.any { it !in STOPWORDS }
}

internal fun normalize(text: String): String = text.lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), "")

private fun stripAuthor(
  title: String,
  author: String,
): String {
  val stripped = title
    .replace(Regex("""^${Regex.escape(author)}\s*[-–:]\s*""", RegexOption.IGNORE_CASE), "")
    .replace(Regex("""\s*[-–]?\s*\bby\s+${Regex.escape(author)}$""", RegexOption.IGNORE_CASE), "")
    .replace(Regex("""\s*[-–,]\s*${Regex.escape(author)}$""", RegexOption.IGNORE_CASE), "")
  return stripped.trim().ifBlank { title }
}

/** What a folder or file name says about a book, e.g. `1950 - I, Robot (Hagon) 128k 08.33.12 {473mb}`. */
internal data class ParsedName(
  val title: String,
  val author: String?,
  val narrator: String?,
  val year: Int?,
) {
  companion object {
    private val NOISE = listOf(
      Regex("""\{[^}]*\}"""),
      Regex("""\[[^]]*\]"""),
      Regex("""(?i)\b\d{2,3}\s?k(bps)?\b"""),
      Regex("""\b\d{1,2}[.:]\d{2}[.:]\d{2}\b"""),
      Regex("""(?i)\(?\bunabridged\b\)?"""),
    )
    private val LEADING_YEAR = Regex("""^((?:19|20)\d\d)\s*[-–.]\s+""")
    private val TRAILING_YEAR = Regex("""\s*\(((?:19|20)\d\d)\)""")
    private val LEADING_INDEX = Regex("""^\d{1,3}(\.\d{1,2})?\s*[-_.)]?\s+(?=\S)""")
    private val NARRATOR = Regex("""\s*\((?:read|narrated) by ([^)]+)\)""", RegexOption.IGNORE_CASE)
    private val TRAILING_PARENS = Regex("""\s*\(([^)]*)\)\s*$""")
    private val PERSON = Regex("""^\p{Lu}[\p{L}.'’-]*(?:\s+\p{Lu}[\p{L}.'’-]*){0,3}$""")
    private val AUTHOR_TITLE = Regex("""^(\p{Lu}[\p{L}.'’]*(?:\s+\p{Lu}[\p{L}.'’]*){1,3})\s+[-–]\s+(.+)$""")
    private val SERIES_SUFFIX = Regex("""(?i)^(.+?)\s*[-–]\s*.*\b(series|saga|trilogy|cycle|collection|chronicles?)\s*$""")

    /**
     * Folder names are curated and often end in the narrator (`I, Robot (Hagon)`); file names
     * more often end in an edition (`The Aeneid (Course)`), so [isFile] keeps that in the title.
     */
    fun parse(
      raw: String,
      isFile: Boolean = false,
    ): ParsedName {
      var name = raw.replace('_', ' ').takeIf { ' ' !in raw } ?: raw
      NOISE.forEach { name = it.replace(name, " ") }
      name = name.replace(Regex("""\s+"""), " ").trim()

      var year: Int? = null
      LEADING_YEAR.find(name)?.let {
        year = it.groupValues[1].toInt()
        name = name.substring(it.range.last + 1)
      }
      TRAILING_YEAR.find(name)?.let {
        year = year ?: it.groupValues[1].toInt()
        name = name.removeRange(it.range)
      }
      var narrator: String? = null
      NARRATOR.find(name)?.let {
        narrator = it.groupValues[1].trim()
        name = name.removeRange(it.range)
      }
      TRAILING_PARENS.find(name)?.takeUnless { isFile }?.let {
        val inner = it.groupValues[1].trim()
        if (narrator == null && PERSON.matches(inner) && inner.split(' ').size <= 3) narrator = inner
        name = name.removeRange(it.range)
      }
      LEADING_INDEX.find(name)?.let { name = name.substring(it.range.last + 1) }
      name = name.trim().trim('-', '–', '_', '.', ',', ' ')

      var author: String? = null
      AUTHOR_TITLE.find(name)?.let {
        author = it.groupValues[1]
        name = it.groupValues[2]
      }
      return ParsedName(title = name.ifBlank { raw.trim() }, author = author, narrator = narrator, year = year)
    }

    /** The author a directory name stands for (`Stephen King`, `Robert Charles Wilson-Spin Series`), if it looks like one. */
    fun parseAuthor(raw: String): String? {
      val name = raw.replace('_', ' ').trim()
      SERIES_SUFFIX.find(name)?.let { return it.groupValues[1].trim() }
      AUTHOR_TITLE.find(name)?.let { return it.groupValues[1] }
      val parsed = parse(name)
      val words = parsed.title.split(' ')
      return parsed.title.takeIf { PERSON.matches(it) && words.size in 1..4 && words.first().lowercase() !in STOPWORDS }
    }
  }
}

/** The tree below this document, for [detectBooks]. Unnamed documents are left out. */
public fun CachedDocumentFile.toLayoutNode(): LayoutNode<CachedDocumentFile> = LayoutNode(
  name = name.orEmpty(),
  value = this,
  size = if (isFile) length else 0,
  children = if (isDirectory) children.filter { it.name != null }.map { it.toLayoutNode() } else null,
)

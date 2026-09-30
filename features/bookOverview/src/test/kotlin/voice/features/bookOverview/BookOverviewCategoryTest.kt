package voice.features.bookOverview

import voice.features.bookOverview.overview.BookOverviewCategory
import voice.features.bookOverview.overview.category
import kotlin.test.Test
import kotlin.test.assertEquals

class BookOverviewCategoryTest {

  @Test
  fun `finished books are naturally sorted by name`() {
    val book11 = book(name = "11 Knife of Dreams")
    val book2 = book(name = "02 The Great Hunt")

    assertEquals(
      expected = listOf(book2, book11),
      actual = listOf(book11, book2).sortedWith(BookOverviewCategory.FINISHED.comparator),
    )
  }

  @Test
  fun finished() {
    val book = book().let { book ->
      val lastChapter = book.chapters.last()
      book.copy(
        content = book.content.copy(
          currentChapter = lastChapter.id,
          positionInChapter = lastChapter.duration,
        ),
      )
    }
    assertEquals(expected = BookOverviewCategory.FINISHED, actual = book.category)
  }

  @Test
  fun notStarted() {
    val book = book().let { book ->
      val firstChapter = book.chapters.first()
      book.copy(
        content = book.content.copy(
          currentChapter = firstChapter.id,
          positionInChapter = 0,
        ),
      )
    }
    assertEquals(expected = BookOverviewCategory.NOT_STARTED, actual = book.category)
  }

  @Test
  fun current() {
    val book = book().let { book ->
      book.copy(
        content = book.content.copy(
          currentChapter = book.chapters.last().id,
          positionInChapter = 0,
        ),
      )
    }
    assertEquals(expected = BookOverviewCategory.CURRENT, actual = book.category)
  }
}

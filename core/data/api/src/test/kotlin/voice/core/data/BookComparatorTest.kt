package voice.core.data

import kotlin.test.Test
import kotlin.test.assertEquals

class BookComparatorTest {

  private val b1 = book(name = "A", lastPlayedAtMillis = 0, addedAtMillis = 2)
  private val b2 = book(name = "B", lastPlayedAtMillis = 0, addedAtMillis = 2)
  private val b3 = book(name = "B", lastPlayedAtMillis = 2, addedAtMillis = 1)
  private val b4 = book(name = "D", lastPlayedAtMillis = 5, addedAtMillis = 7)
  private val b5 = book(name = "C", lastPlayedAtMillis = 5, addedAtMillis = 6)
  private val books = listOf(b1, b2, b3, b4, b5)

  @Test
  fun byLastPlayed() {
    val sorted = books.sortedWith(BookComparator.ByLastPlayed)
    assertEquals(expected = listOf(b4, b5, b3, b1, b2), actual = sorted)
  }

  @Test
  fun byName() {
    val sorted = books.sortedWith(BookComparator.ByName)
    assertEquals(expected = listOf(b1, b2, b3, b5, b4), actual = sorted)
  }

  @Test
  fun `by series keeps series together and orders by part`() {
    val standalone = book(name = "A Standalone")
    val otherSeries = book(name = "01 Other", series = "Other Series", part = "1")
    val series2 = book(name = "Second", series = "The Series", part = "2")
    val series11 = book(name = "Eleventh", series = "The Series", part = "11")

    val sorted = listOf(series11, otherSeries, series2, standalone).sortedWith(BookComparator.BySeries)

    assertEquals(expected = listOf(standalone, otherSeries, series2, series11), actual = sorted)
  }
}

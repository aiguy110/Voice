package voice.core.data

import voice.core.common.comparator.NaturalOrderComparator

public enum class BookComparator(private val comparatorFunction: Comparator<Book>) : Comparator<Book> by comparatorFunction {

  ByLastPlayed(
    compareByDescending {
      it.content.lastPlayedAt
    },
  ),
  ByName(
    Comparator { left, right ->
      NaturalOrderComparator.stringComparator.compare(left.content.name, right.content.name)
    },
  ),
  BySeries(
    Comparator { left, right ->
      val comparator = NaturalOrderComparator.stringComparator
      val leftSeries = left.content.series?.takeIf(String::isNotBlank)
      val rightSeries = right.content.series?.takeIf(String::isNotBlank)
      val groupComparison = comparator.compare(leftSeries ?: left.content.name, rightSeries ?: right.content.name)
      if (groupComparison != 0) {
        groupComparison
      } else if (leftSeries == null || rightSeries == null) {
        comparator.compare(left.content.name, right.content.name)
      } else {
        val partComparison = comparator.compare(left.content.part.orEmpty(), right.content.part.orEmpty())
        if (partComparison != 0) partComparison else comparator.compare(left.content.name, right.content.name)
      }
    },
  ),
}

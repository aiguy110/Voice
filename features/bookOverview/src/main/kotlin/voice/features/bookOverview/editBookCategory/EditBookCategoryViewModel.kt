package voice.features.bookOverview.editBookCategory

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.SingleIn
import voice.core.data.BookId
import voice.core.data.repo.BookRepository
import voice.features.bookOverview.bottomSheet.BottomSheetItem
import voice.features.bookOverview.bottomSheet.BottomSheetItemViewModel
import voice.features.bookOverview.di.BookOverviewScope
import voice.features.bookOverview.overview.BookOverviewCategory
import voice.features.bookOverview.overview.category

@SingleIn(BookOverviewScope::class)
@ContributesIntoSet(BookOverviewScope::class)
class EditBookCategoryViewModel(private val repo: BookRepository) : BottomSheetItemViewModel {

  override suspend fun items(bookId: BookId): List<BottomSheetItem> {
    val book = repo.get(bookId) ?: return emptyList()
    val categoryItems = when (book.category) {
      BookOverviewCategory.CURRENT -> listOf(
        BottomSheetItem.BookCategoryMarkAsNotStarted,
        BottomSheetItem.BookCategoryMarkAsCompleted,
      )
      BookOverviewCategory.NOT_STARTED -> listOf(
        BottomSheetItem.BookCategoryMarkAsCurrent,
        BottomSheetItem.BookCategoryMarkAsCompleted,
      )
      BookOverviewCategory.FINISHED -> listOf(
        BottomSheetItem.BookCategoryMarkAsCurrent,
        BottomSheetItem.BookCategoryMarkAsNotStarted,
      )
    }
    return categoryItems + BottomSheetItem.SelectMultiple
  }

  override suspend fun onItemClick(
    bookId: BookId,
    item: BottomSheetItem,
  ) {
    val category = when (item) {
      BottomSheetItem.BookCategoryMarkAsCurrent -> BookOverviewCategory.CURRENT
      BottomSheetItem.BookCategoryMarkAsNotStarted -> BookOverviewCategory.NOT_STARTED
      BottomSheetItem.BookCategoryMarkAsCompleted -> BookOverviewCategory.FINISHED
      else -> return
    }
    repo.moveToCategory(bookId, category)
  }
}

internal suspend fun BookRepository.moveToCategory(
  bookId: BookId,
  category: BookOverviewCategory,
) {
  val book = get(bookId) ?: return

  val (currentChapter, positionInChapter) = when (category) {
    BookOverviewCategory.CURRENT -> {
      book.chapters.first().id to 1L
    }
    BookOverviewCategory.NOT_STARTED -> {
      book.chapters.first().id to 0L
    }
    BookOverviewCategory.FINISHED -> {
      val lastChapter = book.chapters.last()
      lastChapter.id to lastChapter.duration
    }
  }

  updateBook(book.id) {
    it.copy(
      currentChapter = currentChapter,
      positionInChapter = positionInChapter,
    )
  }
}

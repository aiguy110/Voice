package voice.features.bookOverview.community

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.first
import voice.core.data.BookId
import voice.features.bookOverview.bottomSheet.BottomSheetItem
import voice.features.bookOverview.bottomSheet.BottomSheetItemViewModel
import voice.features.bookOverview.di.BookOverviewScope

@SingleIn(BookOverviewScope::class)
@ContributesIntoSet(BookOverviewScope::class)
class CommunityBottomSheetItemViewModel(libraries: Set<CommunityLibrary>) : BottomSheetItemViewModel {

  private val library = libraries.firstOrNull()

  override suspend fun items(bookId: BookId): List<BottomSheetItem> {
    val state = library?.state?.first() ?: return emptyList()
    return if (bookId in state.shared) {
      listOf(BottomSheetItem.StopSharingWithCommunity)
    } else {
      listOf(BottomSheetItem.ShareWithCommunity)
    }
  }

  override suspend fun onItemClick(
    bookId: BookId,
    item: BottomSheetItem,
  ) {
    when (item) {
      BottomSheetItem.ShareWithCommunity -> library?.share(bookId)
      BottomSheetItem.StopSharingWithCommunity -> library?.stopSharing(bookId)
      else -> Unit
    }
  }
}

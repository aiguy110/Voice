package voice.features.bookOverview.community

import androidx.compose.runtime.Immutable
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Multibinds
import kotlinx.coroutines.flow.Flow
import voice.core.data.BookId

/**
 * A library shared with other people, shown in the overview below the local categories.
 * Contribute an implementation into the [AppScope] set; the overview uses the first one.
 */
interface CommunityLibrary {

  /** Null while the library is not set up, which hides the section and its actions. */
  val state: Flow<CommunityLibraryState?>

  /** Re-fetches the community's books. Called whenever the overview is shown. */
  fun refresh()

  fun share(bookId: BookId)

  fun stopSharing(bookId: BookId)

  fun request(communityBookId: String)

  fun cancelRequest(communityBookId: String)
}

@Immutable
data class CommunityLibraryState(
  val name: String,
  /** Books other people share that are not on this device. */
  val books: List<CommunityBook>,
  /** Local books this device shares. */
  val shared: Set<BookId>,
)

@Immutable
data class CommunityBook(
  val id: String,
  val title: String,
  val author: String?,
  val cover: String?,
  /** One line of status, e.g. who has it or how a request is going. */
  val status: String,
  val requested: Boolean,
)

@ContributesTo(AppScope::class)
interface CommunityLibraryBindings {
  @Multibinds(allowEmpty = true)
  fun communityLibraries(): Set<CommunityLibrary>
}

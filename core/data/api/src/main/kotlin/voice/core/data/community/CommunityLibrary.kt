package voice.core.data.community

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.Flow
import voice.core.data.BookId

/**
 * A library shared with other people, shown in the book overview below the local categories.
 * Contribute an implementation into the AppScope set; the overview uses the first one.
 */
public interface CommunityLibrary {

  /** Null while the library is not set up, which hides the section and its actions. */
  public val state: Flow<CommunityLibraryState?>

  /** Re-fetches the community's books. Called whenever the overview is shown. */
  public fun refresh()

  public fun share(bookId: BookId)

  public fun stopSharing(bookId: BookId)

  public fun request(communityBookId: String)

  public fun cancelRequest(communityBookId: String)
}

@Immutable
public data class CommunityLibraryState(
  val name: String,
  /** Books other people share that are not on this device. */
  val books: List<CommunityBook>,
  /** Local books this device shares. */
  val shared: Set<BookId>,
)

@Immutable
public data class CommunityBook(
  val id: String,
  val title: String,
  val author: String?,
  val cover: String?,
  /** One line of status, e.g. who has it or how a request is going. */
  val status: String,
  val requested: Boolean,
)

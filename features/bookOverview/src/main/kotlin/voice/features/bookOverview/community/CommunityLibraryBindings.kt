package voice.features.bookOverview.community

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Multibinds
import voice.core.data.community.CommunityLibrary

/** Upstream Voice has no community library; this keeps the set injectable when it's empty. */
@ContributesTo(AppScope::class)
interface CommunityLibraryBindings {
  @Multibinds(allowEmpty = true)
  fun communityLibraries(): Set<CommunityLibrary>
}

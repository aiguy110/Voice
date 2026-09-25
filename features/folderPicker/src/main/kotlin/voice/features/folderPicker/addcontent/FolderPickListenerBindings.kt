package voice.features.folderPicker.addcontent

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Multibinds
import voice.core.data.folders.FolderPickListener

/** Upstream Voice has no pick listeners; this keeps the set injectable when it's empty. */
@ContributesTo(AppScope::class)
interface FolderPickListenerBindings {
  @Multibinds(allowEmpty = true)
  fun folderPickListeners(): Set<FolderPickListener>
}

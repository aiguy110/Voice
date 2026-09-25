package voice.core.data.folders

import android.net.Uri

/**
 * Told whenever the user picks a folder or file to add to the library, before Voice decides how to treat it.
 * Contribute implementations into the AppScope set.
 */
public interface FolderPickListener {
  /** [uri] is a SAF tree uri for folders, a document uri for single files. Called on the main thread; don't block. */
  public fun onPicked(uri: Uri)
}

package voice.core.data.folders

import android.net.Uri

public interface PersistedUriPermissions {
  public fun persistedUris(): Set<Uri>

  public fun take(uri: Uri)

  public fun release(uri: Uri)
}

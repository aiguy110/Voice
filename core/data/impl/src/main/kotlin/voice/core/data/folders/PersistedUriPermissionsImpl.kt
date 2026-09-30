package voice.core.data.folders

import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding

@ContributesBinding(AppScope::class)
public class PersistedUriPermissionsImpl(private val context: Context) : PersistedUriPermissions {
  public override fun persistedUris(): Set<Uri> {
    return context.contentResolver.persistedUriPermissions.map { it.uri }.toSet()
  }

  public override fun take(uri: Uri) {
    context.contentResolver.takePersistableUriPermission(
      uri,
      Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
    )
  }

  public override fun release(uri: Uri) {
    context.contentResolver.releasePersistableUriPermission(
      uri,
      Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
    )
  }
}

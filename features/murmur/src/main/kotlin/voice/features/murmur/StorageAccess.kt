package voice.features.murmur

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import java.io.File

/**
 * BitTorrent needs real file paths, but Voice knows books by Storage Access
 * Framework uris. With "all files access" (or the storage permission before
 * Android 11), a uri from the device's own storage maps onto its path.
 */
object StorageAccess {

  fun granted(context: Context): Boolean = if (Build.VERSION.SDK_INT >= 30) {
    Environment.isExternalStorageManager()
  } else {
    ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
  }

  /** Android 11+: the settings page that grants all files access. Earlier versions use a runtime permission. */
  fun settingsIntent(context: Context): Intent? = if (Build.VERSION.SDK_INT >= 30) {
    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, "package:${context.packageName}".toUri())
  } else {
    null
  }

  const val LEGACY_PERMISSION = Manifest.permission.WRITE_EXTERNAL_STORAGE

  /** The file or directory behind a document or tree uri on the device's storage, or null for other providers. */
  fun file(uri: Uri): File? {
    if (uri.scheme == "file") return uri.path?.let(::File)
    if (uri.authority != EXTERNAL_STORAGE) return null
    val segments = uri.pathSegments
    val documentId = when {
      segments.size >= 4 && segments[0] == "tree" && segments[2] == "document" -> segments[3]
      segments.size >= 2 && (segments[0] == "tree" || segments[0] == "document") -> segments[1]
      else -> return null
    }
    val volume = documentId.substringBefore(':')
    val path = documentId.substringAfter(':', "")
    val root = if (volume == "primary") Environment.getExternalStorageDirectory() else File("/storage/$volume")
    return if (path.isEmpty()) root else File(root, path)
  }

  fun file(uri: String): File? = file(uri.toUri())

  private const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"
}

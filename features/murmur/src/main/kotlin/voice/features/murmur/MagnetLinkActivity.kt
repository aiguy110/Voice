package voice.features.murmur

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import voice.core.common.rootGraphAs
import voice.navigation.Destination

/** Receives magnet links from browsers and other apps and opens the Murmur screen to import them. */
class MagnetLinkActivity : Activity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    intent?.dataString?.let { rootGraphAs<MurmurGraph>().magnetImports.lookUp(it) }
    packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
      launch.action = Destination.Murmur.OPEN_ACTION
      launch.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
      startActivity(launch)
    }
    finish()
  }
}

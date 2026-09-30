package voice.features.murmur

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream

@Serializable
data class MurmurSettings(
  val serverUrl: String? = null,
  val username: String? = null,
  val token: String? = null,
  /** SAF tree uri where received books are written. Also registered as a Voice root folder. */
  val downloadFolder: String? = null,
  val allowMetered: Boolean = false,
  /** Share books automatically once they finish downloading (opt-out). */
  val keepSharing: Boolean = true,
  /** Murmur book id -> Voice BookId value, for every book this device shares. */
  val shared: Map<String, String> = emptyMap(),
  /** Books that came from public torrents, by the absolute path of their folder (or file), so they can be seeded there. */
  val imported: Map<String, ImportedBook> = emptyMap(),
  /** Seed every shared and imported book on Wi-Fi; otherwise only books someone is waiting for. */
  val seedOnWifi: Boolean = true,
  /** ...and only while charging. */
  val seedOnlyCharging: Boolean = true,
  /** Send diagnostic reports, like the tree of a folder being added, to the server (opt-in). */
  val reportTelemetry: Boolean = false,
  /** Highest release versionCode the user was notified about. */
  val updateNotified: Long = 0,
  /** Magnet imports still downloading or being moved into the download folder, by info hash. */
  val imports: Map<String, TorrentImport> = emptyMap(),
) {
  val connection: Connection?
    get() = if (serverUrl != null && token != null && username != null) Connection(serverUrl, token, username) else null

  data class Connection(
    val serverUrl: String,
    val token: String,
    val username: String,
  )
}

/**
 * Books chosen from a torrent, downloaded into a hidden folder in the download
 * folder (Voice's scanner skips dot folders), then moved into place.
 */
@Serializable
data class TorrentImport(
  val name: String,
  val magnet: String,
  val books: List<ImportBook>,
)

@Serializable
data class ImportBook(
  val title: String,
  val author: String?,
  /** Audio files, then the cover if there is one. */
  val files: List<ImportFile>,
  /** Absolute path of the book's folder once moved into place. */
  val target: String? = null,
  val done: Boolean = false,
)

@Serializable
data class ImportFile(
  /** Index in the torrent's file list. */
  val index: Int,
  /** Path inside the torrent. */
  val source: String,
  /** Path inside the book's folder. */
  val path: String,
  val size: Long,
  val isCover: Boolean = false,
)

/** Where an imported book's files sit in the public torrent it came from. */
@Serializable
data class ImportedBook(
  val infoHash: String,
  val magnet: String,
  /** Torrent file index -> path inside the book (audio and cover). */
  val files: Map<Int, String>,
)

internal val murmurJson = Json {
  ignoreUnknownKeys = true
  encodeDefaults = true
}

@ContributesTo(AppScope::class)
interface MurmurSettingsModule {

  @Provides
  @SingleIn(AppScope::class)
  fun murmurSettingsStore(context: Context): DataStore<MurmurSettings> = DataStoreFactory.create(
    serializer = object : Serializer<MurmurSettings> {
      override val defaultValue = MurmurSettings()

      override suspend fun readFrom(input: InputStream): MurmurSettings = try {
        murmurJson.decodeFromString(MurmurSettings.serializer(), input.readBytes().decodeToString())
      } catch (e: SerializationException) {
        throw CorruptionException("Unable to read murmur settings", e)
      }

      override suspend fun writeTo(
        t: MurmurSettings,
        output: OutputStream,
      ) {
        output.write(murmurJson.encodeToString(MurmurSettings.serializer(), t).encodeToByteArray())
      }
    },
  ) {
    File(context.filesDir, "datastore/murmurSettings")
  }
}

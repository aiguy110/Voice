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
  /** Murmur book id -> document uri a download is being written to. */
  val downloading: Map<String, String> = emptyMap(),
  /** Highest release versionCode the user was notified about. */
  val updateNotified: Long = 0,
) {
  val connection: Connection?
    get() = if (serverUrl != null && token != null && username != null) Connection(serverUrl, token, username) else null

  data class Connection(
    val serverUrl: String,
    val token: String,
    val username: String,
  )
}

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

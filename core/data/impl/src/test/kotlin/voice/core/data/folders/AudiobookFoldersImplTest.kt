package voice.core.data.folders

import android.net.Uri
import android.os.Looper
import androidx.datastore.core.DataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import voice.core.analytics.api.Analytics
import voice.core.data.repo.internals.MemoryDataStore
import voice.core.documentfile.CachedDocumentFile
import voice.core.documentfile.CachedDocumentFileFactory

@RunWith(RobolectricTestRunner::class)
class AudiobookFoldersImplTest {

  @Test
  fun `removing duplicate uri keeps permission until last folder mode is removed`() = runTest {
    val permissions = FakePersistedUriPermissions()
    val stores = FolderType.entries.associateWith { MemoryDataStore<Set<Uri>>(emptySet()) }
    val folders = AudiobookFoldersImpl(
      rootAudioBookFoldersStore = stores.getValue(FolderType.Root),
      singleFolderAudiobookFoldersStore = stores.getValue(FolderType.SingleFolder),
      singleFileAudiobookFoldersStore = stores.getValue(FolderType.SingleFile),
      authorAudiobookFoldersStore = stores.getValue(FolderType.Author),
      smartAudiobookFoldersStore = stores.getValue(FolderType.Smart),
      cachedDocumentFileFactory = UnusedDocumentFileFactory,
      analytics = NoOpAnalytics,
      persistedUriPermissions = permissions,
    )
    val uri = Uri.parse("content://documents/tree/Audiobooks")

    folders.add(uri, FolderType.Root)
    folders.add(uri, FolderType.Smart)
    idleMainLooper()

    folders.remove(uri, FolderType.Root)
    idleMainLooper()

    assertEquals(emptyList<Uri>(), permissions.released)
    assertEquals(setOf(uri), stores.getValue(FolderType.Smart).current())

    folders.remove(uri, FolderType.Smart)
    idleMainLooper()

    assertEquals(listOf(uri), permissions.released)
  }

  private fun idleMainLooper() {
    shadowOf(Looper.getMainLooper()).idle()
  }

  private suspend fun DataStore<Set<Uri>>.current(): Set<Uri> = data.first()

  private class FakePersistedUriPermissions : PersistedUriPermissions {
    val released = mutableListOf<Uri>()
    private val persisted = mutableSetOf<Uri>()

    override fun persistedUris(): Set<Uri> = persisted

    override fun take(uri: Uri) {
      persisted += uri
    }

    override fun release(uri: Uri) {
      persisted -= uri
      released += uri
    }
  }

  private object NoOpAnalytics : Analytics {
    override fun screenView(screenName: String) = Unit

    override fun event(
      name: String,
      params: Map<String, String>,
    ) = Unit
  }

  private object UnusedDocumentFileFactory : CachedDocumentFileFactory {
    override fun create(uri: Uri): CachedDocumentFile = error("Not used")
  }
}

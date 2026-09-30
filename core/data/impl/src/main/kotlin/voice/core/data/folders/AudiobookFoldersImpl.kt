package voice.core.data.folders

import android.net.Uri
import android.provider.DocumentsContract
import androidx.datastore.core.DataStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import voice.core.analytics.api.Analytics
import voice.core.documentfile.CachedDocumentFile
import voice.core.documentfile.CachedDocumentFileFactory
import voice.core.logging.api.Logger

@ContributesBinding(AppScope::class)
public class AudiobookFoldersImpl
internal constructor(
  @RootAudiobookFoldersStore
  private val rootAudioBookFoldersStore: DataStore<Set<@JvmSuppressWildcards Uri>>,
  @SingleFolderAudiobookFoldersStore
  private val singleFolderAudiobookFoldersStore: DataStore<Set<@JvmSuppressWildcards Uri>>,
  @SingleFileAudiobookFoldersStore
  private val singleFileAudiobookFoldersStore: DataStore<Set<@JvmSuppressWildcards Uri>>,
  @AuthorAudiobookFoldersStore
  private val authorAudiobookFoldersStore: DataStore<Set<@JvmSuppressWildcards Uri>>,
  @SmartAudiobookFoldersStore
  private val smartAudiobookFoldersStore: DataStore<Set<@JvmSuppressWildcards Uri>>,
  private val cachedDocumentFileFactory: CachedDocumentFileFactory,
  private val analytics: Analytics,
  private val persistedUriPermissions: PersistedUriPermissions,
) : AudiobookFolders {

  private val scope = MainScope()
  private val mutationMutex = Mutex()

  public override fun all(): Flow<Map<FolderType, List<DocumentFileWithUri>>> {
    val flows = FolderType.entries
      .map { folderType ->
        dataStore(folderType).data.map { uris ->
          val persistedUris = persistedUriPermissions.persistedUris()
          val documentFiles = uris
            .filter {
              it in persistedUris
            }
            .map { uri ->
              DocumentFileWithUri(
                documentFile = uri.toDocumentFile(folderType),
                uri = uri,
              )
            }
          folderType to documentFiles
        }
      }
    return combine(flows) { it.toMap() }
  }

  private fun Uri.toDocumentFile(folderType: FolderType): CachedDocumentFile {
    val uri = when (folderType) {
      FolderType.SingleFile -> this
      FolderType.SingleFolder,
      FolderType.Root,
      FolderType.Author,
      FolderType.Smart,
      -> {
        DocumentsContract.buildDocumentUriUsingTree(
          this,
          DocumentsContract.getTreeDocumentId(this),
        )
      }
    }
    return cachedDocumentFileFactory.create(uri)
  }

  public override fun add(
    uri: Uri,
    type: FolderType,
  ) {
    analytics.event("add_folder", mapOf("type" to type.name))
    try {
      persistedUriPermissions.take(uri)
    } catch (_: SecurityException) {
      Logger.w("Could not persist uri permission for $uri")
    }
    scope.launch {
      mutationMutex.withLock {
        dataStore(type).updateData {
          it + uri
        }
      }
    }
  }

  public override fun remove(
    uri: Uri,
    folderType: FolderType,
  ) {
    analytics.event("remove_folder", mapOf("type" to folderType.name))
    scope.launch {
      mutationMutex.withLock {
        dataStore(folderType).updateData { folders ->
          folders - uri
        }
        val stillUsed = FolderType.entries.any { type ->
          uri in dataStore(type).data.first()
        }
        if (!stillUsed) {
          try {
            persistedUriPermissions.release(uri)
          } catch (_: SecurityException) {
            Logger.w("Could not release uri permission for $uri")
          }
        }
      }
    }
  }

  private fun dataStore(type: FolderType): DataStore<Set<Uri>> {
    return when (type) {
      FolderType.SingleFile -> singleFileAudiobookFoldersStore
      FolderType.SingleFolder -> singleFolderAudiobookFoldersStore
      FolderType.Root -> rootAudioBookFoldersStore
      FolderType.Author -> authorAudiobookFoldersStore
      FolderType.Smart -> smartAudiobookFoldersStore
    }
  }

  public override suspend fun hasAnyFolders(): Boolean {
    return FolderType.entries.any {
      dataStore(it).data.first().isNotEmpty()
    }
  }
}

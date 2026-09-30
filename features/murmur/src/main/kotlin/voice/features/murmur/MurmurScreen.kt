package voice.features.murmur

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.retain.retain
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import voice.core.common.rootGraphAs
import voice.core.ui.icons.VoiceIcons
import voice.navigation.Destination
import voice.navigation.NavEntryProvider

@ContributesTo(AppScope::class)
interface MurmurProvider {

  @Provides
  @IntoSet
  fun murmurNavEntryProvider(): NavEntryProvider<*> = NavEntryProvider<Destination.Murmur> { key ->
    NavEntry(key) {
      MurmurScreen()
    }
  }
}

@Composable
fun MurmurScreen(modifier: Modifier = Modifier) {
  val viewModel = retain { rootGraphAs<MurmurGraph>().murmurViewModel }
  val viewState = viewModel.viewState()
  val connected = viewState.connection != null
  LaunchedEffect(connected) {
    if (connected) viewModel.refresh()
  }
  val requestNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
  LaunchedEffect(Unit) {
    // Update notifications need this; Voice itself only posts the exempt media notification.
    if (Build.VERSION.SDK_INT >= 33 && viewState.installedVersion != null) {
      requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
  }
  Scaffold(
    modifier = modifier,
    topBar = {
      TopAppBar(
        title = { Text("Murmur") },
        navigationIcon = {
          IconButton(onClick = viewModel::close) {
            Icon(VoiceIcons.Close, contentDescription = "Close")
          }
        },
        actions = {
          if (connected) {
            TextButton(onClick = viewModel::refresh) { Text("Refresh") }
          }
        },
      )
    },
    floatingActionButton = {
      if (connected) {
        ExtendedFloatingActionButton(
          onClick = viewModel::openSharePicker,
          icon = { Icon(VoiceIcons.Add, contentDescription = null) },
          text = { Text("Share a book") },
        )
      }
    },
  ) { padding ->
    Column(Modifier.padding(padding).fillMaxSize()) {
      if (viewState.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
      if (connected) {
        Library(viewState, viewModel)
      } else {
        JoinForm(viewState.settings.serverUrl, viewModel::join)
        SabpImportRow(viewModel::importSabp)
        VersionRow(viewState, viewModel::checkForUpdate)
      }
    }
  }

  viewState.magnet?.let { MagnetDialog(it, viewModel) }

  viewState.error?.let { error ->
    AlertDialog(
      onDismissRequest = viewModel::dismissError,
      confirmButton = { TextButton(onClick = viewModel::dismissError) { Text("OK") } },
      title = { Text("Murmur") },
      text = { Text(error) },
    )
  }
  viewState.message?.let { message ->
    AlertDialog(
      onDismissRequest = viewModel::dismissMessage,
      confirmButton = { TextButton(onClick = viewModel::dismissMessage) { Text("OK") } },
      title = { Text("Murmur") },
      text = { Text(message) },
    )
  }
  if (viewState.showSharePicker) {
    AlertDialog(
      onDismissRequest = viewModel::dismissSharePicker,
      confirmButton = { TextButton(onClick = viewModel::dismissSharePicker) { Text("Cancel") } },
      title = { Text("Share a book") },
      text = {
        if (viewState.shareableBooks.isEmpty()) {
          Text("Every book in your library is already shared.")
        } else {
          LazyColumn {
            items(viewState.shareableBooks, key = { it.id.value }) { book ->
              ListItem(
                modifier = Modifier.clickable { viewModel.share(book) },
                supportingContent = book.content.author?.let { { Text(it) } },
              ) { Text(book.content.name) }
            }
          }
        }
      },
    )
  }
}

@Composable
private fun JoinForm(
  savedServerUrl: String?,
  onJoin: (serverUrl: String) -> Unit,
) {
  var serverUrl by remember { mutableStateOf(savedServerUrl.orEmpty()) }
  Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(
      "Murmur shares audiobooks with a small community. Books you share stay on your phone; " +
        "when someone asks for one, your phone sends it to them over BitTorrent, and the community server keeps a copy. " +
        "Your Tailscale account is your Murmur account, so joining from another device picks up where you left off.",
    )
    OutlinedTextField(
      value = serverUrl,
      onValueChange = { serverUrl = it },
      label = { Text("Server URL") },
      singleLine = true,
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
      modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = { onJoin(serverUrl) }, enabled = serverUrl.isNotBlank()) {
      Text("Join")
    }
  }
}

@Composable
private fun Library(
  viewState: MurmurViewState,
  viewModel: MurmurViewModel,
) {
  val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
    if (uri != null) viewModel.setDownloadFolder(uri)
  }
  val settings = viewState.settings
  var confirmLeave by remember { mutableStateOf(false) }
  if (confirmLeave) {
    AlertDialog(
      onDismissRequest = { confirmLeave = false },
      confirmButton = {
        TextButton(onClick = {
          confirmLeave = false
          viewModel.leave()
        }) { Text("Leave") }
      },
      dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Stay") } },
      title = { Text("Leave this community?") },
      text = { Text("Your account stays on the server. Join again from the same Tailscale account to pick it back up.") },
    )
  }
  LazyColumn(Modifier.fillMaxSize()) {
    item { FileAccessRow() }
    item {
      ListItem(
        modifier = Modifier.clickable { pickFolder.launch(null) },
        leadingContent = { Icon(VoiceIcons.Folder, contentDescription = null) },
        supportingContent = {
          Text(settings.downloadFolder?.let { Uri.decode(it).substringAfterLast(':') } ?: "Choose where received books are saved")
        },
      ) { Text("Download folder") }
    }
    item {
      ListItem(
        modifier = Modifier.clickable(onClick = viewModel::openMagnet),
        leadingContent = { Icon(VoiceIcons.Add, contentDescription = null) },
        supportingContent = { Text("Download audiobooks from a torrent into the download folder") },
      ) { Text("Add from magnet link") }
    }
    items(settings.imports.entries.toList(), key = { it.key }) { (hash, import) ->
      ImportRow(import, viewState.importProgress[hash]) { viewModel.cancelImport(hash) }
    }
    item {
      SwitchRow("Use mobile data", "Send and receive books when not on Wi-Fi", settings.allowMetered, viewModel::setAllowMetered)
    }
    item {
      SwitchRow("Share books I receive", "Offer downloaded books to others automatically", settings.keepSharing, viewModel::setKeepSharing)
    }
    item {
      SwitchRow(
        "Seed on Wi-Fi",
        "Keep your shared and imported books available to others. Books someone is waiting for are always sent.",
        settings.seedOnWifi,
        viewModel::setSeedOnWifi,
      )
    }
    if (settings.seedOnWifi) {
      item {
        SwitchRow("Only while charging", "Seed only while the phone is charging", settings.seedOnlyCharging, viewModel::setSeedOnlyCharging)
      }
    }
    item {
      SwitchRow(
        "Report app telemetry to Murmur server",
        "Send the folder and file names of folders you add, to help diagnose library problems",
        settings.reportTelemetry,
        viewModel::setReportTelemetry,
      )
    }
    item { SabpImportRow(viewModel::importSabp) }
    item { VersionRow(viewState, viewModel::checkForUpdate) }
    item {
      ListItem(
        modifier = Modifier.clickable { confirmLeave = true },
        leadingContent = { Icon(VoiceIcons.Person, contentDescription = null) },
        supportingContent = { Text("${viewState.connection?.serverUrl} · tap to leave") },
      ) { Text("Signed in as ${viewState.connection?.username}") }
    }
    item {
      val uriHandler = LocalUriHandler.current
      ListItem(
        modifier = Modifier.clickable { viewState.connection?.let { uriHandler.openUri(it.serverUrl + "/account") } },
        leadingContent = { Icon(VoiceIcons.Language, contentDescription = null) },
        supportingContent = { Text("Choose the name others see, on the server's web page") },
      ) { Text("Change your name") }
    }
    item { Spacer(Modifier.padding(40.dp)) }
  }
}

/** BitTorrent reads and writes books by path, which needs all files access (the storage permission before Android 11). */
@Composable
private fun FileAccessRow() {
  val context = LocalContext.current
  var granted by remember { mutableStateOf(StorageAccess.granted(context)) }
  val openSettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
    granted = StorageAccess.granted(context)
  }
  val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
    granted = StorageAccess.granted(context)
  }
  if (granted) return
  ListItem(
    modifier = Modifier.clickable {
      val intent = StorageAccess.settingsIntent(context)
      if (intent != null) openSettings.launch(intent) else requestPermission.launch(StorageAccess.LEGACY_PERMISSION)
    },
    leadingContent = { Icon(VoiceIcons.Folder, contentDescription = null) },
    supportingContent = { Text("Needed to download and share books. Tap to allow.") },
  ) { Text("Allow file access") }
}

@Composable
private fun ImportRow(
  import: TorrentImport,
  progress: Float?,
  onCancel: () -> Unit,
) {
  var confirmCancel by remember { mutableStateOf(false) }
  if (confirmCancel) {
    AlertDialog(
      onDismissRequest = { confirmCancel = false },
      confirmButton = {
        TextButton(onClick = {
          confirmCancel = false
          onCancel()
        }) { Text("Stop import") }
      },
      dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text("Keep going") } },
      title = { Text("Stop importing ${import.name}?") },
      text = { Text("What has downloaded so far is deleted. Books already in your library stay.") },
    )
  }
  val books = import.books.size
  Column(Modifier.clickable { confirmCancel = true }) {
    ListItem(
      leadingContent = { Icon(VoiceIcons.Download, contentDescription = null) },
      supportingContent = {
        val status = if (progress == null) "Waiting to download" else "Downloading, ${(progress * 100).toInt()}%"
        Text("$status · $books book${if (books == 1) "" else "s"} · tap to stop")
      },
    ) { Text(import.name) }
    val modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
    if (progress == null) LinearProgressIndicator(modifier) else LinearProgressIndicator(progress = { progress }, modifier = modifier)
  }
}

@Composable
private fun MagnetDialog(
  state: MagnetState,
  viewModel: MurmurViewModel,
) {
  val plan = state.plan
  if (plan == null) {
    var link by remember { mutableStateOf("") }
    AlertDialog(
      onDismissRequest = viewModel::dismissMagnet,
      confirmButton = {
        TextButton(onClick = { viewModel.lookUpMagnet(link) }, enabled = !state.lookingUp && link.isNotBlank()) { Text("Look up") }
      },
      dismissButton = { TextButton(onClick = viewModel::dismissMagnet) { Text("Cancel") } },
      title = { Text("Add from magnet link") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
          if (state.lookingUp) {
            Text("Asking the swarm for the torrent's file list. This can take a minute.")
            LinearProgressIndicator(Modifier.fillMaxWidth())
          } else {
            state.error?.let { Text(it) }
            OutlinedTextField(
              value = link,
              onValueChange = { link = it },
              label = { Text("magnet:?xt=…") },
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
              modifier = Modifier.fillMaxWidth(),
            )
          }
        }
      },
    )
    return
  }
  AlertDialog(
    onDismissRequest = viewModel::dismissMagnet,
    confirmButton = {
      TextButton(onClick = viewModel::startMagnetImport, enabled = state.selected.isNotEmpty()) {
        Text("Import ${state.selected.size}")
      }
    },
    dismissButton = { TextButton(onClick = viewModel::dismissMagnet) { Text("Cancel") } },
    title = { Text(plan.name) },
    text = {
      LazyColumn {
        itemsIndexed(plan.books) { index, book ->
          ListItem(
            modifier = Modifier.clickable { viewModel.toggleMagnetBook(index) },
            leadingContent = { Checkbox(checked = index in state.selected, onCheckedChange = { viewModel.toggleMagnetBook(index) }) },
            supportingContent = {
              val files = book.audio.size
              val details = listOfNotNull(
                book.author,
                book.narrator?.let { "read by $it" },
                "$files file${if (files == 1) "" else "s"}",
                formatSize(book.size),
              ).joinToString(" · ")
              Text((listOf(details) + book.notes).joinToString("\n"))
            },
          ) { Text(book.title) }
        }
      }
    },
  )
}

private fun formatSize(bytes: Long): String = when {
  bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1e9)
  bytes >= 1_000_000 -> "${bytes / 1_000_000} MB"
  else -> "${bytes / 1_000} KB"
}

/** Checks for, downloads, and installs updates, all from the same spot. */
@Composable
private fun VersionRow(
  viewState: MurmurViewState,
  onCheck: () -> Unit,
) {
  val installed = viewState.installedVersion ?: return
  val context = LocalContext.current
  val install = { context.startActivity(MurmurUpdater.updateActivityIntent(context)) }
  val update = viewState.update
  val download = viewState.updateDownload
  Column {
    when {
      update == null -> ListItem(
        modifier = Modifier.clickable(onClick = onCheck),
        leadingContent = { Icon(VoiceIcons.Download, contentDescription = null) },
        supportingContent = { Text("Tap to check for updates") },
      ) { Text("App version murmur-$installed") }
      download is UpdateDownload.Running -> ListItem(
        leadingContent = { Icon(VoiceIcons.Download, contentDescription = null) },
        supportingContent = { Text("Downloading…") },
      ) { Text("Update available: ${update.tag}") }
      download == UpdateDownload.Done -> ListItem(
        modifier = Modifier.clickable(onClick = install),
        leadingContent = { Icon(VoiceIcons.Download, contentDescription = null) },
        supportingContent = { Text("Tap to install") },
      ) { Text("Update downloaded: ${update.tag}") }
      else -> ListItem(
        modifier = Modifier.clickable(onClick = install),
        leadingContent = { Icon(VoiceIcons.Download, contentDescription = null) },
        supportingContent = { Text("Tap to download and install") },
      ) { Text("Update available: ${update.tag}") }
    }
    if (update != null && download is UpdateDownload.Running) {
      val fraction = download.fraction
      val modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
      if (fraction == null) LinearProgressIndicator(modifier) else LinearProgressIndicator(progress = { fraction }, modifier = modifier)
    }
  }
}

@Composable
private fun SabpImportRow(onClick: () -> Unit) {
  ListItem(
    modifier = Modifier.clickable(onClick = onClick),
    leadingContent = { Icon(VoiceIcons.History, contentDescription = null) },
    supportingContent = { Text("Copy finished books and positions for books not started here yet") },
  ) { Text("Import Smart AudioBook Player progress") }
}

@Composable
private fun SwitchRow(
  title: String,
  summary: String,
  checked: Boolean,
  onChange: (Boolean) -> Unit,
) {
  ListItem(
    modifier = Modifier.clickable { onChange(!checked) },
    supportingContent = { Text(summary) },
    trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
  ) { Text(title) }
}

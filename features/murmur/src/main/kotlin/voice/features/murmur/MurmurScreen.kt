package voice.features.murmur

import android.Manifest
import android.net.Uri
import android.os.Build
import android.text.format.Formatter
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
      viewState.update?.let { UpdateAvailableRow(it) }
      if (connected) {
        Library(viewState, viewModel)
      } else {
        JoinForm(viewState.settings.serverUrl, viewModel::join)
        SabpImportRow(viewModel::importSabp)
        VersionRow(viewState, viewModel::checkForUpdate)
      }
    }
  }

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
  onJoin: (serverUrl: String, username: String) -> Unit,
) {
  var serverUrl by remember { mutableStateOf(savedServerUrl.orEmpty()) }
  var username by remember { mutableStateOf("") }
  Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(
      "Murmur shares audiobooks with a small community. Books you share stay on your phone; " +
        "when someone asks for one, your phone sends it through the community server. " +
        "Already have a username here? Enter it to get your account back.",
    )
    OutlinedTextField(
      value = serverUrl,
      onValueChange = { serverUrl = it },
      label = { Text("Server URL") },
      singleLine = true,
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
      modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
      value = username,
      onValueChange = { username = it },
      label = { Text("Username") },
      singleLine = true,
      modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = { onJoin(serverUrl, username) }, enabled = username.isNotBlank()) {
      Text("Join")
    }
  }
}

@Composable
private fun Library(
  viewState: MurmurViewState,
  viewModel: MurmurViewModel,
) {
  val context = LocalContext.current
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
      text = { Text("To get your username back later, join again with it from the same Tailscale account.") },
    )
  }
  LazyColumn(Modifier.fillMaxSize()) {
    if (viewState.library.isEmpty()) {
      item { ListItem { Text("Nobody has shared a book yet.") } }
    }
    items(viewState.library, key = { it.id }) { book ->
      ListItem(
        supportingContent = {
          Column {
            if (book.author.isNotBlank()) Text(book.author)
            Text(
              buildString {
                append(Formatter.formatShortFileSize(context, book.size))
                if (book.holders.isNotEmpty()) append(" · held by ${book.holders.joinToString()}")
                if (book.wanters.isNotEmpty()) append(" · wanted by ${book.wanters.joinToString()}")
              },
              style = MaterialTheme.typography.bodySmall,
            )
          }
        },
        trailingContent = {
          when {
            book.holding -> OutlinedButton(onClick = { viewModel.stopSharing(book) }) { Text("Unshare") }
            book.wanting -> OutlinedButton(onClick = { viewModel.cancelRequest(book) }) { Text("Cancel") }
            else -> Button(onClick = { viewModel.request(book) }) { Text("Request") }
          }
        },
      ) { Text(book.title) }
    }

    item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
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
      SwitchRow("Use mobile data", "Send and receive books when not on Wi-Fi", settings.allowMetered, viewModel::setAllowMetered)
    }
    item {
      SwitchRow("Share books I receive", "Offer downloaded books to others automatically", settings.keepSharing, viewModel::setKeepSharing)
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
    item { Spacer(Modifier.padding(40.dp)) }
  }
}

@Composable
private fun UpdateAvailableRow(release: MurmurRelease) {
  val context = LocalContext.current
  ListItem(
    modifier = Modifier.clickable { context.startActivity(MurmurUpdater.updateActivityIntent(context)) },
    leadingContent = { Icon(VoiceIcons.Download, contentDescription = null) },
    supportingContent = { Text("Tap to download and install") },
  ) { Text("Update available: ${release.tag}") }
}

@Composable
private fun VersionRow(
  viewState: MurmurViewState,
  onCheck: () -> Unit,
) {
  val installed = viewState.installedVersion ?: return
  if (viewState.update != null) return
  ListItem(
    modifier = Modifier.clickable(onClick = onCheck),
    leadingContent = { Icon(VoiceIcons.Download, contentDescription = null) },
    supportingContent = { Text("Tap to check for updates") },
  ) { Text("App version murmur-$installed") }
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

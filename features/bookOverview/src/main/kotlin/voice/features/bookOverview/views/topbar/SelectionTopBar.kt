package voice.features.bookOverview.views.topbar

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import voice.core.ui.VoiceTheme
import voice.core.ui.icons.VoiceIcons
import voice.features.bookOverview.overview.BookOverviewCategory
import voice.core.strings.R as StringsR

@Composable
internal fun SelectionTopBar(
  selectedCount: Int,
  onClose: () -> Unit,
  onSelectAll: () -> Unit,
  onMoveToCategory: (BookOverviewCategory) -> Unit,
) {
  TopAppBar(
    title = {
      Text(pluralStringResource(StringsR.plurals.book_selection_count, selectedCount, selectedCount))
    },
    navigationIcon = {
      IconButton(onClick = onClose) {
        Icon(
          imageVector = VoiceIcons.Close,
          contentDescription = stringResource(StringsR.string.common_action_close),
        )
      }
    },
    actions = {
      IconButton(onClick = { onMoveToCategory(BookOverviewCategory.FINISHED) }) {
        Icon(
          imageVector = VoiceIcons.Done,
          contentDescription = stringResource(StringsR.string.book_category_action_mark_completed),
        )
      }
      Box {
        var expanded by remember { mutableStateOf(false) }
        IconButton(onClick = { expanded = !expanded }) {
          Icon(
            imageVector = VoiceIcons.MoreVert,
            contentDescription = stringResource(StringsR.string.common_action_more),
          )
        }
        DropdownMenu(
          expanded = expanded,
          onDismissRequest = { expanded = false },
        ) {
          DropdownMenuItem(
            text = { Text(stringResource(StringsR.string.book_selection_action_select_all)) },
            onClick = {
              expanded = false
              onSelectAll()
            },
          )
          DropdownMenuItem(
            text = { Text(stringResource(StringsR.string.book_category_action_mark_completed)) },
            leadingIcon = { Icon(VoiceIcons.Done, contentDescription = null) },
            onClick = {
              expanded = false
              onMoveToCategory(BookOverviewCategory.FINISHED)
            },
          )
          DropdownMenuItem(
            text = { Text(stringResource(StringsR.string.book_category_action_mark_current)) },
            leadingIcon = { Icon(VoiceIcons.NotStarted, contentDescription = null) },
            onClick = {
              expanded = false
              onMoveToCategory(BookOverviewCategory.CURRENT)
            },
          )
          DropdownMenuItem(
            text = { Text(stringResource(StringsR.string.book_category_action_mark_not_started)) },
            leadingIcon = { Icon(VoiceIcons.HourglassEmpty, contentDescription = null) },
            onClick = {
              expanded = false
              onMoveToCategory(BookOverviewCategory.NOT_STARTED)
            },
          )
        }
      }
    },
  )
}

@Composable
@Preview
private fun SelectionTopBarPreview() {
  VoiceTheme {
    SelectionTopBar(
      selectedCount = 3,
      onClose = {},
      onSelectAll = {},
      onMoveToCategory = {},
    )
  }
}

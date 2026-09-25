package voice.features.bookOverview.views

import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import voice.core.data.BookId

@Composable
internal fun BookCard(
  bookId: BookId,
  selected: Boolean,
  onBookClick: (BookId) -> Unit,
  onBookLongClick: (BookId) -> Unit,
  modifier: Modifier = Modifier,
  shared: Boolean = false,
  content: @Composable () -> Unit,
) {
  val shape = MaterialTheme.shapes.extraLarge
  ElevatedCard(
    shape = shape,
    colors = if (selected) {
      CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    } else {
      CardDefaults.elevatedCardColors()
    },
    modifier = modifier
      .fillMaxWidth()
      .then(
        if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape) else Modifier,
      )
      .combinedClickable(
        onClick = { onBookClick(bookId) },
        onLongClick = { onBookLongClick(bookId) },
      ),
  ) {
    Box {
      content()
      if (shared) {
        SharedBadge(Modifier.align(Alignment.TopEnd))
      }
    }
  }
}

@Composable
internal fun BookRemainingProgressRow(
  remainingTime: String,
  progress: Float,
  modifier: Modifier = Modifier,
  remainingTimeMaxLines: Int = Int.MAX_VALUE,
  progressMaxLines: Int = Int.MAX_VALUE,
) {
  Row(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = remainingTime,
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = remainingTimeMaxLines,
    )
    if (progress > 0f) {
      Text(
        text = "${(progress * 100).toInt()}%",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = progressMaxLines,
      )
    }
  }
}

@Composable
internal fun BookProgressIndicator(
  progress: Float,
  modifier: Modifier = Modifier,
  color: Color? = null,
  trackColor: Color? = null,
) {
  if (progress > 0.05f) {
    if (color != null && trackColor != null) {
      LinearProgressIndicator(
        progress = { progress },
        modifier = modifier,
        color = color,
        trackColor = trackColor,
      )
    } else {
      LinearProgressIndicator(
        progress = { progress },
        modifier = modifier,
      )
    }
  }
}

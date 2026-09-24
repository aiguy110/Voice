package voice.features.bookOverview.views

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.toUpperCase
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import voice.core.ui.icons.VoiceIcons
import voice.features.bookOverview.community.CommunityBook
import voice.core.strings.R as StringsR
import voice.core.ui.R as UiR

/** Marks a local book that is shared with the community library. */
@Composable
internal fun SharedBadge(modifier: Modifier = Modifier) {
  Box(
    modifier = modifier
      .padding(6.dp)
      .size(24.dp)
      .clip(CircleShape)
      .background(MaterialTheme.colorScheme.primaryContainer),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      imageVector = VoiceIcons.Share,
      contentDescription = stringResource(StringsR.string.book_community_shared),
      tint = MaterialTheme.colorScheme.onPrimaryContainer,
      modifier = Modifier.size(16.dp),
    )
  }
}

@Composable
private fun CommunityBookCard(
  book: CommunityBook,
  onClick: (CommunityBook) -> Unit,
  content: @Composable () -> Unit,
) {
  ElevatedCard(
    shape = MaterialTheme.shapes.extraLarge,
    modifier = Modifier
      .fillMaxWidth()
      .combinedClickable(
        onClick = { onClick(book) },
        onLongClick = { onClick(book) },
      ),
  ) {
    content()
  }
}

@Composable
private fun CommunityBookStatus(
  book: CommunityBook,
  maxLines: Int,
) {
  Text(
    text = book.status,
    style = MaterialTheme.typography.labelMedium,
    color = if (book.requested) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    maxLines = maxLines,
    overflow = TextOverflow.Ellipsis,
  )
}

@Composable
internal fun ListCommunityBookRow(
  book: CommunityBook,
  onClick: (CommunityBook) -> Unit,
) {
  CommunityBookCard(book, onClick) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      AsyncImage(
        modifier = Modifier
          .padding(top = 8.dp, start = 8.dp, bottom = 8.dp)
          .size(76.dp)
          .clip(RoundedCornerShape(16.dp)),
        model = book.cover,
        placeholder = painterResource(id = UiR.drawable.album_art),
        error = painterResource(id = UiR.drawable.album_art),
        contentScale = ContentScale.Crop,
        contentDescription = null,
      )
      Column(
        Modifier
          .padding(start = 12.dp, end = 12.dp)
          .weight(1f),
      ) {
        if (book.author != null) {
          Text(
            text = book.author.toUpperCase(LocaleList.current),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
          )
        }
        Text(
          text = book.title,
          style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 2,
        )
        CommunityBookStatus(book, maxLines = 1)
      }
    }
  }
}

@Composable
internal fun GridCommunityBook(
  book: CommunityBook,
  onClick: (CommunityBook) -> Unit,
) {
  CommunityBookCard(book, onClick) {
    Column(
      modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
    ) {
      AsyncImage(
        modifier = Modifier
          .fillMaxWidth()
          .aspectRatio(4f / 3f)
          .clip(MaterialTheme.shapes.large)
          .background(MaterialTheme.colorScheme.surfaceVariant),
        contentScale = ContentScale.Crop,
        model = book.cover,
        placeholder = painterResource(id = UiR.drawable.album_art),
        error = painterResource(id = UiR.drawable.album_art),
        contentDescription = null,
      )
      Spacer(Modifier.height(4.dp))
      Text(
        text = book.title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
      )
      CommunityBookStatus(book, maxLines = 2)
    }
  }
}

@Composable
internal fun CommunityBookSheetContent(
  book: CommunityBook,
  onRequest: () -> Unit,
  onCancelRequest: () -> Unit,
) {
  Column {
    Text(
      text = book.title,
      style = MaterialTheme.typography.titleMedium,
      modifier = Modifier.padding(horizontal = 16.dp),
    )
    Text(
      text = book.status,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
    )
    val (title, icon, onClick) = if (book.requested) {
      Triple(StringsR.string.book_community_action_request_cancel, VoiceIcons.Close, onCancelRequest)
    } else {
      Triple(StringsR.string.book_community_action_request, VoiceIcons.Download, onRequest)
    }
    ListItem(
      colors = ListItemDefaults.colors(containerColor = BottomSheetDefaults.ContainerColor),
      modifier = Modifier.clickable(onClick = onClick),
      leadingContent = { Icon(imageVector = icon, contentDescription = null) },
    ) {
      Text(text = stringResource(title))
    }
    Spacer(modifier = Modifier.size(24.dp))
  }
}

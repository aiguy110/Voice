package voice.features.bookOverview.views

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import voice.features.bookOverview.overview.BookOverviewCategory

@Composable
internal fun Header(
  category: BookOverviewCategory,
  modifier: Modifier = Modifier,
) {
  Header(text = stringResource(id = category.nameRes), modifier = modifier)
}

@Composable
internal fun Header(
  text: String,
  modifier: Modifier = Modifier,
) {
  Text(
    modifier = modifier,
    text = text,
    style = MaterialTheme.typography.headlineSmall,
  )
}

package voice.features.settings.views.sleeptimer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import voice.core.ui.VoiceTheme
import voice.features.settings.SettingsListener
import voice.features.settings.SettingsViewState
import voice.core.strings.R as StringsR

@Composable
internal fun AutoSleepTimerCard(
  viewState: SettingsViewState.AutoSleepTimerViewState,
  listener: SettingsListener,
) {
  OutlinedCard(modifier = Modifier.padding(horizontal = 8.dp, vertical = 16.dp)) {
    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
      AutoSleepTimerRow(
        autoSleepTimer = viewState.enabled,
        start = viewState.startTime,
        end = viewState.endTime,
        toggleAutoSleepTimer = listener::setAutoSleepTimer,
      )
      Row(Modifier.padding(start = 36.dp)) {
        AutoSleepTimerSetting(
          time = viewState.startTime,
          label = stringResource(StringsR.string.settings_auto_sleep_timer_start_label),
          enabled = viewState.enabled,
          setAutoSleepTime = listener::setAutoSleepTimerStart,
        )
        AutoSleepTimerSetting(
          time = viewState.endTime,
          label = stringResource(StringsR.string.settings_auto_sleep_timer_end_label),
          enabled = viewState.enabled,
          setAutoSleepTime = listener::setAutoSleepTimerEnd,
        )
      }
      val textAlpha = if (viewState.enabled) 1f else 0.38f
      ListItem(
        modifier = Modifier
          .padding(start = 36.dp)
          .clickable(enabled = viewState.enabled) {
            listener.setResetSleepTimerOnPlaybackInteraction(!viewState.resetOnPlaybackInteraction)
          },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        supportingContent = {
          Text(
            text = stringResource(StringsR.string.settings_sleep_reset_summary),
            modifier = Modifier.alpha(textAlpha),
          )
        },
        trailingContent = {
          Switch(
            checked = viewState.resetOnPlaybackInteraction,
            enabled = viewState.enabled,
            onCheckedChange = listener::setResetSleepTimerOnPlaybackInteraction,
          )
        },
      ) {
        Text(
          text = stringResource(StringsR.string.settings_sleep_reset_title),
          modifier = Modifier.alpha(textAlpha),
        )
      }
    }
  }
}

@Composable
@Preview
private fun AutoSleepTimerCardPreview() {
  VoiceTheme {
    AutoSleepTimerCard(SettingsViewState.AutoSleepTimerViewState.preview(), SettingsListener.noop())
  }
}

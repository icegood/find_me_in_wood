package com.icegood.findmeinwood.app

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.icegood.findmeinwood.transport.api.DebugLog

/**
 * A multi-choice setting (theme, Wi-Fi Direct band) as a Material 3 single-choice
 * segmented button row: one segment per option, the [selected] one checked. Tapping the
 * selected segment again does nothing ([Choices.pick]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> SegmentedChoice(
    name: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        options.forEachIndexed { i, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = {
                    Choices.pick(selected, option)?.let { picked ->
                        DebugLog.d { Choices.tapLog(name, picked.toString()) }
                        onSelect(picked)
                    }
                },
                shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size),
            ) { Text(label(option), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** Plain-Kotlin side of [SegmentedChoice] (constitution P6). */
internal object Choices {
    const val THEME = "theme"
    const val WIFI_BAND = "wifi direct band"

    /** The option to apply after a tap on [picked], or null when it is already [current]. */
    fun <T> pick(current: T, picked: T): T? = if (picked == current) null else picked

    /** Debug log line for a segment tap. */
    fun tapLog(setting: String, value: String): String = "ui: choice $setting set to $value"
}

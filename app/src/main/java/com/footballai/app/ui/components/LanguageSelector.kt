package com.footballai.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.footballai.app.R
import com.footballai.app.data.settings.AppLanguage

/** Independent rounded choices avoid joined physical-edge shapes when RTL changes.
 * FlowRow also wraps at large font scales instead of truncating the language names.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LanguageSelector(selected: AppLanguage, onSelect: (AppLanguage) -> Unit, modifier: Modifier = Modifier) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AppLanguage.entries.forEach { language ->
            FilterChip(
                selected = selected == language,
                onClick = { onSelect(language) },
                label = {
                    Text(stringResource(when (language) {
                        AppLanguage.SYSTEM -> R.string.language_system
                        AppLanguage.PERSIAN -> R.string.language_persian
                        AppLanguage.ENGLISH -> R.string.language_english
                    }))
                },
            )
        }
    }
}

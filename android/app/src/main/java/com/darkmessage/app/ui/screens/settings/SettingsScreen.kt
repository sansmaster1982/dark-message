package com.darkmessage.app.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.darkmessage.app.BuildConfig
import com.darkmessage.app.R
import com.darkmessage.app.ui.theme.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val themeMode by viewModel.themeMode.collectAsState()
    val language by viewModel.language.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.settings_title)) })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Language section
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Language, contentDescription = null)
                        Spacer(modifier = Modifier.padding(8.dp))
                        Text(
                            stringResource(R.string.settings_language),
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))

                    SettingsOption(
                        label = stringResource(R.string.settings_language_en),
                        selected = language == AppLanguage.ENGLISH,
                        onClick = { viewModel.setLanguage(AppLanguage.ENGLISH) }
                    )
                    SettingsOption(
                        label = stringResource(R.string.settings_language_ru),
                        selected = language == AppLanguage.RUSSIAN,
                        onClick = { viewModel.setLanguage(AppLanguage.RUSSIAN) }
                    )
                }
            }

            // Theme section
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.settings_theme),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    ThemeOption(
                        icon = { Icon(Icons.Outlined.DarkMode, contentDescription = null) },
                        label = stringResource(R.string.settings_theme_dark),
                        selected = themeMode == ThemeMode.DARK,
                        onClick = { viewModel.setThemeMode(ThemeMode.DARK) }
                    )
                    ThemeOption(
                        icon = { Icon(Icons.Outlined.LightMode, contentDescription = null) },
                        label = stringResource(R.string.settings_theme_light),
                        selected = themeMode == ThemeMode.LIGHT,
                        onClick = { viewModel.setThemeMode(ThemeMode.LIGHT) }
                    )
                    ThemeOption(
                        icon = { Icon(Icons.Outlined.PhoneAndroid, contentDescription = null) },
                        label = stringResource(R.string.settings_theme_system),
                        selected = themeMode == ThemeMode.SYSTEM,
                        onClick = { viewModel.setThemeMode(ThemeMode.SYSTEM) }
                    )
                }
            }

            // Detailed instructions section
            DetailedInstructionsCard()

            // About section
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Info, contentDescription = null)
                        Spacer(modifier = Modifier.padding(8.dp))
                        Text(
                            stringResource(R.string.settings_about),
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.settings_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.settings_encryption),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        stringResource(R.string.settings_kdf),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        stringResource(R.string.settings_no_network),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.settings_supported_docs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        stringResource(R.string.settings_doc_formats),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun DetailedInstructionsCard() {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.AutoMirrored.Outlined.MenuBook, contentDescription = null)
                Spacer(modifier = Modifier.padding(8.dp))
                Text(
                    stringResource(R.string.settings_instr_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    // Overview
                    InstructionSection(
                        title = stringResource(R.string.settings_instr_overview_title),
                        items = listOf(stringResource(R.string.settings_instr_overview))
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                    // Step 1
                    InstructionSection(
                        title = stringResource(R.string.settings_instr_step1_title),
                        items = listOf(
                            stringResource(R.string.settings_instr_step1_1),
                            stringResource(R.string.settings_instr_step1_2),
                            stringResource(R.string.settings_instr_step1_3),
                            stringResource(R.string.settings_instr_step1_4),
                            stringResource(R.string.settings_instr_step1_5),
                            stringResource(R.string.settings_instr_step1_6),
                            stringResource(R.string.qr_howto_step)
                        )
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                    // Step 2
                    InstructionSection(
                        title = stringResource(R.string.settings_instr_step2_title),
                        items = listOf(
                            stringResource(R.string.settings_instr_step2_1),
                            stringResource(R.string.settings_instr_step2_2),
                            stringResource(R.string.settings_instr_step2_3),
                            stringResource(R.string.settings_instr_step2_4),
                            stringResource(R.string.settings_instr_step2_5),
                            stringResource(R.string.settings_instr_step2_6),
                            stringResource(R.string.settings_instr_step2_7),
                            stringResource(R.string.settings_instr_step2_8),
                            stringResource(R.string.settings_instr_step2_9)
                        )
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                    // Step 3
                    InstructionSection(
                        title = stringResource(R.string.settings_instr_step3_title),
                        items = listOf(
                            stringResource(R.string.settings_instr_step3_1),
                            stringResource(R.string.settings_instr_step3_2),
                            stringResource(R.string.settings_instr_step3_3)
                        )
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                    // Step 4
                    InstructionSection(
                        title = stringResource(R.string.settings_instr_step4_title),
                        items = listOf(
                            stringResource(R.string.settings_instr_step4_1),
                            stringResource(R.string.settings_instr_step4_2),
                            stringResource(R.string.settings_instr_step4_3),
                            stringResource(R.string.settings_instr_step4_4),
                            stringResource(R.string.settings_instr_step4_5),
                            stringResource(R.string.settings_instr_step4_6),
                            stringResource(R.string.settings_instr_step4_7)
                        )
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                    // Tips
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.Lightbulb,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.padding(4.dp))
                        Text(
                            stringResource(R.string.settings_instr_tips_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    val tips = listOf(
                        stringResource(R.string.settings_instr_tip1),
                        stringResource(R.string.settings_instr_tip2),
                        stringResource(R.string.settings_instr_tip3),
                        stringResource(R.string.settings_instr_tip4),
                        stringResource(R.string.settings_instr_tip5)
                    )
                    tips.forEach { tip ->
                        Text(
                            text = tip,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 3.dp, horizontal = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InstructionSection(title: String, items: List<String>) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold
    )
    Spacer(modifier = Modifier.height(6.dp))
    items.forEach { item ->
        Text(
            text = item,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 2.dp, horizontal = 4.dp)
        )
    }
}

@Composable
private fun ThemeOption(
    icon: @Composable () -> Unit,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    // The whole row is the target, not just the radio button: a 40 dp circle next
    // to dead label text is below the 48 dp minimum and people tap the word.
    // selectable() also gives the row the right role for screen readers, so the
    // RadioButton itself must not handle the click a second time.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton
            )
            .heightIn(min = 48.dp)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(modifier = Modifier.padding(4.dp))
        icon()
        Spacer(modifier = Modifier.padding(4.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SettingsOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    // Same rule as ThemeOption: the label is part of the target.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton
            )
            .heightIn(min = 48.dp)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(modifier = Modifier.padding(4.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

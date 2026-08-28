package com.nutrilens.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nutrilens.core.designsystem.R as UiR
import com.nutrilens.core.designsystem.component.ErrorState
import com.nutrilens.core.designsystem.component.NutriLensCard
import com.nutrilens.core.designsystem.component.PrimaryButton
import com.nutrilens.core.designsystem.component.SecondaryButton
import com.nutrilens.core.designsystem.component.SectionHeader
import com.nutrilens.core.designsystem.theme.Dimens
import com.nutrilens.core.designsystem.theme.NutriLensTheme
import com.nutrilens.core.model.AppLanguage
import com.nutrilens.feature.settings.BuildConfig

@Composable
fun SettingsRoute(
    onSignedOut: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val signedOut by viewModel.signedOut.collectAsStateWithLifecycle()
    val pendingExport by viewModel.pendingExport.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(signedOut) {
        if (signedOut) onSignedOut()
    }

    // The system document picker chooses the destination, so the export lands
    // wherever the person wants it and the app needs no storage permission and
    // no FileProvider.
    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(EXPORT_MIME_TYPE),
    ) { uri ->
        val export = pendingExport
        if (uri != null && export != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(export.json.toByteArray())
                }
            }
        }
        viewModel.onExportHandled()
    }

    LaunchedEffect(pendingExport) {
        pendingExport?.let { createDocument.launch(it.fileName) }
    }

    SettingsScreen(
        uiState = uiState,
        onLanguageSelected = viewModel::onLanguageSelected,
        onStoreImagesRemotelyChanged = viewModel::onStoreImagesRemotelyChanged,
        onExport = viewModel::onExportRequested,
        onExportErrorDismissed = viewModel::onExportErrorDismissed,
        onSignOut = viewModel::onSignOut,
        onDeleteAccount = viewModel::onDeleteAccount,
        modifier = modifier,
    )
}

private const val EXPORT_MIME_TYPE = "application/json"

@Composable
fun SettingsScreen(
    uiState: SettingsUiState,
    onLanguageSelected: (AppLanguage) -> Unit,
    onStoreImagesRemotelyChanged: (Boolean) -> Unit,
    onExport: () -> Unit,
    onExportErrorDismissed: () -> Unit,
    onSignOut: () -> Unit,
    onDeleteAccount: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmingDelete by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Dimens.spaceMedium),
        verticalArrangement = Arrangement.spacedBy(Dimens.spaceSmall),
    ) {
        Text(
            text = stringResource(UiR.string.settings_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(vertical = Dimens.spaceMedium),
        )

        uiState.profile?.let { profile ->
            NutriLensCard {
                profile.displayName?.let {
                    Text(text = it, style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    text = profile.email,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SectionHeader(title = stringResource(UiR.string.settings_language))
        NutriLensCard {
            AppLanguage.entries.forEach { language ->
                LanguageOption(
                    language = language,
                    selected = language == uiState.language,
                    onSelect = { onLanguageSelected(language) },
                )
            }
        }

        SectionHeader(title = stringResource(UiR.string.settings_privacy))
        NutriLensCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = Dimens.minimumTouchTarget),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(UiR.string.settings_store_images),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Switch(
                    checked = uiState.storeImagesRemotely,
                    onCheckedChange = onStoreImagesRemotelyChanged,
                )
            }
            Text(
                text = stringResource(UiR.string.settings_store_images_explainer),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionHeader(title = stringResource(UiR.string.settings_data))
        NutriLensCard {
            Text(
                text = stringResource(UiR.string.settings_export_explainer),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            uiState.exportError?.let {
                ErrorState(
                    message = stringResource(UiR.string.error_device),
                    retryLabel = stringResource(UiR.string.error_generic_retry),
                    onRetry = onExportErrorDismissed,
                )
            }
            PrimaryButton(
                text = stringResource(UiR.string.settings_export),
                onClick = onExport,
                loading = uiState.isExporting,
                modifier = Modifier.padding(top = Dimens.spaceSmall),
            )
        }

        SectionHeader(title = stringResource(UiR.string.settings_about))
        NutriLensCard {
            Text(
                text = stringResource(UiR.string.settings_not_medical_advice),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(UiR.string.settings_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Dimens.spaceSmall),
            )
        }

        SecondaryButton(
            text = stringResource(UiR.string.settings_sign_out),
            onClick = onSignOut,
            modifier = Modifier.padding(top = Dimens.spaceMedium),
        )
        SecondaryButton(
            text = stringResource(UiR.string.settings_delete_account),
            onClick = { confirmingDelete = true },
            modifier = Modifier.padding(bottom = Dimens.spaceExtraLarge * 2),
        )
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(UiR.string.settings_delete_account_confirm_title)) },
            text = { Text(stringResource(UiR.string.settings_delete_account_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingDelete = false
                        onDeleteAccount()
                    },
                ) {
                    Text(stringResource(UiR.string.settings_delete_account_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) {
                    Text(stringResource(UiR.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun LanguageOption(
    language: AppLanguage,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = Dimens.minimumTouchTarget)
            // The whole row is the target, and it carries the RadioButton role
            // so a screen reader announces it as a selectable option.
            .clickable(onClick = onSelect)
            .semantics(mergeDescendants = true) { role = Role.RadioButton },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(
            text = stringResource(language.labelRes()),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = Dimens.spaceSmall),
        )
    }
}

private fun AppLanguage.labelRes(): Int = when (this) {
    AppLanguage.ENGLISH -> UiR.string.settings_language_english
    AppLanguage.TELUGU -> UiR.string.settings_language_telugu
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreview() {
    NutriLensTheme {
        SettingsScreen(
            uiState = SettingsUiState(),
            onLanguageSelected = {},
            onStoreImagesRemotelyChanged = {},
            onExport = {},
            onExportErrorDismissed = {},
            onSignOut = {},
            onDeleteAccount = {},
        )
    }
}

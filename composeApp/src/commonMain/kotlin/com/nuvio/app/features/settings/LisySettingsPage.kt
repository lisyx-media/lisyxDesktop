package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.*
import com.nuvio.app.features.lisy.*
import com.nuvio.app.features.profiles.ProfileRepository
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun LisySettingsContent(isTablet: Boolean) {
    val tokens = MaterialTheme.nuvio
    val profile by ProfileRepository.state.collectAsStateWithLifecycle()
    val profileId = profile.activeProfile?.profileIndex ?: ProfileRepository.activeProfileId
    remember(profileId) { LisyServerRepository.ensureLoaded(profileId) }
    val server by LisyServerRepository.uiState.collectAsStateWithLifecycle()
    var name by rememberSaveable(profileId, server) { mutableStateOf(server?.name.orEmpty()) }
    var url by rememberSaveable(profileId, server) { mutableStateOf(server?.url.orEmpty()) }
    var mac by rememberSaveable(profileId, server) { mutableStateOf(server?.mac.orEmpty()) }
    var error by remember { mutableStateOf<LisyServerValidation?>(null) }
    var saved by remember { mutableStateOf(false) }
    var storageError by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val fieldColors = lisySettingsFieldColors()
    SettingsGroup(isTablet = isTablet) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(if (isTablet) 20.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(Res.string.lisy_settings_description),
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textSecondary,
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it; saved = false; error = null; storageError = false },
                label = { Text(stringResource(Res.string.lisy_server_name)) },
                singleLine = true,
                isError = error == LisyServerValidation.Name,
                colors = fieldColors,
                shape = tokens.shapes.button,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = url,
                onValueChange = { url = it; saved = false; error = null; storageError = false },
                label = { Text(stringResource(Res.string.lisy_server_url)) },
                placeholder = { Text("https://serveur.example/c/") },
                singleLine = true,
                isError = error == LisyServerValidation.Url,
                colors = fieldColors,
                shape = tokens.shapes.button,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = mac,
                onValueChange = { mac = it; saved = false; error = null; storageError = false },
                label = { Text(stringResource(Res.string.lisy_server_mac)) },
                placeholder = { Text("00:1A:79:00:00:00") },
                singleLine = true,
                isError = error == LisyServerValidation.Mac,
                colors = fieldColors,
                shape = tokens.shapes.button,
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let {
                Text(
                    stringResource(when (it) {
                        LisyServerValidation.Name -> Res.string.lisy_invalid_name
                        LisyServerValidation.Url -> Res.string.lisy_invalid_url
                        LisyServerValidation.Mac -> Res.string.lisy_invalid_mac
                    }),
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.danger,
                )
            }
            if (storageError) Text(stringResource(Res.string.lisy_save_error), color = tokens.colors.danger)
            if (saved) Text(stringResource(Res.string.lisy_saved), color = tokens.colors.textSecondary)
            DialogButtons {
                if (server != null) {
                    DialogButton(
                        text = stringResource(Res.string.lisy_remove),
                        onClick = { confirmDelete = true },
                    )
                }
                DialogButton(
                    text = stringResource(Res.string.lisy_save),
                    style = DialogButtonStyle.Primary,
                    onClick = {
                        storageError = false
                        try {
                            error = LisyServerRepository.save(name, url, mac)
                            saved = error == null
                        } catch (_: Exception) {
                            storageError = true
                            saved = false
                        }
                    },
                )
            }
        }
    }
    TvParentalSettings(isTablet)
    if (confirmDelete) {
        DialogSurface(
            onDismissRequest = { confirmDelete = false },
            title = stringResource(Res.string.lisy_remove),
            message = stringResource(Res.string.lisy_remove_confirmation),
        ) {
            DialogButtons {
                DialogButton(stringResource(Res.string.action_cancel), { confirmDelete = false })
                DialogButton(
                    text = stringResource(Res.string.lisy_remove),
                    style = DialogButtonStyle.Destructive,
                    onClick = {
                        try {
                            LisyServerRepository.remove()
                            saved = false
                            storageError = false
                        } catch (_: Exception) {
                            storageError = true
                        }
                        confirmDelete = false
                    },
                )
            }
        }
    }
}

@Composable
internal fun lisySettingsFieldColors(): TextFieldColors {
    val tokens = MaterialTheme.nuvio
    return OutlinedTextFieldDefaults.colors(
        focusedTextColor = tokens.colors.textPrimary,
        unfocusedTextColor = tokens.colors.textPrimary,
        errorTextColor = tokens.colors.textPrimary,
        focusedLabelColor = tokens.colors.accent,
        unfocusedLabelColor = tokens.colors.textSecondary,
        focusedPlaceholderColor = tokens.colors.textSecondary,
        unfocusedPlaceholderColor = tokens.colors.textSecondary,
        cursorColor = tokens.colors.accent,
        focusedBorderColor = tokens.colors.accent.copy(alpha = .75f),
        unfocusedBorderColor = tokens.colors.borderSubtle,
        focusedContainerColor = tokens.colors.surface,
        unfocusedContainerColor = tokens.colors.surface,
        errorContainerColor = tokens.colors.surface,
    )
}

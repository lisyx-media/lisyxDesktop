package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.lisy.TvParentalControls
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun TvParentalSettings(isTablet: Boolean) {
    val state by remember { TvParentalControls.ensureLoaded(); TvParentalControls.state }
        .collectAsStateWithLifecycle()
    val tokens = MaterialTheme.nuvio
    var editing by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }

    Spacer(Modifier.height(16.dp))
    SettingsGroup(isTablet = isTablet) {
        Column(Modifier.fillMaxWidth().padding(if (isTablet) 20.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(Res.string.tv_parental_settings_title), style = MaterialTheme.typography.titleMedium, color = tokens.colors.textPrimary)
            Text(stringResource(Res.string.tv_parental_settings_description),
                style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary)
            Text(stringResource(when {
                !state.configured -> Res.string.tv_parental_settings_no_code
                state.unlocked -> Res.string.tv_parental_settings_unlocked
                else -> Res.string.tv_parental_settings_locked
            }), style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary)
            if (saved) Text(stringResource(Res.string.tv_parental_saved), color = tokens.colors.textSecondary)
            DialogButtons {
                if (state.unlocked) DialogButton(
                    text = stringResource(Res.string.tv_parental_lock_now),
                    onClick = { TvParentalControls.lock() },
                )
                DialogButton(
                    text = stringResource(if (state.configured) Res.string.tv_parental_change_code else Res.string.tv_parental_create_code),
                    onClick = { saved = false; editing = true },
                    style = DialogButtonStyle.Primary,
                )
            }
        }
    }
    if (editing) TvParentalCodeDialog(
        configured = state.configured,
        onDismiss = { editing = false },
        onSaved = { editing = false; saved = true },
    )
}

@Composable
private fun TvParentalCodeDialog(configured: Boolean, onDismiss: () -> Unit, onSaved: () -> Unit) {
    var current by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    DialogSurface(
        onDismissRequest = { if (!pending) onDismiss() },
        title = stringResource(if (configured) Res.string.tv_parental_change_code else Res.string.tv_parental_create_code),
        message = stringResource(Res.string.tv_parental_code_description),
    ) {
        if (configured) ParentalPinField(
            value = current, onValueChange = { current = it; error = null },
            label = stringResource(Res.string.tv_parental_current_code), enabled = !pending,
        )
        ParentalPinField(
            value = pin, onValueChange = { pin = it; error = null },
            label = stringResource(Res.string.tv_parental_new_code), enabled = !pending,
        )
        ParentalPinField(
            value = confirmation, onValueChange = { confirmation = it; error = null },
            label = stringResource(Res.string.tv_parental_confirm_code), enabled = !pending,
        )
        error?.let { Text(it, color = MaterialTheme.nuvio.colors.danger, style = MaterialTheme.typography.bodyMedium) }
        DialogButtons {
            DialogButton(stringResource(Res.string.action_cancel), { if (!pending) onDismiss() })
            DialogButton(
                text = stringResource(if (pending) Res.string.tv_parental_saving else Res.string.action_save),
                style = DialogButtonStyle.Primary,
                onClick = {
                    if (!pending) {
                        pending = true
                        scope.launch {
                            try {
                                val result = TvParentalControls.setPin(pin, confirmation, current.takeIf { configured })
                                if (result.unlocked) {
                                    current = ""; pin = ""; confirmation = ""
                                    onSaved()
                                } else {
                                    error = result.message
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                error = getString(Res.string.tv_parental_storage_error)
                            } finally {
                                pending = false
                            }
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun ParentalPinField(value: String, onValueChange: (String) -> Unit, label: String, enabled: Boolean) {
    OutlinedTextField(
        value = value,
        onValueChange = { value -> onValueChange(value.filter { it in '0'..'9' }.take(4)) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        colors = lisySettingsFieldColors(),
        shape = MaterialTheme.nuvio.shapes.button,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
    )
}

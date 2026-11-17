package com.nuvio.app

import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.lisy.LisyServerRepository
import kotlinx.coroutines.delay
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

// Process-local: returning from a detail page or switching profiles must not
// repeatedly interrupt someone who chose to browse without a server.
private object LisyPromptSession {
    val handledProfiles = mutableSetOf<Int>()
}

@Composable
internal fun MissingLisyServerPrompt(profileId: Int, enabled: Boolean, onAddServer: () -> Unit) {
    remember(profileId) { LisyServerRepository.ensureLoaded(profileId) }
    val configured by LisyServerRepository.configured.collectAsStateWithLifecycle()
    var handled by remember(profileId) { mutableStateOf(profileId in LisyPromptSession.handledProfiles) }
    var launchFinished by remember(profileId) { mutableStateOf(false) }
    LaunchedEffect(profileId) {
        // The desktop ident runs above the app for 4.4 seconds.
        delay(5_000)
        launchFinished = true
    }
    fun dismiss() {
        LisyPromptSession.handledProfiles.add(profileId)
        handled = true
    }
    if (enabled && launchFinished && !handled && !configured) {
        DialogSurface(
            onDismissRequest = { dismiss() },
            title = stringResource(Res.string.lisy_prompt_title),
            message = stringResource(Res.string.lisy_prompt_message),
        ) {
            DialogButtons {
                DialogButton(
                    text = stringResource(Res.string.lisy_prompt_later),
                    onClick = { dismiss() },
                )
                DialogButton(
                    text = stringResource(Res.string.lisy_prompt_add),
                    onClick = { dismiss(); onAddServer() },
                    style = DialogButtonStyle.Primary,
                )
            }
        }
    }
}

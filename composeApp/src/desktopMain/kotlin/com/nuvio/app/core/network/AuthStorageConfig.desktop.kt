package com.nuvio.app.core.network

import com.nuvio.app.features.player.desktop.DesktopHostOs
import com.russhwolf.settings.PreferencesSettings
import io.github.jan.supabase.auth.AuthConfig
import io.github.jan.supabase.auth.SettingsCodeVerifierCache
import io.github.jan.supabase.auth.SettingsSessionManager
import java.util.prefs.Preferences

internal actual fun configureAuthStorage(config: AuthConfig) {
    if (DesktopHostOs.current != DesktopHostOs.MACOS) return

    val settings = PreferencesSettings(Preferences.userRoot().node("/com/lisyx/media/desktop"))
    config.sessionManager = SettingsSessionManager(settings)
    config.codeVerifierCache = SettingsCodeVerifierCache(settings)
}

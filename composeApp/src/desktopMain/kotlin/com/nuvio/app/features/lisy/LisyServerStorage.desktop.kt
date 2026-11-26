package com.nuvio.app.features.lisy
import com.nuvio.app.core.storage.DesktopStorage
internal actual object LisyServerStorage {
    private val store = DesktopStorage.store("lisy_servers")
    actual fun load(profileId: Int): String? = store.getString("server_$profileId")
    actual fun save(profileId: Int, value: String?) { store.putString("server_$profileId", value) }
    actual fun loadTvPreference(key: String): String? = store.getString(key)
    actual fun saveTvPreference(key: String, value: String) = store.putString(key, value)
}

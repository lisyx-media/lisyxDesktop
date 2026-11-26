package com.nuvio.app.features.lisy
import platform.Foundation.NSUserDefaults
internal actual object LisyServerStorage {
    actual fun load(profileId: Int): String? = NSUserDefaults.standardUserDefaults.stringForKey("lisy_server_$profileId")
    actual fun save(profileId: Int, value: String?) {
        if (value == null) NSUserDefaults.standardUserDefaults.removeObjectForKey("lisy_server_$profileId")
        else NSUserDefaults.standardUserDefaults.setObject(value, forKey = "lisy_server_$profileId")
    }
    actual fun loadTvPreference(key: String): String? = NSUserDefaults.standardUserDefaults.stringForKey(key)
    actual fun saveTvPreference(key: String, value: String) { NSUserDefaults.standardUserDefaults.setObject(value, forKey = key) }
}

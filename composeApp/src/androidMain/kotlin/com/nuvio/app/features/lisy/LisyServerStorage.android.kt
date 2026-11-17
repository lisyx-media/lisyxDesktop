package com.nuvio.app.features.lisy
import android.content.Context
import android.content.SharedPreferences
internal actual object LisyServerStorage {
    private var preferences: SharedPreferences? = null
    fun initialize(context: Context) { preferences = context.getSharedPreferences("lisy_servers", Context.MODE_PRIVATE) }
    actual fun load(profileId: Int): String? = preferences?.getString("server_$profileId", null)
    actual fun save(profileId: Int, value: String?) { checkNotNull(preferences).edit().putString("server_$profileId", value).apply() }
}

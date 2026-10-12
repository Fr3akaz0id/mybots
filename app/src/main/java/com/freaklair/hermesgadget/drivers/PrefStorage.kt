package com.freaklair.hermesgadget.drivers

import com.freaklair.hermesgadget.StorageDriver

// NVS stand-in; the sim uses a JSON file, Android uses SharedPreferences, same
// flat string KV contract the core expects.
//
// alias maps core-facing keys onto real prefs keys. Multiple cores (one per
// page) share this prefs file, so a bare "name" would collide — the last page
// to start silently renames every hub connection (every hub then registers
// "FreakBot"). PageEngine namespaces "name" per profile id so each page
// announces itself; device_key stays deliberately shared (one device identity
// across hubs by design).
class PrefStorage(
    private val prefs: android.content.SharedPreferences,
    private val alias: Map<String, String> = emptyMap(),
) : StorageDriver {
    private fun k(key: String) = alias[key] ?: key
    override fun get(key: String): String? = prefs.getString(k(key), null)
    override fun set(key: String, value: String) { prefs.edit().putString(k(key), value).apply() }
    override fun erase(key: String) { prefs.edit().remove(k(key)).apply() }
}

package com.freaklair.hermesgadget.drivers

import com.freaklair.hermesgadget.StorageDriver

// NVS stand-in; the sim uses a JSON file, Android uses SharedPreferences, same
// flat string KV contract the core expects.
class PrefStorage(private val prefs: android.content.SharedPreferences) : StorageDriver {
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun set(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    override fun erase(key: String) { prefs.edit().remove(key).apply() }
}

package com.freaklair.hermesgadget

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Profile(val id: String, var name: String, var url: String, var token: String)

// Named connection profiles: one per agent/host the phone gadget connects to.
// Persisted as JSON in the `gadget` prefs alongside the core's NVS keys. The
// active profile drives Engine.start; switching restarts the core session.
class ProfileStore(context: Context) {

    private val prefs = context.getSharedPreferences("gadget", Context.MODE_PRIVATE)

    fun all(): List<Profile> {
        val raw = prefs.getString("profiles", null) ?: return seedFromLegacy()
        val arr = try { JSONArray(raw) } catch (e: Exception) { return seedFromLegacy() }
        val out = ArrayList<Profile>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(Profile(
                o.getString("id"),
                o.optString("name", "Profile"),
                o.optString("url", ""),
                o.optString("token", "")))
        }
        return out
    }

    // First run after upgrade: if only legacy single server/token prefs exist,
    // promote them into one "Default" profile so existing setups keep working.
    private fun seedFromLegacy(): List<Profile> {
        val server = prefs.getString("server", "") ?: ""
        if (server.isEmpty()) return emptyList()
        val p = Profile("default", "Default", server, prefs.getString("token", "") ?: "")
        save(listOf(p))
        prefs.edit().putString("active", "default").apply()
        return listOf(p)
    }

    fun active(): Profile? =
        all().firstOrNull { it.id == prefs.getString("active", "") }
            ?: all().firstOrNull()?.also { setActive(it.id) }

    fun setActive(id: String) { prefs.edit().putString("active", id).apply() }

    fun save(list: List<Profile>) {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(JSONObject().put("id", p.id).put("name", p.name)
                .put("url", p.url).put("token", p.token))
        }
        prefs.edit().putString("profiles", arr.toString()).apply()
    }

    fun upsert(p: Profile) {
        val l = all().toMutableList()
        val i = l.indexOfFirst { it.id == p.id }
        if (i >= 0) l[i] = p else l.add(p)
        save(l)
        if (active() == null) setActive(p.id)
    }

    fun delete(id: String) {
        val l = all().filter { it.id != id }
        save(l)
        if (active()?.id == id && l.isNotEmpty()) setActive(l[0].id)
    }
}

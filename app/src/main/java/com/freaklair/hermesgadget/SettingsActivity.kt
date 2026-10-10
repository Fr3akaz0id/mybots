package com.freaklair.hermesgadget

import android.content.Intent
import android.os.Bundle
import android.text.method.SingleLineTransformationMethod
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import android.view.View

// Profile settings, shown as a dim overlay above the gadget. One profile per
// agent/host: pick one and it goes active + the gadget reconnects there.
class SettingsActivity : AppCompatActivity() {

    private lateinit var profiles: ProfileStore
    private lateinit var group: RadioGroup
    private lateinit var fName: EditText
    private lateinit var fUrl: EditText
    private lateinit var fToken: EditText
    private lateinit var emptyHint: TextView
    private lateinit var statusText: TextView
    private var currentId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        profiles = ProfileStore(this)
        group = findViewById(R.id.profile_group)
        fName = findViewById(R.id.f_name)
        fUrl = findViewById(R.id.f_url)
        fToken = findViewById(R.id.f_token)
        emptyHint = findViewById(R.id.empty_hint)
        statusText = findViewById(R.id.settings_status)

        rebuildList()

        group.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == ViewGroup.NO_ID) return@setOnCheckedChangeListener
            (findViewById<RadioButton>(checkedId))?.let { rb ->
                val p = rb.tag as? Profile ?: return@let
                loadIntoForm(p)
            }
        }

        findViewById<Button>(R.id.btn_save).setOnClickListener { onSave() }
        findViewById<Button>(R.id.btn_new).setOnClickListener { newForm() }
        findViewById<Button>(R.id.btn_delete).setOnClickListener { onDelete() }
        findViewById<Button>(R.id.btn_done).setOnClickListener { finish() }
    }

    private fun rebuildList() {
        group.removeAllViews()
        val list = profiles.all()
        val active = profiles.active()?.id
        emptyHint.visibility = if (list.isEmpty()) TextView.VISIBLE else TextView.GONE
        for (p in list) {
            val rb = RadioButton(this)
            rb.id = nextId()
            rb.text = "${p.name}\n${p.url}"
            rb.setTextColor(0xFFE8E8EC.toInt())
            rb.textSize = 13f
            // wrap, never truncate (user preference: no ellipsis on names)
            rb.maxLines = 4
            rb.setSingleLine(false)
            rb.transformationMethod = null
            rb.tag = p
            rb.isChecked = p.id == active
            rb.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            group.addView(rb)
        }
    }

    private var nextIdBase = 0x5A00
    private fun nextId(): Int {
        // generated ids won't collide with aapt resources
        return nextIdBase++
    }

    private fun loadIntoForm(p: Profile) {
        currentId = p.id
        fName.setText(p.name)
        fUrl.setText(p.url)
        fToken.setText(p.token)
        statusText.text = "Editing: ${p.name}"
    }

    private fun newForm() {
        currentId = null
        fName.text.clear(); fUrl.text.clear(); fToken.text.clear()
        statusText.text = "New profile — fill in and save"
    }

    private fun onSave() {
        val name = fName.text.toString().trim().ifEmpty { "Profile" }
        val url = fUrl.text.toString().trim()
        if (!url.startsWith("ws://") && !url.startsWith("wss://")) {
            statusText.text = "URL must start with ws:// or wss://"
            return
        }
        val id = currentId ?: "p${System.currentTimeMillis()}"
        profiles.upsert(Profile(id, name, url, fToken.text.toString().trim()))
        profiles.setActive(id)
        rebuildList()
        statusText.text = "Saved. Reconnecting to: $name"
        // hand the new target back to MainActivity
        setResult(RESULT_OK, Intent().putExtra("profile", url).putExtra("profile_name", name)
            .putExtra("profile_id", id))
    }

    private fun onDelete() {
        val id = currentId ?: return
        profiles.delete(id)
        newForm()
        rebuildList()
        statusText.text = "Profile deleted"
        setResult(RESULT_OK, Intent().putExtra("changed", true))
    }
}

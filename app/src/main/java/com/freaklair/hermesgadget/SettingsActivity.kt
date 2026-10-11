package com.freaklair.hermesgadget

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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
// Head look (shape + color) previews live on the robot behind the dim.
class SettingsActivity : AppCompatActivity() {

    companion object {
        val SHAPES = listOf(
            "rectangle" to "Rect", "square" to "Square",
            "circle" to "Circle", "hexagon" to "Hex")
        val COLORS = listOf(
            "#3050AC" to "Blue", "#0E7C86" to "Cyan", "#0F8A5F" to "Green",
            "#C77700" to "Amber", "#B23B3B" to "Red", "#6D4FC2" to "Violet",
            "#C2447E" to "Pink", "#6E7480" to "Slate")
        const val DEFAULT_COLOR = "#3050AC"
    }

    private lateinit var profiles: ProfileStore
    private lateinit var group: RadioGroup
    private lateinit var fName: EditText
    private lateinit var fUrl: EditText
    private lateinit var fToken: EditText
    private lateinit var emptyHint: TextView
    private lateinit var statusText: TextView
    private var currentId: String? = null
    private var curShape = "rectangle"
    private var curColor = DEFAULT_COLOR
    private var applyingForm = false
    private val shapeChips = ArrayList<android.widget.TextView>()
    private val colorDots = ArrayList<View>()

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
        buildShapeRow()
        buildColorRow()
        // A pre-checked radio never fires the listener — load the active
        // profile so the form and the head look reflect it from the start.
        profiles.active()?.let { loadIntoForm(it) }

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

    // ---- head look pickers ------------------------------------------------

    private fun buildShapeRow() {
        val row = findViewById<LinearLayout>(R.id.shape_row)
        for ((key, label) in SHAPES) {
            val chip = android.widget.TextView(this)
            chip.text = label
            chip.textSize = 13f
            chip.gravity = Gravity.CENTER
            chip.setTextColor(0xFFE8E8EC.toInt())
            val lp = LinearLayout.LayoutParams(0, dp(38), 1f)
            lp.marginEnd = dp(6)
            chip.layoutParams = lp
            chip.isClickable = true
            chip.setOnClickListener {
                curShape = key
                updateShapeSelection()
                sendPreview()
            }
            shapeChips.add(chip)
            row.addView(chip)
        }
        updateShapeSelection()
    }

    private fun buildColorRow() {
        val row = findViewById<LinearLayout>(R.id.color_row)
        // rows of 4 keep the panel from overflowing in portrait
        var sub = LinearLayout(this)
        row.addView(sub)
        COLORS.forEachIndexed { i, (hex, name) ->
            if (i > 0 && i % 4 == 0) {
                sub = LinearLayout(this)
                sub.orientation = LinearLayout.HORIZONTAL
                row.addView(sub)
            }
            val dot = View(this)
            dot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(hex))
            }
            val lp = LinearLayout.LayoutParams(dp(46), dp(46))
            lp.marginEnd = dp(10); lp.topMargin = dp(6); lp.bottomMargin = dp(6)
            dot.layoutParams = lp
            dot.isClickable = true
            dot.contentDescription = name
            dot.setOnClickListener {
                curColor = hex
                updateColorSelection()
                sendPreview()
            }
            dot.setOnLongClickListener {
                statusText.text = "$name · $hex"; true
            }
            colorDots.add(dot)
            sub.addView(dot)
        }
        updateColorSelection()
    }

    private fun updateShapeSelection() {
        shapeChips.forEachIndexed { i, chip ->
            val sel = SHAPES[i].first == curShape
            chip.background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(if (sel) Color.parseColor("#3050AC") else Color.parseColor("#232329"))
                setStroke(dp(1), if (sel) Color.parseColor("#7FA0FF") else Color.parseColor("#3A3A42"))
            }
        }
    }

    private fun updateColorSelection() {
        colorDots.forEachIndexed { i, dot ->
            (dot.background as GradientDrawable).setStroke(dp(3),
                if (COLORS[i].first.equals(curColor, true)) Color.parseColor("#E8E8EC") else Color.parseColor("#232329"))
        }
    }

    private fun sendPreview() {
        if (applyingForm) return
        sendBroadcast(Intent("com.freaklair.mybots.HEAD_PREVIEW")
            .setPackage(packageName)
            .putExtra("shape", curShape).putExtra("color", curColor))
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt().coerceAtLeast(1)

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
        applyingForm = true
        curShape = p.shape.ifEmpty { "rectangle" }
        curColor = p.color.ifEmpty { DEFAULT_COLOR }
        updateShapeSelection()
        updateColorSelection()
        applyingForm = false
        sendPreview()
        statusText.text = "Editing: ${p.name}"
    }

    private fun newForm() {
        currentId = null
        fName.text.clear(); fUrl.text.clear(); fToken.text.clear()
        curShape = "rectangle"; curColor = DEFAULT_COLOR
        updateShapeSelection(); updateColorSelection()
        sendPreview()
        statusText.text = "New profile — fill in and save"
    }

    private fun onSave() {
        val name = fName.text.toString().trim().ifEmpty { "Profile" }
        val url = fUrl.text.toString().trim()
        if (!url.startsWith("ws://") && !url.startsWith("wss://")) {
            statusText.text = "URL must start with ws:// or wss://"
            android.widget.Toast.makeText(this, "URL must start with ws:// or wss://",
                android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val id = currentId ?: "p${System.currentTimeMillis()}"
        val prof = Profile(id, name, url, fToken.text.toString().trim(), curShape, curColor)
        android.util.Log.i("hgcfg", "onSave id=$id shape=$curShape color=$curColor")
        profiles.upsert(prof)
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

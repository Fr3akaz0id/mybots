package com.freaklair.hermesgadget

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import android.view.View

// Profiles as small cards on a centered sheet: one per agent, active badge,
// connect right from the card. Edit/Add hides behind a tap, so the main view
// stays glanceable. Head look (shape + color) previews live while editing.
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
    private lateinit var paneCards: View
    private lateinit var paneEditor: View
    private lateinit var cardContainer: LinearLayout
    private lateinit var editorTitle: TextView
    private lateinit var fName: EditText
    private lateinit var fUrl: EditText
    private lateinit var fToken: EditText
    private lateinit var emptyHint: TextView
    private lateinit var statusText: TextView
    private var currentId: String? = null
    private var curShape = "rectangle"
    private var curColor = DEFAULT_COLOR
    private var applyingForm = false
    private var resultDirty = false
    private val shapeChips = ArrayList<TextView>()
    private val colorDots = ArrayList<View>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        profiles = ProfileStore(this)
        paneCards = findViewById(R.id.pane_cards)
        paneEditor = findViewById(R.id.pane_editor)
        cardContainer = findViewById(R.id.card_container)
        editorTitle = findViewById(R.id.editor_title)
        fName = findViewById(R.id.f_name)
        fUrl = findViewById(R.id.f_url)
        fToken = findViewById(R.id.f_token)
        emptyHint = findViewById(R.id.empty_hint)
        statusText = findViewById(R.id.settings_status)

        rebuildCards()
        buildShapeRow()
        buildColorRow()

        findViewById<Button>(R.id.btn_new).setOnClickListener { openEditor(null) }
        findViewById<View>(R.id.btn_back).setOnClickListener { showCards() }
        findViewById<Button>(R.id.btn_save).setOnClickListener { onSave() }
        findViewById<Button>(R.id.btn_delete).setOnClickListener { onDelete() }
        findViewById<Button>(R.id.btn_save).text = "Save"
    }

    // back: editor closes to cards first, app sheet closes second
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (paneEditor.visibility == View.VISIBLE) showCards() else super.onBackPressed()
    }

    // ---- cards pane --------------------------------------------------------

    private fun rebuildCards() {
        cardContainer.removeAllViews()
        val list = profiles.all()
        val active = profiles.active()?.id
        emptyHint.visibility = if (list.isEmpty()) TextView.VISIBLE else TextView.GONE
        for (p in list) cardContainer.addView(buildCard(p, p.id == active))
    }

    private fun buildCard(p: Profile, activeCard: Boolean): View {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(14), dp(12), dp(14), dp(12))
        card.background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(Color.parseColor(if (activeCard) "#24242E" else "#1C1C22"))
            setStroke(dp(if (activeCard) 2 else 1),
                if (activeCard) Color.parseColor("#7FA0FF") else Color.parseColor("#2C2C34"))
        }
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(10)
        card.layoutParams = lp

        // row 1: name + status/connection
        val row1 = LinearLayout(this)
        row1.orientation = LinearLayout.HORIZONTAL
        row1.gravity = Gravity.CENTER_VERTICAL
        card.addView(row1)
        val name = TextView(this)
        name.text = p.name
        name.setTextColor(0xFFE8E8EC.toInt())
        name.textSize = 15f
        name.maxLines = 3            // wrap, never truncate (user preference)
        name.setSingleLine(false)
        val nlp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        name.layoutParams = nlp
        row1.addView(name)
        if (activeCard) {
            val pill = TextView(this)
            pill.text = "ACTIVE"
            pill.textSize = 10f
            pill.gravity = Gravity.CENTER
            pill.setTextColor(0xFFE8E8EC.toInt())
            pill.background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#3050AC"))
            }
            pill.setPadding(dp(8), dp(3), dp(8), dp(3))
            row1.addView(pill)
        } else {
            val conn = TextView(this)
            conn.text = "Connect"
            conn.textSize = 13f
            conn.setTextColor(0xFF7FA0FF.toInt())
            conn.setPadding(dp(10), dp(4), dp(4), dp(4))
            conn.isClickable = true
            conn.setOnClickListener { connect(p) }
            row1.addView(conn)
        }

        // row 2: url
        val url = TextView(this)
        url.text = p.url
        url.setTextColor(0xFF8A8A93.toInt())
        url.textSize = 12f
        url.maxLines = 3
        url.setSingleLine(false)
        url.setPadding(0, dp(3), 0, 0)
        card.addView(url)

        // row 3: head look indicator (color dot + shape)
        val row3 = LinearLayout(this)
        row3.orientation = LinearLayout.HORIZONTAL
        row3.gravity = Gravity.CENTER_VERTICAL
        row3.setPadding(0, dp(6), 0, 0)
        card.addView(row3)
        val dot = View(this)
        dot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(p.color.ifEmpty { DEFAULT_COLOR }))
        }
        val dlp = LinearLayout.LayoutParams(dp(13), dp(13))
        dlp.marginEnd = dp(7)
        dot.layoutParams = dlp
        row3.addView(dot)
        val shapeName = TextView(this)
        shapeName.text = p.shape.ifEmpty { "rectangle" }
        shapeName.setTextColor(0xFF6A6A72.toInt())
        shapeName.textSize = 11f
        row3.addView(shapeName)

        card.isClickable = true
        card.setOnClickListener { openEditor(p) }
        return card
    }

    private fun connect(p: Profile) {
        profiles.setActive(p.id)
        sendHead(p.shape.ifEmpty { "rectangle" }, p.color.ifEmpty { DEFAULT_COLOR }, editing = false)
        rebuildCards()
        setResult(RESULT_OK, Intent().putExtra("changed", true))
        resultDirty = true
        statusText.text = "Connected: ${p.name}"
        android.widget.Toast.makeText(this, "Connected ${p.name}",
            android.widget.Toast.LENGTH_SHORT).show()
    }

    // ---- editor pane --------------------------------------------------------

    private fun openEditor(p: Profile?) {
        paneEditor.visibility = View.VISIBLE
        paneCards.visibility = View.GONE
        if (p != null) {
            currentId = p.id
            editorTitle.text = p.name
            fName.setText(p.name)
            fUrl.setText(p.url)
            fToken.setText(p.token)
            applyingForm = true
            curShape = p.shape.ifEmpty { "rectangle" }
            curColor = p.color.ifEmpty { DEFAULT_COLOR }
            applyingForm = false
            statusText.text = "Editing: ${p.name}"
        } else {
            currentId = null
            editorTitle.text = "New profile"
            fName.text.clear(); fUrl.text.clear(); fToken.text.clear()
            curShape = "rectangle"; curColor = DEFAULT_COLOR
            statusText.text = "Fill in and save"
        }
        updateShapeSelection(); updateColorSelection()
        sendHead(curShape, curColor, editing = true)
    }

    private fun showCards() {
        paneEditor.visibility = View.GONE
        paneCards.visibility = View.VISIBLE
        rebuildCards()
        // editing view ended — head goes back to the ACTIVE profile's look
        val act = profiles.active()
        sendHead(act?.shape?.ifEmpty { "rectangle" } ?: "rectangle",
                 act?.color?.ifEmpty { DEFAULT_COLOR } ?: DEFAULT_COLOR, editing = false)
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
        if (currentId == null) profiles.setActive(id)  // brand-new profile connects
        resultDirty = true
        setResult(RESULT_OK, Intent().putExtra("changed", true))
        statusText.text = "Saved"
        editorTitle.text = name
        currentId = id
    }

    private fun onDelete() {
        val id = currentId ?: return
        profiles.delete(id)
        resultDirty = true
        setResult(RESULT_OK, Intent().putExtra("changed", true))
        showCards()
    }

    // ---- head look pickers ------------------------------------------------

    private fun buildShapeRow() {
        val row = findViewById<LinearLayout>(R.id.shape_row)
        for ((key, label) in SHAPES) {
            val chip = TextView(this)
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
                sendHead(curShape, curColor, editing = true)
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
                sendHead(curShape, curColor, editing = true)
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

    private fun sendHead(shape: String, color: String, editing: Boolean) {
        sendBroadcast(Intent("com.freaklair.mybots.HEAD_PREVIEW")
            .setPackage(packageName)
            .putExtra("shape", shape).putExtra("color", color)
            .putExtra("editing", editing))
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt().coerceAtLeast(1)
}

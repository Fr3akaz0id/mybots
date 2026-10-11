package com.freaklair.hermesgadget

import android.content.Context
import android.graphics.Color
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

// Robot-head visualization layer (MyBots): a WebView drawing the bundled
// robot-heads renderer. Sits UNDER the PagerView, which keeps owning touch
// and the name strip. State changes are pushed via JS, never polled by JS.
class HeadView(context: Context) : WebView(context) {

    private var ready = false
    private var last: HeadState? = null
    private var pendingState: HeadState? = null

    init {
        setBackgroundColor(Color.BLACK)
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        settings.javaScriptEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = false
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, req: WebResourceRequest?): Boolean = true
            override fun onPageFinished(view: WebView?, url: String?) {
                ready = true
                pendingState?.let { setState(it) }
                pendingState = null
                pendingConfig?.let { post { evaluateJavascript(it, null) } }
                pendingConfig = null
            }
        }
        // route JS console/errors to logcat — this is our only JS test harness
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                Log.i("hghead", "[%s] %s (%s:%d)".format(msg.messageLevel(), msg.message(), msg.sourceId(), msg.lineNumber()))
                return true
            }
        }
        loadUrl("file:///android_asset/mybots/index.html")
    }

    fun setState(s: HeadState) {
        if (s == last && ready) return
        last = s
        val js = "MB.state('${s.js}')"
        if (ready) post { evaluateJavascript(js, null) } else pendingState = s
    }

    // head box in device px; JS lays out in CSS px
    private var lastBox = 0
    fun setBox(px: Float) {
        if (px <= 0) return
        val css = resources.displayMetrics.density.let { if (it <= 0f) 1f else it }
        val size = (px / css).toInt().coerceIn(120, 560)
        if (size == lastBox) return
        lastBox = size
        post { if (ready) evaluateJavascript("MB.size($size)", null) }
    }

    fun poke() {
        last = null  // next derived push must not get deduped away
        post { if (ready) evaluateJavascript("MB.poke()", null) }
    }

    // head look (shape + shell color) — pushed eagerly, replayed once ready.
    // Empty colorHex keeps the renderer's default palette.
    private var lastShape: String? = null
    private var lastColor: String? = null
    fun setConfig(shape: String, colorHex: String) {
        val js = StringBuilder("MB.shape('$shape')")
        if (colorHex.isNotEmpty()) js.append(";MB.color('$colorHex')")
        val cmd = js.toString()
        if (ready) post { evaluateJavascript(cmd, null) } else pendingConfig = cmd
        lastShape = shape; lastColor = colorHex
    }
    private var pendingConfig: String? = null

    // device px -> CSS px relative to the view center (JS clamps at 700)
    fun pointer(x: Float?, y: Float?) {
        if (!ready) return
        val css = resources.displayMetrics.density.let { if (it <= 0f) 1f else it }
        val js = if (x == null || y == null) "MB.pointer(null,null)"
                 else "MB.pointer(${(x - width / 2f) / css},${(y - height / 2f) / css})"
        post { if (ready) evaluateJavascript(js, null) }
    }

    // freeze the JS rAF loop when the app is off-screen
    override fun onPause() { super.onPause(); if (ready) evaluateJavascript("MB.hidden(true)", null) }
    override fun onResume() { super.onResume(); if (ready) evaluateJavascript("MB.hidden(false)", null) }
}

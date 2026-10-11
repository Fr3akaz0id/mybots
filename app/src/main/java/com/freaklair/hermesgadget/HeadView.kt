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

    // freeze the JS rAF loop when the app is off-screen
    override fun onPause() { super.onPause(); if (ready) evaluateJavascript("MB.hidden(true)", null) }
    override fun onResume() { super.onResume(); if (ready) evaluateJavascript("MB.hidden(false)", null) }
}

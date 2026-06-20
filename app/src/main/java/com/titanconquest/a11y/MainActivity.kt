package com.titanconquest.a11y

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Bloodwar 2 — Android client.
 *
 * This is a faithful port of the Bloodwar 2 Electron client. The Electron app
 * loads titanconquest.com in a Chromium window and injects `enhancer.js` (the
 * accessibility/performance enhancer) plus a `window.__bw2Audio` map of sound
 * effects. We do exactly the same here: a full-screen WebView (which IS Chrome
 * on Android) loads the live site and we inject the SAME, UNCHANGED enhancer.js
 * after each page load. Every enhancer module — enemy-list memory, decoy
 * safety, battle SFX, ARIA labels, keyboard shortcuts, battle hooks — comes
 * along because it is the identical code running in the same browser engine.
 *
 * The enhancer's entire native contract is two globals:
 *   - window.__bw2Audio          (map of "name" -> data:audio/...;base64,...)
 *   - window.__bw2Log(level,...)  (optional diagnostic logging)
 * Both are provided here via a JS bridge ([NativeBridge]) and a small bootstrap
 * snippet injected immediately before the enhancer.
 */
class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView

    /** Built once from bundled assets: { "benemy.ogg": "data:audio/ogg;base64,..." }. */
    private val audioJson: String by lazy { buildAudioJson() }

    /** The enhancer source, read verbatim from assets. */
    private val enhancerJs: String by lazy {
        assets.open("enhancer.js").bufferedReader().use { it.readText() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Persistent cookies so the TQRPG session survives across launches —
        // the Electron app relies on Electron's persistent session for the same
        // reason. The user logs in once through the web UI and stays logged in.
        CookieManager.getInstance().setAcceptCookie(true)

        webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            // Critical: the enhancer plays battle SFX without a user gesture.
            // Without this, new Audio(...).play() is blocked by the autoplay policy.
            mediaPlaybackRequiresUserGesture = false
            // Serve the DESKTOP DOM. titanconquest.com is a Framework7 app and
            // enhancer.js is written against its desktop layout. Match the
            // Electron client's spoofed desktop-Chrome UA exactly.
            userAgentString =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36"
            // Encourage a desktop-width viewport on a phone screen.
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            javaScriptCanOpenWindowsAutomatically = true
        }

        // The native side of window.__bw2Audio / window.__bw2Log.
        webView.addJavascriptInterface(NativeBridge(), "__bw2Native")

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                injectEnhancer(view)
            }
        }

        setContentView(webView)

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl("https://titanconquest.com/")
        }
    }

    /**
     * Inject, in order: (1) a tiny bootstrap that wires window.__bw2Audio and
     * window.__bw2Log to the native bridge, then (2) enhancer.js verbatim. The
     * enhancer self-guards against double-init via window.__tcEnhancerLoaded,
     * so re-running on every page load is safe — mirroring how the Electron
     * client re-injects on every dom-ready.
     */
    private fun injectEnhancer(view: WebView) {
        val bootstrap = """
            (function () {
              try {
                if (window.__bw2Native) {
                  if (!window.__bw2Audio) {
                    try { window.__bw2Audio = JSON.parse(window.__bw2Native.audioJson()); }
                    catch (e) { window.__bw2Audio = {}; }
                  }
                  if (!window.__bw2Log) {
                    window.__bw2Log = function (level) {
                      try {
                        var rest = Array.prototype.slice.call(arguments, 1).map(function (x) {
                          return (typeof x === 'string') ? x : JSON.stringify(x);
                        }).join(' ');
                        window.__bw2Native.log(String(level || 'log'), rest);
                      } catch (e) {}
                    };
                  }
                }
              } catch (e) {}
            })();
        """.trimIndent()

        view.evaluateJavascript(bootstrap) {
            view.evaluateJavascript(enhancerJs, null)
        }
    }

    /**
     * Walk assets/audio recursively and build the JSON map of
     * name -> data:<mime>;base64,<bytes>, using forward-slash relative paths
     * (e.g. "currency/low/Cash_Pickup_Low.ogg") — the same shape main.js builds
     * for the Electron client.
     */
    private fun buildAudioJson(): String {
        val map = JSONObject()
        fun mimeFor(name: String): String? = when {
            name.endsWith(".wav", true) -> "audio/wav"
            name.endsWith(".ogg", true) -> "audio/ogg"
            name.endsWith(".mp3", true) -> "audio/mpeg"
            else -> null
        }
        fun walk(dir: String, prefix: String) {
            val entries = assets.list(dir) ?: return
            for (entry in entries) {
                val full = if (dir.isEmpty()) entry else "$dir/$entry"
                val key = if (prefix.isEmpty()) entry else "$prefix/$entry"
                val children = assets.list(full)
                if (children != null && children.isNotEmpty()) {
                    walk(full, key)
                } else {
                    val mime = mimeFor(entry) ?: continue
                    val bytes = assets.open(full).use { input ->
                        val out = ByteArrayOutputStream()
                        input.copyTo(out)
                        out.toByteArray()
                    }
                    val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    map.put(key, "data:$mime;base64,$b64")
                }
            }
        }
        walk("audio", "")
        return map.toString()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        // Persist cookies (the session) to disk.
        CookieManager.getInstance().flush()
    }

    @Suppress("MissingSuperCall", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        // In-app back navigation through the game's history before exiting.
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    /** Native implementation of the two globals enhancer.js depends on. */
    private inner class NativeBridge {
        @JavascriptInterface
        fun audioJson(): String = this@MainActivity.audioJson

        @JavascriptInterface
        fun log(level: String, text: String) {
            Log.println(
                when (level.lowercase()) {
                    "error" -> Log.ERROR
                    "warn" -> Log.WARN
                    "info" -> Log.INFO
                    else -> Log.DEBUG
                },
                "BW2",
                text
            )
        }
    }
}

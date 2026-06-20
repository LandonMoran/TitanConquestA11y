package com.titanconquest.a11y

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
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

    override fun onGenericMotionEvent(event: MotionEvent?): Boolean {
        if (event == null) return super.onGenericMotionEvent(event)
        if ((event.source and MotionEvent.TOOL_TYPE_UNKNOWN) == 0) return super.onGenericMotionEvent(event)

        // Handle analog stick triggers (LT/RT)
        val leftTrigger = getCenteredAxis(event, MotionEvent.AXIS_LTRIGGER)
        val rightTrigger = getCenteredAxis(event, MotionEvent.AXIS_RTRIGGER)

        // LT (> 0.5) = "/" (go to next area)
        if (leftTrigger > 0.5f && !ltTriggerPressed) {
            ltTriggerPressed = true
            injectKeyEvent("/")
        } else if (leftTrigger <= 0.5f && ltTriggerPressed) {
            ltTriggerPressed = false
        }

        // RT (> 0.5) = "r" (use remedy)
        if (rightTrigger > 0.5f && !rtTriggerPressed) {
            rtTriggerPressed = true
            injectKeyEvent("r")
        } else if (rightTrigger <= 0.5f && rtTriggerPressed) {
            rtTriggerPressed = false
        }

        return super.onGenericMotionEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> {
                // A is the "confirm/activate" button — it works in tandem with
                // TalkBack. The user navigates with the D-Pad (TalkBack owns
                // those keys; we never consume them), DOM focus follows onto the
                // focused enemy row / action link, and A clicks THAT element —
                // engaging the exact enemy TalkBack just announced. Falls back
                // to the "1" attack shortcut when nothing is focused, so combat
                // still works exactly as before.
                injectActivateOrAttack()
                true
            }
            KeyEvent.KEYCODE_BUTTON_B -> {
                injectKeyEvent("2")
                true
            }
            KeyEvent.KEYCODE_BUTTON_X -> {
                injectKeyEvent("3")
                true
            }
            KeyEvent.KEYCODE_BUTTON_Y -> {
                injectKeyEvent("4")
                true
            }
            KeyEvent.KEYCODE_BUTTON_L1 -> {
                injectKeyEvent("5")
                true
            }
            KeyEvent.KEYCODE_BUTTON_R1 -> {
                injectKeyEvent("[")
                true
            }
            KeyEvent.KEYCODE_BUTTON_START -> {
                injectKeyEventWithModifier("h", altKey = true)
                true
            }
            KeyEvent.KEYCODE_BUTTON_SELECT -> {
                injectKeyEvent("Escape")
                true
            }
            KeyEvent.KEYCODE_BUTTON_THUMBL -> {
                injectKeyEvent("x")
                true
            }
            KeyEvent.KEYCODE_BUTTON_THUMBR -> {
                injectKeyEventWithModifier("v", altKey = true)
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private var ltTriggerPressed = false
    private var rtTriggerPressed = false

    private fun getCenteredAxis(event: MotionEvent, axis: Int): Float {
        val range = event.device?.getMotionRange(axis, MotionEvent.TOOL_TYPE_UNKNOWN)
        val flat = range?.flat ?: 0f
        val value = event.getAxisValue(axis)
        return if (Math.abs(value) > flat) value else 0f
    }

    /**
     * Click whatever the user is currently focused on, so the controller works
     * IN TANDEM with TalkBack. With TalkBack running, the user moves
     * accessibility focus with the D-Pad (which we deliberately never consume);
     * for interactive elements — enemy rows (`a.initBattle`), battle action
     * links, menu items — the WebView syncs DOM focus onto that element, so
     * `document.activeElement` IS the thing TalkBack just announced. Pressing A
     * dispatches a real DOM click on it, engaging the exact enemy the player
     * chose rather than the game's auto-picked priority target.
     *
     * If nothing meaningful is focused (e.g. a fresh patrol screen before the
     * user has navigated), we fall back to the game's "1" attack shortcut so
     * one-press combat keeps working exactly as it did before.
     */
    private fun injectActivateOrAttack() {
        val jsCode = """
            (function() {
                var ae = document.activeElement;
                var target = (ae && ae !== document.body) ? ae.closest(
                    'a, button, [role="button"], .initBattle, .attacklink, ' +
                    '.speciallink, .heavylink, .superlink, .coverlink, ' +
                    '.remedylink, .runlink'
                ) : null;
                if (target) {
                    target.click();
                    return;
                }
                var event = new KeyboardEvent('keydown', {
                    key: '1',
                    code: 'Digit1',
                    keyCode: 49,
                    which: 49,
                    bubbles: true,
                    cancelable: true
                });
                document.dispatchEvent(event);
            })();
        """.trimIndent()
        webView.evaluateJavascript(jsCode, null)
    }

    private fun injectKeyEvent(keyValue: String) {
        val jsCode = """
            (function() {
                const event = new KeyboardEvent('keydown', {
                    key: '$keyValue',
                    code: 'Key${keyValue.uppercase()}',
                    keyCode: getKeyCode('$keyValue'),
                    bubbles: true,
                    cancelable: true
                });
                document.dispatchEvent(event);
            })();

            function getKeyCode(key) {
                const keyMap = {
                    '1': 49, '2': 50, '3': 51, '4': 52, '5': 53,
                    'r': 82, 'Escape': 27, '[': 219, '/': 191, 'x': 88,
                    'h': 72
                };
                return keyMap[key] || 0;
            }
        """.trimIndent()
        webView.evaluateJavascript(jsCode, null)
    }

    private fun injectKeyEventWithModifier(keyValue: String, altKey: Boolean = false, ctrlKey: Boolean = false, shiftKey: Boolean = false) {
        val jsCode = """
            (function() {
                const event = new KeyboardEvent('keydown', {
                    key: '$keyValue',
                    code: 'Key${keyValue.uppercase()}',
                    keyCode: getKeyCode('$keyValue'),
                    altKey: $altKey,
                    ctrlKey: $ctrlKey,
                    shiftKey: $shiftKey,
                    bubbles: true,
                    cancelable: true
                });
                document.dispatchEvent(event);
            })();

            function getKeyCode(key) {
                const keyMap = {
                    '1': 49, '2': 50, '3': 51, '4': 52, '5': 53,
                    'r': 82, 'Escape': 27, '[': 219, '/': 191, 'x': 88,
                    'h': 72, 'v': 86, 't': 84
                };
                return keyMap[key] || 0;
            }
        """.trimIndent()
        webView.evaluateJavascript(jsCode, null)
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

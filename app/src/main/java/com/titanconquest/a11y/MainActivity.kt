package com.titanconquest.a11y

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
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
import androidx.activity.result.contract.ActivityResultContracts
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

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
    private var logFile: File? = null
    private var logWriter: FileWriter? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /** Built once from bundled assets: { "benemy.ogg": "data:audio/ogg;base64,..." }. */
    private val audioJson: String by lazy { buildAudioJson() }

    /** The enhancer source, read verbatim from assets. */
    private val enhancerJs: String by lazy {
        assets.open("enhancer.js").bufferedReader().use { it.readText() }
    }

    private val createDocumentLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
        if (uri != null) {
            initializeLogging(uri)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Prompt for log file location
        promptForLogFile()

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

        Log.i("GameController", "App started - controller input listening active")
        android.widget.Toast.makeText(this, "Controller ready", android.widget.Toast.LENGTH_SHORT).show()

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

            // ===== COMPREHENSIVE ACTIVITY LOGGING =====
            (function() {
              var activityLog = [];

              // Log all fetch/XHR requests
              var origFetch = window.fetch;
              window.fetch = function() {
                var args = Array.prototype.slice.call(arguments);
                var url = args[0];
                window.__bw2Log('info', 'FETCH:', typeof url === 'string' ? url : url.url, 'method:', args[1]?.method || 'GET');
                return origFetch.apply(this, arguments);
              };

              var origXHR = XMLHttpRequest.prototype.open;
              XMLHttpRequest.prototype.open = function(method, url) {
                window.__bw2Log('info', 'XHR:', method, url);
                return origXHR.apply(this, arguments);
              };

              // Log all clicks
              document.addEventListener('click', function(e) {
                var target = e.target;
                var text = target.textContent?.substring(0, 50) || target.className || target.id || 'unknown';
                window.__bw2Log('info', 'CLICK:', text, 'class:', target.className, 'id:', target.id);
              }, true);

              // Log all form submissions
              document.addEventListener('submit', function(e) {
                window.__bw2Log('info', 'SUBMIT:', e.target.action || 'unknown', 'fields:', Object.keys(new FormData(e.target)).join(','));
              }, true);

              // Log key events
              document.addEventListener('keydown', function(e) {
                window.__bw2Log('debug', 'KEY:', e.key, 'code:', e.code, 'keyCode:', e.keyCode);
              }, true);

              // Log DOM mutations
              var observer = new MutationObserver(function(mutations) {
                mutations.forEach(function(m) {
                  if (m.type === 'childList' && m.addedNodes.length > 0) {
                    var sample = Array.prototype.slice.call(m.addedNodes, 0, 2).map(n =>
                      n.nodeType === 1 ? '<' + n.tagName.toLowerCase() + '>' : n.textContent?.substring(0, 30)
                    ).join(', ');
                    window.__bw2Log('debug', 'DOM_ADD:', sample, 'at:', m.target.className || m.target.id);
                  }
                  if (m.type === 'attributes') {
                    window.__bw2Log('debug', 'ATTR_CHANGE:', m.attributeName, 'on:', m.target.className || m.target.id);
                  }
                });
              });
              observer.observe(document.body, {
                childList: true,
                subtree: true,
                attributes: true,
                attributeFilter: ['style', 'class', 'data-page', 'href'],
                characterData: false
              });

              // Log storage changes
              var origSetItem = Storage.prototype.setItem;
              Storage.prototype.setItem = function(key, val) {
                window.__bw2Log('info', 'STORAGE_SET:', key, '=', typeof val === 'string' ? val.substring(0, 100) : val);
                return origSetItem.call(this, key, val);
              };

              window.__bw2Log('info', '===== GAME SESSION STARTED =====');
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

        // Handle analog stick triggers (LT/RT)
        val leftTrigger = getCenteredAxis(event, MotionEvent.AXIS_LTRIGGER)
        val rightTrigger = getCenteredAxis(event, MotionEvent.AXIS_RTRIGGER)

        // LT (> 0.5) = "/" (go to next area)
        if (leftTrigger > 0.5f && !ltTriggerPressed) {
            ltTriggerPressed = true
            showButtonToast("LT - Next Area")
            injectKeyEvent("/")
        } else if (leftTrigger <= 0.5f && ltTriggerPressed) {
            ltTriggerPressed = false
        }

        // RT (> 0.5) = "r" (use remedy)
        if (rightTrigger > 0.5f && !rtTriggerPressed) {
            rtTriggerPressed = true
            showButtonToast("RT - Remedy")
            injectKeyEvent("r")
        } else if (rightTrigger <= 0.5f && rtTriggerPressed) {
            rtTriggerPressed = false
        }

        return super.onGenericMotionEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> {
                showButtonToast("A - Attack")
                injectKeyEvent("1")
                true
            }
            KeyEvent.KEYCODE_BUTTON_B -> {
                showButtonToast("B - Special")
                injectKeyEvent("2")
                true
            }
            KeyEvent.KEYCODE_BUTTON_X -> {
                showButtonToast("X - Heavy")
                injectKeyEvent("3")
                true
            }
            KeyEvent.KEYCODE_BUTTON_Y -> {
                showButtonToast("Y - Super")
                injectKeyEvent("4")
                true
            }
            KeyEvent.KEYCODE_BUTTON_L1 -> {
                showButtonToast("LB - Cover")
                injectKeyEvent("5")
                true
            }
            KeyEvent.KEYCODE_BUTTON_R1 -> {
                showButtonToast("RB - Look Around")
                injectKeyEvent("[")
                true
            }
            KeyEvent.KEYCODE_BUTTON_START -> {
                showButtonToast("Start - Home")
                injectKeyEventWithModifier("h", altKey = true)
                true
            }
            KeyEvent.KEYCODE_BUTTON_SELECT -> {
                showButtonToast("Select - Escape")
                injectKeyEvent("Escape")
                true
            }
            KeyEvent.KEYCODE_BUTTON_THUMBL -> {
                showButtonToast("LS - Stats")
                injectKeyEvent("x")
                true
            }
            KeyEvent.KEYCODE_BUTTON_THUMBR -> {
                showButtonToast("RS - Skip Victory")
                injectKeyEventWithModifier("v", altKey = true)
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun showButtonToast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    private var ltTriggerPressed = false
    private var rtTriggerPressed = false

    private fun promptForLogFile() {
        AlertDialog.Builder(this)
            .setTitle("Game Activity Logging")
            .setMessage("Save game activity log to file? This will capture all network, UI, and game interactions.")
            .setPositiveButton("Yes, Save Log") { _, _ ->
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                createDocumentLauncher.launch("titanconquest_$timestamp.log")
            }
            .setNegativeButton("No Logging") { _, _ ->
                writeLog("=== Logging disabled ===")
            }
            .setCancelable(false)
            .show()
    }

    private fun initializeLogging(uri: Uri) {
        try {
            logFile = File(uri.path ?: return)
            logWriter = contentResolver.openOutputStream(uri)?.bufferedWriter()?.let { FileWriter(uri.path ?: return) }

            // Try to get actual file path
            val displayName = "titanconquest_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.log"
            logFile = File(getExternalFilesDir(null), displayName)
            logWriter = FileWriter(logFile, true)

            writeLog("=".repeat(80))
            writeLog("GAME SESSION STARTED: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            writeLog("=".repeat(80))

            android.widget.Toast.makeText(
                this,
                "Logging to: ${logFile?.absolutePath}",
                android.widget.Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Log.e("Logging", "Failed to initialize log file", e)
            writeLog("ERROR: Failed to create log file: ${e.message}")
        }
    }

    private fun writeLog(message: String) {
        try {
            val timestamp = dateFormat.format(Date())
            val logLine = "[$timestamp] $message\n"

            logWriter?.apply {
                write(logLine)
                flush()
            }

            Log.d("GameLog", message)
        } catch (e: Exception) {
            Log.e("GameLog", "Error writing to log", e)
        }
    }

    private fun getCenteredAxis(event: MotionEvent, axis: Int): Float {
        val range = event.device?.getMotionRange(axis, MotionEvent.TOOL_TYPE_UNKNOWN)
        val flat = range?.flat ?: 0f
        val value = event.getAxisValue(axis)
        return if (Math.abs(value) > flat) value else 0f
    }

    private fun injectKeyEvent(keyValue: String) {
        val jsCode = """
            (function() {
                function getCodeForKey(key) {
                    const codeMap = {
                        '1': 'Digit1', '2': 'Digit2', '3': 'Digit3', '4': 'Digit4', '5': 'Digit5',
                        'r': 'KeyR', 'x': 'KeyX', 'h': 'KeyH',
                        '[': 'BracketLeft', '/': 'Slash', 'Escape': 'Escape'
                    };
                    return codeMap[key] || 'Unknown';
                }
                function getKeyCode(key) {
                    const keyMap = {
                        '1': 49, '2': 50, '3': 51, '4': 52, '5': 53,
                        'r': 82, 'Escape': 27, '[': 219, '/': 191, 'x': 88,
                        'h': 72
                    };
                    return keyMap[key] || 0;
                }
                const event = new KeyboardEvent('keydown', {
                    key: '$keyValue',
                    code: getCodeForKey('$keyValue'),
                    keyCode: getKeyCode('$keyValue'),
                    which: getKeyCode('$keyValue'),
                    bubbles: true,
                    cancelable: true
                });
                document.dispatchEvent(event);
            })();
        """.trimIndent()
        webView.evaluateJavascript(jsCode, null)
    }

    private fun injectKeyEventWithModifier(keyValue: String, altKey: Boolean = false, ctrlKey: Boolean = false, shiftKey: Boolean = false) {
        val jsCode = """
            (function() {
                function getCodeForKey(key) {
                    const codeMap = {
                        '1': 'Digit1', '2': 'Digit2', '3': 'Digit3', '4': 'Digit4', '5': 'Digit5',
                        'r': 'KeyR', 'x': 'KeyX', 'h': 'KeyH', 'v': 'KeyV', 't': 'KeyT',
                        '[': 'BracketLeft', '/': 'Slash', 'Escape': 'Escape'
                    };
                    return codeMap[key] || 'Unknown';
                }
                function getKeyCode(key) {
                    const keyMap = {
                        '1': 49, '2': 50, '3': 51, '4': 52, '5': 53,
                        'r': 82, 'Escape': 27, '[': 219, '/': 191, 'x': 88,
                        'h': 72, 'v': 86, 't': 84
                    };
                    return keyMap[key] || 0;
                }
                const event = new KeyboardEvent('keydown', {
                    key: '$keyValue',
                    code: getCodeForKey('$keyValue'),
                    keyCode: getKeyCode('$keyValue'),
                    which: getKeyCode('$keyValue'),
                    altKey: $altKey,
                    ctrlKey: $ctrlKey,
                    shiftKey: $shiftKey,
                    bubbles: true,
                    cancelable: true
                });
                document.dispatchEvent(event);
            })();
        """.trimIndent()
        webView.evaluateJavascript(jsCode, null)
    }

    /** Native implementation of the two globals enhancer.js depends on. */
    private inner class NativeBridge {
        @JavascriptInterface
        fun audioJson(): String = this@MainActivity.audioJson

        @JavascriptInterface
        fun log(level: String, text: String) {
            val tag = when {
                text.contains("FETCH") || text.contains("XHR") -> "🌐 NET"
                text.contains("CLICK") -> "👆 CLICK"
                text.contains("KEY") -> "⌨️  KEY"
                text.contains("DOM") || text.contains("ATTR") -> "📄 DOM"
                text.contains("STORAGE") -> "💾 STORE"
                text.contains("SESSION") -> "▶️  SESSION"
                else -> "BW2"
            }

            val priority = when (level.lowercase()) {
                "error" -> Log.ERROR
                "warn" -> Log.WARN
                "info" -> Log.INFO
                else -> Log.DEBUG
            }

            Log.println(priority, tag, text)
            writeLog("[$tag] $text")
        }
    }
}

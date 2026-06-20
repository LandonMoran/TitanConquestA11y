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
import java.io.BufferedWriter
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
    private var logUri: Uri? = null
    private val logBuffer = StringBuilder()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private val createDocumentLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
        if (uri != null) {
            logUri = uri
            logBuffer.append("================================================================================\n")
            logBuffer.append("GAME SESSION STARTED: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\n")
            logBuffer.append("================================================================================\n")
            android.widget.Toast.makeText(
                this,
                "✓ Logging enabled - file will be saved when you close the app",
                android.widget.Toast.LENGTH_LONG
            ).show()
            writeLog("Logging initialized - file will be saved on app close")
        }
    }

    /** Built once from bundled assets: { "benemy.ogg": "data:audio/ogg;base64,..." }. */
    private val audioJson: String by lazy { buildAudioJson() }

    /** The enhancer source, read verbatim from assets. */
    private val enhancerJs: String by lazy {
        assets.open("enhancer.js").bufferedReader().use { it.readText() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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

                window.__audioCache = {};
                window.__audioPreload = function() {
                  try {
                    for (var key in window.__bw2Audio) {
                      var audio = new Audio();
                      audio.src = window.__bw2Audio[key];
                      audio.preload = 'auto';
                      window.__audioCache[key] = audio;
                    }
                  } catch (e) {}
                };

                setTimeout(window.__audioPreload, 100);
              } catch (e) {}
            })();
        """.trimIndent()

        val audioOptimizer = """
            (function () {
              if (window.__audioOptimizerLoaded) return;
              window.__audioOptimizerLoaded = true;

              var OriginalAudio = window.Audio;
              var audioPool = {};

              window.Audio = function(src) {
                try {
                  if (src && window.__bw2Audio && window.__bw2Audio[src]) {
                    if (!audioPool[src]) {
                      audioPool[src] = [];
                    }
                    var pooledAudio = audioPool[src].find(function(a) { return !a.__playing; });
                    if (pooledAudio) {
                      return pooledAudio;
                    }
                  }
                } catch (e) {}

                var audio = new OriginalAudio(src);
                audio.__originalPlay = audio.play;
                audio.play = function() {
                  var self = this;
                  this.__playing = true;
                  var result = this.__originalPlay.apply(this, arguments);
                  if (result && result.then) {
                    result.then(function() {
                      setTimeout(function() { self.__playing = false; }, this.duration * 1000 + 100);
                    }).catch(function() {
                      self.__playing = false;
                    });
                  } else {
                    setTimeout(function() { self.__playing = false; }, audio.duration * 1000 + 100);
                  }
                  return result;
                };
                return audio;
              };

              window.Audio.prototype = OriginalAudio.prototype;
            })();
        """.trimIndent()

        val stripChat = """
            (function () {
              if (window.__stripChatLoaded) return;
              window.__stripChatLoaded = true;

              function removeChat() {
                var chatSelectors = [
                  '.panel-right',
                  '[class*="chat"]',
                  '[id*="chat"]',
                  '[class*="conversation"]',
                  '[class*="messenger"]'
                ];

                chatSelectors.forEach(function(selector) {
                  try {
                    var elements = document.querySelectorAll(selector);
                    elements.forEach(function(el) {
                      if (el && el.parentNode) {
                        el.remove();
                      }
                    });
                  } catch (e) {}
                });
              }

              removeChat();
              setInterval(removeChat, 500);
            })();
        """.trimIndent()

        val hardvenVictorySkip = """
            (function () {
              if (window.__victoryHardenedLoaded) return;
              window.__victoryHardenedLoaded = true;

              var lastVictoryCheck = 0;
              var lastVictoryTime = 0;

              function detectAndSkipVictory() {
                var now = Date.now();
                if (now - lastVictoryCheck < 100) return;
                lastVictoryCheck = now;

                try {
                  var pageText = document.body.textContent || '';
                  var isVictory = pageText.includes('You killed') && !pageText.includes('You died');

                  if (isVictory && (now - lastVictoryTime) > 500) {
                    lastVictoryTime = now;
                    window.__bw2Log && window.__bw2Log('info', 'VICTORY-HARDENED: Victory detected, attempting skip');

                    var patrolLink = null;

                    // Try multiple selectors for the patrol link
                    var selectors = [
                      'a.patrollink',
                      'a[href*="patrol"]',
                      'a[class*="patrol"]',
                      '[data-page="patrol"] a',
                      'a:contains("Patrol")'
                    ];

                    for (var i = 0; i < selectors.length && !patrolLink; i++) {
                      try {
                        if (selectors[i].includes('contains')) {
                          var links = document.querySelectorAll('a');
                          for (var j = 0; j < links.length; j++) {
                            if (links[j].textContent.includes('Patrol')) {
                              patrolLink = links[j];
                              break;
                            }
                          }
                        } else {
                          patrolLink = document.querySelector(selectors[i]);
                        }
                        if (patrolLink) {
                          window.__bw2Log && window.__bw2Log('info', 'VICTORY-HARDENED: Found patrol link with selector: ' + selectors[i]);
                          break;
                        }
                      } catch (e) {}
                    }

                    if (patrolLink) {
                      try {
                        var href = patrolLink.getAttribute('href');
                        if (href && href !== 'x') {
                          window.location.href = href;
                        } else {
                          patrolLink.click();
                        }
                        window.__bw2Log && window.__bw2Log('info', 'VICTORY-HARDENED: Clicked patrol link');
                      } catch (e) {
                        window.__bw2Log && window.__bw2Log('warn', 'VICTORY-HARDENED: Failed to click: ' + e.message);
                      }
                    } else {
                      window.__bw2Log && window.__bw2Log('warn', 'VICTORY-HARDENED: Could not find patrol link');
                    }
                  }
                } catch (e) {
                  window.__bw2Log && window.__bw2Log('error', 'VICTORY-HARDENED: ' + e.message);
                }
              }

              setInterval(detectAndSkipVictory, 100);
            })();
        """.trimIndent()

        view.evaluateJavascript(bootstrap) {
            view.evaluateJavascript(audioOptimizer) { _ ->
                view.evaluateJavascript(enhancerJs) { _ ->
                    view.evaluateJavascript(stripChat) { _ ->
                        view.evaluateJavascript(hardvenVictorySkip, null)
                    }
                }
            }
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

    override fun onDestroy() {
        super.onDestroy()
        // Write all buffered logs to file on app close
        if (logUri != null && logBuffer.isNotEmpty()) {
            try {
                val outputStream = contentResolver.openOutputStream(logUri!!) ?: return
                outputStream.bufferedWriter().use { writer ->
                    writer.write(logBuffer.toString())
                    writer.flush()
                }
                Log.i("GameLog", "Session log saved successfully (${logBuffer.length} bytes)")
            } catch (e: Exception) {
                Log.e("GameLog", "Error saving log file: ${e.message}", e)
            }
        }
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
        // Prevent key repeat events from being processed
        // Only process on first press (repeat count == 0)
        if (event?.repeatCount != 0) {
            return when (keyCode) {
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_X,
                KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1,
                KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT,
                KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> true // Consume repeats
                else -> super.onKeyDown(keyCode, event)
            }
        }

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
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                showButtonToast("D-Pad Down")
                scrollPage(300)
                true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                showButtonToast("D-Pad Up")
                scrollPage(-300)
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun scrollPage(pixels: Int) {
        val jsCode = """
            (function() {
                window.scrollBy(0, $pixels);
            })();
        """.trimIndent()
        webView.evaluateJavascript(jsCode, null)
    }

    private fun showButtonToast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    private var ltTriggerPressed = false
    private var rtTriggerPressed = false

    // Track pressed keys to prevent repeated onKeyDown events from triggering multiple injections
    private val pressedKeys = mutableSetOf<Int>()

    private fun getCenteredAxis(event: MotionEvent, axis: Int): Float {
        val range = event.device?.getMotionRange(axis, MotionEvent.TOOL_TYPE_UNKNOWN)
        val flat = range?.flat ?: 0f
        val value = event.getAxisValue(axis)
        return if (Math.abs(value) > flat) value else 0f
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

    private fun writeLog(message: String) {
        try {
            val timestamp = dateFormat.format(Date())
            val logLine = "[$timestamp] $message\n"

            synchronized(logBuffer) {
                logBuffer.append(logLine)
            }

            Log.d("GameLog", message)
        } catch (e: Exception) {
            Log.e("GameLog", "Error writing to log: ${e.message}", e)
        }
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
                text.contains("KEY") -> "⌨️ KEY"
                text.contains("DOM") || text.contains("ATTR") -> "📄 DOM"
                text.contains("STORAGE") -> "💾 STORE"
                text.contains("SESSION") -> "▶️ SESSION"
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

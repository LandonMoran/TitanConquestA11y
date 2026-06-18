# Keep @JavascriptInterface methods so the enhancer's window.__bw2Native bridge
# survives R8 shrinking in release builds.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

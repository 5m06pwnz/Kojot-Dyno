# Keep JS interface methods
-keepclassmembers class com.kojot.dyno.NativeBridge {
    @android.webkit.JavascriptInterface <methods>;
}

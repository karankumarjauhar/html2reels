# Keep JNA & UniFFI JNI/FFI classes intact in R8 release builds
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class uniffi.** { *; }
-keepclassmembers class com.html2reel.render.VirtualWebViewHost$JsBridge {
    @android.webkit.JavascriptInterface <methods>;
}

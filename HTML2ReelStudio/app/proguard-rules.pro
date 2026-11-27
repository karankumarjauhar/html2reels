# Keep JNA & UniFFI JNI/FFI classes intact in R8 release builds
-keepattributes InnerClasses,EnclosingMethod,Signature
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class * extends com.sun.jna.Structure { *; }
-keep class * extends com.sun.jna.Union { *; }
-keep class uniffi.** { *; }
-dontwarn java.awt.**
-dontwarn java.beans.**
-dontwarn javax.swing.**
-dontwarn com.sun.jna.**
-keepclassmembers class com.html2reel.render.VirtualWebViewHost$JsBridge {
    @android.webkit.JavascriptInterface <methods>;
}

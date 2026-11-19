# JNA + bindings UniFFI (usan reflexión). Necesario si se activa minify.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class cl.vintagemanager.core.** { *; }
-dontwarn java.awt.**

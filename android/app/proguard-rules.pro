# Add project specific ProGuard rules here.
-keep class com.myvault.app.data.** { *; }
-keepclassmembers class com.myvault.app.data.** { *; }
-dontwarn kotlinx.serialization.**
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

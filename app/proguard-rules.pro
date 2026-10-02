# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.somecatcode.ebookreader.**$$serializer { *; }
-keepclassmembers class com.somecatcode.ebookreader.** {
    *** Companion;
}
-keepclasseswithmembers class com.somecatcode.ebookreader.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp / Okio
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**

# JavaScript bridge methods of the reader WebView
-keepclassmembers class com.somecatcode.ebookreader.reader.** {
    @android.webkit.JavascriptInterface <methods>;
}
